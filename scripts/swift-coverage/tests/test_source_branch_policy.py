"""Synthetic metadata controls; no fixture count is application runtime evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("source_branch_policy", TOOL / "source_branch_policy.py")
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class SourceBranchPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest_path = self.root / "manifest.json"
        self.raw_path = self.root / "runtime.json"
        self.ast_path = self.root / "syntax.json"
        self.snapshot = self.root / "original.swift"
        self.snapshot.write_text("x" * 4096)
        self.sites = []

    def write(self, path, value):
        path.write_text(json.dumps(value))
        return policy.sha256(path)

    def site(self, kind="condition", outcomes=None, composite=False, start=None, end=None):
        expected = {
            "condition": ("source-condition", ["false", "true"]),
            "ternary-condition": ("source-condition", ["false", "true"]),
            "logical-left": ("source-condition", ["false", "true"]),
            "logical-right": ("source-condition", ["false", "true"]),
            "for-where": ("source-condition", ["false", "true"]),
            "availability": ("source-condition", ["false", "true"]),
            "optional-binding": ("source-condition", ["nil", "some"]),
            "implicit-optional-binding": ("source-condition", ["nil", "some"]),
            "coalescing-left": ("operation-diagnostic", ["nil", "some"]),
            "optional-chain-base": ("operation-diagnostic", ["nil", "some"]),
            "conditional-cast": ("operation-diagnostic", ["nil", "some"]),
            "type-check": ("operation-diagnostic", ["false", "true"]),
            "throwing-expression": ("error-diagnostic", ["success", "error"]),
            "iterator-next": ("iteration-diagnostic", ["exhausted", "item"]),
            "switch-dispatch": ("dispatch-diagnostic", ["selected"]),
            "switch-pattern-dispatch": ("dispatch-diagnostic", ["selected"]),
            "catch-all": ("dispatch-diagnostic", ["caught"]),
            "catch-pattern": ("dispatch-diagnostic", ["nil", "some"]),
        }
        role, default_outcomes = expected[kind]
        index = len(self.sites)
        start = index * 8 if start is None else start
        site = {"id": index, "kind": kind, "startUTF8": start, "endUTF8": start + 4 if end is None else end,
                "outcomes": outcomes or default_outcomes, "metricRole": "composite-diagnostic" if composite else role,
                "metricRule": policy.COMPOSITE_RULE if composite else policy.RULES[role]}
        self.sites.append(site)
        return site

    def prepare(self):
        ast = {"schemaVersion": 1, "sites": self.sites, "unsupported": [], "reviewRequired": [], "structural": [],
               "classificationInventory": [], "configurationRegions": []}
        ast_sha = self.write(self.ast_path, ast)
        source = {"source": str(self.root / "production.swift"), "sourceSHA256": policy.sha256(self.snapshot),
                  "originalSnapshot": {"path": str(self.snapshot), "sha256": policy.sha256(self.snapshot)},
                  "ast": str(self.ast_path), "astSHA256": ast_sha, "namespace": "source", **copy.deepcopy(ast)}
        manifest = {"schemaVersion": 1, "registryDigest": "a" * 64, "preparationDigest": "b" * 64,
                    "registry": {f'source:{s["id"]}': s["outcomes"] for s in self.sites}, "sources": [source]}
        raw = {"schemaVersion": 1, "registryDigest": "a" * 64, "invalid": False, "runID": "fixture-run",
               "runtimeOS": "SYNTHETIC-METADATA-NOT-A-RUNTIME-CLAIM", "hits": {
                   key: {o: 0 for o in outcomes} for key, outcomes in manifest["registry"].items()}}
        self.write(self.manifest_path, manifest)
        self.write(self.raw_path, raw)
        return manifest, raw

    def analyze(self):
        return policy.analyze(self.manifest_path, policy.sha256(self.manifest_path),
                              [(self.raw_path, policy.sha256(self.raw_path))])

    def resave_source(self, manifest):
        source = manifest["sources"][0]
        ast = {"schemaVersion": 1, **{key: source[key] for key in ("sites", "unsupported", "reviewRequired", "structural", "classificationInventory", "configurationRegions")}}
        source["astSHA256"] = self.write(self.ast_path, ast)
        self.write(self.manifest_path, manifest)

    def test_every_kind_complete_and_single_selected_vs_binary_outcomes(self):
        binary = ["condition", "ternary-condition", "logical-left", "logical-right", "for-where", "availability",
                  "optional-binding", "implicit-optional-binding", "coalescing-left", "optional-chain-base", "iterator-next"]
        for kind in binary:
            self.site(kind)
        for kind in ["switch-dispatch", "switch-pattern-dispatch", "catch-all", "catch-pattern"]:
            self.site(kind)
        self.site("catch-pattern", outcomes=["false", "true"])
        for kind in ["conditional-cast", "type-check", "throwing-expression"]:
            self.site(kind)
        for kind in ["condition", "ternary-condition", "logical-left", "logical-right", "for-where"]:
            self.site(kind, composite=True)
        self.prepare()
        report = self.analyze()
        self.assertEqual(report["sourceBranchCoverage"]["denominator"], 2 * len(binary) + 5)
        self.assertEqual(report["rawSiteCount"], 24)
        self.assertEqual(report["rawOutcomeCount"], 45)
        self.assertEqual(len(report["allRawZeros"]), 45)
        self.assertEqual(report["sourceBranchCoverage"]["covered"], 0)
        for record in report["records"]:
            if record["originalSite"]["kind"] == "catch-pattern":
                self.assertEqual(len(record["eligibleOutcomes"]), 1)
                self.assertEqual(len(record["diagnosticOutcomes"]), 1)
        self.assertTrue(all(v == "not-evaluated" for v in report["acceptance"].values()))

    def test_actual_counts_are_not_rewritten_and_diagnostics_do_not_duplicate(self):
        self.site("condition"); self.site("throwing-expression"); self.site("catch-pattern")
        _, raw = self.prepare()
        raw["hits"]["source:0"] = {"false": 3, "true": 2}
        raw["hits"]["source:1"] = {"success": 7, "error": 0}
        raw["hits"]["source:2"] = {"nil": 0, "some": 1}
        self.write(self.raw_path, raw)
        before = self.raw_path.read_bytes()
        report = self.analyze()
        self.assertEqual(report["sourceBranchCoverage"]["covered"], 3)
        self.assertEqual(report["sourceBranchCoverage"]["denominator"], 3)
        self.assertEqual(report["sourceBranchCoverage"]["percent"], 100)
        self.assertEqual(len(report["allRawZeros"]), 2)
        self.assertEqual(report["rawArtifacts"][0]["record"], raw)
        self.assertEqual(self.raw_path.read_bytes(), before)
        self.assertEqual(report["acceptance"]["applicationGate"], "not-evaluated")

    def test_overlapping_cast_binding_and_chain_binding_remain_distinct(self):
        self.site("conditional-cast", start=0, end=4)
        self.site("optional-binding", start=0, end=4)
        self.site("optional-chain-base", start=8, end=12)
        self.site("optional-binding", start=8, end=12)
        self.prepare()
        report = self.analyze()
        self.assertEqual(report["rawSiteCount"], 4)
        self.assertEqual(report["sourceBranchCoverage"]["denominator"], 6)

    def test_same_operation_cannot_be_counted_twice_under_distinct_ids(self):
        self.site(start=0, end=4); self.site(start=0, end=4)
        self.prepare()
        with self.assertRaisesRegex(policy.PolicyError, "Duplicate source operation"):
            self.analyze()

    def test_unknown_kind_role_rule_and_outcomes_rejected(self):
        self.site(); manifest, _ = self.prepare()
        for key, value in [("kind", "new-unmapped-form"), ("metricRole", "unmapped-blocker"),
                           ("metricRule", "unknown-rule"), ("outcomes", ["true"]),
                           ("metricRole", "composite-diagnostic")]:
            with self.subTest(key=key, value=value):
                changed = copy.deepcopy(manifest)
                changed["sources"][0]["sites"][0][key] = value
                self.resave_source(changed)
                with self.assertRaises(policy.PolicyError): self.analyze()

    def test_composite_role_not_allowed_to_hide_optional_or_availability(self):
        for kind in ["optional-binding", "availability", "coalescing-left", "iterator-next"]:
            self.sites = []; self.site(kind, composite=True); self.prepare()
            with self.assertRaises(policy.PolicyError): self.analyze()

    def test_missing_unknown_record_or_outcome_rejected_including_diagnostics(self):
        self.site(); self.site("throwing-expression"); _, raw = self.prepare()
        for mutation in [lambda r: r["hits"].pop("source:1"),
                         lambda r: r["hits"].update({"unknown": {"false": 0}}),
                         lambda r: r["hits"]["source:1"].pop("error"),
                         lambda r: r["hits"]["source:0"].update({"extra": 1})]:
            changed = copy.deepcopy(raw); mutation(changed); self.write(self.raw_path, changed)
            with self.assertRaises(policy.PolicyError): self.analyze()

    def test_source_registry_mismatch_rejected(self):
        self.site(); manifest, _ = self.prepare()
        manifest["registry"]["extra:0"] = ["selected"]
        self.write(self.manifest_path, manifest)
        with self.assertRaisesRegex(policy.PolicyError, "Registry/source site mismatch"): self.analyze()

    def test_manifest_raw_source_and_ast_hash_drift_rejected(self):
        self.site(); self.prepare()
        mh, rh = policy.sha256(self.manifest_path), policy.sha256(self.raw_path)
        for path in [self.manifest_path, self.raw_path, self.snapshot, self.ast_path]:
            before = path.read_bytes(); path.write_bytes(before + b" ")
            with self.subTest(path=path.name), self.assertRaises(policy.PolicyError):
                policy.analyze(self.manifest_path, mh, [(self.raw_path, rh)])
            path.write_bytes(before)

    def test_ast_inventory_source_mismatch_rejected_even_with_new_manifest_hash(self):
        self.site(); manifest, _ = self.prepare()
        manifest["sources"][0]["sites"][0]["kind"] = "type-check"
        self.write(self.manifest_path, manifest)
        with self.assertRaisesRegex(policy.PolicyError, "Manifest/syntax inventory mismatch"): self.analyze()

    def test_duplicate_run_and_bad_counters_or_identity_rejected(self):
        self.site(); _, raw = self.prepare()
        ref = (self.raw_path, policy.sha256(self.raw_path))
        with self.assertRaisesRegex(policy.PolicyError, "duplicate run"):
            policy.analyze(self.manifest_path, policy.sha256(self.manifest_path), [ref, ref])
        for value in [-1, True, 1.5, "1", 2**64]:
            changed = copy.deepcopy(raw); changed["hits"]["source:0"]["false"] = value
            self.write(self.raw_path, changed)
            with self.assertRaises(policy.PolicyError): self.analyze()
        for key, value in [("invalid", True), ("registryDigest", "c" * 64), ("runID", ""), ("schemaVersion", 2), ("schemaVersion", True)]:
            changed = copy.deepcopy(raw); changed[key] = value; self.write(self.raw_path, changed)
            with self.assertRaises(policy.PolicyError): self.analyze()

    def test_multi_run_sums_only_supplied_raw_counts_and_keeps_each_record(self):
        self.site(); _, raw = self.prepare()
        raw["hits"]["source:0"]["false"] = 2; self.write(self.raw_path, raw)
        second = copy.deepcopy(raw); second["runID"] = "other-fixture-run"
        second["hits"]["source:0"] = {"false": 0, "true": 4}
        second_path = self.root / "other.json"; self.write(second_path, second)
        report = policy.analyze(self.manifest_path, policy.sha256(self.manifest_path),
                                [(self.raw_path, policy.sha256(self.raw_path)), (second_path, policy.sha256(second_path))])
        self.assertEqual(report["records"][0]["rawCounts"], {"false": 2, "true": 4})
        self.assertEqual(len(report["rawArtifacts"]), 2)

    def test_classifications_and_structural_obligations_retained_without_waivers(self):
        self.site(); manifest, _ = self.prepare()
        source = manifest["sources"][0]
        for key in ["classificationInventory", "reviewRequired", "structural"]:
            source[key] = [{"reason": "pending exact source review", "reviewStatus": "pending", "startUTF8": 0, "endUTF8": 4}]
        self.resave_source(manifest)
        report = self.analyze()
        self.assertEqual(report["sourceBranchCoverage"]["denominator"], 2)
        self.assertEqual(report["sourceBranchCoverage"]["covered"], 0)
        for key in ["classificationInventory", "reviewRequired", "structural"]:
            self.assertEqual(report["sourceAnnex"][0][key], source[key])
        self.assertTrue(report["independentAcceptanceObligations"])

    def test_unsupported_unresolved_configuration_and_empty_denominator_rejected(self):
        self.site(); manifest, _ = self.prepare()
        for key, value in [("unsupported", [{"kind": "future-source-construct"}]),
                           ("configurationRegions", [{"state": "unresolved"}])]:
            changed = copy.deepcopy(manifest); changed["sources"][0][key] = value; self.resave_source(changed)
            with self.assertRaises(policy.PolicyError): self.analyze()
        self.sites = []; self.site("throwing-expression"); self.prepare()
        with self.assertRaisesRegex(policy.PolicyError, "Empty source denominator"): self.analyze()

    def test_duplicate_json_keys_rejected(self):
        self.site(); self.prepare()
        self.raw_path.write_text('{"hits":{},"hits":{}}')
        with self.assertRaisesRegex(policy.PolicyError, "Duplicate JSON key"): self.analyze()

    def test_cli_cannot_overwrite_preserved_artifact_or_write_on_invalid_input(self):
        self.site(); self.prepare()
        command = [sys.executable, str(TOOL / "source_branch_policy.py"), "--manifest", str(self.manifest_path),
                   "--manifest-sha256", policy.sha256(self.manifest_path), "--result", f"{policy.sha256(self.raw_path)}={self.raw_path}"]
        output = self.root / "analysis.json"
        completed = subprocess.run(command + ["--output", str(output)], capture_output=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        before = output.read_bytes()
        completed = subprocess.run(command + ["--output", str(output)], capture_output=True)
        self.assertEqual(completed.returncode, 2)
        self.assertEqual(output.read_bytes(), before)
        self.raw_path.write_text("{}")
        rejected = self.root / "rejected.json"
        completed = subprocess.run(command + ["--output", str(rejected)], capture_output=True)
        self.assertEqual(completed.returncode, 2)
        self.assertFalse(rejected.exists())


if __name__ == "__main__":
    unittest.main()
