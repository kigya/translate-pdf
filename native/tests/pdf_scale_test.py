"""Structural scale checks against real PDFium; not full-document quality acceptance."""
import json
from pathlib import Path
import tempfile
import unittest

from pdf_export_test import run, sample_pdf


class PdfScaleTest(unittest.TestCase):
    def test_one_thousand_checkpoint_pages_merge_in_order_and_remain_readable(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'completed-page.pdf'
            destination = Path(folder) / 'thousand-pages.pdf'
            sample_pdf(source)
            result = run('merge', json.dumps([str(source)] * 1000), destination)
            self.assertNotIn('error', result)
            self.assertEqual(1000, result['pageCount'])
            self.assertEqual(1000, run('inspect', destination)['pageCount'])
            for index in (0, 499, 999):
                page = run('page', destination, index)
                self.assertEqual(index, page['index'])
                self.assertEqual(612, page['width'])
                self.assertEqual(792, page['height'])
                self.assertIn('A short line.', ' '.join(block['text'] for block in page['blocks']))
            self.assertFalse(Path(str(destination) + '.part').exists())

    def test_extremely_tall_page_preview_is_bounded_without_truncating_its_aspect(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'tall-page.pdf'
            sample_pdf(source, dimensions=(612, 12000))
            raster = run('render', source, 0, 1800)
            self.assertNotIn('error', raster)
            self.assertLessEqual(raster['width'], 1800)
            self.assertLessEqual(raster['height'], 4096)
            self.assertGreater(raster['width'], 0)
            self.assertAlmostEqual(612 / 12000, raster['width'] / raster['height'], delta=.001)
            self.assertEqual(raster['width'] * raster['height'] * 4, raster['bytes'])


if __name__ == '__main__':
    unittest.main()
