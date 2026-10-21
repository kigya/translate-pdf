"""Real two-module runtime injection and loaded-image/VFS provenance."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("injection_coverage",TOOL / "coverage.py")
coverage = importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)


class InjectionTests(unittest.TestCase):
    def test_two_real_modules_export_their_loaded_images_and_merge_only_real_hits(self):
        root = Path(tempfile.mkdtemp(prefix="swift-injection-",dir=coverage.ROOT / ".local-private"))
        first = root / "Alpha.swift"; second = root / "Beta.swift"
        first.write_text("public func alpha(_ value: Bool) -> Int { if value { return 1 }; return 0 }\n")
        second.write_text("public func beta(_ value: Bool) -> Int { if value { return 2 }; return 0 }\n")
        driver = root / "Driver.swift"; driver.write_text('@main struct D { static func main() { print("original-driver") } }\n')
        test_driver = root / "Driver.test.swift"
        test_driver.write_text('''
@testable import Alpha
@testable import Beta
@main struct D {
 static func main() throws {
  print(alpha(true),alpha(false),beta(true),beta(false))
  try Alpha.__FBCov.export(to:CommandLine.arguments[1],runID:"alpha-run")
  try Beta.__FBCov.export(to:CommandLine.arguments[2],runID:"beta-run")
 }
}
''')
        output = root / "prepared"
        manifest = coverage.prepare(output,[first,second],runtime_injections={"Alpha":first,"Beta":second},extra_overlays={driver:test_driver})
        overlay = str(output / "overlay.json")
        commands = []
        for module,source in [("Alpha",first),("Beta",second)]:
            command = ["xcrun","swiftc","-j","1","-swift-version","5","-Onone","-enable-testing","-emit-library","-emit-module","-module-name",module,
                       "-vfsoverlay",overlay,str(source),"-emit-module-path",str(root / (module+".swiftmodule")),"-o",str(root / ("lib"+module+".dylib"))]
            coverage.run(command);commands.append(command)
        binary = root / "driver"
        command = ["xcrun","swiftc","-j","1","-swift-version","5","-Onone","-parse-as-library","-vfsoverlay",overlay,"-I",str(root),"-L",str(root),"-lAlpha","-lBeta","-Xlinker","-rpath","-Xlinker",str(root),str(driver),"-o",str(binary)]
        coverage.run(command);commands.append(command)
        first_result = root / "alpha.json"; second_result = root / "beta.json"
        observed = coverage.run([str(binary),str(first_result),str(second_result)])
        self.assertEqual(observed,"1 0 2 0\n")
        records = [json.loads(path.read_text()) for path in [first_result,second_result]]
        for module,record in zip(["Alpha","Beta"],records):
            self.assertEqual(record["recorderModule"],module)
            self.assertEqual(record["declaredModule"],module)
            self.assertEqual(record["binaryRole"],"loaded-instrumented-image")
            self.assertNotEqual(Path(record["binaryPath"]),binary)
            self.assertEqual(Path(record["binaryPath"]).name,"lib"+module+".dylib")
            self.assertEqual(coverage.binary_uuid(Path(record["binaryPath"])),record["binaryUUID"])
            self.assertEqual(sum(v>0 for site in record["hits"].values() for v in site.values()),2)
        report = coverage.validate(output / "manifest.json",[first_result,second_result],fixture_only=True)
        self.assertTrue(report["passed"],report)
        self.assertEqual((report["covered"],report["count"]),(4,4))
        # Appending bytes after a correctly generated manifest must fail.
        transformed = Path(manifest["sources"][0]["transformed"])
        raw = transformed.read_bytes()
        transformed.write_bytes(raw+b" ")
        self.assertFalse(coverage.validate(output / "manifest.json",[first_result,second_result],fixture_only=True)["passed"])
        transformed.write_bytes(raw)
        coverage.write(root / "commands.json",commands)
        coverage.write(root / "observations.json",{"stdout":observed,"report":report})
        print("Two-module evidence:",root)
