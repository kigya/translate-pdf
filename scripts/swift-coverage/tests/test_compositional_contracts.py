"""Real nested typed-context rules; no AST/probe-name-only approval."""
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

TOOL=Path(__file__).resolve().parents[1];sys.path.insert(0,str(TOOL))
import certification as cert
import compositional_contracts as composition
import prove_local_contracts as local
import semantic_contracts as sil
import typed_contracts as typed
import observer_callback_contracts as observer


class ActualNestedContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(dir=cert.coverage.ROOT/'.local-private',prefix='nested-contracts-')
        cls.root=Path(cls.temp.name);cls.path=cls.root/'Nested.swift'
        cls.path.write_text('''func accepts(_ value:Any)->Bool {
  if value is Int { return true }
  return false
}
func text(_ value:Any?)->String? {
  return (value as? String)?.lowercased()
}
''')
        cls.manifest=cert.coverage.prepare(cls.root/'prepared',[cls.path]);cls.source=cls.manifest['sources'][0]
        directory=cls.root/'lexical';directory.mkdir();binary,_=local.syntax_tool(directory)
        cls.syntax={};cls.trees={}
        env={**os.environ,'DEVELOPER_DIR':cert.coverage.DEVELOPER};env.pop('SDKROOT',None)
        sdk=cert.coverage.run(['xcrun','--sdk','macosx','--show-sdk-path']).strip()
        for variant,path in [('original',cls.path),('transformed',Path(cls.source['transformed']))]:
            output=directory/(variant+'.json');subprocess.run([str(binary),str(path),str(output)],check=True,text=True,capture_output=True)
            cls.syntax[variant]=json.loads(output.read_text())
            arguments=['-j1','-sdk',sdk,'-module-name','Nested','-swift-version','5','-module-cache-path',str(cls.root/(variant+'-cache')),str(cls.path)]
            if variant=='transformed':arguments+=['-vfsoverlay',str(cls.root/'prepared/overlay.json'),str(cls.root/'prepared/BranchRuntime.swift')]
            result=subprocess.run([str(cert.COMPILER),*arguments,'-dump-ast'],env=env,check=True,text=True,capture_output=True)
            raw=result.stdout if '(source_file ' in result.stdout else result.stderr
            cls.trees[variant]=typed.source_tree(cert.typed_nodes(raw),str(cls.path))
            if variant=='transformed':
                runtime=cls.root/'prepared/BranchRuntime.swift'
                cls.runtime_source={'source':str(runtime),'transformed':str(runtime),'sourceSHA256':cert.coverage.sha(runtime),
                                    'transformedSHA256':cert.coverage.sha(runtime),'runtimeInjectedModule':'Nested'}
                cls.runtime_tree=typed.source_tree(cert.typed_nodes(raw),str(runtime))
                result=subprocess.run([str(cert.COMPILER),*arguments,'-emit-silgen'],env=env,check=True,text=True,capture_output=True)
                cls.raw_sil=result.stdout if 'sil_stage raw' in result.stdout else result.stderr
                cls.inventory=sil.functions(cls.raw_sil)

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def context(self,source=None,tree=None,syntax=None,inventory=None):
        return composition.Context(source or self.source,self.trees['original'],tree or self.trees['transformed'],
          self.syntax['original'],syntax or self.syntax['transformed'],'Nested',inventory or self.inventory)

    def test_actual_nested_bool_and_optional_contexts_keep_complete_premises(self):
        rows=[self.context().prove(site) for site in self.source['sites']]
        self.assertTrue(all(row['typedContextPreserved'] for row in rows),rows)
        self.assertTrue(any(len(row['checkedContractPremises'])>=2 for row in rows),rows)
        self.assertTrue(all(not row['applicationGateApproved'] and not row['semanticEquivalenceCertified'] for row in rows))
        self.assertTrue(all(row['remainingObligations'] for row in rows))

    def test_actual_sil_kernel_mutation_blocks_nested_bool_reduction(self):
        function=sil.unique_function(self.inventory,'Nested','boolean')
        bad=copy.deepcopy(self.inventory);bad[function.symbol].lines=[re.sub(r'return %1(?=\s|$)', 'return %0',line) for line in function.lines]
        row=self.context(inventory=bad).prove(self.source['sites'][0])
        self.assertFalse(row['typedContextPreserved']);self.assertIn('Actual helper SIL transfer precondition is unproved',row['failures'])

    def test_literal_id_payload_span_and_declaration_changes_are_rejected(self):
        site=self.source['sites'][0]
        for old,new in [('value="'+self.source['namespace']+':0"','value="wrong"'),('.boolean@','.different@')]:
            tree=copy.deepcopy(self.trees['transformed']);changed=False
            for item in typed.walk(tree):
                node=item['node']
                if old in node['header']:
                    node['header']=node['header'].replace(old,new);changed=True
                    if 'decl' in node:node['decl']=node['decl'].replace(old,new)
            self.assertTrue(changed);self.assertFalse(self.context(tree=tree).prove(site)['typedContextPreserved'])
        syntax=copy.deepcopy(self.syntax['transformed'])
        call=next(item for item in syntax['recorderCalls'] if item.get('registryID')==self.source['namespace']+':0')
        call['argumentRanges'][1][0]+=1
        self.assertFalse(self.context(syntax=syntax).prove(site)['typedContextPreserved'])

    def test_copied_byte_map_cannot_be_forged_or_omitted(self):
        source=copy.deepcopy(self.source);source['sourceMap'][0]['transformedStartUTF8']+=1
        with self.assertRaisesRegex(ValueError,'copied-byte map'):self.context(source=source)
        source=copy.deepcopy(self.source);source['sourceMap']=[]
        row=self.context(source=source).prove(source['sites'][0]);self.assertFalse(row['typedContextPreserved'])

    def test_actual_immutable_registry_literal_retains_exact_unique_outcomes(self):
        row=observer.registry_literal(self.runtime_tree,self.runtime_source,self.manifest['registry'])
        self.assertTrue(row['immutableRegistryLiteralProved'],row)
        self.assertEqual(row['uniqueSiteIDs'],len(self.manifest['registry']))
        self.assertFalse(row['observerNoninterferenceCertified']);self.assertFalse(row['applicationGateApproved'])
        effects=observer.initializer_effects(self.runtime_tree,self.runtime_source)
        self.assertTrue(effects['compilerCheckedNothrowInitialization'],effects)
        self.assertEqual(len(effects['calls']),2)

    def test_initializer_nothrow_fact_cannot_be_inferred_from_symbol_name(self):
        tree=copy.deepcopy(self.runtime_tree)
        for item in typed.walk(tree):
            if item['node']['kind']=='call_expr':item['node']['nothrow']=False
        self.assertFalse(observer.initializer_effects(tree,self.runtime_source)['compilerCheckedNothrowInitialization'])

    def test_changed_duplicate_or_mutable_registry_is_rejected(self):
        for mode in ['duplicate-key','duplicate-outcome','changed-key','mutable']:
            tree=copy.deepcopy(self.runtime_tree)
            if mode=='mutable':
                declaration=next(item for item in typed.walk(tree) if item['node']['kind']=='var_decl' and '"__fbRegistry"' in item['node']['header'])
                declaration['node']['header']=declaration['node']['header'].replace('private let','private var')
            else:
                initializer=next(item for item in typed.walk(tree) if item['node']['kind']=='dictionary_expr' and item['node']['header'].startswith('(processed_init=') and item['node'].get('type')=='[String : [String]]')
                if mode=='duplicate-key':initializer['children'].append(copy.deepcopy(initializer['children'][0]))
                elif mode=='duplicate-outcome':
                    array=initializer['children'][0]['children'][1];array['children'].append(copy.deepcopy(array['children'][0]))
                else:
                    literal=initializer['children'][0]['children'][0]['node'];literal['header']=re.sub(r'value="[^"]+"','value="wrong:0"',literal['header'])
            self.assertFalse(observer.registry_literal(tree,self.runtime_source,self.manifest['registry'])['immutableRegistryLiteralProved'])


if __name__=='__main__':unittest.main()
