#!/usr/bin/env python3
"""Finite typed-context reduction with freshly checked compiler/SIL premises.

Each contracted node must be an exact compiler-resolved Bool/Optional recorder
call with an actual complete helper SIL transfer rule, a literal declared ID,
identical input/output type and a payload byte map covering its original site.
This proves a typed-tree fact only. It never concludes site SIL ordering, ARC,
observer noninterference, measured outcomes or application acceptance.
"""
from __future__ import annotations
from pathlib import Path
import certification as cert
import semantic_contracts as sil
import typed_contracts as typed


def processed_walk(tree):
    if tree['node']['header'].startswith('(original_init='):return
    yield tree
    for child in tree['children']:yield from processed_walk(child)


def expression_root(tree,interval,raw,syntax):
    if '_processedExpressions' not in tree:
        index={}
        for item in processed_walk(tree):
            node=item['node']
            if node['kind'].endswith('_expr') and 'type' in node and node.get('range',{}).get('source')==tree['source']:
                span=typed.extent(node,raw,syntax)
                if span is not None:index.setdefault(span,[]).append(item)
        tree['_processedExpressions']=index
    candidates=tree['_processedExpressions'].get(tuple(interval),[])
    if not candidates:raise ValueError('No exact processed typed expression extent')
    depth=min(item['node']['indent'] for item in candidates)
    candidates=[item for item in candidates if item['node']['indent']==depth]
    if len(candidates)!=1:raise ValueError('Ambiguous processed typed expression root')
    return candidates[0]


class Context:
    def __init__(self,source,original_tree,transformed_tree,original_syntax,transformed_syntax,module,inventory):
        self.source=source;self.old=typed.original_bytes(source);self.new=Path(source['transformed']).read_bytes()
        if cert.coverage.sha(Path(source['transformed']))!=source['transformedSHA256']:raise ValueError('Transformed byte binding changed')
        previous_old=previous_new=0
        for segment in source['sourceMap']:
            a,b=segment['originalStartUTF8'],segment['originalEndUTF8'];x,y=segment['transformedStartUTF8'],segment['transformedEndUTF8']
            if not(0<=previous_old<=a<b<=len(self.old) and 0<=previous_new<=x<y<=len(self.new)) or self.old[a:b]!=self.new[x:y]:
                raise ValueError('Historical copied-byte map differs from bound originals')
            previous_old,previous_new=b,y
        self.original_tree=original_tree;self.transformed_tree=transformed_tree
        self.original_syntax=original_syntax;self.transformed_syntax=transformed_syntax;self.module=module
        self.kernels={helper:sil.exact_transfer_rule(inventory,module,helper) for helper in ['boolean','optional']}
        self.sites={source['namespace']+':'+str(site['id']):site for site in source['sites']}
        self.calls={};self.ast_calls={}
        for call in transformed_syntax['recorderCalls']:
            key=(call['startUTF8'],call['endUTF8']);self.calls.setdefault(key,[]).append(call)
        for item in processed_walk(transformed_tree):
            node=item['node']
            if node['kind']=='call_expr' and node.get('range',{}).get('source')==source['source']:
                span=typed.extent(node,self.new,transformed_syntax)
                if span is not None:self.ast_calls.setdefault(span,[]).append(item)

    def projection(self,interval):
        pieces=[]
        for segment in self.source['sourceMap']:
            a=max(interval[0],segment['transformedStartUTF8']);b=min(interval[1],segment['transformedEndUTF8'])
            if a<b:pieces.append((segment['originalStartUTF8']+a-segment['transformedStartUTF8'],segment['originalStartUTF8']+b-segment['transformedStartUTF8']))
        if not pieces:raise ValueError('Recorder payload has no copied original bytes')
        if any(left[1]!=right[0] for left,right in zip(pieces,pieces[1:])):raise ValueError('Recorder payload omits/replaces original expression bytes')
        return pieces[0][0],pieces[-1][1]

    def contract(self,tree,premises,stack):
        node=tree['node']
        if node['kind']!='call_expr':return None
        span=typed.extent(node,self.new,self.transformed_syntax)
        matches=self.calls.get(span,[])
        if not matches:return None
        if len(matches)!=1 or len(self.ast_calls.get(span,[]))!=1:raise ValueError('Ambiguous exact compiler/lexical recorder invocation')
        call=matches[0];site_id=call.get('registryID');site=self.sites.get(site_id)
        if site is None:raise ValueError('Nested recorder ID is not a declared concrete source site')
        helper={'bool-identity':'boolean','optional-identity':'optional'}.get(cert.FAMILIES.get(site['kind']))
        if helper is None or call['helper']!=helper:raise ValueError('No nested typed-context rule for this exact recorder family')
        kernel=self.kernels[helper]
        if not kernel['proved']:raise ValueError('Actual helper SIL transfer precondition is unproved')
        if site_id in stack:raise ValueError('Cyclic recorder source-site contraction')
        if len(call['argumentRanges'])!=2:raise ValueError('Unexpected recorder syntax arguments')
        if self.projection(call['argumentRanges'][1])!=(site['startUTF8'],site['endUTF8']):
            raise ValueError('Nested payload does not cover this exact original source site')
        lists=[child for child in tree['children'] if child['node']['kind']=='argument_list']
        if len(lists)!=1 or len(lists[0]['children'])!=2:raise ValueError('Unexpected compiler recorder arguments')
        arguments=lists[0]['children']
        if any(len(argument['children'])!=1 for argument in arguments):raise ValueError('Ambiguous compiler recorder value')
        literal=arguments[0]['children'][0]['node'];payload=arguments[1]['children'][0]
        if literal['kind']!='string_literal_expr' or literal.get('type')!='String' or f'value="{site_id}"' not in literal['header']:
            raise ValueError('Registry ID is not an exact compiler-typed String literal')
        if typed.extent(payload['node'],self.new,self.transformed_syntax)!=tuple(call['argumentRanges'][1]):
            raise ValueError('Compiler payload extent differs from copied syntax')
        refs=[item['node'] for item in processed_walk(tree['children'][0]) if item['node']['kind']=='declref_expr']
        expected=self.module+'.(file).__FBCov.'+helper+'@'
        if len(refs)!=1 or not refs[0].get('decl','').startswith(expected):raise ValueError('Recorder declaration does not resolve to the proved helper')
        actual_type=payload['node'].get('type')
        if not actual_type or node.get('type')!=actual_type:raise ValueError('Helper input/output contextual types differ')
        if helper=='boolean' and actual_type!='Bool':raise ValueError('Bool helper input substitution differs')
        if helper=='optional' and not(actual_type.endswith('?') or actual_type.startswith('Optional<')):
            raise ValueError('Optional helper input substitution differs')
        if not node.get('nothrow'):raise ValueError('Identity recorder call is not compiler nonthrowing')
        premises.append({'siteID':site_id,'kind':site['kind'],'sourceSHA256':self.source['sourceSHA256'],
          'callRange':list(span),'copiedOriginalRange':[site['startUTF8'],site['endUTF8']],
          'expressionType':actual_type,'resolvedHelper':refs[0]['decl'],'helperSymbol':kernel['symbol'],'helperTransferRule':kernel['rule']})
        return self.normalize(payload,True,premises,stack|{site_id})

    def normalize(self,tree,transformed,premises,stack):
        if tree['node']['header'].startswith('(original_init='):raise ValueError('Unprocessed initializer cannot be a reduction root')
        if transformed:
            contracted=self.contract(tree,premises,stack)
            if contracted is not None:return contracted
        # Reuse the same full-header normalization without permitting it to
        # erase a child. Only the exact checked contract above can replace one.
        header=typed.canonical({'node':tree['node'],'children':[]},self.source,self.new if transformed else self.old,transformed)[0]
        return [header,[self.normalize(child,transformed,premises,stack) for child in tree['children']
                       if not child['node']['header'].startswith('(original_init=')]]

    def prove(self,site):
        site_id=self.source['namespace']+':'+str(site['id']);premises=[]
        result={'siteID':site_id,'sourceSHA256':self.source['sourceSHA256'],'kind':site['kind'],
          'originalRange':[site['startUTF8'],site['endUTF8']],'typedContextPreserved':False,
          'semanticEquivalenceCertified':False,'applicationGateApproved':False,'failures':[]}
        try:
            if cert.FAMILIES.get(site['kind']) not in {'bool-identity','optional-identity'}:raise ValueError('No typed-context rule for this source family')
            calls=[call for values in self.calls.values() for call in values if call.get('registryID')==site_id]
            if len(calls)!=1:raise ValueError('No unique exact source registry invocation')
            call=calls[0]
            old=expression_root(self.original_tree,[site['startUTF8'],site['endUTF8']],self.old,self.original_syntax)
            new=expression_root(self.transformed_tree,[call['startUTF8'],call['endUTF8']],self.new,self.transformed_syntax)
            before=self.normalize(old,False,[],set());after=self.normalize(new,True,premises,set())
            if before!=after:raise ValueError('Reduced full contextual expression tree differs')
            if site_id not in {premise['siteID'] for premise in premises}:raise ValueError('Root recorder was not discharged')
            result.update(typedContextPreserved=True,typedTreeSHA256=cert.coverage.digest(before),
                          originalDumpLine=old['node']['dumpLine'],transformedDumpLine=new['node']['dumpLine'])
        except (ValueError,KeyError,IndexError) as error:result['failures'].append(str(error))
        result['checkedContractPremises']=premises
        result['remainingObligations']=['same concrete SIL evaluation and short-circuit order',
          'same weak/value/capture lifetime, ownership and exclusivity','complete observer effect and alias noninterference',
          'actual source/runtime/binary-bound outcomes and independent review']
        return result
