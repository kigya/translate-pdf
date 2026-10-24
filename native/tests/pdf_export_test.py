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


def sample_pdf(path, dimensions=(612, 792)):
    stream = b'BT /F1 18 Tf 70 710 Td (A short line.) Tj 0 -55 Td (Another line.) Tj ET 1 0 0 RG 70 590 180 50 re S'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               (f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {dimensions[0]} {dimensions[1]}] '
                '/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>').encode(),
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


def type3_icon_pdf(path, content=None, triangle=None, paint_type=0):
    """A filled triangle whose text extraction is '>', not its visible shape."""
    content = content or b'BT /F1 60 Tf 20 20 Td <41> Tj ET'
    triangle = triangle or b'1000 0 0 0 1000 1000 d1 0 0 m 1000 500 l 0 1000 l h f'
    cmap = b'''/CIDInit /ProcSet findresource begin
12 dict begin begincmap
/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
/CMapName /TriangleToUnicode def /CMapType 2 def
1 begincodespacerange <00> <FF> endcodespacerange
1 beginbfchar <41> <003E> endbfchar
endcmap CMapName currentdict /CMap defineresource pop end end'''
    def stream(value):
        return b'<< /Length ' + str(len(value)).encode() + b' >>\nstream\n' + value + b'\nendstream'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 120 120] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        f'<< /Type /Font /Subtype /Type3 /Name /TriangleIcon /BaseFont /TriangleIcon /PaintType {paint_type} /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /triangle 6 0 R >> /Encoding << /Type /Encoding /Differences [65 /triangle] >> /FirstChar 65 /LastChar 65 /Widths [1000] /Resources << >> /ToUnicode 7 0 R >>'.encode(),
        stream(content), stream(triangle), stream(cmap),
    ]
    data = b'%PDF-1.4\n'; offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    start = len(data)
    data += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += f'trailer << /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
    path.write_bytes(data)


def mixed_type3_inline_pdf(path, content=None):
    """Three same-line text objects, with a pictogram between two text runs."""
    content = content or (b'BT /F1 24 Tf 20 55 Td (Press ) Tj ET '
               b'BT /F2 24 Tf 88 55 Td <41> Tj ET '
               b'BT /F1 24 Tf 116 55 Td (now.) Tj ET')
    triangle = b'1000 0 0 0 1000 1000 d1 0 0 m 1000 500 l 0 1000 l h f'
    cmap = b'''/CIDInit /ProcSet findresource begin
12 dict begin begincmap
/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
/CMapName /TriangleToUnicode def /CMapType 2 def
1 begincodespacerange <00> <FF> endcodespacerange
1 beginbfchar <41> <003E> endbfchar
endcmap CMapName currentdict /CMap defineresource pop end end'''
    def stream(value):
        return b'<< /Length ' + str(len(value)).encode() + b' >>\nstream\n' + value + b'\nendstream'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 120] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Type /Font /Subtype /Type3 /Name /TriangleIcon /BaseFont /TriangleIcon /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /triangle 7 0 R >> /Encoding << /Type /Encoding /Differences [65 /triangle] >> /FirstChar 65 /LastChar 65 /Widths [1000] /Resources << >> /ToUnicode 8 0 R >>',
        stream(content), stream(triangle), stream(cmap),
    ]
    data = b'%PDF-1.4\n'; offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    start = len(data)
    data += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += f'trailer << /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
    path.write_bytes(data)


def pdfium_snapshot(path, allow_graphics=False, dimensions=(120, 120)):
    """Read actual pixels and text-object properties through pinned PDFium."""
    lib = ctypes.CDLL(str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib/libpdfium.dylib'))
    pointer = ctypes.c_void_p
    signatures = {
        'FPDF_LoadDocument': ([ctypes.c_char_p, ctypes.c_char_p], pointer),
        'FPDF_LoadPage': ([pointer, ctypes.c_int], pointer),
        'FPDFPage_CountObjects': ([pointer], ctypes.c_int),
        'FPDFPage_GetObject': ([pointer, ctypes.c_int], pointer),
        'FPDFPageObj_GetType': ([pointer], ctypes.c_int),
        'FPDFPageObj_GetBounds': ([pointer] + [ctypes.POINTER(ctypes.c_float)] * 4, ctypes.c_int),
        'FPDFTextObj_GetFont': ([pointer], pointer),
        'FPDFTextObj_GetFontSize': ([pointer, ctypes.POINTER(ctypes.c_float)], ctypes.c_int),
        'FPDFFont_GetBaseFontName': ([pointer, pointer, ctypes.c_size_t], ctypes.c_size_t),
        'FPDFPage_GetAnnotCount': ([pointer], ctypes.c_int),
        'FPDFBitmap_Create': ([ctypes.c_int, ctypes.c_int, ctypes.c_int], pointer),
        'FPDFBitmap_FillRect': ([pointer] + [ctypes.c_int] * 4 + [ctypes.c_uint32], None),
        'FPDF_RenderPageBitmap': ([pointer, pointer] + [ctypes.c_int] * 6, None),
        'FPDFBitmap_GetBuffer': ([pointer], pointer),
        'FPDFBitmap_GetStride': ([pointer], ctypes.c_int),
        'FPDFBitmap_Destroy': ([pointer], None),
        'FPDF_ClosePage': ([pointer], None),
        'FPDF_CloseDocument': ([pointer], None),
    }
    for name, (arguments, result) in signatures.items():
        function = getattr(lib, name); function.argtypes = arguments; function.restype = result
    lib.FPDF_InitLibrary()
    document = page = bitmap = None
    try:
        document = lib.FPDF_LoadDocument(os.fsencode(path), None)
        if not document: raise AssertionError('snapshot PDF must load')
        page = lib.FPDF_LoadPage(document, 0)
        if not page: raise AssertionError('snapshot page must load')
        width, height = dimensions
        bitmap = lib.FPDFBitmap_Create(width, height, 1)
        if not bitmap: raise AssertionError('snapshot bitmap must allocate')
        lib.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xffffffff)
        lib.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, 0)
        stride = lib.FPDFBitmap_GetStride(bitmap)
        pixels = ctypes.string_at(lib.FPDFBitmap_GetBuffer(bitmap), stride * height)
        objects = []
        for index in range(lib.FPDFPage_CountObjects(page)):
            obj = lib.FPDFPage_GetObject(page, index)
            kind = lib.FPDFPageObj_GetType(obj)
            if kind != 1:
                if not allow_graphics: raise AssertionError('fixture must retain only text objects')
                objects.append((kind,)); continue
            font = lib.FPDFTextObj_GetFont(obj)
            name = ctypes.create_string_buffer(lib.FPDFFont_GetBaseFontName(font, None, 0))
            lib.FPDFFont_GetBaseFontName(font, name, len(name))
            size = ctypes.c_float(); bounds = [ctypes.c_float() for _ in range(4)]
            if not lib.FPDFTextObj_GetFontSize(obj, ctypes.byref(size)): raise AssertionError('font size must be readable')
            if not lib.FPDFPageObj_GetBounds(obj, *(ctypes.byref(edge) for edge in bounds)): raise AssertionError('text bounds must be readable')
            objects.append((kind, name.value, size.value, tuple(edge.value for edge in bounds)))
        return {'pixels': pixels, 'stride': stride, 'objects': objects, 'annotations': lib.FPDFPage_GetAnnotCount(page)}
    finally:
        if bitmap: lib.FPDFBitmap_Destroy(bitmap)
        if page: lib.FPDF_ClosePage(page)
        if document: lib.FPDF_CloseDocument(document)
        lib.FPDF_DestroyLibrary()


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

    def export(self, cjk_font=None):
        return run('export', self.source, 0, json.dumps(self.blocks, ensure_ascii=False), self.output,
                   ROOT / 'assets/fonts/NotoSans-Regular.ttf', *([cjk_font] if cjk_font else []))

    def test_safe_native_text_is_replaced_with_extractable_cyrillic(self):
        for block in self.blocks:
            self.assertEqual(block['text'], block['sourceText'])
            self.assertEqual(block['text'], block['modelText'])
            self.assertEqual([], block['symbols'])
        result = self.export()
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertEqual(1, run('inspect', self.output)['pageCount'])
        text = ' '.join(block['text'] for block in run('page', self.output, 0)['blocks'])
        self.assertIn('Коротко.', text)
        self.assertNotIn('A short line.', text)
        self.assertFalse(Path(str(self.output) + '.part').exists())

    def test_whitespace_only_translation_keeps_original_text(self):
        for whitespace in ('\n', ' \t\r\n'):
            with self.subTest(whitespace=repr(whitespace)):
                self.blocks[0]['translatedText'] = whitespace
                result = self.export()
                self.assertTrue(result['partial'])
                self.assertEqual('missing_translation', result['issues'][0]['reason'])
                text = ' '.join(block['text'] for block in run('page', self.output, 0)['blocks'])
                self.assertIn('A short line.', text)
                self.assertIn('Коротко.', text)

    def test_identical_native_text_retains_original_objects_without_layout_fallback(self):
        # Force geometry that cannot fit a replacement: an unchanged source must
        # retain the actual font/glyph objects rather than create an annotation.
        for block in self.blocks:
            block['translatedText'] = block['sourceText']
            block['right'] = block['left']
            block['status'] = 'partial'
        result = self.export()
        self.assertTrue(result['partial'])
        self.assertEqual(0, result['partialBlocks'])
        self.assertEqual([], result['issues'])
        after = run('page', self.output, 0)['blocks']
        self.assertEqual([b['sourceText'] for b in self.page['blocks']], [b['sourceText'] for b in after])
        for block in self.blocks:
            block['status'] = 'translated'
        complete = self.export()
        self.assertFalse(complete['partial'])
        self.assertEqual(0, complete['partialBlocks'])
        self.assertEqual([], complete['issues'])
        final = run('page', self.output, 0)['blocks']
        self.assertEqual([b['sourceText'] for b in self.page['blocks']], [b['sourceText'] for b in final])

    def test_identical_type3_icon_keeps_original_pixels_and_text_object(self):
        type3_icon_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        self.assertEqual(['>'], [block['sourceText'] for block in self.blocks])
        before = pdfium_snapshot(self.source)
        self.assertEqual(1, len(before['objects']))
        # A solid triangle has substantial ink, including its centre; a literal
        # greater-than character or an empty rendered fixture cannot satisfy it.
        dark = sum(max(before['pixels'][at:at+3]) < 64 for at in range(0, len(before['pixels']), 4))
        self.assertGreater(dark, 1000)
        centre = 70 * before['stride'] + 40 * 4
        self.assertEqual(b'\0\0\0', before['pixels'][centre:centre+3])
        self.blocks[0]['translatedText'] = '>'
        self.blocks[0]['status'] = 'translated'
        result = self.export()
        self.assertFalse(result['partial'])
        self.assertEqual(0, result['partialBlocks'])
        self.assertEqual([], result['issues'])
        after = pdfium_snapshot(self.output)
        self.assertEqual(0, after['annotations'])
        self.assertEqual(before['objects'], after['objects'])
        self.assertEqual(before['stride'], after['stride'])
        self.assertEqual(before['pixels'], after['pixels'])
        exported = run('page', self.output, 0)['blocks']
        self.assertEqual(['>'], [block['sourceText'] for block in exported])
        self.assertEqual(self.blocks[0]['objectIndices'], exported[0]['objectIndices'])

    def test_readable_v2_preserves_type3_icon_as_visible_symbol_asset(self):
        type3_icon_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        self.assertEqual(['>'], [block['sourceText'] for block in self.blocks])
        self.assertEqual(self.blocks[0]['sourceText'], '>')
        self.assertRegex(self.blocks[0]['modelText'], r'^\[\[FVSYM[A-Z]+\]\]$')
        self.assertEqual(self.blocks[0]['text'], self.blocks[0]['sourceText'])
        self.blocks[0]['translatedText'] = self.blocks[0]['modelText']
        self.blocks[0]['status'] = 'translated'
        payload = {'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}
        result = run('export', self.source, 0, json.dumps(payload, ensure_ascii=False), self.output,
                     ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        self.assertEqual(1, result['pageCount'])
        symbol_placements = [p for p in result['placements'] if p.get('role') == 'symbol']
        self.assertEqual(1, len(symbol_placements))
        placement = symbol_placements[0]
        self.assertEqual(self.blocks[0]['id'], placement['blockId'])
        self.assertEqual('symbol', placement['role'])
        self.assertEqual(0, placement['outputPageIndex'])
        self.assertGreater(placement['right'], placement['left'])
        self.assertGreater(placement['bottom'], placement['top'])
        after = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(595, 842))
        self.assertEqual(0, after['annotations'])
        self.assertEqual(1, len(after['objects']))
        self.assertEqual(3, after['objects'][0][0])  # PDFium image object
        dark = sum(max(after['pixels'][at:at+3]) < 64 for at in range(0, len(after['pixels']), 4))
        self.assertGreater(dark, 100)
        inside_x = int(placement['left'] + (placement['right'] - placement['left']) * 2 / 3)
        inside_y = int(placement['top'] + (placement['bottom'] - placement['top']) / 2)
        inside = inside_y * after['stride'] + inside_x * 4
        self.assertLess(max(after['pixels'][inside:inside+3]), 64)
        self.assertEqual([], run('page', self.output, 0)['blocks'])

    def test_readable_v2_keeps_legacy_standalone_invariant_pictogram_compatible(self):
        type3_icon_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        block = self.blocks[0]
        block.pop('modelText')
        block.pop('symbols')
        block['translatedText'] = block['sourceText']
        block['status'] = 'translated'
        result = run('export', self.source, 0,
                     json.dumps({'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}, ensure_ascii=False),
                     self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        image_objects = [obj for obj in pdfium_snapshot(self.output, allow_graphics=True,
                                                        dimensions=(595, 842))['objects'] if obj[0] == 3]
        self.assertEqual(1, len(image_objects), 'legacy invariant pictogram uses the original block bitmap path')
        self.assertEqual([], run('page', self.output, 0)['blocks'])

    def test_readable_v2_rejects_legacy_mixed_pictogram_checkpoint_atomically(self):
        mixed_type3_inline_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        block = self.blocks[0]
        token = block['symbols'][0]['token']
        block['translatedText'] = f'Press {token} now.'
        block['status'] = 'translated'
        valid = {'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}
        baseline = run('export', self.source, 0, json.dumps(valid, ensure_ascii=False), self.output,
                       ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertNotIn('error', baseline)
        previous = self.output.read_bytes()
        block.pop('modelText')
        block.pop('symbols')
        block['translatedText'] = 'Нажмите >, чтобы продолжить.'
        result = run('export', self.source, 0,
                     json.dumps({'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}, ensure_ascii=False),
                     self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertIn('Legacy checkpoint omits inline pictogram metadata', result['error'])
        self.assertEqual(previous, self.output.read_bytes(), 'legacy mixed checkpoint must leave the old export untouched')

    def test_readable_v2_fails_closed_for_white_pictogram_on_black_source_art(self):
        type3_icon_pdf(self.source,
                       content=b'0 g 0 0 120 120 re f 1 g BT /F1 60 Tf 20 20 Td <41> Tj ET')
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        block = self.blocks[0]
        self.assertEqual('>', block['sourceText'])
        source_pixels = pdfium_snapshot(self.source, allow_graphics=True)
        self.assertLess(max(source_pixels['pixels'][10 * source_pixels['stride'] + 10 * 4:
                                                   10 * source_pixels['stride'] + 10 * 4 + 3]), 64,
                        'the black path provides a source contrast background for the white glyph')
        white_sample = 70 * source_pixels['stride'] + 60 * 4
        self.assertGreater(min(source_pixels['pixels'][white_sample:white_sample+3]), 240,
                           'the reference fixture must visibly render a white Type3 glyph')
        block['translatedText'] = block['modelText']
        block['status'] = 'translated'
        previous = self.output.read_bytes() if self.output.exists() else None
        result = run('export', self.source, 0,
                     json.dumps({'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}, ensure_ascii=False),
                     self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertIn('insufficient contrast on the white output page', result['error'])
        if previous is None:
            self.assertFalse(self.output.exists())
        else:
            self.assertEqual(previous, self.output.read_bytes())

    def test_readable_v2_flows_inline_symbol_between_translated_text_and_wraps(self):
        mixed_type3_inline_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        self.assertEqual(1, len(self.blocks), 'same-line text and icon must remain one semantic block')
        block = self.blocks[0]
        self.assertEqual('Press > now.', block['text'])
        self.assertEqual(block['text'], block['sourceText'])
        self.assertEqual(3, len(block['objectIndices']))
        self.assertEqual(1, len(block['symbols']))
        symbol = block['symbols'][0]
        self.assertEqual('>', symbol['sourceText'])
        self.assertEqual('Press ' + symbol['token'] + ' now.', block['modelText'])
        self.assertEqual(1, symbol['objectIndex'])
        sentence = f'Нажмите {symbol["token"]}, чтобы продолжить перевод. '
        block['translatedText'] = sentence + ' '.join(['Сохраняйте внимательность при выполнении инструкции.'] * 80)
        block['status'] = 'translated'
        payload = {'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}
        result = run('export', self.source, 0, json.dumps(payload, ensure_ascii=False), self.output,
                     ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertFalse(result['partial'])
        self.assertEqual([], result['issues'])
        placements = [p for p in result['placements'] if p.get('role') == 'symbol']
        self.assertEqual(1, len(placements))
        self.assertEqual(symbol['token'], placements[0]['symbolToken'])
        self.assertLess(placements[0]['right'] - placements[0]['left'], 36,
                        'the image segment must contain only the source pictogram, not nearby English text')
        self.assertLess(placements[0]['bottom'] - placements[0]['top'], 36)
        self.assertGreater(result['pageCount'], 1, 'long translated prose must wrap over output pages')
        exported = run('page', self.output, 0)['blocks']
        visible_text = ' '.join(b['text'] for b in exported)
        self.assertIn('Нажмите', visible_text)
        self.assertIn('продолжить перевод.', visible_text)
        self.assertNotIn('FVSYM', visible_text)
        self.assertFalse(any('\ue000' <= char <= '\uf8ff' for char in visible_text))
        rendered = pdfium_snapshot(self.output, allow_graphics=True, dimensions=(595, 842))
        self.assertEqual(1, sum(1 for obj in rendered['objects'] if obj[0] == 3),
                         'the pictogram is a single image object alongside target text')
        sample_x = int(placements[0]['left'] + (placements[0]['right'] - placements[0]['left']) * 2 / 3)
        sample_y = int(placements[0]['top'] + (placements[0]['bottom'] - placements[0]['top']) / 2)
        pixel = sample_y * rendered['stride'] + sample_x * 4
        self.assertLess(max(rendered['pixels'][pixel:pixel+3]), 64,
                        'the original symbol glyph pixels remain visible inline')

    def test_tall_inline_symbols_keep_line_spacing_and_page_margins(self):
        mixed_type3_inline_pdf(self.source, (
            b'BT /F1 24 Tf 20 55 Td (Press ) Tj ET '
            b'BT /F2 24 Tf 88 55 Td <41> Tj ET '
            b'BT /F1 24 Tf 116 55 Td (and ) Tj ET '
            b'BT /F2 24 Tf 163 55 Td <41> Tj ET '
            b'BT /F1 24 Tf 191 55 Td (now.) Tj ET'))
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        tokens = [s['token'] for s in blocks[0]['symbols']]
        self.assertEqual(2, len(tokens))
        blocks[0]['translatedText'] = ('Строка.\n' * 52) + tokens[0] + '\n' + tokens[1]
        blocks[0]['status'] = 'translated'
        result = run('export', self.source, 0,
                     json.dumps({'layoutVersion': 2, 'mode': 'reflow', 'blocks': blocks}, ensure_ascii=False),
                     self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        icons = [p for p in result['placements'] if p.get('role') == 'symbol']
        self.assertEqual(2, len(icons))
        self.assertEqual([1, 1], [p['outputPageIndex'] for p in icons],
                         'the bottom-of-page icon must move with its complete line box')
        for icon in icons:
            self.assertGreaterEqual(icon['top'], 36)
            self.assertLessEqual(icon['bottom'], 842 - 36)
            self.assertGreater(icon['bottom'] - icon['top'], 10.5 * 1.38,
                               'the fixture must exercise symbols taller than ordinary line leading')
        self.assertGreater(icons[1]['top'], icons[0]['bottom'], 'adjacent icon lines need real clearance')

    def test_legacy_readable_export_keeps_overlapping_diagram_parts_and_flags_ocr_label(self):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject
        writer = PdfWriter(PdfReader(self.source))
        content = writer.pages[0].get_contents().get_data()
        stream = DecodedStreamObject()
        stream.set_data(content + b' 0 0 1 RG 150 600 150 50 re S')
        writer.pages[0][NameObject('/Contents')] = writer._add_object(stream)
        with self.source.open('wb') as output:
            writer.write(output)
        blocks = run('page', self.source, 0)['blocks']
        for block in blocks:
            block['translatedText'] = 'Коротко.'
            block['status'] = 'translated'
        blocks.append(dict(id='recognized-label', source='ocr', sourceText='Valve',
                           text='Valve', translatedText='Клапан', status='translated',
                           left=100, top=170, right=150, bottom=182, fontSize=10))
        result = run('export', self.source, 0,
                     json.dumps(dict(layoutVersion=2, mode='reflow', blocks=blocks), ensure_ascii=False),
                     self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertNotIn('error', result)
        figures = [p for p in result['placements'] if p.get('role') == 'figure']
        self.assertEqual(1, len(figures), 'overlapping diagram pieces must be kept together, not duplicated')
        self.assertTrue(result['partial'])
        self.assertEqual(1, len([issue for issue in result['issues']
                                if issue.get('reason') == 'figure_label_reflow'
                                and issue.get('blockId') == 'recognized-label']))
        self.assertTrue(self.output.is_file())

    def test_readable_v2_rejects_missing_or_unknown_inline_symbol_tokens_atomically(self):
        mixed_type3_inline_pdf(self.source)
        self.page = run('page', self.source, 0)
        self.blocks = self.page['blocks']
        block = self.blocks[0]
        token = block['symbols'][0]['token']
        block['translatedText'] = f'Нажмите {token}, чтобы продолжить.'
        block['status'] = 'translated'
        payload = {'layoutVersion': 2, 'mode': 'reflow', 'blocks': self.blocks}
        good = run('export', self.source, 0, json.dumps(payload, ensure_ascii=False), self.output,
                   ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertFalse(good['partial'])
        previous = self.output.read_bytes()
        for invalid in (f'Нажмите, чтобы продолжить.',
                        f'Нажмите {token} {token}, чтобы продолжить.',
                        f'Нажмите {token} [[FVSYMZZ]], чтобы продолжить.',
                        f'Нажмите {token} \ue000, чтобы продолжить.',
                        f'Нажмите {token} \uf8ff, чтобы продолжить.'):
            with self.subTest(invalid=invalid):
                block['translatedText'] = invalid
                result = run('export', self.source, 0, json.dumps(payload, ensure_ascii=False), self.output,
                             ROOT / 'assets/fonts/NotoSans-Regular.ttf')
                self.assertIn('error', result)
                self.assertEqual(previous, self.output.read_bytes(), 'rejected token output must not replace prior export')

    @unittest.skipUnless((ROOT / 'assets/fonts/NotoSansSC-Regular.ttf').is_file(), 'CJK font asset is not present in this checkout')
    def test_simplified_chinese_font_fallback_and_no_space_wrapping(self):
        block = self.blocks[0]
        block['translatedText'] = '中文测试，继续阅读中文说明，保存原始数字 128。'
        block['fontSize'] = 10
        block['bottom'] = block['top'] + 48
        block['right'] = block['left'] + 72
        result = self.export(ROOT / 'assets/fonts/NotoSansSC-Regular.ttf')
        self.assertFalse(result['partial'])
        text = ''.join(' '.join(item['text'] for item in run('page', self.output, 0)['blocks']).split())
        for token in ('中文测试', '继续阅读', '128'):
            self.assertIn(token, text)
        self.assertIn('。', text)

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
        self.assertEqual(128, run('render', self.source, 0, 1)['width'])
        self.assertEqual(1800, run('render', self.source, 0, 2000)['width'])
        self.assertIn('error', run('render', self.source, 999999, 200))

    def test_unloaded_translation_and_unknown_model_profile_return_errors(self):
        self.assertEqual('Model is not loaded', run('translate-unloaded', 'Sample text')['error'])
        unknown = run('load-model', self.source, 2048, 4, 'unknown-profile')
        self.assertEqual('Unknown translation profile', unknown['error'])

    def test_export_rejects_missing_optional_cjk_font_and_malformed_blocks(self):
        self.blocks[0]['translatedText'] = '中文说明'
        missing_font = run('export', self.source, 0, json.dumps(self.blocks, ensure_ascii=False),
                           self.output, ROOT / 'assets/fonts/NotoSans-Regular.ttf',
                           self.directory / 'missing-cjk.ttf')
        self.assertEqual('Simplified Chinese export font is missing', missing_font['error'])
        malformed = run('export', self.source, 0, '{', self.output,
                        ROOT / 'assets/fonts/NotoSans-Regular.ttf')
        self.assertIn('error', malformed)

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

    def test_merge_rejects_empty_or_invalid_page_lists(self):
        self.assertEqual('No completed pages to export', run('merge', '[]', self.output)['error'])
        self.assertIn('error', run('merge', '{"page":"missing"}', self.output))


if __name__ == '__main__':
    unittest.main()
