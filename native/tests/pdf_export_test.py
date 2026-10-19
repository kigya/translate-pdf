"""Host integration tests against real PDFium; no models or private fixtures."""
import json
import ctypes
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SMOKE = os.environ.get('TRANSLATEPDF_NATIVE_SMOKE', str(ROOT / '.local-private/build-mac/native-smoke'))
ENV = dict(os.environ, DYLD_LIBRARY_PATH=str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib'))


def sample_pdf(path):
    stream = b'BT /F1 18 Tf 70 710 Td (A short line.) Tj 0 -55 Td (Another line.) Tj ET 1 0 0 RG 70 590 180 50 re S'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream']
    data = b'%PDF-1.4\n'; offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    start = len(data)
    data += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += f'trailer << /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
    path.write_bytes(data)


def run(*arguments):
    result = subprocess.run([SMOKE, *map(str, arguments)], env=ENV, text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


class PdfExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'source.pdf'
        self.output = self.directory / 'translated.pdf'
        sample_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        for block in self.blocks:
            block['translatedText'] = 'Коротко.'
            block['status'] = 'translated'

    def tearDown(self):
        self.temp.cleanup()

    def export(self):
        return run('export', self.source, 0, json.dumps(self.blocks, ensure_ascii=False), self.output,
                   ROOT / 'assets/fonts/NotoSans-Regular.ttf')

    def test_safe_native_text_is_replaced_with_extractable_cyrillic(self):
        result = self.export()
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertEqual(1, run('inspect', self.output)['pageCount'])
        text = ' '.join(block['text'] for block in run('page', self.output, 0)['blocks'])
        self.assertIn('Коротко.', text)
        self.assertNotIn('A short line.', text)
        self.assertFalse(Path(str(self.output) + '.part').exists())

    def test_image_text_fallback_retains_source_and_reports_exact_block(self):
        self.blocks[0]['source'] = 'ocr'
        result = self.export()
        self.assertTrue(result['partial'])
        issue = result['issues'][0]
        self.assertEqual(self.blocks[0]['id'], issue['blockId'])
        self.assertEqual('layout_fallback', issue['reason'])
        text = ' '.join(block['text'] for block in run('page', self.output, 0)['blocks'])
        self.assertIn('A short line.', text)
        # Check the exported PDF through PDFium's public annotation API: viewers
        # must discover a visible compact comment containing the full translation.
        lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
        lib.FPDF_InitLibrary()
        lib.FPDF_LoadDocument.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        lib.FPDF_LoadDocument.restype = ctypes.c_void_p
        lib.FPDF_LoadPage.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.FPDF_LoadPage.restype = ctypes.c_void_p
        lib.FPDFPage_GetAnnot.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.FPDFPage_GetAnnot.restype = ctypes.c_void_p
        lib.FPDFAnnot_GetFlags.argtypes = [ctypes.c_void_p]
        class Rect(ctypes.Structure):
            _fields_ = [(key, ctypes.c_float) for key in ('left', 'top', 'right', 'bottom')]
        lib.FPDFAnnot_GetRect.argtypes = [ctypes.c_void_p, ctypes.POINTER(Rect)]
        lib.FPDFAnnot_GetStringValue.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_ulong]
        for name in ('FPDFPage_CloseAnnot', 'FPDF_ClosePage', 'FPDF_CloseDocument'):
            getattr(lib, name).argtypes = [ctypes.c_void_p]
        document = lib.FPDF_LoadDocument(os.fsencode(self.output), None)
        page = lib.FPDF_LoadPage(document, 0)
        annotation = lib.FPDFPage_GetAnnot(page, 0)
        try:
            self.assertTrue(annotation)
            self.assertEqual(0, lib.FPDFAnnot_GetFlags(annotation) & (1 | 2 | 32))
            rect = Rect(); self.assertTrue(lib.FPDFAnnot_GetRect(annotation, ctypes.byref(rect)))
            self.assertLessEqual(rect.right - rect.left, 16)
            self.assertLessEqual(rect.top - rect.bottom, 16)
            buffer = ctypes.create_string_buffer(512)
            count = lib.FPDFAnnot_GetStringValue(annotation, b'Contents', buffer, len(buffer))
            self.assertEqual('Коротко.', buffer.raw[:count-2].decode('utf-16-le'))
        finally:
            lib.FPDFPage_CloseAnnot(annotation); lib.FPDF_ClosePage(page); lib.FPDF_CloseDocument(document)
            lib.FPDF_DestroyLibrary()

    def test_missing_translation_is_explicitly_partial(self):
        self.blocks[0]['translatedText'] = None
        result = self.export()
        self.assertEqual('missing_translation', result['issues'][0]['reason'])
        self.assertTrue(result['partial'])

    def test_invalid_object_reference_preserves_original(self):
        self.blocks[0]['objectIndices'] = [999999]
        result = self.export()
        self.assertEqual('unsafe_text_object', result['issues'][0]['reason'])
        self.assertTrue(result['partial'])
        text = ' '.join(block['text'] for block in run('page', self.output, 0)['blocks'])
        self.assertIn('A short line.', text)

    def test_invalid_input_and_page_do_not_create_output(self):
        self.assertIn('error', run('page', self.source, 999999))
        invalid = self.directory / 'invalid.pdf'; invalid.write_bytes(b'not a PDF')
        self.assertIn('error', run('inspect', invalid))
        self.assertFalse(self.output.exists())

    def test_real_page_raster_is_bounded_and_invalid_page_reports_error(self):
        raster = run('render', self.source, 0, 200)
        self.assertEqual(200, raster['width'])
        self.assertLessEqual(raster['height'], 4096)
        self.assertEqual(raster['width'] * raster['height'] * 4, raster['bytes'])
        self.assertIn('error', run('render', self.source, 999999, 200))

    def test_invalid_model_is_rejected_without_inference(self):
        result = run('translate', self.source, 'Hello')
        self.assertIn('error', result)

    @unittest.skipUnless(os.environ.get('TRANSLATEPDF_TEST_MODEL'), 'Optional local model was not supplied')
    def test_real_model_returns_text_and_context_overflow_is_not_committed(self):
        model = os.environ['TRANSLATEPDF_TEST_MODEL']
        configuration = run('model-configuration', model)
        self.assertEqual((1024, 1), (configuration['first']['context'], configuration['first']['threads']))
        self.assertEqual((2048, 2), (configuration['second']['context'], configuration['second']['threads']))
        self.assertEqual((2048, 2), (configuration['cached']['context'], configuration['cached']['threads']))
        translated = run('translate', model, 'Do not remove the head restraint.')
        self.assertNotIn('error', translated)
        self.assertIn('не', translated['text'].lower())
        canceled = run('cancel-translate', model)
        self.assertIn('Canceled', canceled['error'])
        self.assertNotIn('text', canceled)
        overflow = run('translate', model, 'A very long paragraph. ' * 3000)
        self.assertIn('error', overflow)
        self.assertNotIn('text', overflow)

    def test_merge_validates_saved_page_count(self):
        self.export()
        merged = self.directory / 'merged.pdf'
        result = run('merge', json.dumps([str(self.output), str(self.output)]), merged)
        self.assertEqual(2, result['pageCount'])
        self.assertEqual(2, run('inspect', merged)['pageCount'])


if __name__ == '__main__':
    unittest.main()
