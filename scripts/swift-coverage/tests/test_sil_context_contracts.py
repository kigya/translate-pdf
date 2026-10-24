"""Real compiler function-level Bool projection with adversarial controls."""
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
import prove_local_contracts as local
import semantic_contracts as sil
import sil_context_contracts as context


class ActualSILContext(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(dir=cert.coverage.ROOT/'.local-private',prefix='sil-context-')
        cls.root=Path(cls.temp.name);cls.path=cls.root/'Trace.swift'
        cls.path.write_text('''func choose(_ flag:Bool)->Int {
  if flag { return 1 }
  return 0
}
func repeated(_ first:Bool,_ second:Bool)->Int {
  if first { return 2 }
  if second { return 1 }
  return 0
}
func conditional(_ first:Bool,_ second:Bool)->Int {
  if first ? second : false { return 1 }
  return 0
}
''')
        cls.manifest=cert.coverage.prepare(cls.root/'prepared',[cls.path]);cls.sources=cls.manifest['sources']
        tool=cls.root/'lexical';tool.mkdir();binary,_=local.syntax_tool(tool)
        output=tool/'transformed.json'
        subprocess.run([str(binary),cls.sources[0]['transformed'],str(output)],check=True,text=True,capture_output=True)
        cls.syntax={str(cls.path):json.loads(output.read_text())};cls.inventories={}
        env={**os.environ,'DEVELOPER_DIR':cert.coverage.DEVELOPER};env.pop('SDKROOT',None)
        sdk=cert.coverage.run(['xcrun','--sdk','macosx','--show-sdk-path']).strip()
        for variant in ['original','transformed']:
            arguments=['-j1','-sdk',sdk,'-module-name','Trace','-swift-version','5','-module-cache-path',str(cls.root/(variant+'-cache')),str(cls.path)]
            if variant=='transformed':arguments+=['-vfsoverlay',str(cls.root/'prepared/overlay.json'),str(cls.root/'prepared/BranchRuntime.swift')]
            result=subprocess.run([str(cert.COMPILER),*arguments,'-emit-silgen','-Xllvm','-sil-print-debuginfo'],env=env,check=True,text=True,capture_output=True)
            raw=result.stdout if 'sil_stage raw' in result.stdout else result.stderr
            cls.inventories[variant]=sil.functions(raw)
        cls.symbols=[symbol for symbol in cls.inventories['original'] if symbol.startswith('$s5Trace') and any(name in symbol for name in ['choose','repeated','conditional'])]

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def prove(self,symbol=None,new=None,inventory=None,syntax=None):
        symbol=symbol or self.symbols[0]
        return context.prove_function(self.inventories['original'][symbol],new or self.inventories['transformed'][symbol],
          inventory or self.inventories['transformed'],'Trace',self.sources,syntax or self.syntax)

    def test_actual_whole_cfg_with_one_or_two_bool_additions_matches(self):
        self.assertEqual(len(self.symbols),3)
        for symbol in self.symbols:
            row=self.prove(symbol);self.assertTrue(row['retainedOwnedSILIdentical'],row)
            self.assertTrue(row['contractedSites']);self.assertFalse(row['semanticEquivalenceCertified']);self.assertFalse(row['applicationGateApproved'])
            self.assertTrue(row['remainingObligations'])
        conditional=next(symbol for symbol in self.symbols if 'conditional' in symbol)
        row=self.prove(conditional)
        self.assertTrue(any(site['privateLiteralLifetimeRule']=='exclusive-acyclic-CFG-dominance-and-postdominance' for site in row['contractedSites']))

    def test_changed_owned_instruction_or_control_edge_is_retained_and_rejected(self):
        for old,new in [('$Builtin.IntLiteral, 1','$Builtin.IntLiteral, 7'),('cond_br','br')]:
            fn=copy.deepcopy(self.inventories['transformed'][self.symbols[0]])
            before='\n'.join(fn.lines);after=before.replace(old,new);self.assertNotEqual(before,after)
            fn.lines=after.splitlines();self.assertFalse(self.prove(new=fn)['retainedOwnedSILIdentical'])

    def test_escaped_private_string_wrong_literal_and_call_location_fail_closed(self):
        original=self.inventories['transformed'][self.symbols[0]]
        for edit in [lambda s:re.sub(r'(destroy_value %\d+)',r'%999 = copy_value %0\n  \1',s,count=1),
                     lambda s:s.replace(self.sources[0]['namespace']+':0','wrong:0'),
                     lambda s:re.sub(r'(apply %\d+\([^\n]+ -> Bool, loc ")[^"]+',r'\1/wrong.swift',s)]:
            fn=copy.deepcopy(original);before='\n'.join(fn.lines);after=edit(before);self.assertNotEqual(before,after)
            fn.lines=after.splitlines();self.assertFalse(self.prove(new=fn)['retainedOwnedSILIdentical'])

    def test_wrong_actual_helper_and_syntax_site_binding_fail_closed(self):
        inventory=copy.deepcopy(self.inventories['transformed']);fn=sil.unique_function(inventory,'Trace','boolean')
        fn.lines=[re.sub(r'return %1(?=,|\s|$)','return %0',line) for line in fn.lines]
        self.assertFalse(self.prove(inventory=inventory)['retainedOwnedSILIdentical'])
        syntax=copy.deepcopy(self.syntax)
        syntax[str(self.path)]['recorderCalls'][0]['endUTF8']=0
        self.assertFalse(self.prove(syntax=syntax)['retainedOwnedSILIdentical'])

    def test_string_contents_are_never_metadata_or_ssa_tokens(self):
        line='%3 = string_literal utf8 "%12, loc quoted // text", loc "path":2:3, scope 4 // id'
        semantic=context.semantic_line(line)
        self.assertEqual(semantic,'%3 = string_literal utf8 "%12, loc quoted // text"')
        self.assertEqual(context.canonical([{'text':semantic}]),['%v0 = string_literal utf8 "%12, loc quoted // text"'])
        other=semantic.replace('%12, loc quoted','%13, loc changed')
        self.assertNotEqual(context.canonical([{'text':semantic}]),context.canonical([{'text':other}]))

    def test_cross_edge_private_literal_requires_postdominance_and_acyclic_cfg(self):
        def body(second):
            fn=sil.SILFunction('fixture','fixture',[
              'bb0:', '%0 = integer_literal $Builtin.Int1, 1','cond_br %0, bb1, bb2',
              'bb1:', '%1 = tuple ()','br bb3',
              'bb2:',second,
              'bb3:','%2 = tuple ()','return %2'],0)
            return context.program(fn)
        for terminator,message in [('return %0','postdominate'),('br bb2','CFG cycle')]:
            nodes=body(terminator)
            with self.assertRaisesRegex(ValueError,message):context.private_literal_lifetime(nodes,{1,4,9},1,4,9)


if __name__=='__main__':unittest.main()
