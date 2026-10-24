#!/usr/bin/env python3
"""Exact retained-function SIL comparison after finite Bool recorder contraction.

The result is conditional on observer termination and disjoint effects. It is
not whole-program semantic equivalence. No payload ownership operation, branch,
callback, capture, argument or cleanup instruction may be removed by this rule.
"""
from __future__ import annotations
from pathlib import Path
import re
import certification as cert
import semantic_contracts as sil


STRING_INIT='$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC'
STRING_SIGNATURE='$@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String'
BOOL_SIGNATURE='$@convention(method) (@guaranteed String, Bool, @thin __FBCov.Type) -> Bool'


outside_quotes=sil.outside_quotes
semantic_line=sil.strip_metadata


def program(function):
    if function.conflicting_duplicates:raise ValueError('Conflicting original/transformed function definition')
    result=[]
    for line in function.lines:
        value=semantic_line(line)
        if not value or value.startswith('//') or re.match(r'debug_value(?:_addr)?\b',value):continue
        block=re.fullmatch(r'(bb\d+)(?:\([^\n]*\))?:',value)
        if block:result.append({'text':value,'raw':line,'block':block[1]});continue
        instruction=re.fullmatch(r'(?:(%\d+|\(%\d+(?:, %\d+)+\)) = )?([a-z_]+)(?: (.*))?',value)
        if not instruction:raise ValueError('Unrecognized retained SIL instruction: '+value)
        result.append({'text':value,'raw':line,'result':instruction[1],
                       'opcode':instruction[2],'operands':instruction[3] or ''})
    return result


def uses(value):
    found=[]
    outside_quotes(value,lambda part:found.extend(re.findall(r'%\d+',part)) or part)
    return found


def canonical(items,aliases=None):
    names={};blocks={};aliases=aliases or {}
    def token(match):
        value=match[0];seen=set()
        while value in aliases:
            if value in seen:raise ValueError('Cyclic contracted SSA aliases')
            seen.add(value);value=aliases[value]
        table=names if value.startswith('%') else blocks
        if value not in table:table[value]=('%v' if value.startswith('%') else 'bb')+str(len(table))
        return table[value]
    return [outside_quotes(item['text'],lambda part:re.sub(r'%\d+|\bbb\d+\b',token,part)) for item in items]


def private_literal_lifetime(body,erase,construction,call,destruction):
    """Prove a finite private lifetime over an exact acyclic function CFG.

    Same-block lifetimes need no CFG abstraction. Cross-block lifetimes require
    all block terminators, reachability, absence of cycles, setup dominance and
    destruction postdominance; unknown/error/loop shapes remain pending.
    """
    at_block={};blocks={};current=None
    for at,node in enumerate(body):
        if 'block' in node:current=node['block'];blocks[current]=[]
        elif current is not None:blocks[current].append(node)
        if current is None:raise ValueError('Instruction before basic block')
        at_block[at]=current
    if len({at_block[at] for at in erase})==1:return 'exclusive-same-basic-block'
    edges={}
    for block,nodes in blocks.items():
        if not nodes:raise ValueError('Empty basic block in private lifetime proof')
        terminal=nodes[-1]
        if terminal['opcode'] in {'return','throw','unreachable'}:targets=[]
        elif terminal['opcode'] in {'br','cond_br','switch_enum','switch_enum_addr','checked_cast_br','checked_cast_addr_br','try_apply'}:
            targets=[]
            outside_quotes(terminal['operands'],lambda part:targets.extend(re.findall(r'\bbb\d+\b',part)) or part)
            if not targets:raise ValueError('Missing CFG targets in private lifetime proof')
        else:raise ValueError('Unknown CFG terminator in private lifetime proof')
        if any(target not in blocks for target in targets):raise ValueError('Unknown target block in private lifetime proof')
        edges[block]=set(targets)
    entry=next(iter(blocks));visiting=set();visited=set()
    def visit(block):
        if block in visiting:raise ValueError('Cross-block private literal lifetime has a CFG cycle')
        if block in visited:return
        visiting.add(block)
        for child in edges[block]:visit(child)
        visiting.remove(block);visited.add(block)
    visit(entry)
    if visited!=set(blocks):raise ValueError('Unreachable block in private lifetime proof')
    predecessors={block:{parent for parent in blocks if block in edges[parent]} for block in blocks}
    dominators={block:({entry} if block==entry else set(blocks)) for block in blocks}
    postdominators={block:({block} if not edges[block] else set(blocks)) for block in blocks}
    changed=True
    while changed:
        changed=False
        for block in blocks:
            dom={block}|(set.intersection(*(dominators[parent] for parent in predecessors[block])) if predecessors[block] else set())
            post={block}|(set.intersection(*(postdominators[child] for child in edges[block])) if edges[block] else set())
            if dom!=dominators[block]:dominators[block]=dom;changed=True
            if post!=postdominators[block]:postdominators[block]=post;changed=True
    if any(at_block[at] not in dominators[at_block[call]] for at in erase if at<call):
        raise ValueError('Private recorder setup does not dominate its call')
    if at_block[destruction] not in postdominators[at_block[construction]]:
        raise ValueError('Private String destruction does not postdominate construction')
    return 'exclusive-acyclic-CFG-dominance-and-postdominance'


def contract_boolean(function,inventory,module,sources,syntax_by_source):
    kernel=sil.exact_transfer_rule(inventory,module,'boolean')
    if not kernel['proved']:raise ValueError('Actual complete Bool helper transfer is unproved')
    body=program(function);definitions={};references={};uses_by_value={}
    for index,item in enumerate(body):
        if item.get('result'):
            if item['result'].startswith('('):continue
            if item['result'] in definitions:raise ValueError('Duplicate SSA definition')
            definitions[item['result']]=index
        if item.get('opcode')=='function_ref':
            match=re.fullmatch(r'@([^ ]+) : (.+)',item['operands'])
            if not match:raise ValueError('Malformed SIL function reference')
            references[item['result']]=(match[1],match[2])
        for value in uses(item.get('operands',item['text'] if 'block' in item else '')):
            uses_by_value.setdefault(value,[]).append(index)
    registry={source['namespace']+':'+str(site['id']):(source,site) for source in sources for site in source['sites']}
    removed=set();aliases={};premises=[]
    for index,item in enumerate(body):
        if item.get('opcode')!='apply':continue
        match=re.fullmatch(r'(%\d+)\((%\d+), (%\d+), (%\d+)\) : (.+)',item['operands'])
        if not match or references.get(match[1],(None,None))[0]!=kernel['symbol']:continue
        function_ref,string,payload,metatype,signature=match.groups()
        if signature!=BOOL_SIGNATURE or references[function_ref][1]!=BOOL_SIGNATURE or not item['result']:
            raise ValueError('Concrete Bool call signature differs')
        def definition(value,opcode):
            if value not in definitions:raise ValueError('Missing generated SSA definition')
            at=definitions[value];node=body[at]
            if node.get('opcode')!=opcode or at>=index:raise ValueError('Generated setup provenance/order differs')
            return at,node
        meta_at,meta=definition(metatype,'metatype')
        if meta['operands']!='$@thin __FBCov.Type':raise ValueError('Bool metatype differs')
        string_at,string_node=definition(string,'apply')
        constructor=re.fullmatch(r'(%\d+)\((%\d+), (%\d+), (%\d+), (%\d+)\) : (.+)',string_node['operands'])
        if not constructor:raise ValueError('Site ID is not finite String literal construction')
        initializer,literal,length,ascii_flag,string_type,string_signature=constructor.groups()
        if references.get(initializer)!=(STRING_INIT,STRING_SIGNATURE) or string_signature!=STRING_SIGNATURE:
            raise ValueError('Site String construction target/type differs')
        literal_at,literal_node=definition(literal,'string_literal')
        literal_match=re.fullmatch(r'utf8 "([a-z0-9]+:[0-9]+)"',literal_node['operands'])
        if not literal_match or literal_match[1] not in registry:raise ValueError('Site ID is not an exact declared literal')
        site_id=literal_match[1];source,site=registry[site_id]
        if cert.coverage.sha(Path(source['transformed']))!=source['transformedSHA256']:
            raise ValueError('Source-bound transformed bytes changed')
        if cert.FAMILIES[site['kind']]!='bool-identity':raise ValueError('Declared site does not use Bool identity')
        dependencies={metatype:(meta_at,{index}),function_ref:(definitions[function_ref],{index}),
                      initializer:(definitions[initializer],{string_at}),literal:(literal_at,{string_at})}
        for value,opcode,operand in [(length,'integer_literal','$Builtin.Word, '+str(len(site_id.encode()))),
                                    (ascii_flag,'integer_literal','$Builtin.Int1, -1'),
                                    (string_type,'metatype','$@thin String.Type')]:
            at,node=definition(value,opcode)
            if node['operands']!=operand:raise ValueError('Site literal construction operand differs')
            dependencies[value]=(at,{string_at})
        destroy=[at for at in uses_by_value.get(string,[]) if body[at].get('opcode')=='destroy_value' and body[at]['operands']==string]
        if len(destroy)!=1 or destroy[0]<=index or uses_by_value.get(string)!=[index,destroy[0]]:
            raise ValueError('Generated String escapes or has different lifetime cleanup')
        if any(set(uses_by_value.get(value,[]))!=expected for value,(_,expected) in dependencies.items()):
            raise ValueError('Generated setup SSA escapes its exact recorder call')
        erase={index,string_at,destroy[0],*(at for at,_ in dependencies.values())}
        if removed&erase:raise ValueError('Recorder setup is shared/ambiguous')
        lifetime=private_literal_lifetime(body,erase,string_at,index,destroy[0])
        location=re.search(r', loc "([^"]+)":(\d+):(\d+)(?:,|$)',item['raw'])
        if not location or location[1] not in {source['source'],source['transformed']}:
            raise ValueError('Recorder SIL location lacks exact source binding')
        offset=cert.byte_offset(Path(source['transformed']).read_bytes(),int(location[2]),int(location[3]))
        calls=[call for call in syntax_by_source[source['source']]['recorderCalls'] if call.get('registryID')==site_id and call['helper']=='boolean']
        if len(calls)!=1 or not(calls[0]['startUTF8']<=offset<calls[0]['endUTF8']):
            raise ValueError('Recorder SIL does not lie in its exact source call')
        removed.update(erase);aliases[item['result']]=payload
        premises.append({'siteID':site_id,'sourceSHA256':source['sourceSHA256'],
                         'originalRange':[site['startUTF8'],site['endUTF8']],
                         'callLocationUTF8':offset,'literalSetupCleanupInstructions':len(erase)-1,
                         'privateLiteralLifetimeRule':lifetime,
                         'helperTransferRule':kernel['rule'],'helperSymbol':kernel['symbol']})
    if not premises:raise ValueError('No exact Bool call contracted')
    remaining=[item for at,item in enumerate(body) if at not in removed]
    if any(kernel['symbol'] in item['text'] for item in remaining):raise ValueError('Uncontracted Bool helper reference remains')
    return remaining,aliases,premises


def prove_function(original,transformed,inventory,module,sources,syntax_by_source):
    result={'symbol':transformed.symbol,'retainedOwnedSILIdentical':False,'failures':[],
            'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        if original.header!=transformed.header:raise ValueError('Complete original/transformed SIL function signature differs')
        reduced,aliases,premises=contract_boolean(transformed,inventory,module,sources,syntax_by_source)
        before=canonical(program(original));after=canonical(reduced,aliases)
        if before!=after:raise ValueError('Complete retained original function SSA/CFG/ownership program differs')
        result.update(retainedOwnedSILIdentical=True,contractedSites=premises,
                      retainedInstructionCount=len(before),retainedProgramSHA256=cert.coverage.digest(before))
    except (ValueError,KeyError,IndexError) as error:result['failures'].append(str(error))
    result['scope']='Entire original function instruction/CFG/ownership sequence after exact Bool additions are contracted'
    result['remainingObligations']=['observer normal return, disjoint effects, lock behavior and resource behavior',
        'literal String construction/destruction effects under exact bound standard-library implementation',
        'source-bound typed context and complete whole-program ownership/weak/exclusivity interactions',
        'actual source/runtime/binary-bound outcomes and independent review']
    return result
