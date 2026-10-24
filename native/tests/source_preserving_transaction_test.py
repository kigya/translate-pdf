"""Public-PDF tests for stale snapshots and atomic source-preserving export."""
import json
from pathlib import Path
import tempfile
import unittest

from pypdf import PdfReader, PdfWriter

from pdf_export_test import ROOT, run, sample_pdf

FONT = ROOT / 'assets/fonts/NotoSans-Regular.ttf'


class SourcePreservingTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'source.pdf'
        self.output = self.directory / 'translated.pdf'
        sample_pdf(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def request(self):
        blocks = run('page', self.source, 0)['blocks']
        self.assertTrue(blocks)
        for block in blocks:
            block.update(translatedText='Короткая строка.', status='translated')
        return {'layoutVersion': 3, 'mode': 'preserve-source',
                'sourcePageIndex': 0, 'blocks': blocks}

    def assert_prior_export_survives(self, outcome, sentinel):
        self.assertIn('error', outcome, 'the invalid source-preserving transaction must be rejected')
        self.assertEqual(sentinel, self.output.read_bytes(),
                         'failed validation/finalization must leave the previous artifact intact')
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_changed_source_text_rejects_a_stale_saved_checkpoint(self):
        request = self.request()
        # Keep the PDF structure and text-object position intact while changing
        # the actual source character after the checkpoint was produced.
        source_bytes = self.source.read_bytes()
        self.assertIn(b'(A short line.)', source_bytes)
        self.source.write_bytes(source_bytes.replace(b'(A short line.)', b'(B short line.)', 1))

        sentinel = b'previous verified export'
        self.output.write_bytes(sentinel)
        result = run('export', self.source, 0, json.dumps(request, ensure_ascii=False),
                     self.output, FONT)
        self.assertIn('error', result)
        self.assertIn('saved source text no longer matches', result['error'])
        self.assert_prior_export_survives(result, sentinel)

    def test_preview_page_geometry_mismatch_rejects_merge_before_publication(self):
        request = self.request()
        snapshot_path = self.directory / 'blocks.json'
        snapshot_path.write_text(json.dumps(request, ensure_ascii=False))

        valid_preview = self.directory / 'valid-preview.pdf'
        exported = run('export', self.source, 0, json.dumps(request, ensure_ascii=False),
                       valid_preview, FONT)
        self.assertNotIn('error', exported, 'control preview must first be valid')

        reader = PdfReader(valid_preview)
        writer = PdfWriter(reader)
        writer.pages[0].mediabox.upper_right = (613, 792)
        wrong_geometry = self.directory / 'wrong-geometry.pdf'
        with wrong_geometry.open('wb') as stream:
            writer.write(stream)
        self.assertNotEqual(PdfReader(self.source).pages[0].mediabox,
                            PdfReader(wrong_geometry).pages[0].mediabox)

        envelope = {'layoutVersion': 3, 'mode': 'preserve-source',
                    'sourcePath': str(self.source), 'fontPath': str(FONT),
                    'shards': [{'sourcePageIndex': 0, 'expectedPageCount': 1,
                                'path': str(wrong_geometry), 'blocksPath': str(snapshot_path)}]}
        sentinel = b'previous complete document'
        self.output.write_bytes(sentinel)
        result = run('merge', json.dumps(envelope, ensure_ascii=False), self.output)
        self.assertIn('error', result)
        self.assertIn('page geometry changed', result['error'])
        self.assert_prior_export_survives(result, sentinel)

    def test_unreplaceable_destination_cleans_staging_after_save(self):
        request = self.request()
        self.output.mkdir()
        sentinel = b'kept inside destination directory'
        existing = self.output / 'existing.txt'
        existing.write_bytes(sentinel)

        result = run('export', self.source, 0, json.dumps(request, ensure_ascii=False),
                     self.output, FONT)
        self.assertIn('error', result, 'a directory cannot be atomically replaced by the PDF')
        self.assertEqual(sentinel, existing.read_bytes())
        self.assertTrue(self.output.is_dir())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())


if __name__ == '__main__':
    unittest.main()
