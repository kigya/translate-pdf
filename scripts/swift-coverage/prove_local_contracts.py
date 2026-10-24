#!/usr/bin/env python3
"""Compute finite local proof obligations from exact historical compiler artifacts.

This does not certify an app or its current build. Source drift is reported;
source bytes are reconstructed and checked against the captured compiler packet.
"""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import certification as cert
import semantic_contracts as sil
import typed_contracts as typed
import statement_contracts as statement
import compositional_contracts as composition
import sil_context_contracts as sil_context
import observer_callback_contracts as observer_callbacks
import registry_sil_contracts as registry_sil


def checked(record):
    path=Path(record['path'])
    if not path.is_file() or cert.coverage.sha(path)!=record['sha256']:raise ValueError('Bound compiler artifact changed: '+str(path))
    return path


def syntax_tool(output):
    developer=Path(cert.coverage.DEVELOPER);host=developer/'Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/host'
    sdk=cert.coverage.run(['xcrun','--sdk','macosx','--show-sdk-path']).strip()
    binary=output/'syntax-evidence'
    args=['-sdk',sdk,'-swift-version','5','-module-cache-path',str(output/'syntax-module-cache'),
          '-I',str(host),'-L',str(host),'-Xlinker','-rpath','-Xlinker',str(host),str(cert.HERE/'SyntaxEvidence.swift'),'-o',str(binary)]
    result=cert.execute(args,cert.coverage.ROOT,output/'syntax-build.stdout',output/'syntax-build.stderr')
    if result['exit']:raise ValueError('Lexical evidence tool failed to compile')
    return binary,{'source':cert.artifact(cert.HERE/'SyntaxEvidence.swift'),'binary':cert.artifact(binary),'invocation':result}


def collect(packet_path,output):
    output=output.resolve()
    if not output.is_relative_to(cert.coverage.ROOT/'.local-private'):raise ValueError('Local proofs must remain in this project private evidence directory')
    output.mkdir(parents=True,exist_ok=False)
    packet_binding=cert.artifact(packet_path)
    packet=json.loads(packet_path.read_text());checked(packet['compiler']);manifest=json.loads(checked(packet['manifest']).read_text())
    if manifest['registryDigest']!=cert.coverage.digest(cert.coverage.registry_payload(manifest)):
        raise ValueError('Manifest registry binding differs')
    if packet['registryDigest']!=manifest['registryDigest']:raise ValueError('Compiler packet registry differs')
    implementation_names=['prove_local_contracts.py','typed_contracts.py','semantic_contracts.py',
        'statement_contracts.py','compositional_contracts.py','sil_context_contracts.py','observer_callback_contracts.py','registry_sil_contracts.py','SyntaxEvidence.swift',
        'certification.py','coverage.py','contracts/attempt.transfer.sil','contracts/selected.transfer.sil',
        'contracts/observer-initializer.sil','contracts/observer-array-map.sil','contracts/observer-literal-pair.sil']
    implementation={}
    for name in implementation_names:
        live=cert.HERE/name;copy=output/'implementation'/name;copy.parent.mkdir(parents=True,exist_ok=True)
        raw=live.read_bytes();copy.write_bytes(raw)
        implementation[name]={**cert.artifact(copy),'liveSourcePath':str(live),'liveSourceSHA256':cert.coverage.sha(live)}
    binary,lexical_build=syntax_tool(output)
    report={'schemaVersion':1,'scope':'historical exact compiler artifacts; current app build is not attested',
      'compilerPacket':packet_binding,'manifest':packet['manifest'],
      'implementation':implementation,
      'lexicalTool':lexical_build,'modules':[],'sites':[],'sourceBindings':[],
      'semanticEquivalenceCertified':False,'applicationGateApproved':False,'counterStatus':'No runtime hits collected or inferred'}
    for module in packet['modules']:
        variants=module['variants'];ast={};inventory=None;original_inventory=None
        for variant in ['original','transformed']:
            actions=variants[variant]['actions']
            for action in ['ast','sil']:
                for stream in ['stdout','stderr']:checked(actions[action][stream])
                if actions[action]['exit']!=0:raise ValueError('Real compiler action failed')
            ast[variant]=cert.typed_nodes(cert.compiler_output(actions['ast'],'ast')[0])
            if variant=='transformed':inventory=sil.functions(cert.compiler_output(actions['sil'],'sil')[0])
            else:original_inventory=sil.functions(cert.compiler_output(actions['sil'],'sil')[0])
        kernels={helper:sil.exact_transfer_rule(inventory,module['module'],helper) for helper in ['boolean','optional','selected','attempt']}
        separation=sil.observer_separation(inventory,module['module'])
        interface_inputs=[item for item in variants['transformed']['actions']['sil']['loadedSwiftModuleInputs'] if '/Swift.swiftmodule/' in item['path']]
        callback_rules=observer_callbacks.prove(inventory,module['module'],interface_inputs[0]) if len(interface_inputs)==1 else {
            'callbackBindingsAndErrorTransfersProved':False,'failures':['No unique compiler-loaded Swift interface'],
            'observerNoninterferenceCertified':False,'semanticEquivalenceCertified':False,'applicationGateApproved':False}
        module_report={'module':module['module'],'helperTransfers':kernels,'observerSeparation':separation,
                       'observerInitializationCallbacks':callback_rules,
                       'observerRegistryConstruction':registry_sil.prove(inventory,module['module'],manifest['registry']),
                       'ownedSILContexts':[]}
        report['modules'].append(module_report)
        source_paths=set(variants['original']['actions']['ast']['argv'])
        module_sources=[];module_syntax={}
        for index,source in enumerate(manifest['sources']):
            if source['source'] not in source_paths:continue
            stem=output/(str(index)+'-'+Path(source['source']).stem);stem.mkdir()
            try:old=typed.original_bytes(source)
            except ValueError as error:
                report['sourceBindings'].append({'source':source['source'],'sourceSHA256':source['sourceSHA256'],
                     'currentSourceMatches':False,'historicalBytesUnavailable':str(error)})
                for site in source['sites']:
                    report['sites'].append({'siteID':source['namespace']+':'+str(site['id']),
                      'sourceSHA256':source['sourceSHA256'],'kind':site['kind'],'originalRange':[site['startUTF8'],site['endUTF8']],
                      'typedExpressionPreserved':False,'failures':['Historical original bytes unavailable: '+str(error)],
                      'outcomes':{outcome:0 for outcome in site['outcomes']},'semanticEquivalenceCertified':False,'applicationGateApproved':False})
                continue
            original=stem/'original.swift';original.write_bytes(old)
            binding={'source':source['source'],'sourceSHA256':source['sourceSHA256'],'reconstructedHistoricalSource':cert.artifact(original),
                'currentSourceMatches':Path(source['source']).is_file() and cert.coverage.sha(Path(source['source']))==source['sourceSHA256']}
            report['sourceBindings'].append(binding)
            module_sources.append(source)
            syntax={}
            for variant,path in [('original',original),('transformed',Path(source['transformed']))]:
                result=stem/(variant+'-syntax.json')
                subprocess.run([str(binary),str(path),str(result)],check=True,text=True,capture_output=True)
                syntax[variant]=json.loads(result.read_text());binding[variant+'Syntax']=cert.artifact(result)
            module_syntax[source['source']]=syntax['transformed']
            try:
                original_tree=typed.source_tree(ast['original'],source['source']);transformed_tree=typed.source_tree(ast['transformed'],source['source'])
                context=composition.Context(source,original_tree,transformed_tree,syntax['original'],syntax['transformed'],module['module'],inventory)
                if source['runtimeInjectedModule']==module['module']:
                    module_report['observerImmutableRegistry']=observer_callbacks.registry_literal(transformed_tree,source,manifest['registry'])
                    module_report['observerInitializerNothrow']=observer_callbacks.initializer_effects(transformed_tree,source)
                tree_error=None
            except ValueError as error:tree_error=str(error)
            calls=syntax['transformed']['recorderCalls']
            for site in source['sites']:
                family=cert.FAMILIES[site['kind']];site_id=source['namespace']+':'+str(site['id'])
                matches=[call for call in calls if call.get('registryID')==site_id]
                helper='boolean' if family=='bool-identity' else 'optional' if family=='optional-identity' else 'selected' if site['kind'] in {'switch-dispatch','catch-all'} else None
                if not helper or len(matches)!=1 or tree_error:
                    row={'siteID':site_id,'sourceSHA256':source['sourceSHA256'],'kind':site['kind'],
                         'originalRange':[site['startUTF8'],site['endUTF8']],'typedExpressionPreserved':False,
                         'failures':[tree_error or ('No local transfer rule for '+family if not helper else 'Missing/ambiguous exact registry call')],
                         'semanticEquivalenceCertified':False,'applicationGateApproved':False}
                else:
                    rule=statement.statement_rule if helper=='selected' else typed.local_expression_rule
                    row=rule(source,site,matches[0],original_tree,transformed_tree,
                               syntax['original'],syntax['transformed'],module['module'],kernels[helper])
                    row.setdefault('typedExpressionPreserved',False)
                if not tree_error and family in {'bool-identity','optional-identity'}:
                    row['compositionalTypedContext']=context.prove(site)
                row['outcomes']={outcome:0 for outcome in site['outcomes']}
                report['sites'].append(row)
        helper_symbol=kernels['boolean']['symbol']
        for symbol,function in inventory.items():
            if symbol.startswith(f"$s{len(module['module'])}{module['module']}7__FBCovO") or not any(helper_symbol in line for line in function.lines):continue
            if symbol not in original_inventory:
                module_report['ownedSILContexts'].append({'symbol':symbol,'retainedOwnedSILIdentical':False,
                    'failures':['No exact original function symbol; closure/declaration identity remains unproved'],
                    'semanticEquivalenceCertified':False,'applicationGateApproved':False})
            else:
                module_report['ownedSILContexts'].append(sil_context.prove_function(original_inventory[symbol],function,
                    inventory,module['module'],module_sources,module_syntax))
    identities=[row['siteID'] for row in report['sites']]
    if len(identities)!=len(set(identities)) or set(identities)!=set(manifest['registry']):raise ValueError('Local proof inventory lost/duplicated a registered site')
    for row in report['sites']:
        if row['outcomes']!={outcome:0 for outcome in manifest['registry'][row['siteID']]}:raise ValueError('Local proof altered a declared outcome')
    for binding in report['implementation'].values():
        checked(binding)
        if cert.coverage.sha(Path(binding['liveSourcePath']))!=binding['liveSourceSHA256']:raise ValueError('Proof implementation changed during collection')
    checked(packet_binding)
    report['classifications']=packet['classifications'];report['unsupported']=packet['unsupported']
    report['counts']={'sites':len(report['sites']),'typedExpressionsPreserved':sum(x['typedExpressionPreserved'] for x in report['sites']),
      'originalArmStructuresPreserved':sum(x.get('originalArmStructurePreserved',False) for x in report['sites']),
      'compositionalTypedContextsPreserved':sum(x.get('compositionalTypedContext',{}).get('typedContextPreserved',False) for x in report['sites']),
      'retainedOwnedSILFunctionsIdentical':sum(row['retainedOwnedSILIdentical'] for module in report['modules'] for row in module['ownedSILContexts']),
      'sitesWithRetainedOwnedSILContext':len({site['siteID'] for module in report['modules'] for row in module['ownedSILContexts'] if row['retainedOwnedSILIdentical'] for site in row['contractedSites']}),
      'pendingReasons':dict(Counter(reason for row in report['sites'] for reason in row['failures'])),
      'compositionalPendingReasons':dict(Counter(reason for row in report['sites'] for reason in row.get('compositionalTypedContext',{}).get('failures',[])))}
    report['remainingWholeProgramObligations']=packet['remainingProofs']
    cert.coverage.write(output/'local-contracts.json',report)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--packet',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();report=collect(args.packet.resolve(),args.output)
    print(json.dumps(report['counts'],indent=2));print('semanticEquivalenceCertified=false; applicationGateApproved=false')
