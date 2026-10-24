"""Real compiler helper-transfer rules and adversarial SSA/CFG controls."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOL))
import semantic_contracts as contracts
import observer_callback_contracts as callbacks
import certification as cert
spec = importlib.util.spec_from_file_location('semantic_coverage',TOOL/'coverage.py')
coverage = importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)


class ActualHelperTransfer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(dir=coverage.ROOT/'.local-private',prefix='semantic-kernel-')
        cls.root = Path(cls.temp.name)
        source = cls.root/'Runtime.swift'
        source.write_text(coverage.render_runtime({'fixture':['nil','some']},'fixture','Control'))
        env = {**os.environ,'DEVELOPER_DIR':coverage.DEVELOPER};env.pop('SDKROOT',None)
        sdk = subprocess.run(['xcrun','--sdk','macosx','--show-sdk-path'],env=env,text=True,capture_output=True,check=True).stdout.strip()
        compiler = Path(coverage.DEVELOPER)/'Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc'
        result = subprocess.run([str(compiler),'-j1','-parse-as-library','-sdk',sdk,'-module-name','Control','-swift-version','5',
                 '-module-cache-path',str(cls.root/'cache'),'-emit-loaded-module-trace-path',str(cls.root/'loaded.json'),
                 '-emit-silgen',str(source)],env=env,text=True,capture_output=True,check=True)
        cls.raw = result.stdout
        cls.inventory = contracts.functions(cls.raw)
        loaded=json.loads((cls.root/'loaded.json').read_text())
        cls.interface=cert.artifact(Path(next(path for path in loaded['swiftmodules'] if '/Swift.swiftmodule/' in path)))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def assert_blocked(self,helper,edit):
        inventory = copy.deepcopy(self.inventory)
        function = contracts.unique_function(inventory,'Control',helper)
        before = '\n'.join(function.lines);after = edit(before)
        self.assertNotEqual(before,after,'negative control did not modify the real lowering')
        function.lines = after.splitlines()
        try: result = contracts.exact_transfer_rule(inventory,'Control',helper)
        except ValueError: return
        self.assertFalse(result['proved'],result)
        self.assertFalse(result['semanticEquivalenceCertified'])
        self.assertFalse(result['applicationGateApproved'])

    def test_real_boolean_and_optional_complete_transfer_programs(self):
        for helper in ['boolean','optional','selected','attempt']:
            result = contracts.exact_transfer_rule(self.inventory,'Control',helper)
            self.assertTrue(result['proved'],result)
            self.assertFalse(result['semanticEquivalenceCertified'])
            self.assertFalse(result['applicationGateApproved'])

    def test_boolean_wrong_return_and_condition_block(self):
        self.assert_blocked('boolean',lambda s:re.sub(r'return %1(?=,|\s|$)','return %0',s))
        self.assert_blocked('boolean',lambda s:s.replace('struct_extract %1, #Bool._value','struct_extract %0, #Bool._value'))

    def test_optional_take_extra_copy_and_wrong_nil_test_block(self):
        self.assert_blocked('optional',lambda s:s.replace('copy_addr %2 to [init] %0','copy_addr [take] %2 to [init] %0'))
        self.assert_blocked('optional',lambda s:s.replace('copy_addr %2 to [init] %0','copy_addr %2 to [init] %0\n  copy_addr %2 to [init] %0'))
        self.assert_blocked('optional',lambda s:s.replace('$sSq2eeoiySbxSg_s26_OptionalNilComparisonTypeVtFZ','$sSq2neoiySbxSg_s26_OptionalNilComparisonTypeVtFZ'))

    def test_wrong_outcome_literal_or_flow_blocks(self):
        self.assert_blocked('boolean',lambda s:s.replace('utf8 "true"','utf8 "false"'))
        self.assert_blocked('optional',lambda s:re.sub(r'br bb3\(%\d+\)', 'br bb3(%999)',s,count=1))
        self.assert_blocked('boolean',lambda s:re.sub(r'(bb3\(%\d+ : )@owned',r'\1@guaranteed',s))

    def test_payload_escape_extra_instruction_and_different_recorder_block(self):
        self.assert_blocked('optional',lambda s:s.replace('copy_addr %2 to [init] %0','destroy_addr %2\n  copy_addr %2 to [init] %0'))
        self.assert_blocked('boolean',lambda s:re.sub(r'(apply %\d+\(%0, )%\d+(, %2\))',r'\1%0\2',s))
        self.assert_blocked('boolean',lambda s:s.replace('7__FBCovO6record','7__FBCovO6evilxx'))

    def test_callback_repeat_wrong_success_error_and_delegation_arguments_block(self):
        self.assert_blocked('attempt',lambda s:re.sub(r'(try_apply %\d+)\(',r'\1(%999, ',s,count=1))
        self.assert_blocked('attempt',lambda s:s.replace('utf8 "success"','utf8 "error"'))
        self.assert_blocked('attempt',lambda s:re.sub(r'throw %\d+', 'throw %999',s,count=1))
        self.assert_blocked('selected',lambda s:re.sub(r'(apply %\d+\(%0, )%1',r'\1%0',s,count=1))

    def test_ssa_renaming_and_debug_metadata_are_immaterial(self):
        inventory = copy.deepcopy(self.inventory)
        for helper in ['boolean','optional']:
            function = contracts.unique_function(inventory,'Control',helper)
            function.lines = [re.sub(r'%(\d+)',lambda m:'%'+str(int(m[1])+300),line) for line in function.lines]
            self.assertTrue(contracts.exact_transfer_rule(inventory,'Control',helper)['proved'])

    def test_repeated_conflicting_helper_definitions_fail_closed(self):
        function = contracts.unique_function(self.inventory,'Control','boolean')
        body = function.header+'\n'+'\n'.join(function.lines)+'\n} // end sil function\n'
        inventory = contracts.functions(body+body.replace('utf8 "true"','utf8 "wrong"'))
        with self.assertRaisesRegex(ValueError,'Conflicting repeated'):
            contracts.instructions(contracts.unique_function(inventory,'Control','boolean'))

    def test_quoted_comment_metadata_and_ssa_text_remain_semantic_literals(self):
        function=contracts.unique_function(self.inventory,'Control','boolean')
        body=function.header+'\n'+'\n'.join(function.lines)+'\n} // end sil function\n'
        first=body.replace('utf8 "true"','utf8 "true // %12, loc one"')
        second=body.replace('utf8 "true"','utf8 "true // %13, loc two"')
        inventory=contracts.functions(first+second)
        with self.assertRaisesRegex(ValueError,'Conflicting repeated'):
            contracts.instructions(contracts.unique_function(inventory,'Control','boolean'))
        literal='%12 = string_literal utf8 "%3 // text, loc literal"'
        normalized=contracts.transfer_program([literal],'unrelated')
        self.assertEqual(normalized,['%v0 = string_literal utf8 "%3 // text, loc literal"'])

    def test_no_rule_can_approve_other_transformation_families(self):
        for helper in ['sequence','beginAvailable','passAvailable','finishAvailable']:
            with self.assertRaises(ValueError):contracts.exact_transfer_rule(self.inventory,'Control',helper)

    def test_actual_initialization_callback_and_typed_error_transfers(self):
        row=callbacks.prove(self.inventory,'Control',self.interface)
        self.assertTrue(row['callbackBindingsAndErrorTransfersProved'],row)
        self.assertEqual(len(row['programs']),3)
        self.assertFalse(row['observerNoninterferenceCertified']);self.assertFalse(row['semanticEquivalenceCertified']);self.assertFalse(row['applicationGateApproved'])

    def test_initializer_callback_mutations_do_not_inherit_contract(self):
        row=callbacks.prove(self.inventory,'Control',self.interface)
        self.assertTrue(row['callbackBindingsAndErrorTransfersProved'],row)
        functions={entry['label']:entry['symbol'] for entry in row['programs']}
        edits=[('literal-pair',lambda s:s.replace('$Builtin.IntLiteral, 0','$Builtin.IntLiteral, 1')),
               ('literal-pair',lambda s:s.replace('copy_value %3','copy_value %1')),
               ('array-map',lambda s:s.replace('Dictionary<String, UInt64>','Dictionary<String, String>')),
               ('array-map',lambda s:s.replace('Never>','any Error>')),
               ('initializer',lambda s:s.replace('thin_to_thick_function','partial_apply'))]
        for label,edit in edits:
            inventory=copy.deepcopy(self.inventory);function=inventory[functions[label]]
            before='\n'.join(function.lines);after=edit(before);self.assertNotEqual(before,after)
            function.lines=after.splitlines()
            self.assertFalse(callbacks.prove(inventory,'Control',self.interface)['callbackBindingsAndErrorTransfersProved'])

    def test_changed_swift_interface_binding_is_rejected(self):
        record={**self.interface,'sha256':'0'*64}
        self.assertFalse(callbacks.prove(self.inventory,'Control',record)['callbackBindingsAndErrorTransfersProved'])


if __name__ == '__main__': unittest.main()
