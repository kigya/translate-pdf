"""Metadata parser controls, not fabricated Xcode or runtime evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ios_provenance_tool",TOOL / "run_ios.py")
runner = importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)


class HostedMetadataTests(unittest.TestCase):
    def test_test_driver_can_only_export_and_preserves_original_test_body(self):
        original = 'final class NativeCoverageTests: XCTestCase {\n func testA() { print("unchanged") }\n}\n'
        transformed = runner.teardown_driver(original,{"TranslatePDF":"app.json","TranslateCore":"core.json"},"fixture")
        self.assertIn('func testA() { print("unchanged") }',transformed)
        self.assertEqual(transformed.count('__FBCov.export'),2)
        self.assertIn('defer { super.tearDown() }',transformed)
        for invalid in [original.replace('print("unchanged")','__FBCov.mark()'),original.replace('func testA()','class func tearDown()')]:
            with self.assertRaises(ValueError): runner.teardown_driver(invalid,{},"fixture")

    def test_compile_metadata_requires_all_owned_inputs_and_vfs(self):
        with tempfile.TemporaryDirectory(dir=runner.coverage.ROOT / ".local-private") as temporary:
            root = Path(temporary).resolve();sources = [];commands = []
            overlay = root / 'overlay.json';sdk = root / 'SDK'
            for module,names in runner.MODULE_FILES.items():
                paths = []
                for name in sorted(names):
                    path = root / name;path.write_text('// metadata only\n');paths.append(str(path))
                    sources.append({'source':str(path),'sourceSHA256':'metadata-original','transformedSHA256':'metadata-copy'})
                response = root / (module+'.SwiftFileList');response.write_text('\n'.join(paths))
                commands.append(f'swiftc -module-name {module} @{response} -target arm64-apple-ios17.0-simulator -sdk {sdk} -swift-version 5 -D DEBUG -vfsoverlay {overlay}')
            commands.append(f'swiftc -module-name TranslatePDFNativeCoverageTests -target arm64-apple-ios17.0-simulator -sdk {sdk} -swift-version 5 -vfsoverlay {overlay}')
            manifest = {'compiler':{'command':['swiftc','-o',str(root / 'instrumenter')]},'sources':sources}
            log = root / 'synthetic-parser-control.log';log.write_text('\n'.join(commands))
            report = runner.compile_attestation(log,manifest,str(sdk))
            self.assertTrue(report['passed'],report)
            expected_modules = {
                "TranslatePDF": {"AppDelegate.swift", "SceneDelegate.swift"},
                "TranslateCore": {
                    "ModelCatalog.swift", "ModelDownloadCoordinator.swift",
                    "PdfTranslatorModule.swift", "TranslatorStore.swift",
                    "DocxPreviewRenderer.swift", "MyMemoryTranslator.swift",
                },
            }
            self.assertEqual(len(report['ownedInputs']),8)
            observed_modules = {}
            for path, evidence in report['ownedInputs'].items():
                observed_modules.setdefault(evidence['module'], set()).add(Path(path).name)
            self.assertEqual(observed_modules, expected_modules)
            self.assertEqual(sum(len(job['responseFiles']) for job in report['compileJobs']),2)
            log.write_text('\n'.join(commands).replace('-vfsoverlay '+str(overlay),''))
            self.assertFalse(runner.compile_attestation(log,manifest,str(sdk))['passed'])
            log.write_text('\n'.join(commands[1:]))
            self.assertFalse(runner.compile_attestation(log,manifest,str(sdk))['passed'])
