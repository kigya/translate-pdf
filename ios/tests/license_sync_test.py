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
        for font in ('NotoSans-Regular.ttf', 'NotoSansSC-Regular.ttf'):
            (self.root / 'assets/fonts' / font).write_bytes(b'fixture')
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
