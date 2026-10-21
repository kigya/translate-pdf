"""Real compiler/runtime differential tests; no hand-written coverage hits."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("swift_branch_tool", TOOL / "coverage.py")
coverage = importlib.util.module_from_spec(spec); spec.loader.exec_module(coverage)

FIXTURE = '''
// 文档 📄 — source locations use UTF-8 bytes, not character offsets.
func sample(_ a: Bool, _ b: Bool, _ value: Int?, _ numbers: [Int]) -> Int {
  var score = 0
  if a && b { score += 1 }
  if a || b { score += 2 }
  guard let v = value else { return -1 }
  let second: Int? = b ? value : nil
  let fallback = second ?? 7
  for n in numbers where n > 0 {
    if n == 2 { continue }
    score += n
    if n == 4 { break }
  }
  var i = 0
  while i < 2 { i += 1 }
  return score + v + fallback + i
}
'''
DRIVER = '''
import Foundation
@main struct Driver {
  static func main() throws {
    let full = CommandLine.arguments.contains("full")
    var sum = 0
    for a in full ? [false,true] : [false] {
      for b in full ? [false,true] : [false] {
        for value: Int? in full ? [nil,3] : [nil] {
          for numbers in [[],[-1,1,2,3],[4,5]] { sum += sample(a,b,value,numbers) }
        }
      }
    }
    print(sum)
    #if BRANCH_COVERAGE
    try __FBCov.export(to:CommandLine.arguments[1],runID:CommandLine.arguments[2])
    #endif
  }
}
'''

class ToolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp(prefix="swift-branches-", dir=coverage.ROOT / ".local-private"))
        cls.source = cls.root / "Fixture.swift"; cls.source.write_text(FIXTURE)
        cls.driver = cls.root / "Driver.swift"; cls.driver.write_text(DRIVER)
        cls.output = cls.root / "instrumented"
        cls.manifest = coverage.prepare(cls.output, [cls.source])
        cls.manifest_path = cls.output / "manifest.json"
        cls.original = cls.root / "original"
        cls.instrumented = cls.root / "instrumented-run"
        cls.commands = []
        def compile_binary(target, extra):
            command = ["xcrun","swiftc","-swift-version","5","-Onone","-g",*extra,str(cls.source),str(cls.driver),"-o",str(target)]
            coverage.run(command); cls.commands.append(command)
        compile_binary(cls.original, [])
        compile_binary(cls.instrumented, ["-D","BRANCH_COVERAGE","-vfsoverlay",str(cls.output / "overlay.json"),str(cls.output / "BranchRuntime.swift")])
        cls.partial = cls.root / "partial.json"; cls.full = cls.root / "full.json"
        cls.original_partial = coverage.run([str(cls.original)])
        cls.actual_partial = coverage.run([str(cls.instrumented),str(cls.partial),"partial"])
        cls.original_full = coverage.run([str(cls.original),"full"])
        cls.actual_full = coverage.run([str(cls.instrumented),str(cls.full),"full","full"])
        coverage.write(cls.root / "commands.json",cls.commands)
        # Keep exact real evidence after tests. Caller can inspect even on failure.
        print("Swift differential evidence:",cls.root)

    def test_original_observable_values_match(self):
        self.assertEqual(self.original_partial,self.actual_partial)
        self.assertEqual(self.original_full,self.actual_full)
        self.assertNotEqual(self.original_partial,self.original_full)
        raw = self.source.read_bytes()
        expressions = [raw[s["startUTF8"]:s["endUTF8"]].decode() for s in self.manifest["sources"][0]["sites"]]
        self.assertIn("a && b",expressions)
        self.assertIn("a || b",expressions)

    def test_runtime_partial_fails_and_full_measures_all_supported_outcomes(self):
        partial = coverage.validate(self.manifest_path,[self.partial],fixture_only=True)
        full = coverage.validate(self.manifest_path,[self.full],fixture_only=True)
        self.assertFalse(partial["passed"],partial)
        self.assertGreater(len(partial["missing"]),0)
        self.assertTrue(full["passed"],full)
        self.assertEqual(full["count"],full["covered"])
        self.assertGreater(full["count"],20)

    def test_full_fixture_never_certifies_application(self):
        report = coverage.validate(self.manifest_path,[self.full])
        self.assertFalse(report["passed"])
        self.assertTrue(any("typed-equivalence" in reason for reason in report["failures"]))
        self.assertIsNone(report["sourceBranchDenominator"])
        self.assertNotIn("count",report)

    def test_missing_duplicate_wrong_digest_and_bad_count_fail(self):
        self.assertFalse(coverage.validate(self.manifest_path,[],fixture_only=True)["passed"])
        self.assertFalse(coverage.validate(self.manifest_path,[self.full,self.full],fixture_only=True)["passed"])
        original = json.loads(self.full.read_text())
        for mutation in ["digest","site","outcome","count","invalid","binary","uuid"]:
            data = copy.deepcopy(original)
            site = next(iter(data["hits"]))
            if mutation == "digest": data["registryDigest"] = "0"*64
            elif mutation == "site": del data["hits"][site]
            elif mutation == "outcome": data["hits"][site]["invented"] = 1
            elif mutation == "count": data["hits"][site][next(iter(data["hits"][site]))] = True
            elif mutation == "invalid": data["invalid"] = True
            elif mutation == "binary": data["binarySHA256"] = "0"*64
            elif mutation == "uuid": data["binaryUUID"] = "0"*32
            path = self.root / f"mutation-{mutation}.json"; coverage.write(path,data)
            self.assertFalse(coverage.validate(self.manifest_path,[path],fixture_only=True)["passed"],mutation)

    def test_source_and_transformed_and_manifest_drift_fail(self):
        for path in [self.source,Path(self.manifest["sources"][0]["transformed"]),Path(self.manifest["sources"][0]["ast"])]:
            original = path.read_bytes()
            try:
                path.write_bytes(original + b" ")
                self.assertFalse(coverage.validate(self.manifest_path,[self.full],fixture_only=True)["passed"])
            finally: path.write_bytes(original)
        modified = copy.deepcopy(self.manifest); modified["registry"] = {}
        path = self.root / "mutated-manifest.json"; coverage.write(path,modified)
        self.assertFalse(coverage.validate(path,[self.full],fixture_only=True)["passed"])

    def test_laziness_iteration_and_error_semantics(self):
        source = self.root / "Effects.swift"
        source.write_text('''
import Foundation
var calls: [String] = []
func side(_ label: String, _ value: Bool) -> Bool { calls.append(label); return value }
func fallback() -> Int { calls.append("fallback"); return 9 }
enum Failure: Error { case marker }
func throwing(_ fail: Bool) throws -> Bool { calls.append("throw"); if fail { throw Failure.marker }; return true }
func exercise(_ a: Bool, _ optional: Int?, _ fail: Bool) -> String {
  defer { calls.append("defer") }
  if side("left-and",a) && side("right-and",true) { calls.append("and") }
  if side("left-or",a) || side("right-or",false) { calls.append("or") }
  let result = optional ?? fallback()
  do { if try throwing(fail) { calls.append("success") } } catch { calls.append("caught") }
  for n in [1,2,3] { if n == 2 { break }; calls.append("item") }
  return String(result)
}
''')
        driver = self.root / "EffectsDriver.swift"
        driver.write_text('''
@main struct EffectsDriver {
 static func main() {
  for a in [false,true] { for n: Int? in [nil,4] { for fail in [false,true] { print(exercise(a,n,fail)) } } }
  print(calls.joined(separator:","))
 }
}
''')
        output = self.root / "effects-instrumented"; manifest = coverage.prepare(output,[source])
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        binaries = []
        for transformed in [False,True]:
            binary = self.root / ("effects-transformed" if transformed else "effects-original")
            command = ["xcrun","swiftc","-swift-version","5","-Onone"]
            if transformed: command += ["-vfsoverlay",str(output / "overlay.json"),str(output / "BranchRuntime.swift")]
            coverage.run(command + [str(source),str(driver),"-o",str(binary)])
            binaries.append(coverage.run([str(binary)]))
        self.assertEqual(binaries[0],binaries[1])
        self.assertIn("caught,item,defer",binaries[0])

    def test_runtime_is_thread_safe_and_preserves_inout_and_cooperative_cancel(self):
        source = self.root / "Concurrent.swift"
        source.write_text("""
import Foundation
import Dispatch
func predicate(_ value: Bool) -> Bool { if value { return true }; return false }
func cancelled(_ stop: Int, _ result: inout [Int]) {
  defer { result.append(-1) }
  for value in [1,2,3] { if value == stop { return }; result.append(value) }
}
""")
        driver = self.root / "ConcurrentDriver.swift"
        driver.write_text("""
import Foundation
import Dispatch
@main struct ConcurrentDriver {
 static func main() throws {
  DispatchQueue.concurrentPerform(iterations:2000) { _ in _ = predicate(true); _ = predicate(false) }
  for stop in [1,2,9] { var result: [Int] = []; cancelled(stop,&result); print(result) }
  #if BRANCH_COVERAGE
  try __FBCov.export(to:CommandLine.arguments[1],runID:"concurrency")
  #endif
 }
}
""")
        output = self.root / "concurrent"; manifest = coverage.prepare(output,[source])
        self.assertTrue(any("Ownership" in item["reason"] for item in manifest["sources"][0]["unsupported"]))
        observed = []
        for instrumented in [False,True]:
            binary = self.root / ("concurrent-transformed" if instrumented else "concurrent-original")
            command = ["xcrun","swiftc","-swift-version","5","-Onone"]
            if instrumented: command += ["-D","BRANCH_COVERAGE","-vfsoverlay",str(output / "overlay.json"),str(output / "BranchRuntime.swift")]
            coverage.run(command + [str(source),str(driver),"-o",str(binary)])
            observed.append(coverage.run([str(binary),str(self.root / "concurrent-results.json")]))
        self.assertEqual(observed[0],observed[1])
        record = json.loads((self.root / "concurrent-results.json").read_text())
        predicate_site = manifest["sources"][0]["sites"][0]
        counts = record["hits"][manifest["sources"][0]["namespace"]+":"+str(predicate_site["id"])]
        self.assertEqual(counts,{"false":2000,"true":2000})
        self.assertFalse(coverage.validate(output / "manifest.json",[self.root / "concurrent-results.json"],fixture_only=True)["passed"])

    def compare_fixture(self, name, source_text, body, configuration=None):
        folder = self.root / name; folder.mkdir()
        source = folder / "Source.swift"; source.write_text(source_text)
        driver = folder / "Driver.swift"
        driver.write_text("import Foundation\n@main struct D { static func main() throws {\n"+body+"\n#if BRANCH_COVERAGE\ntry __FBCov.export(to:CommandLine.arguments[1],runID:"+json.dumps(name)+")\n#endif\n} }\n")
        output = folder / "instrumented"; manifest = coverage.prepare(output,[source],configuration)
        observed = []
        for transformed in [False,True]:
            binary = folder / ("transformed" if transformed else "original")
            command = ["xcrun","swiftc","-swift-version","5","-Onone"]
            if configuration:
                for flag in configuration["defines"]: command += ["-D",flag]
            if transformed: command += ["-D","BRANCH_COVERAGE","-vfsoverlay",str(output / "overlay.json"),str(output / "BranchRuntime.swift")]
            coverage.run(command+[str(source),str(driver),"-o",str(binary)])
            observed.append(coverage.run([str(binary),str(folder / "results.json")]))
        self.assertEqual(observed[0],observed[1])
        return manifest, coverage.validate(output / "manifest.json",[folder / "results.json"],fixture_only=True)

    def test_try_optional_flattening_errors_catch_and_shorthand_closure_scope(self):
        manifest, report = self.compare_fixture("try-lazy", """
enum Failure: Error { case marker }
func produce(_ mode: Int) throws -> Int? {
  if mode == 0 { throw Failure.marker }
  return mode == 1 ? nil : 7
}
func flattened(_ mode: Int) -> Int { let result = try? produce(mode); return result ?? -1 }
func mapped(_ values: [Int]) throws -> [Int?] { try values.map { try produce($0) } }
func caught(_ mode: Int) -> Bool { do { _ = try produce(mode); return true } catch { return false } }
""", """
for value in [0,1,2] { print(flattened(value)); print(caught(value)) }
for values in [[1,2],[0]] { print(String(describing:try? mapped(values))) }
""")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        self.assertTrue(report["passed"],report)

    def test_optional_read_and_implicit_binding_preserve_nested_nil(self):
        manifest, report = self.compare_fixture("optional-read", """
struct Box { let value: Int? }
func read(_ box: Box?) -> Int { box?.value ?? -1 }
func bound(_ box: Box?) -> Int { guard let box else { return 0 }; return box.value ?? 2 }
""", """
for value: Box? in [nil,Box(value:nil),Box(value:7)] { print(read(value)); print(bound(value)) }
""")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        self.assertTrue(report["passed"],report)

    def test_single_pattern_switch_dispatch_and_default(self):
        manifest, report = self.compare_fixture("dispatch", """
enum Mode { case one, two }
func select(_ mode: Mode) -> Int { switch mode { case .one: return 1; case .two: return 2 } }
func integer(_ value: Int) -> Int { switch value { case 1: return 1; case 2: return 2; default: return 3 } }
""", """
for mode in [Mode.one,.two] { print(select(mode)) }
for value in [1,2,3] { print(integer(value)) }
""")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        self.assertTrue(report["passed"],report)

    def test_active_configuration_excludes_only_explicitly_inactive_regions(self):
        configuration = {"os":"macOS","arch":"arm64","environment":None,"defines":["TEST_BUILD"],"languageVersion":[5],"compilerVersion":[6,3,3]}
        manifest, report = self.compare_fixture("conditional", """
func platform(_ flag: Bool) -> Int {
 #if os(macOS) && TEST_BUILD
 if flag { return 1 }; return 2
 #elseif os(iOS)
 return 3
 #else
 return 4
 #endif
}
""", "print(platform(false)); print(platform(true))",configuration)
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        states = {item["state"] for item in manifest["sources"][0]["configurationRegions"]}
        self.assertEqual(states,{"active","inactive"})
        self.assertTrue(report["passed"],report)

    def test_typed_catch_order_matching_failure_and_error_identity(self):
        manifest, report = self.compare_fixture("typed-catch", """
struct Pause: Error { let value: Int }
struct Cancel: Error {}
final class Other: Error { let value = 9 }
let other = Other()
func fail(_ mode: Int) throws {
 if mode == 1 { throw Pause(value:3) }
 if mode == 2 { throw Cancel() }
 if mode == 3 { throw other }
 if mode == 4 { throw Other() }
}
func route(_ mode: Int) -> Int {
 do { try fail(mode); return 0 }
 catch let signal as Pause { return signal.value }
 catch is Cancel { return -1 }
 catch { return (error as AnyObject) === other ? -2 : -3 }
}
func rethrowing(_ mode: Int) throws {
 do { try fail(mode) } catch let signal as Pause { throw signal } catch { throw error }
}
""", """
for value in [0,1,2,3,4] { print(route(value)); do { try rethrowing(value); print("ok") } catch { print(String(describing:type(of:error))) } }
""")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        self.assertTrue(report["passed"],report)

    def test_multi_pattern_constant_assignment_has_distinct_dispatch_outcomes(self):
        manifest, report = self.compare_fixture("multi-pattern", """
enum State { case one,two,three,four }
func name(_ state: State) -> String {
 let result: String
 switch state { case .one,.two,.three: result="ready"; case .four: result="other" }
 return result
}
""", "for value in [State.one,.two,.three,.four] { print(name(value)) }")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        self.assertTrue(report["passed"],report)
        self.assertEqual(sum(s["kind"] == "switch-pattern-dispatch" for s in manifest["sources"][0]["sites"]),3)

    def test_availability_preserves_refinement_and_comma_condition_evaluation(self):
        manifest, report = self.compare_fixture("availability", """
var events: [String] = []
@available(macOS 99,*) func futureOnly() { events.append("future") }
func observed(_ flag: Bool) -> Int {
 defer { events.append("defer") }
 if #available(macOS 99,*),flag { futureOnly(); return 1 } else { events.append("unavailable") }
 if #available(macOS 10.15,*),flag { return 2 }
 return 3
}
""", 'print(observed(false)); print(observed(true)); print(events.joined(separator:","))' )
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        availability = [s for s in manifest["sources"][0]["sites"] if s["kind"] == "availability"]
        self.assertEqual(len(availability),2)
        self.assertFalse(report["passed"])
        # One real OS cannot hit both outcomes of a fixed availability threshold.
        self.assertEqual(sum("uncovered outcomes" in message for message in report["failures"]),1)

    def test_weak_capture_lifetime_is_preserved_but_remains_flagged_for_review(self):
        manifest, report = self.compare_fixture("weak-lifetime", """
var events: [String] = []
final class Owner {
 let name: String
 init(_ name: String) { self.name = name }
 deinit { events.append("deinit") }
 func callback() -> () -> Void {
  { [weak self] in guard let self else { events.append("gone"); return }; events.append(self.name) }
 }
}
func exercise() {
 var owner: Owner? = Owner("alive")
 var callback: (() -> Void)?
 if let current = owner { callback = current.callback() }
 callback?()
 owner = nil
 callback?()
}
""", 'exercise(); print(events.joined(separator:","))')
        self.assertTrue(any(item["kind"] == "token" for item in manifest["sources"][0]["reviewRequired"]))
        self.assertFalse(report["passed"])
        inventory = manifest["sources"][0]["classificationInventory"]
        self.assertTrue(any(item["sourceText"] == "weak" and item["reviewStatus"] == "pending" for item in inventory))

    def test_typed_catch_lowering_preserves_outer_error_and_is_hygienic(self):
        name = "catch-shadow"
        source_path = (self.root / name / "Source.swift").resolve()
        namespace = "s" + coverage.hashlib.sha256(str(source_path).encode()).hexdigest()[:16]
        reserved = "__fbCaught_" + namespace
        text = """
enum Failure: Error { case marker }
final class Other: Error {}
let other = Other()
let error = "outer"
let __RESERVED__ = "reserved"
func raise(_ fallback: Bool) throws { if fallback { throw other }; throw Failure.marker }
func observed(_ fallback: Bool) -> String {
 do { try raise(fallback); return "unexpected" }
 catch let value as Failure { return error + String(describing:value) + __RESERVED__ }
 catch { return (error as AnyObject) === other ? "fallback" : "wrong" }
}
func boundError(_ fallback: Bool) -> String {
 do { try raise(fallback); return "unexpected" }
 catch let error as Failure { return String(describing:error) }
 catch { return (error as AnyObject) === other ? "fallback" : "wrong" }
}
func typeOnly(_ fallback: Bool) -> String {
 do { try raise(fallback); return "unexpected" }
 catch is Failure { return error }
 catch { return (error as AnyObject) === other ? "fallback" : "wrong" }
}
func localDefault(_ fallback: Bool) -> String {
 do { try raise(fallback); return "unexpected" }
 catch let value as Failure { return String(describing:value) }
 catch { let error = "local"; return error }
}
func propagated(_ fallback: Bool) throws {
 do { try raise(fallback) } catch let value as Failure { throw value } catch { throw error }
}
""".replace("__RESERVED__",reserved)
        manifest, report = self.compare_fixture(name,text,"""
for fallback in [false,true] {
 print(observed(fallback)); print(boundError(fallback)); print(typeOnly(fallback)); print(localDefault(fallback))
 do { try propagated(fallback) } catch { print((error as AnyObject) === other ? "same-object" : String(describing:error)) }
}
""")
        self.assertEqual(manifest["sources"][0]["unsupported"],[])
        generated = Path(manifest["sources"][0]["transformed"]).read_text()
        self.assertIn("catch let "+reserved+"_1",generated)
        self.assertIn("else { let error = "+reserved+"_1",generated)
        # The fixture intentionally also observes an impossible success return from raise.
        self.assertFalse(report["passed"])

    def test_literal_true_while_is_structural_without_fictitious_false_outcome(self):
        manifest, report = self.compare_fixture("structural-loop","""
func tally(_ limit: Int) -> Int {
 var value = 0
 while true { if value >= limit { break }; value += 1 }
 return value
}
""", "print(tally(0)); print(tally(3))")
        self.assertTrue(report["passed"],report)
        source = manifest["sources"][0]
        self.assertEqual(len(source["structural"]),1)
        self.assertEqual(source["unsupported"],[])
        self.assertEqual(report["count"],2)
        self.assertEqual(report["covered"],2)
        self.assertTrue(any(item["classification"] == "structural-constant-loop" for item in source["classificationInventory"]))

    def test_instrumenter_and_generated_runtime_overlay_drift_fail(self):
        for path, failure in [(self.output / "swift-branch-instrument","Instrumenter binary drift"),
                              (self.output / "BranchRuntime.swift","Generated runtime drift"),
                              (self.output / "overlay.json","VFS overlay drift")]:
            original = path.read_bytes()
            try:
                path.write_bytes(original+b" ")
                report = coverage.validate(self.manifest_path,[self.full],fixture_only=True)
                self.assertFalse(report["passed"])
                self.assertIn(failure,report["failures"])
            finally: path.write_bytes(original)
        modified = copy.deepcopy(self.manifest)
        modified["compiler"]["toolSourceSHA256"] = "0"*64
        path = self.root / "instrumenter-source-drift.json"; coverage.write(path,modified)
        self.assertIn("Instrumenter source drift",coverage.validate(path,[self.full],fixture_only=True)["failures"])

    def test_unsupported_grammar_is_explicit(self):
        source = self.root / "Unsupported.swift"
        source.write_text('''
func f(_ x: Int?) -> Int {
 #if os(macOS)
 guard let x else { return 0 }
 #endif
 switch x { case .some(let n) where n > 0: return n; default: return 0 }
}
func g(_ x: [Int]?) { var x = x; x?[0] = 1; repeat {} while false }
''')
        manifest = coverage.prepare(self.root / "unsupported",[source])
        kinds = {item["kind"] for item in manifest["sources"][0]["unsupported"]}
        self.assertTrue({"ifConfigDecl","switchCase","optionalChainingExpr","repeatStmt"} <= kinds)
        self.assertFalse(coverage.validate(self.root / "unsupported/manifest.json",[],fixture_only=True)["passed"])

if __name__ == "__main__": unittest.main()
