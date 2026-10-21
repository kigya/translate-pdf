"""Contract tests for honest cross-tool native coverage aggregation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "native_coverage_gate", ROOT / "scripts/check-native-coverage.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class NativeCoverageGateTest(unittest.TestCase):
    def test_exact_host_harness_exclusions_do_not_exclude_production_core(self):
        owned = GATE.load_owned_sources(ROOT / "scripts/native-owned-sources.txt")
        audited = GATE.verify_source_inventory(ROOT, owned)
        self.assertIn("native/core.cpp", owned)
        self.assertIn("native/core_detail.hpp", owned)
        self.assertIn("native/core_operations.hpp", owned)
        self.assertNotIn("native/tests/core_detail_test.cpp", owned)
        self.assertIn("native/tests/core_detail_test.cpp", GATE.EXCLUDED_NATIVE_SOURCES)
        self.assertNotIn("native/tests/core_fault_test.cpp", owned)
        self.assertIn("native/tests/core_fault_test.cpp", GATE.EXCLUDED_NATIVE_SOURCES)
        self.assertEqual(
            {"native/core_detail.hpp", "native/core_operations.hpp"}, set(audited),
        )
        self.assertTrue(all(item["function_body_count"] == 0 for item in audited.values()))

    def test_declaration_only_headers_pass_only_with_exact_source_audit_and_empty_counters(self):
        owned = ["native/core_detail.hpp", "native/core_operations.hpp"]
        audited = GATE.verify_source_inventory(ROOT, GATE.load_owned_sources(ROOT / "scripts/native-owned-sources.txt"))
        result = GATE.analyze(owned, {}, declaration_only_sources=audited)
        self.assertEqual(["passed", "passed"], [item["status"] for item in result["results"]])
        for item in result["results"]:
            self.assertEqual(0, item["source_audit"]["function_body_count"])
            self.assertEqual({0}, {metric["count"] for metric in item["metrics"].values()})
            self.assertEqual(100, item["metrics"]["functions"]["percent"])

        llvm_with_executable_code = {
            "native/core_detail.hpp": {
                "lines": {"percent": 100, "covered": 1, "count": 1},
                "functions": {"percent": 100, "covered": 1, "count": 1},
                "regions": {"percent": 100, "covered": 1, "count": 1},
                "branches": {"percent": 100, "covered": 0, "count": 0},
            },
        }
        rejected = GATE.analyze(owned, llvm_with_executable_code, declaration_only_sources=audited)
        self.assertEqual("failed", rejected["results"][0]["status"])
        self.assertIn("LLVM reports executable counters", rejected["results"][0]["errors"][0])

    def test_cpp_declaration_audit_detects_a_function_body(self):
        self.assertEqual(0, GATE.cpp_function_body_count("int declared(int value); struct X { int field; };"))
        self.assertEqual(1, GATE.cpp_function_body_count("int implementation(int value) { return value; }"))

    def test_declaration_only_classification_rejects_any_source_hash_change(self):
        original = (ROOT / "native/core_detail.hpp").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "native/core_detail.hpp"
            path.parent.mkdir()
            path.write_text(original + "\ninline int added() { return 1; }\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "changed and needs coverage review"):
                GATE.declaration_only_evidence(Path(directory), "native/core_detail.hpp")

    def test_jacoco_maps_supported_counters_and_does_not_claim_regions(self):
        report = """<report name="app"><package name="dev/kigya/translatepdf">
          <sourcefile name="Example.kt">
            <counter type="INSTRUCTION" missed="2" covered="8"/>
            <counter type="LINE" missed="1" covered="3"/>
            <counter type="METHOD" missed="0" covered="2"/>
            <counter type="BRANCH" missed="1" covered="1"/>
          </sourcefile></package></report>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jacoco.xml"
            path.write_text(report, encoding="utf-8")
            files = GATE.load_jacoco_files(path)

        metrics = files["dev/kigya/translatepdf/Example.kt"]
        self.assertEqual((3, 4, 75.0),
                         (metrics["lines"]["covered"], metrics["lines"]["count"], metrics["lines"]["percent"]))
        self.assertEqual("JaCoCo METHOD", metrics["functions"]["provenance"])
        self.assertEqual((8, 10), (metrics["instructions"]["covered"], metrics["instructions"]["count"]))
        self.assertNotIn("regions", metrics)
        result = GATE.analyze(["android/app/src/main/java/dev/kigya/translatepdf/Example.kt"], {}, files)
        self.assertEqual("failed", result["results"][0]["status"])
        self.assertIn("instructions coverage 80%", result["results"][0]["errors"])
        self.assertEqual("not_applicable", result["results"][0]["metrics"]["regions"]["status"])

    def test_jacoco_requires_every_real_jvm_metric_at_one_hundred_percent(self):
        source = "android/app/src/main/java/dev/kigya/translatepdf/Example.kt"
        counters = {"INSTRUCTION": (0, 10), "LINE": (0, 4), "METHOD": (0, 2), "BRANCH": (0, 6)}
        for failing_counter in counters:
            report_counters = []
            for kind, (missed, covered) in counters.items():
                if kind == failing_counter:
                    missed, covered = 1, max(covered - 1, 0)
                report_counters.append(f'<counter type="{kind}" missed="{missed}" covered="{covered}"/>')
            report = '<report><package name="dev/kigya/translatepdf"><sourcefile name="Example.kt">' + ''.join(report_counters) + '</sourcefile></package></report>'
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "jacoco.xml"
                path.write_text(report, encoding="utf-8")
                result = GATE.analyze([source], {}, GATE.load_jacoco_files(path))
            self.assertEqual("failed", result["results"][0]["status"], failing_counter)
            self.assertTrue(any("coverage" in error or "measurable" in error for error in result["results"][0]["errors"]))

    def test_llvm_source_metrics_take_precedence_without_hiding_missing_files(self):
        llvm = {"native/core.cpp": {
            "lines": {"percent": 100, "covered": 2, "count": 2},
            "functions": {"percent": 100, "covered": 1, "count": 1},
            "regions": {"percent": 100, "covered": 2, "count": 2},
            "branches": {"percent": 100, "covered": 0, "count": 0},
        }}
        result = GATE.analyze(["native/core.cpp", "native/android_bridge.cpp"], llvm)
        self.assertEqual("passed", result["results"][0]["status"])
        self.assertEqual("unmeasured", result["results"][1]["status"])
        self.assertEqual(100, result["results"][0]["metrics"]["regions"]["percent"])

    def test_zero_coverage_regions_fail_and_do_not_pass_as_empty_branch_only(self):
        summary = {"lines": {"percent": 100, "covered": 1, "count": 1},
                   "functions": {"percent": 100, "covered": 1, "count": 1},
                   "regions": {"percent": 0, "covered": 0, "count": 1},
                   "branches": {"percent": 100, "covered": 0, "count": 0}}
        result = GATE.analyze(["native/core.cpp"], {"native/core.cpp": summary})
        self.assertEqual("failed", result["results"][0]["status"])
        self.assertIn("regions coverage 0%", result["results"][0]["errors"])

    def test_swift_export_without_branch_records_is_reported_as_missing(self):
        export = {"data": [{"files": [{"filename": "ios/TranslatePDF/TranslatorStore.swift",
            "summary": {"lines": {"percent": 100, "covered": 1, "count": 1},
                        "functions": {"percent": 100, "covered": 1, "count": 1},
                        "regions": {"percent": 100, "covered": 1, "count": 1},
                        "branches": {"percent": 100, "covered": 0, "count": 0}}}]}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "swift.json"
            path.write_text(json.dumps(export), encoding="utf-8")
            files = GATE.load_llvm_files(path)

        result = GATE.analyze(["ios/TranslatePDF/TranslatorStore.swift"], files)
        self.assertEqual("failed", result["results"][0]["status"])
        self.assertIn("missing branches coverage data", result["results"][0]["errors"])

    def test_xccov_imports_real_xctest_lines_and_functions_but_keeps_missing_regions(self):
        export = {"targets": [{"name": "libTranslateCore.a", "files": [{
            "path": "/workspace/ios/TranslatePDF/ModelCatalog.swift",
            "coveredLines": 3,
            "executableLines": 4,
            "functions": [
                {"executionCount": 2},
                {"executionCount": 0},
            ],
        }]}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "xccov.json"
            path.write_text(json.dumps(export), encoding="utf-8")
            files = GATE.load_xccov_files(path)

        metrics = GATE.find_coverage("ios/TranslatePDF/ModelCatalog.swift", files)
        self.assertEqual((3, 4, 75.0),
                         (metrics["lines"]["covered"], metrics["lines"]["count"], metrics["lines"]["percent"]))
        self.assertEqual((1, 2, 50.0),
                         (metrics["functions"]["covered"], metrics["functions"]["count"], metrics["functions"]["percent"]))
        self.assertEqual("Xcode xccov XCTest line coverage", metrics["lines"]["provenance"])
        self.assertNotIn("regions", metrics)
        result = GATE.analyze(["ios/TranslatePDF/ModelCatalog.swift"], {}, xccov_files=files)
        self.assertEqual("failed", result["results"][0]["status"])
        self.assertIn("missing regions coverage data", result["results"][0]["errors"])
        self.assertIn("missing branches coverage data", result["results"][0]["errors"])

    def test_unique_basename_maps_legacy_kotlin_source_directory(self):
        files = {"dev/kigya/translatepdf/MainActivity.kt": {"lines": {"covered": 1, "count": 1}}}
        self.assertEqual(files["dev/kigya/translatepdf/MainActivity.kt"],
                         GATE.find_coverage("android/app/src/main/java/com/translatepdf/MainActivity.kt", files))

    def test_duplicate_basename_fallback_is_rejected(self):
        files = {"dev/a/MainActivity.kt": {}, "dev/b/MainActivity.kt": {}}
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            GATE.find_coverage("android/app/src/main/java/com/translatepdf/MainActivity.kt", files)


if __name__ == "__main__":
    unittest.main()
