#!/usr/bin/env python3
"""Finite initialization-callback facts; not observer noninterference approval.

Exact programs bind both higher-order calls to capture-free private callbacks.
The inner callback has a Never error slot and never writes it. The outer map
therefore cannot produce a Swift error under the checked typed-throws/rethrows
declarations. Traps, allocation, locking, pointer effects and ARC are separate.
"""
from pathlib import Path
from functools import lru_cache
import re
import subprocess
import certification as cert
import semantic_contracts as sil
import typed_contracts as typed


SIGNATURES={
 'initializer':'$@convention(c) (Builtin.RawPointer) -> ()',
 'array-map':'$@convention(thin) @substituted <τ_0_0, τ_0_1> (@in_guaranteed τ_0_0) -> (@out τ_0_1, @error any Error) for <Array<String>, Dictionary<String, UInt64>>',
 'literal-pair':'$@convention(thin) @substituted <τ_0_0, τ_0_1, τ_0_2> (@in_guaranteed τ_0_0) -> (@out τ_0_2, @error_indirect τ_0_1) for <String, Never, (String, UInt64)>'}
MAP_VALUES='@inlinable public func mapValues<T>(_ transform: (Value) throws -> T) rethrows -> Swift.Dictionary<Key, T> {'
COLLECTION_MAP='extension Swift.Collection {\n  @inlinable @_alwaysEmitIntoClient public func map<T, E>(_ transform: (Self.Element) throws(E) -> T) throws(E) -> [T] where E : Swift.Error {'


def node(kind,*children,text=None):return {'kind':kind,'text':text,'children':list(children)}


@lru_cache(maxsize=32)
def _demangled(symbol,binary_path,binary_sha):
    binary=Path(binary_path);binding={'path':binary_path,'sha256':binary_sha}
    argv=[str(binary),'--expand','--tree-only',symbol]
    result=subprocess.run(argv,check=True,capture_output=True,text=True)
    roots=[];stack=[]
    for line in result.stdout.splitlines():
        if not line.strip() or line=='Demangling for '+symbol:continue
        match=re.fullmatch(r'( *)kind=([A-Za-z]+)(?:, text="([^"\\]*)")?',line)
        if not match:raise ValueError('Unknown compiler demangler tree grammar')
        indent=len(match[1]);item=node(match[2],text=match[3])
        while stack and stack[-1][0]>=indent:stack.pop()
        if stack:stack[-1][1]['children'].append(item)
        else:roots.append(item)
        stack.append((indent,item))
    if len(roots)!=1 or cert.artifact(binary.resolve())!=binding:raise ValueError('Compiler demangler binding/tree changed')
    return roots[0],{'binary':binding,'argv':argv,'stdout':result.stdout}


def demangled(symbol):
    # Cache only by the current binary bytes, never by a stale tool pathname.
    binding=cert.artifact((cert.COMPILER.parent/'swift-demangle').resolve())
    return _demangled(symbol,binding['path'],binding['sha256'])


def initializer_scope(symbol,module):
    tree,evidence=demangled(symbol)
    try:scope=tree['children'][0]['children'][1]['children'][0]['children'][0]['text']
    except (IndexError,KeyError,TypeError):raise ValueError('Unknown private initializer demangled shape')
    if not scope or not re.fullmatch(r'_[A-F0-9]+',scope):raise ValueError('Unknown private source discriminator')
    expected=node('Global',node('GlobalVariableOnceFunction',
        node('Enum',node('Module',text=module),node('Identifier',text='__FBCov')),
        node('GlobalVariableOnceDeclList',node('PrivateDeclName',node('Identifier',text=scope),node('Identifier',text='hits')))))
    if tree!=expected:raise ValueError('Initializer does not demangle to this exact generated enum/private property')
    return scope,evidence


def registry_scope(symbol,module,scope):
    tree,evidence=demangled(symbol)
    string=node('Type',node('Structure',node('Module',text='Swift'),node('Identifier',text='String')))
    array=node('Type',node('BoundGenericStructure',node('Type',node('Structure',node('Module',text='Swift'),node('Identifier',text='Array'))),node('TypeList',string)))
    dictionary=node('Type',node('BoundGenericStructure',node('Type',node('Structure',node('Module',text='Swift'),node('Identifier',text='Dictionary'))),node('TypeList',string,array)))
    expected=node('Global',node('UnsafeMutableAddressor',node('Variable',node('Module',text=module),
        node('PrivateDeclName',node('Identifier',text=scope),node('Identifier',text='__fbRegistry')),dictionary)))
    if tree!=expected:raise ValueError('Registry accessor module/private scope/type differs')
    return evidence


def initializer_effects(tree,source):
    """Bind the actual compiler's nothrow facts to these two concrete calls."""
    result={'compilerCheckedNothrowInitialization':False,'failures':[],
            'observerNoninterferenceCertified':False,'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        if tree.get('source')!=source['source'] or cert.coverage.sha(Path(source['transformed']))!=source['transformedSHA256']:
            raise ValueError('Initializer typed AST/source binding differs')
        enums=[item for item in typed.walk(tree) if item['node']['kind']=='enum_decl' and re.search(r'"__FBCov" interface_type=',item['node']['header'])]
        if len(enums)!=1:raise ValueError('Missing/ambiguous generated recorder enum')
        entries=[item for item in typed.walk(enums[0]) if item['node']['kind']=='pattern_entry' and any(node['node']['kind']=='pattern_named' and '"hits"' in node['node']['header'] for child in item['children'] if child['node']['kind'].startswith('pattern_') for node in typed.walk(child))]
        if len(entries)!=1:raise ValueError('Missing/ambiguous private hits typed initializer')
        roots=[item for item in entries[0]['children'] if item['node']['header'].startswith('(processed_init=')]
        if len(roots)!=1 or roots[0]['node']['kind']!='call_expr':raise ValueError('Hits initializer is not an exact processed call')
        def reference(call,prefix):
            if not call['children']:return []
            return [item['node'] for item in typed.walk(call['children'][0]) if item['node']['kind']=='declref_expr' and item['node'].get('decl','').startswith(prefix)]
        outer=roots[0];outer_refs=reference(outer,'Swift.(file).Dictionary extension.mapValues ')
        inner=[item for item in typed.walk(outer) if item['node']['kind']=='call_expr' and reference(item,'Swift.(file).Collection extension.map ')]
        if len(outer_refs)!=1 or len(inner)!=1:raise ValueError('Concrete higher-order Swift declarations differ')
        if not outer['node']['nothrow'] or not inner[0]['node']['nothrow']:
            raise ValueError('Actual compiler does not mark both exact initialization calls nothrow')
        if outer['node'].get('type')!='Dictionary<String, [String : UInt64]>' or inner[0]['node'].get('type')!='[(String, UInt64)]':
            raise ValueError('Concrete initialization result substitutions differ')
        result.update(compilerCheckedNothrowInitialization=True,source=source['source'],transformedSHA256=source['transformedSHA256'],
            calls=[{'dumpLine':item['node']['dumpLine'],'type':item['node']['type'],
                    'resolvedDeclaration':refs[0]['decl']} for item,refs in [(outer,outer_refs),(inner[0],reference(inner[0],'Swift.(file).Collection extension.map '))]],
            conclusion='The actual typed compiler marks both exact mapValues/map initialization calls nothrow; raw SIL unreachable error edges are not treated as independent owned decisions')
    except (ValueError,KeyError,IndexError,OSError,subprocess.SubprocessError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['complete callback SIL/type-interface agreement','traps/resources/locking and full observer memory/ownership noninterference']
    return result


def registry_literal(tree,source,expected):
    """Read exact compiler-typed immutable registry data, preserving all keys."""
    result={'immutableRegistryLiteralProved':False,'failures':[],
            'observerNoninterferenceCertified':False,'applicationGateApproved':False}
    try:
        if tree.get('source')!=source['source'] or not source.get('runtimeInjectedModule'):
            raise ValueError('Registry AST is not bound to its declared runtime source')
        if cert.coverage.sha(Path(source['transformed']))!=source['transformedSHA256']:raise ValueError('Transformed registry source changed')
        nodes=list(typed.walk(tree))
        declarations=[item for item in nodes if item['node']['kind']=='var_decl' and re.search(r'"__fbRegistry" interface_type=',item['node']['header'])]
        if len(declarations)!=1 or 'access=private let readImpl=stored immutable' not in declarations[0]['node']['header']:
            raise ValueError('Registry is not a unique compiler-typed private immutable stored let')
        if declarations[0]['node'].get('range',{}).get('source')!=source['source']:
            raise ValueError('Registry declaration location is not in its bound source')
        entries=[item for item in nodes if item['node']['kind']=='pattern_entry' and any(node['node']['kind']=='pattern_named' and '"__fbRegistry"' in node['node']['header'] for child in item['children'] if child['node']['kind'].startswith('pattern_') for node in typed.walk(child))]
        if len(entries)!=1:raise ValueError('Registry binding is missing or ambiguous')
        initializers=[child for child in entries[0]['children'] if child['node']['header'].startswith('(processed_init=')]
        if len(initializers)!=1 or initializers[0]['node']['kind']!='dictionary_expr' or initializers[0]['node'].get('type')!='[String : [String]]':
            raise ValueError('Registry is not an exact processed typed Dictionary literal')
        def literal(item):
            node=item['node']
            if node['kind']!='string_literal_expr' or node.get('type')!='String' or item['children']:
                raise ValueError('Registry contains a nonliteral or non-String value')
            match=re.search(r'\bencoding=utf8 value="([A-Za-z0-9:_-]+)" builtin_initializer="Swift\.\(file\)\.String extension\.init\(_builtinStringLiteral:utf8CodeUnitCount:isASCII:\)"',node['header'])
            if not match:raise ValueError('Registry String literal is not a finite builtin String literal')
            return match[1]
        values={}
        for item in initializers[0]['children']:
            if item['node']['kind']!='tuple_expr' or item['node'].get('type')!='(String, [String])' or len(item['children'])!=2:
                raise ValueError('Registry literal key/value entry differs')
            key=literal(item['children'][0]);array=item['children'][1]
            if array['node']['kind']!='array_expr' or array['node'].get('type')!='[String]':raise ValueError('Registry outcomes are not a typed Array literal')
            outcomes=[literal(child) for child in array['children']]
            if key in values or not outcomes or len(outcomes)!=len(set(outcomes)):raise ValueError('Registry keys/outcomes are duplicate or empty')
            values[key]=outcomes
        if values!=expected:raise ValueError('Compiler-typed registry differs from all declared source outcomes')
        result.update(immutableRegistryLiteralProved=True,source=source['source'],sourceSHA256=source['sourceSHA256'],
          transformedSHA256=source['transformedSHA256'],declarationDumpLine=declarations[0]['node']['dumpLine'],
          initializerDumpLine=initializers[0]['node']['dumpLine'],registrySHA256=cert.coverage.digest(values),
          uniqueSiteIDs=len(values),uniqueNonemptyOutcomeLists=len(values),outcomes=sum(map(len,values.values())),
          conclusion='Every declared site/outcome occurs in the exact immutable typed literal; site keys and each outcome list are unique')
    except (ValueError,KeyError,IndexError,OSError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['complete registry accessor/initializer memory effects and imported Dictionary/Array semantics',
        'all recorder mutation/lock/overflow/ownership effects and complete observer noninterference']
    return result


def interface_contract(record):
    path=Path(record['path'])
    if 'Swift.swiftmodule' not in path.parts or path.suffix!='.swiftinterface':raise ValueError('Not a compiler-loaded Swift interface')
    if cert.coverage.sha(path)!=record['sha256']:raise ValueError('Compiler-loaded Swift interface changed')
    raw=path.read_bytes();text=raw.decode();declarations=[]
    for declaration in [MAP_VALUES,COLLECTION_MAP]:
        if text.count(declaration)!=1:raise ValueError('Exact typed-throws/rethrows Swift declaration is missing or ambiguous')
        at=text.index(declaration);begin=len(text[:at].encode());end=begin+len(declaration.encode())
        declarations.append({'declaration':declaration,'startUTF8':begin,'endUTF8':end})
    return {'interface':record,'declarations':declarations,
            'trustedContract':'Swift compiler/type system and bound standard-library declarations implement typed throws and rethrows',
            'languageReference':'https://docs.swift.org/latest/documentation/the-swift-programming-language/declarations/'}


def prove(inventory,module,interface):
    result={'callbackBindingsAndErrorTransfersProved':False,'failures':[],
            'observerNoninterferenceCertified':False,'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        standard=interface_contract(interface);prefix=f'$s{len(module)}{module}'
        roots=[fn for symbol,fn in inventory.items() if re.fullmatch(re.escape(prefix)+r'7__FBCovO4hits[^ ]+LL_WZ',symbol)]
        if len(roots)!=1:raise ValueError('Missing/ambiguous exact private hits initializer')
        root=roots[0];root_body=sil.instructions(root);root_refs=sil.refs(root_body)
        private_scope,root_demangling=initializer_scope(root.symbol,module)
        def callback(references):
            matches=[inventory[target] for target in references.values() if target.startswith(prefix+'7__FBCovO4hits') and target in inventory]
            if len(matches)!=1:raise ValueError('Missing/ambiguous exact capture-free private callback')
            return matches[0]
        outer=callback(root_refs);inner=callback(sil.refs(sil.instructions(outer)))
        globals_=[re.fullmatch(r'@([^ ]+) : .+',item['operands'])[1] for item in root_body if item['opcode']=='global_addr']
        if globals_!=[root.symbol[:-3]+'SDySSSDySSs6UInt64VGGvpZ']:raise ValueError('Initializer does not bind its exact private hits global')
        if not outer.symbol.startswith(globals_[0]+'fi') or not inner.symbol.startswith(outer.symbol):
            raise ValueError('Private callbacks do not resolve inside this exact hits initializer')
        registry=[target for target in root_refs.values() if target.startswith(prefix+'12__fbRegistry')]
        if len(registry)!=1:
            raise ValueError('Initializer does not use its generated registry accessor')
        registry_demangling=registry_scope(registry[0],module,private_scope)
        bindings={globals_[0]:'HITS_GLOBAL',registry[0]:'REGISTRY_ACCESSOR',outer.symbol:'OUTER_CALLBACK',inner.symbol:'PAIR_CALLBACK'}
        programs=[]
        for label,function in [('initializer',root),('array-map',outer),('literal-pair',inner)]:
            attributes='private [global_init_once_fn] [ossa]' if label=='initializer' else 'private [ossa]'
            if function.header!=f'sil {attributes} @{function.symbol} : {SIGNATURES[label]} {{':
                raise ValueError('Complete private callback ownership/signature/attributes differ')
            sil.instructions(function)
            lines=[]
            for line in function.lines:
                def replace(part):
                    for symbol,name in sorted(bindings.items(),key=lambda pair:-len(pair[0])):
                        part=re.sub('@'+re.escape(symbol)+r'(?=\s|$)','@'+name,part)
                    return part
                lines.append(sil.outside_quotes(sil.strip_metadata(line),replace))
            expected=(Path(__file__).resolve().parent/'contracts'/('observer-'+label+'.sil')).read_text()
            actual_program=sil.transfer_program(lines,'NO_RECORDER_CALL_ALLOWED')
            if actual_program!=sil.transfer_program(expected.splitlines(),'NO_RECORDER_CALL_ALLOWED'):
                raise ValueError('Complete initialization/callback SSA/error program differs: '+label)
            programs.append({'label':label,'symbol':function.symbol,'definitionLine':function.start_line,
                             'programSHA256':cert.coverage.digest(actual_program),'checkedInstructions':len(actual_program)})
        result.update(callbackBindingsAndErrorTransfersProved=True,standardLibraryContract=standard,programs=programs,
          compilerDemangling=[root_demangling,registry_demangling],
          conclusion='Both exact higher-order calls use the checked capture-free private callbacks; pair callback preserves String and returns zero UInt64 without writing its Never error slot; typed Never map and rethrows mapValues cannot introduce a Swift error under the bound language/library contracts')
    except (ValueError,KeyError,IndexError,OSError,subprocess.SubprocessError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['source-specific registry uniqueness/initialization and private memory provenance',
        'traps, allocation/resource behavior, locking, reentry, ARC and complete observer noninterference',
        'all concrete site evaluation/ownership equivalence, independent review and actual bound runtime outcomes']
    return result
