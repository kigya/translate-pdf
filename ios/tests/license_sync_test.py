#!/usr/bin/env python3
"""Invalid legal manifests must fail before replacing packaged attributions."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/sync-license-resources.sh'

class LicenseSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'ios/scripts').mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / 'ios/scripts/sync-license-resources.sh')
        self.dest = self.root / 'ios/TranslateCore/Licenses'
        self.dest.mkdir(parents=True)
        (self.dest / 'existing').write_text('keep on failure')
        (self.root / 'licenses/pkg').mkdir(parents=True)
        (self.root / 'licenses/pkg/LICENSE').write_text('required attribution')
        (self.root / 'assets/fonts').mkdir(parents=True)
        for font in ('NotoSans-Regular.ttf', 'NotoSansCondensed-Regular.ttf', 'NotoSansSC-Regular.ttf'):
            (self.root / 'assets/fonts' / font).write_bytes(b'fixture')
        self.preview = self.root / 'assets/docx-preview'
        self.preview.mkdir()
        for asset in ('index.html', 'docx-preview.min.js', 'jszip.min.js'):
            (self.preview / asset).write_bytes(('fixture:' + asset).encode())
        self.catalog = {'version': 1, 'notices': [{'id': 'pkg', 'title': 'Package', 'platforms': ['ios'], 'files': ['pkg/LICENSE']}]}

    def run_sync(self):
        (self.root / 'licenses/catalog.json').write_text(json.dumps(self.catalog))
        return subprocess.run(['bash', str(self.root / 'ios/scripts/sync-license-resources.sh')], capture_output=True)

    def assert_preserved(self):
        self.assertEqual((self.dest / 'existing').read_text(), 'keep on failure')

    def test_valid_catalog_selects_exact_resources(self):
        self.assertEqual(self.run_sync().returncode, 0)
        self.assertEqual(set(p.name for p in self.dest.iterdir()), {'catalog.json', 'pkg__LICENSE'})
        self.assertEqual((self.dest / 'pkg__LICENSE').read_text(), 'required attribution')
        preview_dest = self.root / 'ios/TranslateCore/Resources/docx-preview'
        self.assertEqual(set(p.name for p in preview_dest.iterdir()), {'index.html', 'docx-preview.min.js', 'jszip.min.js'})
        for asset in preview_dest.iterdir():
            self.assertEqual(asset.read_bytes(), (self.preview / asset.name).read_bytes())
        font_dest = self.root / 'ios/TranslateCore/Resources/fonts'
        self.assertEqual({p.name for p in font_dest.iterdir()},
                         {'NotoSans-Regular.ttf', 'NotoSansCondensed-Regular.ttf', 'NotoSansSC-Regular.ttf'})
        for font in font_dest.iterdir():
            self.assertEqual(font.read_bytes(), (self.root / 'assets/fonts' / font.name).read_bytes())

    def test_missing_preview_asset_fails_before_replacing_existing_resources(self):
        (self.preview / 'jszip.min.js').unlink()
        self.assertNotEqual(self.run_sync().returncode, 0)
        self.assert_preserved()

    def test_malformed_missing_traversal_and_collision_leave_existing_notices(self):
        valid = json.loads(json.dumps(self.catalog))
        for change in ('empty', 'missing', 'traversal', 'absolute', 'duplicate', 'collision'):
            with self.subTest(change=change):
                self.catalog = json.loads(json.dumps(valid))
                if change == 'empty': self.catalog['notices'] = []
                if change == 'missing': self.catalog['notices'][0]['files'] = ['missing']
                if change == 'traversal': self.catalog['notices'][0]['files'] = ['../outside']
                if change == 'absolute': self.catalog['notices'][0]['files'] = ['/outside']
                if change == 'duplicate': self.catalog['notices'] *= 2
                if change == 'collision':
                    (self.root / 'licenses/pkg__LICENSE').write_text('conflict')
                    self.catalog['notices'][0]['files'].append('pkg__LICENSE')
                self.assertNotEqual(self.run_sync().returncode, 0)
                self.assert_preserved()

    def test_invalid_json_does_not_report_success_or_delete_existing(self):
        (self.root / 'licenses/catalog.json').write_text('{broken')
        result = subprocess.run(['bash', str(self.root / 'ios/scripts/sync-license-resources.sh')], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(b'Synced', result.stdout)
        self.assert_preserved()

    def test_symlink_escape_is_rejected(self):
        (self.root / 'outside').write_text('private')
        (self.root / 'licenses/escape').symlink_to(self.root / 'outside')
        self.catalog['notices'][0]['files'] = ['escape']
        self.assertNotEqual(self.run_sync().returncode, 0)
        self.assert_preserved()

if __name__ == '__main__':
    unittest.main()
