"""Negative metadata controls and real host compiler evidence; no app approval."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOL))
import typed_contracts as typed
import semantic_contracts as contracts
import prove_local_contracts as local_prover
spec = importlib.util.spec_from_file_location("swift_certification", TOOL / "certification.py")
cert = importlib.util.module_from_spec(spec); spec.loader.exec_module(cert)
audit_spec = importlib.util.spec_from_file_location("swift_certification_audit", TOOL / "audit_certification.py")
auditor = importlib.util.module_from_spec(audit_spec); audit_spec.loader.exec_module(auditor)


class ASTNormalizationControls(unittest.TestCase):
    def test_position_like_literal_contents_are_never_erased(self):
        first='(string_literal_expr type="String" range=[/real:1:1 - line:1:1] value="range=[a] location=one decl_context=0x1")'
        second=first.replace('range=[a]','range=[b]').replace('location=one','location=two')
        self.assertIn('value="range=[a] location=one decl_context=0x1"',typed.remove_location_metadata(first))
        self.assertNotEqual(typed.remove_location_metadata(first),typed.remove_location_metadata(second))
        self.assertNotIn('range=[/real',typed.remove_location_metadata(first))


class JobControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=cert.coverage.ROOT / ".local-private", prefix="certification-parser-")
        self.root = Path(self.temp.name)
        self.source = self.root / "Control.swift"; self.source.write_text("func control() {}\n")
        self.response = self.root / "inputs.rsp"; self.response.write_text(shlex.quote(str(self.source)))
        self.arguments = ["-module-name", "Control", "@" + str(self.response), "-target", "arm64-apple-ios17.0-simulator",
                          "-sdk", str(self.root / "SDK"), "-swift-version", "5", "-DDEBUG", "-D", "COCOAPODS",
                          "-Xcc", "-DCLANG_ONLY=1", "-Xfrontend", "-serialize-debugging-options"]
        self.log = self.root / "synthetic-parser-control.log"
        self.log.write_text("builtin-SwiftDriver -- " + shlex.join([str(cert.COMPILER), *self.arguments]) + "\n")

    def tearDown(self): self.temp.cleanup()

    def test_real_swift_defines_are_distinct_from_clang_defines(self):
        job, = cert.read_jobs(self.log, self.root, {"Control"})
        self.assertEqual(job["swiftDefines"], ["COCOAPODS", "DEBUG"])
        self.assertEqual(job["responseFiles"], {str(self.response): cert.coverage.sha(self.response)})
        self.assertEqual(job["sources"], [str(self.source)])

    def test_missing_cyclic_and_deep_response_files_block(self):
        self.response.write_text("@missing.rsp")
        with self.assertRaisesRegex(ValueError, "Missing actual compiler response"):
            cert.read_jobs(self.log, self.root, {"Control"})
        self.response.write_text("@" + str(self.response))
        with self.assertRaisesRegex(ValueError, "Cyclic/deep"):
            cert.read_jobs(self.log, self.root, {"Control"})

    def test_arbitrary_shell_or_compiler_cannot_be_replayed(self):
        original = self.log.read_text()
        self.log.write_text(original.replace(str(cert.COMPILER), "/tmp/untrusted/swiftc"))
        with self.assertRaisesRegex(ValueError, "pinned Xcode"):
            cert.read_jobs(self.log, self.root, {"Control"})
        self.log.write_text("sh -c 'execute me'\n" + original.replace("builtin-SwiftDriver", "builtin-Swift-Compilation"))
        with self.assertRaisesRegex(ValueError, "Missing actual Swift driver"):
            cert.read_jobs(self.log, self.root, {"Control"})

    def test_duplicate_module_configurations_cannot_overwrite_each_other(self):
        self.log.write_text(self.log.read_text() + self.log.read_text().replace("-DDEBUG", "-DRELEASE"))
        with self.assertRaisesRegex(ValueError, "Multiple distinct"):
            cert.read_jobs(self.log, self.root, {"Control"})

    def test_replay_rebinds_shared_destinations_and_rejects_unknown_semantics(self):
        job, = cert.read_jobs(self.log, self.root, {"Control"})
        job["arguments"] += ["-c", "-j14", "-module-cache-path", "/shared/modules", "-index-store-path", "/shared/index",
                             "-Xcc", "-ivfsstatcache", "-Xcc", "/shared/stat", "-output-file-map", "/shared/outputs.json"]
        arguments, changes = cert.replay_arguments(job, self.root / "isolated", None)
        self.assertNotIn("/shared/modules", arguments)
        self.assertNotIn("/shared/index", arguments)
        self.assertNotIn("/shared/stat", arguments)
        self.assertIn("-fmodules-cache-path=" + str(self.root / "isolated/clang-module-cache"), arguments)
        self.assertGreater(len(changes), 4)
        for extra in (["-enable-new-semantics"], ["-Xfrontend", "-load-plugin-library"], ["-Xcc", "-fplugin=/tmp/inject"]):
            bad = copy.deepcopy(job); bad["arguments"] += extra
            with self.assertRaisesRegex(ValueError, "Unreviewed"):
                cert.replay_arguments(bad, self.root, None)

    def test_configuration_audit_retains_all_sources_and_requires_exact_defines(self):
        job, = cert.read_jobs(self.log, self.root, {"Control"})
        config = {"os": "iOS", "arch": "arm64", "environment": "simulator", "defines": ["DEBUG"], "languageVersion": [5]}
        source = {"source": str(self.source), "configurationRegions": [{"state": "active", "condition": "DEBUG"}]}
        manifest = {"sources": [source], "configuration": config}
        self.assertFalse(cert.audit_configuration(manifest, [job])["passed"])
        source["configuration"] = {**config, "defines": ["DEBUG", "COCOAPODS"]}
        report = cert.audit_configuration(manifest, [job])
        self.assertTrue(report["passed"], report)
        self.assertFalse(report["configurationUnionCertified"])
        self.assertEqual(report["sources"][0]["configurationRegions"], source["configurationRegions"])
        job["sources"].append(str(self.root / "Unowned.swift"))
        self.assertFalse(cert.audit_configuration(manifest, [job])["passed"])

    def test_active_regions_cannot_claim_wrong_or_unproved_predicates(self):
        job, = cert.read_jobs(self.log, self.root, {"Control"})
        source = {"source": str(self.source), "configuration": {"os": "iOS", "arch": "arm64", "environment": "simulator",
                  "defines": ["DEBUG", "COCOAPODS"], "languageVersion": [5]},
                  "configurationRegions": [{"condition": "os(iOS)", "state": "active"}, {"state": "inactive"}]}
        manifest = {"sources": [source]}
        self.assertTrue(cert.audit_configuration(manifest, [job])["passed"])
        source["configurationRegions"][0]["state"] = "inactive"
        self.assertTrue(any("active source region differs" in item for item in cert.audit_configuration(manifest, [job])["failures"]))
        source["configurationRegions"][0].update(condition="canImport(UnprovedFeature)", state="active")
        self.assertTrue(any("predicate grammar" in item for item in cert.audit_configuration(manifest, [job])["failures"]))


class RealCompilerEvidence(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp(dir=cert.coverage.ROOT / ".local-private", prefix="certification-host-"))
        cls.source = cls.root / "Probe.swift"
        cls.source.write_text('''// 文档 📄 UTF-8 positions must stay exact.
func evidence(_ flag: Bool, _ value: Int?, _ items: [Int]) throws -> Int {
  var total = 0
  if flag && (value != nil) { total += value ?? 1 }
  for item in items where item > 0 { total += item }
  return total
}
''')
        cls.prepared = cls.root / "prepared"
        cls.manifest = cert.coverage.prepare(cls.prepared, [cls.source])
        env = {**os.environ, "DEVELOPER_DIR": cert.coverage.DEVELOPER}; env.pop("SDKROOT", None)
        sdk = subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], env=env, text=True).strip()
        cls.env = env; cls.sdk = sdk
        cls.dumps = {}
        cls.sil = {}
        for variant in ("original", "transformed"):
            arguments = ["-j1", "-module-cache-path", str(cls.root / "host-module-cache"), "-swift-version", "5", "-Onone", "-sdk", sdk, "-module-name", "Probe", str(cls.source)]
            if variant == "transformed": arguments += ["-vfsoverlay", str(cls.prepared / "overlay.json"), str(cls.prepared / "BranchRuntime.swift")]
            for action in ("-dump-ast", "-emit-silgen"):
                result = subprocess.run([str(cert.COMPILER), *arguments, action], env=env, capture_output=True, text=True)
                (cls.root / (variant + action + ".stdout")).write_text(result.stdout)
                (cls.root / (variant + action + ".stderr")).write_text(result.stderr)
                if result.returncode: raise RuntimeError(result.stderr)
                raw = result.stdout if result.stdout.strip() else result.stderr
                if action == "-dump-ast": cls.dumps[variant] = cert.typed_nodes(raw)
                else: cls.sil[variant] = cert.sil_summary(raw)
        print("Real host compiler evidence:", cls.root)
        tool_dir = cls.root / 'lexical-tool';tool_dir.mkdir()
        binary,_ = local_prover.syntax_tool(tool_dir)
        cls.syntax = {}
        for variant,path in [('original',cls.source),('transformed',Path(cls.manifest['sources'][0]['transformed']))]:
            result = tool_dir / (variant+'.json')
            subprocess.run([str(binary),str(path),str(result)],check=True,capture_output=True,text=True)
            cls.syntax[variant]=json.loads(result.read_text())
        raw_sil = (cls.root/'transformed-emit-silgen.stdout').read_text() or (cls.root/'transformed-emit-silgen.stderr').read_text()
        cls.inventory = contracts.functions(raw_sil)

    def local_results(self,source=None,transformed=None):
        source = source or self.manifest['sources'][0]
        original_tree=typed.source_tree(self.dumps['original'],str(self.source))
        transformed_tree=typed.source_tree(transformed or self.dumps['transformed'],str(self.source))
        calls=self.syntax['transformed']['recorderCalls'];result=[]
        for site in source['sites']:
            family=cert.FAMILIES[site['kind']]
            if family not in {'bool-identity','optional-identity'}:continue
            helper='boolean' if family=='bool-identity' else 'optional'
            matches=[call for call in calls if call.get('registryID')==source['namespace']+':'+str(site['id'])]
            if len(matches)!=1:continue
            kernel=contracts.exact_transfer_rule(self.inventory,'Probe',helper)
            result.append(typed.local_expression_rule(source,site,matches[0],original_tree,transformed_tree,
                self.syntax['original'],self.syntax['transformed'],'Probe',kernel))
        return result

    def test_local_typed_expression_rule_uses_actual_context_and_never_approves_app(self):
        results=self.local_results();proved=[row for row in results if row['typedExpressionPreserved']]
        self.assertTrue(proved,results)
        self.assertTrue(all(row['remainingObligations'] for row in results))
        self.assertTrue(all(not row['applicationGateApproved'] and not row['semanticEquivalenceCertified'] for row in results))
        self.assertTrue(any(row['failures'] for row in results))

    def test_local_typed_expression_rejects_resolved_declaration_and_registry_changes(self):
        before=self.local_results();proved={row['siteID'] for row in before if row['typedExpressionPreserved']}
        transformed=copy.deepcopy(self.dumps['transformed'])
        for node in transformed:
            if node.get('kind')=='declref_expr' and 'flag@' in node.get('decl',''):
                node['header']=node['header'].replace('flag@','differentFlag@')
                node['decl']=node['decl'].replace('flag@','differentFlag@')
        after=self.local_results(transformed=transformed)
        self.assertTrue(any(row['siteID'] in proved and not row['typedExpressionPreserved'] for row in after),after)
        source=copy.deepcopy(self.manifest['sources'][0]);source['namespace']='wrongNamespace'
        self.assertFalse(self.local_results(source=source))

    def test_original_snapshot_keeps_exact_bytes_after_current_source_drift(self):
        source=self.manifest['sources'][0];original=self.source.read_bytes()
        self.source.write_bytes(b'// later legitimate source revision\n')
        try:self.assertEqual(typed.original_bytes(source),original)
        finally:self.source.write_bytes(original)
        bad=copy.deepcopy(source);bad['originalSnapshot']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'snapshot binding'):typed.original_bytes(bad)

    def test_source_map_reconstructs_only_unchanged_original_bytes(self):
        source = self.manifest["sources"][0]
        cert.validate_source_map(source)
        self.assertGreater(len(source["sourceMap"]), 5)
        bad = copy.deepcopy(source); bad["sourceMap"][0]["transformedEndUTF8"] += 1
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            cert.validate_source_map(bad)

    def test_real_typed_declarations_and_ownership_sil_are_retained(self):
        source = self.manifest["sources"][0]
        sites = cert.compare_typed_sites(source, self.dumps["original"], self.dumps["transformed"])
        self.assertEqual(len(sites), len(self.manifest["registry"]))
        self.assertTrue(all(item["originalTypedNodes"] for item in sites))
        declarations = [node.get("decl", "") for node in self.dumps["original"]]
        self.assertTrue(any("Bool extension.&&" in decl for decl in declarations))
        self.assertTrue(any("Iterator" in node.get("type", "") for node in self.dumps["original"]))
        self.assertGreater(self.sil["original"]["instructionCounts"]["begin_borrow"], 0)
        self.assertGreater(self.sil["transformed"]["instructionCounts"]["begin_borrow"], 0)
        self.assertTrue(all(item["status"].startswith("pending") for item in sites))
        self.assertTrue(any(item["sourceOriginTypedNodesEqual"] for item in sites))
        self.assertTrue(any(not item["sourceOriginTypedNodesEqual"] for item in sites))

    def test_changing_a_resolved_type_is_reported_per_site(self):
        source = self.manifest["sources"][0]
        transformed = copy.deepcopy(self.dumps["transformed"])
        for node in transformed:
            if node.get("kind") == "declref_expr" and "flag@" in node.get("decl", ""):
                node["type"] = "WrongResolvedType"
        sites = cert.compare_typed_sites(source, self.dumps["original"], transformed)
        additions = [item for site in sites for item in site["addedSourceOriginTypedNodes"]]
        self.assertTrue(any(item["signature"].get("type") == "WrongResolvedType" for item in additions))

    def test_malformed_dump_and_empty_sil_cannot_be_evidence(self):
        with self.assertRaises(ValueError): cert.typed_nodes("not a compiler dump")
        with self.assertRaises(ValueError): cert.sil_summary("sil_stage raw\n")

    def test_any_optional_try_inference_repair_requires_real_original_typing(self):
        directory = self.root / "any-try"; directory.mkdir()
        source = directory / "AnyResult.swift"
        source.write_text('''import Foundation
func parse(_ data:Data) -> Int {
  let values = (try? JSONSerialization.jsonObject(with:data)) as? [[String:Any]] ?? []
  return values.count
}
''')
        initial = cert.coverage.prepare(directory / "untyped", [source])
        arguments = ["-j1", "-module-cache-path", str(directory / "host-module-cache"), "-sdk", self.sdk, "-swift-version", "5", "-module-name", "AnyResult", str(source)]
        broken = subprocess.run([str(cert.COMPILER), *arguments, "-typecheck", "-vfsoverlay", str(directory / "untyped/overlay.json"),
                                 str(directory / "untyped/BranchRuntime.swift")], env=self.env, text=True, capture_output=True)
        self.assertNotEqual(broken.returncode, 0)
        self.assertIn("conflicting arguments to generic parameter", broken.stderr)
        original = subprocess.run([str(cert.COMPILER), *arguments, "-dump-ast"], env=self.env, text=True, capture_output=True, check=True)
        nodes = cert.typed_nodes(original.stdout)
        index = directory / "original-typed-index.json"; cert.coverage.write(index, nodes)
        proof = cert.any_result_hints(initial["sources"][0], nodes, index)
        self.assertEqual(len(proof["typingHints"]), 1)
        config = {"os": "macOS", "arch": "arm64", "environment": None, "defines": [], "languageVersion": [5], "compilerVersion": [6, 3, 3],
                  "typingHints": proof["typingHints"], "typingHintsProof": proof}
        fixed = cert.coverage.prepare(directory / "typed", [source], config)
        actual = subprocess.run([str(cert.COMPILER), *arguments, "-typecheck", "-vfsoverlay", str(directory / "typed/overlay.json"),
                                 str(directory / "typed/BranchRuntime.swift")], env=self.env, text=True, capture_output=True)
        self.assertEqual(actual.returncode, 0, actual.stderr)
        self.assertIn(") as Any)", Path(fixed["sources"][0]["transformed"]).read_text())
        driver = directory / "Driver.swift"
        driver.write_text('''import Foundation
@main struct Driver {
 static func main() {
  print(parse(Data("[{\\\"value\\\":1}]".utf8)))
  print(parse(Data("broken json".utf8)))
 }
}
''')
        results = []
        for transformed in (False, True):
            binary = directory / ("transformed" if transformed else "original")
            argv = [str(cert.COMPILER), *arguments, str(driver), "-o", str(binary)]
            if transformed: argv += ["-vfsoverlay", str(directory / "typed/overlay.json"), str(directory / "typed/BranchRuntime.swift")]
            subprocess.run(argv, env=self.env, text=True, capture_output=True, check=True)
            results.append(subprocess.check_output([str(binary)], env=self.env, text=True))
        self.assertEqual(results, ["1\n0\n", "1\n0\n"])
        bad = copy.deepcopy(config); bad["typingHintsProof"]["sourceSHA256"] = "stale"
        with self.assertRaisesRegex(ValueError, "exact original-source"):
            cert.coverage.prepare(directory / "stale-proof", [source], bad)

    def test_complete_real_host_packet_audits_but_cannot_self_certify(self):
        directory = self.root / "packet"; directory.mkdir()
        source = directory / "Packet.swift"; source.write_text("func decision(_ flag:Bool) -> Int { if flag { return 1 }; return 0 }\n")
        info = json.loads(subprocess.check_output([str(cert.COMPILER), "-print-target-info"], env=self.env, text=True))
        triple = info["target"]["triple"]
        config = {"os": "macOS", "arch": triple.split("-")[0], "environment": None, "defines": [], "languageVersion": [5], "compilerVersion": [6, 3, 3]}
        cert.coverage.prepare(directory / "prepared", [source], config, runtime_injections={"Packet": source})
        arguments = ["-j1", "-module-cache-path", str(directory / "host-module-cache"), "-module-name", "Packet", "-target", triple, "-sdk", self.sdk, "-swift-version", "5", "-Onone", str(source), "-c", "-o", str(directory / "original.o")]
        subprocess.run([str(cert.COMPILER), *arguments], env=self.env, text=True, capture_output=True, check=True)
        # A host fixture log records an actually executed compiler command. This
        # is never labelled an Xcode app-build or runtime-coverage receipt.
        log = directory / "executed-host-command.log"; log.write_text("builtin-SwiftDriver -- " + shlex.join([str(cert.COMPILER), *arguments]) + "\n")
        report = cert.collect(directory / "prepared/manifest.json", log, directory / "captured", {"Packet"})
        self.assertEqual(report["failures"], [], report["failures"])
        path = directory / "captured/evidence.json"
        checked = auditor.audit(path)
        self.assertTrue(checked["artifactIntegrityPassed"], checked["failures"])
        self.assertFalse(checked["semanticEquivalenceCertified"])
        self.assertFalse(checked["applicationGateApproved"])
        review = directory / "self-declared-review.json"; review.write_text('{"approved":true,"reviewer":"somebody"}')
        self.assertFalse(auditor.audit(path, review)["applicationGateApproved"])
        mutations = []
        false_approval = copy.deepcopy(report); false_approval["applicationGateApproved"] = True
        mutations.append((false_approval, "must not self-certify"))
        false_hit = copy.deepcopy(report); site = next(iter(false_hit["candidateOutcomes"])); outcome = next(iter(false_hit["candidateOutcomes"][site])); false_hit["candidateOutcomes"][site][outcome] = 1
        mutations.append((false_hit, "unmeasured hits"))
        false_mapping = copy.deepcopy(report); false_mapping["siteObligations"][0]["sourceOriginTypedNodesEqual"] = not false_mapping["siteObligations"][0]["sourceOriginTypedNodesEqual"]
        mutations.append((false_mapping, "comparison differs"))
        missing_site = copy.deepcopy(report); missing_site["siteObligations"] = []
        mutations.append((missing_site, "Missing/unexpected concrete typed"))
        for field, value, expected_failure in [
            ("argv", ["/definitely/not/swiftc", "-target", "x86_64-unrelated", "-DWRONG_CONFIGURATION"], "Compiler action command differs"),
            ("cwd", "/wrong-working-directory", "Compiler action cwd differs"),
        ]:
            changed_action = copy.deepcopy(report)
            changed_action["modules"][0]["variants"]["original"]["actions"]["ast"][field] = value
            mutations.append((changed_action, expected_failure))
        wrong_overlay = copy.deepcopy(report)
        argv = wrong_overlay["modules"][0]["variants"]["transformed"]["actions"]["ast"]["argv"]
        argv[argv.index("-vfsoverlay") + 1] = "/wrong-overlay.json"
        mutations.append((wrong_overlay, "Compiler action command differs"))
        wrong_action = copy.deepcopy(report)
        argv = wrong_action["modules"][0]["variants"]["original"]["actions"]["ast"]["argv"]
        argv[-1] = "-emit-object"
        mutations.append((wrong_action, "Compiler action command differs"))
        missing_action = copy.deepcopy(report)
        missing_action["modules"][0]["variants"]["original"]["actions"].pop("ast")
        mutations.append((missing_action, "Compiler action inventory differs"))
        changed_adjustments = copy.deepcopy(report)
        changed_adjustments["modules"][0]["variants"]["original"]["adjustments"] = []
        mutations.append((changed_adjustments, "Compiler action adjustments differ"))
        original_packet = path.read_bytes()
        for index, (payload, expected_failure) in enumerate(mutations):
            cert.coverage.write(directory / f"mutated-{index}.json", payload)
            cert.coverage.write(path, payload)
            try: actual = auditor.audit(path)
            finally: path.write_bytes(original_packet)
            self.assertFalse(actual["artifactIntegrityPassed"])
            self.assertTrue(any(expected_failure in message for message in actual["failures"]), actual["failures"])
        original_bytes = source.read_bytes(); source.write_bytes(original_bytes + b"\n")
        try:
            actual = auditor.audit(path)
            self.assertFalse(actual["artifactIntegrityPassed"])
            self.assertTrue(any("artifact drift" in item for item in actual["failures"]), actual["failures"])
        finally: source.write_bytes(original_bytes)


if __name__ == "__main__": unittest.main()
