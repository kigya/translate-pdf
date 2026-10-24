"""Actual iOS compiler literal-construction grammar and memory/data controls."""
import copy
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

TOOL=Path(__file__).resolve().parents[1];sys.path.insert(0,str(TOOL))
import certification as cert
import semantic_contracts as sil
import registry_sil_contracts as registry_sil


class ActualIOSRegistry(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(dir=cert.coverage.ROOT/'.local-private',prefix='registry-sil-')
        cls.root=Path(cls.temp.name)
        cls.registry={'site:0':['false','true'],'site:1':['nil','some'],
                      'site:2':['success','error'],'site:3':['selected'],'site:4':['exhausted','item']}
        source=cls.root/'Registry.swift';source.write_text(cert.coverage.render_runtime(cls.registry,'fixture','RegistryControl'))
        sdk=cert.coverage.run(['xcrun','--sdk','iphonesimulator','--show-sdk-path']).strip()
        env={**os.environ,'DEVELOPER_DIR':cert.coverage.DEVELOPER};env.pop('SDKROOT',None)
        argv=[str(cert.COMPILER),'-j1','-parse-as-library','-sdk',sdk,'-target','arm64-apple-ios17.0-simulator',
              '-module-name','RegistryControl','-swift-version','5','-module-cache-path',str(cls.root/'cache'),'-emit-silgen',str(source)]
        result=subprocess.run(argv,env=env,check=True,text=True,capture_output=True)
        cls.inventory=sil.functions(result.stdout)
        cls.positive=registry_sil.prove(cls.inventory,'RegistryControl',cls.registry)

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def mutated(self,edit):
        self.assertTrue(self.positive['privateRegistryConstructionProgramProved'],self.positive)
        inventory=copy.deepcopy(self.inventory);function=inventory[self.positive['symbol']]
        before='\n'.join(function.lines);after=edit(before);self.assertNotEqual(before,after)
        function.lines=after.splitlines();row=registry_sil.prove(inventory,'RegistryControl',self.registry)
        self.assertFalse(row['privateRegistryConstructionProgramProved'],row)
        self.assertFalse(row['semanticEquivalenceCertified']);self.assertFalse(row['applicationGateApproved'])

    def test_actual_complete_literal_array_and_private_global_program(self):
        self.assertTrue(self.positive['privateRegistryConstructionProgramProved'],self.positive)
        self.assertEqual(self.positive['siteKeys'],5)
        self.assertEqual(self.positive['constructedArrays'],6)
        self.assertEqual(self.positive['literalStrings'],14)
        self.assertFalse(self.positive['observerNoninterferenceCertified']);self.assertFalse(self.positive['applicationGateApproved'])

    def test_changed_array_bound_index_or_memory_store_is_rejected(self):
        self.mutated(lambda s:s.replace('integer_literal $Builtin.Word, 5','integer_literal $Builtin.Word, 6',1))
        self.mutated(lambda s:s.replace('integer_literal $Builtin.Word, 1','integer_literal $Builtin.Word, 7',1))
        self.mutated(lambda s:re.sub(r'  store %\d+ to \[init\] %\d+[^\n]*\n','',s,count=1))

    def test_changed_literal_constructor_storage_or_foreign_global_is_rejected(self):
        self.mutated(lambda s:s.replace('utf8 "site:0"','utf8 "site:9"',1))
        self.mutated(lambda s:s.replace('@$ss27_allocateUninitializedArray','@$ss27_allocateUninitializedEvil',1))
        self.mutated(lambda s:s.replace('#_ArrayBuffer._storage','#_ArrayBuffer.evil',1))
        self.mutated(lambda s:re.sub(r'(global_addr @)[^ ]+',r'\1$foreignGlobal',s,count=1))

    def test_changed_source_registry_and_missing_or_duplicate_outcomes_are_rejected(self):
        for values in [[],['true','true'],['changed']]:
            registry=copy.deepcopy(self.registry);registry['site:0']=values
            self.assertFalse(registry_sil.prove(self.inventory,'RegistryControl',registry)['privateRegistryConstructionProgramProved'])


if __name__=='__main__':unittest.main()
