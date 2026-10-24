"""Identity controls only; metadata are never claimed as runtime observations."""
import copy
import importlib.util
from pathlib import Path
import unittest

TOOL=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('identity_coverage',TOOL/'coverage.py')
coverage=importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)

class MeasurementIdentityTests(unittest.TestCase):
    def sample(self):
        return {'compiler':{'version':'pinned compiler','toolSourceSHA256':'tool','toolBinarySHA256':'execution-binary','command':['swiftc','-module-cache-path','/run/A/cache','-o','/run/A/tool']},
          'sources':[{'source':'/production.swift','sourceSHA256':'source','transformedBodySHA256':'body','astSHA256':'syntax',
            'configuration':{'defines':['DEBUG'],'chainReadHints':[{'selectedDeclaration':'actual declaration','rule':'typed-rvalue-receiver'}],
              'chainReadHintsProof':{'originalTypedAST':{'path':'/run/A/ast','sha256':'dump-with-allocation-IDs'}}},
            'sites':[{'kind':'condition','outcomes':['false','true'],'metricRole':'source-condition'}]}],
          'registry':{'site':['false','true']},'toolDriverSHA256':'driver','runtimeTemplateSHA256':'runtime','chainReadPolicySHA256':'policy','metricPolicy':{'version':'v1'},
          'extraOverlays':[{'transformedSHA256':'export-driver-A'}],'execution':{'runIDs':['A']}}
    def test_only_execution_paths_driver_dump_allocation_ids_are_normalized(self):
        a=self.sample();b=copy.deepcopy(a)
        b['compiler']['command']=['swiftc','-module-cache-path','/run/B/cache','-o','/run/B/tool'];b['compiler']['toolBinarySHA256']='different-execution-binary'
        b['sources'][0]['configuration']['chainReadHintsProof']['originalTypedAST']={'path':'/run/B/ast','sha256':'other-raw-dump'}
        b['extraOverlays'][0]['transformedSHA256']='export-driver-B';b['execution']['runIDs']=['B']
        self.assertEqual(coverage.registry_payload(a),coverage.registry_payload(b))
        self.assertNotEqual(coverage.digest(coverage.preparation_payload(a)),coverage.digest(coverage.preparation_payload(b)))
    def test_source_tool_configuration_selected_facts_roles_and_outcomes_remain_bound(self):
        a=self.sample();baseline=coverage.digest(coverage.registry_payload(a))
        for field in ['sourceSHA256','transformedBodySHA256','astSHA256']:
            b=copy.deepcopy(a);b['sources'][0][field]='changed';self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
        for field in ['toolDriverSHA256','runtimeTemplateSHA256','chainReadPolicySHA256']:
            b=copy.deepcopy(a);b[field]='changed';self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
        b=copy.deepcopy(a);b['sources'][0]['configuration']['defines'].append('OTHER');self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
        b=copy.deepcopy(a);b['sources'][0]['configuration']['chainReadHints'][0]['selectedDeclaration']='other';self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
        b=copy.deepcopy(a);b['sources'][0]['sites'][0]['metricRole']='composite-diagnostic';self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
        b=copy.deepcopy(a);b['registry']['site']=['true'];self.assertNotEqual(baseline,coverage.digest(coverage.registry_payload(b)))
