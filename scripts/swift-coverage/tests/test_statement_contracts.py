"""Real compiler selected-arm structure and adversarial probe/body controls."""
import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL=Path(__file__).resolve().parents[1];sys.path.insert(0,str(TOOL))
import certification as cert
import prove_local_contracts as local
import semantic_contracts as sil
import statement_contracts as statement
import typed_contracts as typed
import json


class ActualSelectedArm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(dir=cert.coverage.ROOT/'.local-private',prefix='selected-arm-')
        cls.root=Path(cls.temp.name);cls.path=cls.root/'Arm.swift'
        cls.path.write_text('''func choose(_ value:Int)->String {
  switch value {
  case 0: return "zero"
  case 1: return "range=[one] location=literal"
  default: return "other"
  }
}
''')
        cls.manifest=cert.coverage.prepare(cls.root/'prepared',[cls.path]);cls.source=cls.manifest['sources'][0]
        tool=cls.root/'lexical';tool.mkdir();binary,_=local.syntax_tool(tool)
        cls.syntax={};cls.trees={}
        env={**os.environ,'DEVELOPER_DIR':cert.coverage.DEVELOPER};env.pop('SDKROOT',None)
        sdk=cert.coverage.run(['xcrun','--sdk','macosx','--show-sdk-path']).strip()
        for variant,path in [('original',cls.path),('transformed',Path(cls.source['transformed']))]:
            output=tool/(variant+'.json');subprocess.run([str(binary),str(path),str(output)],check=True,capture_output=True,text=True)
            cls.syntax[variant]=json.loads(output.read_text())
            arguments=['-j1','-sdk',sdk,'-module-name','Arm','-swift-version','5','-module-cache-path',str(cls.root/(variant+'-cache')),str(cls.path)]
            if variant=='transformed':arguments+=['-vfsoverlay',str(cls.root/'prepared/overlay.json'),str(cls.root/'prepared/BranchRuntime.swift')]
            result=subprocess.run([str(cert.COMPILER),*arguments,'-dump-ast'],env=env,check=True,text=True,capture_output=True)
            raw=result.stdout if '(source_file ' in result.stdout else result.stderr
            cls.trees[variant]=typed.source_tree(cert.typed_nodes(raw),str(cls.path))
            if variant=='transformed':
                result=subprocess.run([str(cert.COMPILER),*arguments,'-emit-silgen'],env=env,check=True,text=True,capture_output=True)
                raw=result.stdout if 'sil_stage raw' in result.stdout else result.stderr
                cls.kernel=sil.exact_transfer_rule(sil.functions(raw),'Arm','selected')
        cls.calls={x['registryID']:x for x in cls.syntax['transformed']['recorderCalls'] if x.get('registryID')}

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def check(self,site,tree=None,call=None):
        return statement.statement_rule(self.source,site,call or self.calls[self.source['namespace']+':'+str(site['id'])],
            self.trees['original'],tree or self.trees['transformed'],self.syntax['original'],self.syntax['transformed'],'Arm',self.kernel)

    def test_actual_string_literal_arms_preserve_pattern_and_body(self):
        self.assertEqual(len(self.source['sites']),3)
        for site in self.source['sites']:
            row=self.check(site);self.assertTrue(row['originalArmStructurePreserved'],row)
            self.assertFalse(row['applicationGateApproved']);self.assertFalse(row['semanticEquivalenceCertified'])
            self.assertTrue(row['remainingObligations'])

    def test_changed_probe_literal_or_resolved_helper_is_blocked(self):
        for old,new in [('value="selected"','value="unselected"'),('.selected@','.different@')]:
            tree=copy.deepcopy(self.trees['transformed']);changed=False
            for item in typed.walk(tree):
                node=item['node']
                if old in node['header']:
                    node['header']=node['header'].replace(old,new);changed=True
                    if 'decl' in node:node['decl']=node['decl'].replace(old,new)
            self.assertTrue(changed)
            self.assertTrue(all(not self.check(site,tree)['originalArmStructurePreserved'] for site in self.source['sites']))

    def test_changed_original_literal_body_is_blocked(self):
        tree=copy.deepcopy(self.trees['transformed']);changed=False
        for item in typed.walk(tree):
            node=item['node']
            if 'value="zero"' in node['header']:
                node['header']=node['header'].replace('value="zero"','value="different"');changed=True
        self.assertTrue(changed)
        self.assertFalse(self.check(self.source['sites'][0],tree)['originalArmStructurePreserved'])

    def test_split_pattern_dispatch_cannot_inherit_this_rule(self):
        site={**self.source['sites'][0],'kind':'switch-pattern-dispatch'}
        self.assertFalse(self.check(site)['originalArmStructurePreserved'])


if __name__=='__main__':unittest.main()
