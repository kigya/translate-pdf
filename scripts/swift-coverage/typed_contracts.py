#!/usr/bin/env python3
"""Source-bound local compiler-AST rules; no whole-program approval route."""
from __future__ import annotations
from bisect import bisect_left
import hashlib
from pathlib import Path
import re
import certification as cert


def original_bytes(source: dict) -> bytes:
    """Recover historical bytes only if the lossless map covers every byte."""
    snapshot=source.get('originalSnapshot')
    if snapshot:
        raw=Path(snapshot['path']).read_bytes()
        if hashlib.sha256(raw).hexdigest()!=source['sourceSHA256'] or snapshot['sha256']!=source['sourceSHA256']:
            raise ValueError('Historical original snapshot binding differs')
        return raw
    current=Path(source['source'])
    if current.is_file() and cert.coverage.sha(current)==source['sourceSHA256']:return current.read_bytes()
    transformed = Path(source['transformed']).read_bytes()
    if hashlib.sha256(transformed).hexdigest() != source['transformedSHA256']:
        raise ValueError('Transformed source evidence changed')
    chunks = []; end = 0
    for segment in source['sourceMap']:
        if segment['originalStartUTF8'] != end: raise ValueError('Historical original source map has a gap')
        a,b = segment['transformedStartUTF8'],segment['transformedEndUTF8']
        if b-a != segment['originalEndUTF8']-end: raise ValueError('Source-map length mismatch')
        chunks.append(transformed[a:b]);end=segment['originalEndUTF8']
    raw = b''.join(chunks)
    if hashlib.sha256(raw).hexdigest() != source['sourceSHA256']:
        raise ValueError('Recovered original bytes do not match compiler-bound source')
    return raw


def source_tree(nodes: list[dict], source: str) -> dict:
    roots = [];stack = []
    for item in nodes:
        if item['kind'] == 'source_file':
            match=re.match(r'^\(source_file \"([^\"]+)\"',item['header'])
            if not match or match[1]!=source:stack=[];continue
            root={'node':item,'children':[],'source':source};roots.append(root);stack=[root];continue
        if not stack:continue
        while stack and stack[-1]['node']['indent'] >= item['indent']:stack.pop()
        if not stack:continue
        value={'node':item,'children':[]};stack[-1]['children'].append(value);stack.append(value)
    if not roots:raise ValueError('Owned source has no actual typed AST root')
    def unknown_count(tree):
        return sum('type="<null>"' in value['node']['header'] or 'unresolved_' in value['node']['kind'] for value in walk(tree))
    best=min(roots,key=unknown_count)
    return best


def walk(tree):
    yield tree
    for child in tree['children']:yield from walk(child)


def extent(node:dict,raw:bytes,syntax:dict) -> tuple[int,int] | None:
    span=node.get('range')
    if not span:return None
    a=cert.byte_offset(raw,span['startLine'],span['startColumn'])
    b=cert.byte_offset(raw,span['endLine'],span['endColumn'])
    if '_tokenStarts' not in syntax:syntax['_tokenStarts']=[t['startUTF8'] for t in syntax['tokens']]
    starts=syntax['_tokenStarts']
    index=bisect_left(starts,b)
    if index == len(starts) or starts[index] != b:return None
    # AST ranges can name the opening token of the final String/Regex literal,
    # rather than its final lexical token. Only parser-derived full literals may
    # extend that position; no quote scanning or source-text guesses are used.
    if '_literalStarts' not in syntax:
        syntax['_literalStarts']={item['startUTF8']:item['endUTF8'] for item in syntax.get('literalRanges',[])}
    return a,syntax['_literalStarts'].get(b,syntax['tokens'][index]['endUTF8'])


def outer_expression(tree,interval,raw,syntax):
    # The highest expression with this exact extent includes all implicit
    # conversions introduced by the original contextual type.
    if '_expressionIndex' not in tree:
        index={}
        for item in walk(tree):
            node=item['node']
            if node['kind'].endswith('_expr') and 'type' in node and node.get('range',{}).get('source')==tree['source']:
                span=extent(node,raw,syntax)
                if span is not None:index.setdefault(span,[]).append(item)
        tree['_expressionIndex']=index
    candidates=tree['_expressionIndex'].get(tuple(interval),[])
    if not candidates:raise ValueError('No exact typed expression extent')
    depth=min(item['node']['indent'] for item in candidates)
    candidates=[item for item in candidates if item['node']['indent']==depth]
    if len(candidates)!=1:raise ValueError('Ambiguous typed expression root')
    return candidates[0]


def remove_location_metadata(header):
    """Remove only unquoted compiler-position fields, never literal contents."""
    output=[];index=0;quoted=False;escaped=False
    while index<len(header):
        char=header[index]
        if quoted:
            output.append(char)
            if escaped:escaped=False
            elif char=='\\':escaped=True
            elif char=='"':quoted=False
            index+=1;continue
        if char=='"':quoted=True;output.append(char);index+=1;continue
        match=re.match(r'(range|location|decl_context|init_context)=',header[index:])
        if match and (index==0 or header[index-1].isspace()):
            start=index+len(match[0])
            if match[1]=='range':
                if start==len(header) or header[start]!='[':raise ValueError('Unknown range metadata grammar')
                finish=header.find(']',start)
                if finish<0:raise ValueError('Unclosed compiler range')
                index=finish+1
            else:
                finish=re.search(r'[\s)]',header[start:])
                index=start+finish.start() if finish else len(header)
            continue
        output.append(char);index+=1
    if quoted:raise ValueError('Unclosed quoted AST metadata')
    return ''.join(output)


def canonical(tree,source,raw,transformed):
    node=tree['node'];header=node['header']
    if header.startswith('(original_init='):raise ValueError('Unprocessed syntax cannot prove a typed expression')
    if '<null>' in header or 'unresolved_' in node['kind']:raise ValueError('Unresolved expression metadata')
    if '__FBCov' in header or '__fb' in header:raise ValueError('Nested recorder expression requires a separate compositional rule')
    if 'captures=' in header or '<anonymous @' in header:raise ValueError('Closure/capture identity requires a separate ownership rule')
    header=re.sub(r'^\(processed_init=', '(',header)
    header=remove_location_metadata(header)
    own_paths={source['source'],source['transformed']}
    def location(match):
        path,line,column=match[1],int(match[2]),int(match[3])
        if path not in own_paths:return match[0]
        offset=cert.byte_offset(raw,line,column)
        if transformed:
            offset=cert.mapped_offset(source,offset)
            if offset is None:raise ValueError('Reference resolves to an inserted declaration')
        return '@OWNED:'+str(offset)
    def declaration_attribute(match):
        return match[1]+'='+re.sub(r'@([^"\s\[\]]+):(\d+):(\d+)',location,match[2])
    header=re.sub(r'\b(decl|bind|override|builtin_initializer|initializer)=("(?:[^"\\]|\\.)*")',declaration_attribute,header)
    # Any remaining unstable address or cross-owned declaration requires an
    # explicit declaration-identity mapping, rather than dropping it.
    if re.search(r'0x[0-9a-f]+',header):raise ValueError('Unmapped compiler declaration identity')
    header=' '.join(header.rstrip(')').split())
    children=[canonical(child,source,raw,transformed) for child in tree['children']
              if not child['node']['header'].startswith('(original_init=')]
    return [header,children]


def local_expression_rule(source,site,call,original_tree,transformed_tree,original_syntax,transformed_syntax,module,kernel):
    """Discharge only concrete contextual-expression preservation and helper type.

    This deliberately does not conclude evaluation/short-circuit or ownership
    equivalence merely because ASTs agree. Those remain separate SIL obligations.
    """
    result={'siteID':source['namespace']+':'+str(site['id']),'sourceSHA256':source['sourceSHA256'],
            'kind':site['kind'],'originalRange':[site['startUTF8'],site['endUTF8']],
            'typedExpressionPreserved':False,'failures':[],
            'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        if not kernel.get('proved'):raise ValueError('Exact helper transfer rule is unproved')
        if call.get('registryID')!=result['siteID'] or call.get('helper')!=kernel['helper']:
            raise ValueError('Recorder call is not the exact registry/helper pair')
        if len(call['argumentRanges'])!=2:raise ValueError('Unexpected recorder argument shape')
        old=original_bytes(source);new=Path(source['transformed']).read_bytes()
        original=outer_expression(original_tree,result['originalRange'],old,original_syntax)
        invocation=outer_expression(transformed_tree,[call['startUTF8'],call['endUTF8']],new,transformed_syntax)
        if invocation['node']['kind']!='call_expr':raise ValueError('Recorder is not a compiler call expression')
        lists=[child for child in invocation['children'] if child['node']['kind']=='argument_list']
        if len(lists)!=1 or len(lists[0]['children'])!=2:raise ValueError('Recorder typed argument list differs')
        arguments=lists[0]['children']
        if any(len(arg['children'])!=1 for arg in arguments):raise ValueError('Recorder argument has ambiguous compiler value')
        literal=arguments[0]['children'][0]['node']
        if literal['kind']!='string_literal_expr' or f'value="{result["siteID"]}"' not in literal['header']:
            raise ValueError('Site is not a compiler-verified String literal')
        value=arguments[1]['children'][0]
        if extent(value['node'],new,transformed_syntax)!=tuple(call['argumentRanges'][1]):
            raise ValueError('Compiler payload does not match exact syntax argument extent')
        callee=invocation['children'][0]
        refs=[x['node'] for x in walk(callee) if x['node']['kind']=='declref_expr']
        expected=module+'.(file).__FBCov.'+kernel['helper']+'@'
        if len(refs)!=1 or not refs[0].get('decl','').startswith(expected):
            raise ValueError('Compiler callee does not resolve to the generated helper')
        old_type=original['node']['type'];new_type=value['node'].get('type')
        if old_type!=new_type or invocation['node'].get('type')!=new_type:raise ValueError('Contextual input/output types differ')
        if kernel['helper']=='boolean' and new_type!='Bool':raise ValueError('Bool helper substitution differs')
        if kernel['helper']=='optional' and not(new_type.endswith('?') or new_type.startswith('Optional<')):
            raise ValueError('Optional helper substitution differs')
        before=canonical(original,source,old,False);after=canonical(value,source,new,True)
        if before!=after:raise ValueError('Full retained typed expression tree differs')
        result.update(typedExpressionPreserved=True,expressionType=old_type,typedTreeSHA256=cert.coverage.digest(before),
            originalDumpLine=original['node']['dumpLine'],transformedDumpLine=value['node']['dumpLine'],
            resolvedHelper=refs[0]['decl'],helperTransferRule=kernel['rule'])
    except (ValueError,KeyError,IndexError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['same concrete SIL evaluation/short-circuit order',
        'same value/weak/capture lifetime, ownership and exclusivity','closed disjoint observer state and callback behavior',
        'actual source/runtime/binary-bound outcomes and independent review']
    return result
