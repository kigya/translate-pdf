"""Dependency-graph metadata controls, never app/compiler execution receipts."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("dependency_evidence_tool", TOOL / "dependency_evidence.py")
dependencies = importlib.util.module_from_spec(spec); spec.loader.exec_module(dependencies)
union_spec = importlib.util.spec_from_file_location("configuration_union_tool",TOOL/"configuration_union.py")
union = importlib.util.module_from_spec(union_spec);union_spec.loader.exec_module(union)


class GraphInputControls(unittest.TestCase):
    def test_required_variants_bind_exact_arm64_ios17_target_and_language(self):
        job={'target':'arm64-apple-ios17.0-simulator','swiftDefines':['DEBUG'],'languageMode':'5'}
        self.assertTrue(union.required_variant_matches('debug-simulator',job))
        for key,value in [('target','x86_64-apple-ios17.0-simulator'),('target','arm64-apple-ios18.0-simulator'),
                          ('target','arm64-apple-tvos17.0-simulator'),('languageMode','6'),('swiftDefines',[])]:
            self.assertFalse(union.required_variant_matches('debug-simulator',{**job,key:value}))
        self.assertFalse(union.required_variant_matches('release-device',job))

    def test_existing_inputs_are_hashed_and_planned_outputs_are_not_observed(self):
        with tempfile.TemporaryDirectory(dir=dependencies.cert.coverage.ROOT / ".local-private") as temporary:
            root = Path(temporary)
            for name in ("Source.swift", "header.h", "module.modulemap", "Imported.swiftmodule"):
                (root / name).write_text("metadata control\n")
            graph = {"mainModuleName": "Control", "modules": [
                {"swift": "Control"}, {"sourceFiles": ["Source.swift"], "modulePath": "Planned.swiftmodule"},
                {"clang": "ClangControl"}, {"fileDependencies": ["header.h"], "details": {"clang": {"moduleMapPath": "module.modulemap"}}},
                {"swiftPrebuiltExternal": "Imported"}, {"modulePath": "Imported.swiftmodule"}]}
            actual = dependencies.graph_inputs(graph, root, "Control")
            self.assertEqual(len(actual["inputs"]), 4)
            self.assertEqual(actual["mainModuleSources"],[str(root/"Source.swift")])
            self.assertEqual(actual["missingDeclaredInputs"], [])
            self.assertEqual(actual["plannedModuleOutputs"], [str(root / "Planned.swiftmodule")])
            self.assertTrue(all(len(item["sha256"]) == 64 for item in actual["inputs"]))
            (root / "header.h").unlink()
            actual = dependencies.graph_inputs(graph, root, "Control")
            self.assertEqual(actual["missingDeclaredInputs"], [{"path": str(root / "header.h"), "inputKinds": ["fileDependencies"]}])
            (root / "Imported.swiftmodule").unlink()
            actual = dependencies.graph_inputs(graph, root, "Control")
            self.assertTrue(any(item["path"] == str(root / "Imported.swiftmodule") and item["inputKinds"] == ["prebuilt-modulePath"] for item in actual["missingDeclaredInputs"]))
            self.assertNotIn(str(root / "Imported.swiftmodule"),actual["plannedModuleOutputs"])

    def test_imported_sources_and_macro_inputs_are_bound_separately_from_main_sources(self):
        with tempfile.TemporaryDirectory(dir=dependencies.cert.coverage.ROOT / ".local-private") as temporary:
            root=Path(temporary)
            for name in ['Main.swift','Imported.swiftinterface','Macro.dylib','plugin-server']:(root/name).write_text('metadata control')
            graph={'mainModuleName':'Main','modules':[
                {'swift':'Main'},{'sourceFiles':['Main.swift'],'modulePath':'Main.swiftmodule'},
                {'swift':'Imported'},{'sourceFiles':['Imported.swiftinterface'],'modulePath':'Imported.swiftmodule',
                  'details':{'swift':{'moduleInterfacePath':'Imported.swiftinterface','macroDependencies':[
                    {'moduleName':'Macro','libraryPath':'Macro.dylib','executablePath':'plugin-server'},
                    {'moduleName':'ExecutableMacro','libraryPath':'','executablePath':'plugin-server'}]}}}]}
            result=dependencies.graph_inputs(graph,root,'Main')
            self.assertEqual(result['mainModuleSources'],[str(root/'Main.swift')])
            self.assertEqual(len(result['inputs']),4)
            (root/'Macro.dylib').unlink()
            result=dependencies.graph_inputs(graph,root,'Main')
            self.assertEqual(result['missingDeclaredInputs'],[{'path':str(root/'Macro.dylib'),'inputKinds':['libraryPath']}])

    def test_single_and_repeated_real_driver_stream_shapes_are_unambiguous(self):
        with tempfile.TemporaryDirectory(dir=dependencies.cert.coverage.ROOT/".local-private") as temporary:
            root=Path(temporary);out=root/'out';err=root/'err';out.write_text('');err.write_text('')
            invocation={'stdout':{'path':str(out)},'stderr':{'path':str(err)}}
            graph={'mainModuleName':'Control','modules':[]};text=json.dumps(graph)
            err.write_text(text+'\n'+text)
            value,raw,count=dependencies.dependency_output(invocation)
            self.assertEqual(value,graph);self.assertEqual(count,2);self.assertEqual(raw['path'],str(err))
            err.write_text(text+'\n'+text.replace('Control','Different'))
            with self.assertRaisesRegex(ValueError,'Nonidentical'):dependencies.dependency_output(invocation)
            err.write_text(text);out.write_text(text)
            with self.assertRaisesRegex(ValueError,'ambiguous'):dependencies.dependency_output(invocation)

    def test_wrong_module_empty_and_malformed_graphs_block(self):
        for graph in ({}, {"mainModuleName": "Other", "modules": [{}]}, {"mainModuleName": "Control", "modules": []},
                      {"mainModuleName": "Control", "modules": [{"sourceFiles": "not-a-list"}]},
                      {"mainModuleName": "Control", "modules": [{"sourceFiles": [None]}]}):
            with self.assertRaises(ValueError): dependencies.graph_inputs(graph, Path("."), "Control")


if __name__ == "__main__": unittest.main()
