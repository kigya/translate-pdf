#!/usr/bin/env python3
"""Finite original-arm AST preservation after exact literal probe delegation.

A successful rule concerns control/pattern/body structure only. It does not
establish ARC/observer/runtime equivalence and cannot approve coverage.
"""
from __future__ import annotations
from pathlib import Path
import certification as cert
import typed_contracts as typed


def statement_rule(source,site,call,original_tree,transformed_tree,original_syntax,transformed_syntax,module,kernel):
    result={'siteID':source['namespace']+':'+str(site['id']),'sourceSHA256':source['sourceSHA256'],
      'kind':site['kind'],'originalRange':[site['startUTF8'],site['endUTF8']],
      'originalArmStructurePreserved':False,'failures':[],
      'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        kinds={'switch-dispatch':'case_stmt','catch-all':'catch_stmt'}
        if site['kind'] not in kinds:raise ValueError('No exact original-arm structural rule for this dispatch family')
        if not kernel.get('proved') or kernel.get('helper')!='selected':raise ValueError('Exact selected-delegation helper rule is unproved')
        if call.get('registryID')!=result['siteID'] or call.get('helper')!='selected' or len(call['argumentRanges'])!=2:
            raise ValueError('Not the exact literal registry/delegation call')
        old=typed.original_bytes(source);new=Path(source['transformed']).read_bytes()
        roots=[item for item in typed.walk(original_tree) if item['node']['kind']==kinds[site['kind']] and
               typed.extent(item['node'],old,original_syntax)==tuple(result['originalRange'])]
        if len(roots)!=1:raise ValueError('No unique exact original dispatch arm')
        candidates=[]
        for item in typed.walk(transformed_tree):
            if item['node']['kind']!=kinds[site['kind']]:continue
            span=typed.extent(item['node'],new,transformed_syntax)
            if span is None or span[0]>=span[1]:continue
            a=cert.mapped_offset(source,span[0]);b=cert.mapped_offset(source,span[1]-1)
            if a is not None and b is not None and (a,b+1)==tuple(result['originalRange']):candidates.append(item)
        if len(candidates)!=1:raise ValueError('No unique transformed arm with identical original source extent')
        arm=candidates[0]
        invocations=[item for item in typed.walk(arm) if item['node']['kind']=='call_expr' and
            typed.extent(item['node'],new,transformed_syntax)==(call['startUTF8'],call['endUTF8'])]
        if len(invocations)!=1:raise ValueError('Probe has no unique exact compiler call')
        invocation=invocations[0]
        if invocation['node'].get('type')!='()' or not invocation['node'].get('nothrow'):
            raise ValueError('Delegation call is not typed as nonthrowing Void')
        lists=[item for item in invocation['children'] if item['node']['kind']=='argument_list']
        if len(lists)!=1 or len(lists[0]['children'])!=2:raise ValueError('Probe compiler argument list differs')
        for argument,literal in zip(lists[0]['children'],[result['siteID'],'selected']):
            if len(argument['children'])!=1:raise ValueError('Probe argument is not a single literal')
            value=argument['children'][0]['node']
            if value['kind']!='string_literal_expr' or value.get('type')!='String' or f'value="{literal}"' not in value['header']:
                raise ValueError('Probe registry/outcome is not an exact typed String literal')
        if not invocation['children']:raise ValueError('No probe compiler callee')
        refs=[item['node'] for item in typed.walk(invocation['children'][0]) if item['node']['kind']=='declref_expr']
        if len(refs)!=1 or not refs[0].get('decl','').startswith(module+'.(file).__FBCov.selected@'):
            raise ValueError('Probe compiler declaration is not exact generated selected helper')
        braces=[item for item in arm['children'] if item['node']['kind']=='brace_stmt']
        if len(braces)!=1 or not braces[0]['children'] or braces[0]['children'][0] is not invocation:
            raise ValueError('Probe is not the first compiler statement of the selected arm')
        # Copy only the path being changed. Identity of the already verified
        # exact call is used; no generic helper-name or statement deletion.
        reduced={**arm,'children':[{**item,'children':item['children'][1:]} if item is braces[0] else item for item in arm['children']]}
        before=typed.canonical(roots[0],source,old,False);after=typed.canonical(reduced,source,new,True)
        if before!=after:raise ValueError('Original pattern/binding/body/control-transfer tree differs')
        result.update(originalArmStructurePreserved=True,originalDumpLine=roots[0]['node']['dumpLine'],
           transformedDumpLine=arm['node']['dumpLine'],armTreeSHA256=cert.coverage.digest(before),
           resolvedHelper=refs[0]['decl'],helperTransferRule=kernel['rule'])
    except (ValueError,KeyError,IndexError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['concrete SIL selected-arm control flow and cleanup',
        'same case/catch binding, weak and value lifetimes','closed disjoint observer state and callback behavior',
        'actual source/runtime/binary-bound outcomes and independent review']
    return result
