"""Adversarial source-preserving PDF exports that must fail atomically."""
import json
from pathlib import Path
import tempfile
import unittest

from pdf_export_test import ROOT, run, sample_pdf
from source_preserving_pdf_test import table_cells_pdf, pictogram_pdf

FONT = ROOT / 'assets/fonts/NotoSans-Regular.ttf'


def image_pdf(path, width, height, transform='1 0 0 1 0 0 cm'):
    """Create a real page image XObject with caller-controlled metadata/CTM."""
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import (DecodedStreamObject, DictionaryObject, NameObject,
                               NumberObject)

    sample_pdf(path, dimensions=(200, 300))
    writer = PdfWriter(PdfReader(path))
    image = DecodedStreamObject()
    # A single encoded pixel is deliberately insufficient for the declared
    # oversized raster. The exporter must reject metadata before decoding it.
    image.set_data(b'\x80')
    image.update({NameObject('/Type'): NameObject('/XObject'),
                  NameObject('/Subtype'): NameObject('/Image'),
                  NameObject('/Width'): NumberObject(width),
                  NameObject('/Height'): NumberObject(height),
                  NameObject('/ColorSpace'): NameObject('/DeviceGray'),
                  NameObject('/BitsPerComponent'): NumberObject(8)})
    writer.pages[0]['/Resources'][NameObject('/XObject')] = DictionaryObject({
        NameObject('/Im1'): writer._add_object(image)})
    content = DecodedStreamObject()
    content.set_data(b'q ' + transform.encode('ascii') + b' /Im1 Do Q')
    writer.pages[0][NameObject('/Contents')] = writer._add_object(content)
    with path.open('wb') as output:
        writer.write(output)


def nested_text_form_pdf(path):
    """Place linguistic text inside a Form XObject, outside page text ownership."""
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import (DecodedStreamObject, DictionaryObject, NameObject,
                               NumberObject)

    sample_pdf(path)
    writer = PdfWriter(PdfReader(path))
    form = DecodedStreamObject()
    form.set_data(b'BT /F1 16 Tf 70 650 Td (Nested words) Tj ET')
    form.update({NameObject('/Type'): NameObject('/XObject'),
                 NameObject('/Subtype'): NameObject('/Form'),
                 NameObject('/FormType'): NumberObject(1),
                 NameObject('/BBox'): writer.pages[0].mediabox,
                 NameObject('/Resources'): writer.pages[0]['/Resources']})
    writer.pages[0]['/Resources'][NameObject('/XObject')] = DictionaryObject({
        NameObject('/Nested'): writer._add_object(form)})
    content = DecodedStreamObject()
    content.set_data(b'q /Nested Do Q')
    writer.pages[0][NameObject('/Contents')] = writer._add_object(content)
    with path.open('wb') as output:
        writer.write(output)


def rectangular_clipped_image_pdf(path):
    """One image clipped to a real axis-aligned rectangle inside the crop."""
    from pypdf import PdfWriter
    from pypdf.generic import (DecodedStreamObject, DictionaryObject, NameObject,
                               NumberObject, RectangleObject)

    writer = PdfWriter()
    page = writer.add_blank_page(width=200, height=300)
    page.cropbox = RectangleObject([20, 30, 180, 270])
    image = DecodedStreamObject()
    image.set_data(b'\x30\x90\xe0')
    image.update({NameObject('/Type'): NameObject('/XObject'),
                  NameObject('/Subtype'): NameObject('/Image'),
                  NameObject('/Width'): NumberObject(1),
                  NameObject('/Height'): NumberObject(1),
                  NameObject('/ColorSpace'): NameObject('/DeviceRGB'),
                  NameObject('/BitsPerComponent'): NumberObject(8)})
    page[NameObject('/Resources')] = DictionaryObject({
        NameObject('/XObject'): DictionaryObject({
            NameObject('/ClippedImage'): writer._add_object(image)})})
    content = DecodedStreamObject()
    content.set_data(b'q 40 60 70 100 re W n 140 0 0 140 10 60 cm /ClippedImage Do Q')
    page[NameObject('/Contents')] = writer._add_object(content)
    with path.open('wb') as output:
        writer.write(output)


def blank_pdf(path, dimensions=(200, 300)):
    from pypdf import PdfWriter
    writer = PdfWriter()
    writer.add_blank_page(width=dimensions[0], height=dimensions[1])
    with path.open('wb') as output:
        writer.write(output)


class SourcePreservingFailureTest(unittest.TestCase):
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
        for block in blocks:
            block['translatedText'] = 'Короткая строка.'
            block['status'] = 'translated'
        return {'layoutVersion': 3, 'mode': 'preserve-source',
                'sourcePageIndex': 0, 'blocks': blocks}

    def export(self, request):
        return run('export', self.source, 0,
                   json.dumps(request, ensure_ascii=False), self.output, FONT)

    def assert_atomic_error(self, request, expected_message=None):
        sentinel = b'previous complete export stays intact'
        self.output.write_bytes(sentinel)
        result = self.export(request)
        error = result.get('error', '')
        self.assertTrue(error, 'invalid input must be rejected')
        if expected_message is not None:
            self.assertIn(expected_message, error)
        self.assertEqual(sentinel, self.output.read_bytes())
        self.assertFalse(Path(str(self.output) + '.preserving').exists())
        self.assertFalse(Path(str(self.output) + '.preserving.part').exists())

    def test_oversized_intrinsic_image_is_rejected_from_metadata_before_decode(self):
        request = self.request()
        image_pdf(self.source, 4097, 4097)
        self.assert_atomic_error(request, 'Source image verification exceeds the bounded memory limit')

    def test_oversized_transformed_image_appearance_is_rejected_before_decode(self):
        request = self.request()
        image_pdf(self.source, 1, 1, transform='8192 0 0 8192 0 0 cm')
        self.assert_atomic_error(request, 'Source image appearance exceeds the bounded memory limit')

    def test_zero_dimension_image_is_rejected_without_replacing_export(self):
        request = self.request()
        image_pdf(self.source, 0, 1)
        self.assert_atomic_error(request, 'Source image verification exceeds the bounded memory limit')

    def test_duplicate_cell_character_ownership_cannot_replace_existing_export(self):
        table_cells_pdf(self.source)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(3, len(blocks))
        for block in blocks:
            block['translatedText'] = block['sourceText']
            block['status'] = 'translated'
        indices = blocks[0]['sourceSpans'][0]['characterIndices']
        self.assertGreater(len(indices), 1)
        blocks[0]['sourceSpans'][0]['characterIndices'] = [indices[0], indices[0], *indices[2:]]
        self.assert_atomic_error({'layoutVersion': 3, 'mode': 'preserve-source',
                                  'sourcePageIndex': 0, 'blocks': blocks},
                                 'Source character ownership is missing, duplicated, or stale')

    def test_nested_linguistic_form_is_rejected_without_replacing_export(self):
        nested_text_form_pdf(self.source)
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': []}
        self.assert_atomic_error(request, 'Nested source text requires an explicit translation container')

    def test_rectangular_image_clip_limits_public_artwork_bounds(self):
        rectangular_clipped_image_pdf(self.source)
        result = run('page', self.source, 0)
        self.assertNotIn('error', result)
        images = [item for item in result['artworkRects'] if item['type'] == 'image']
        self.assertEqual(1, len(images))
        for edge, expected in {'left': 20, 'top': 110, 'right': 90, 'bottom': 210}.items():
            self.assertAlmostEqual(expected, images[0][edge], places=2)

    def test_recognized_block_geometry_failures_preserve_existing_export(self):
        blank_pdf(self.source)
        cases = {
            'missing edge': ({'left': 12, 'top': 18, 'bottom': 30},
                             'The recognized source text has invalid geometry'),
            'zero width': ({'left': 12, 'top': 18, 'right': 12, 'bottom': 30},
                           'The recognized source text is outside this page'),
            'outside page': ({'left': 205, 'top': 18, 'right': 215, 'bottom': 30},
                             'The recognized source text is outside this page'),
        }
        for label, (geometry, message) in cases.items():
            with self.subTest(geometry=label):
                block = {'id': 'recognized-label', 'source': 'ocr', 'sourceText': 'Label',
                         'translatedText': 'Подпись', 'fontSize': 10, **geometry}
                request = {'layoutVersion': 3, 'mode': 'preserve-source',
                           'sourcePageIndex': 0, 'blocks': [block]}
                self.assert_atomic_error(request, message)

    def test_nonfinite_recognized_block_geometry_is_rejected_atomically(self):
        blank_pdf(self.source)
        block = {'id': 'recognized-label', 'source': 'ocr', 'sourceText': 'Label',
                 'translatedText': 'Подпись', 'fontSize': 10,
                 'left': 12, 'top': 18, 'right': float('nan'), 'bottom': 30}
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': [block]}
        self.assert_atomic_error(request)

    def test_source_preserving_merge_requires_integer_identity_metadata(self):
        """A malformed identity map cannot become valid by truncating its numbers."""
        from copy import deepcopy

        request = self.request()
        preview = self.directory / 'verified-page.pdf'
        result = run('export', self.source, 0, json.dumps(request, ensure_ascii=False), preview, FONT)
        self.assertNotIn('error', result)
        snapshot = self.directory / 'checkpoint.json'
        snapshot.write_text(json.dumps(request, ensure_ascii=False))
        envelope = {'layoutVersion': 3, 'mode': 'preserve-source',
                    'sourcePath': str(self.source), 'fontPath': str(FONT),
                    'shards': [{'sourcePageIndex': 0, 'expectedPageCount': 1,
                                'path': str(preview), 'blocksPath': str(snapshot)}]}
        control = run('merge', json.dumps(envelope), self.output)
        self.assertNotIn('error', control)
        self.assertEqual(1, control['pageCount'])
        self.assertFalse(control['partial'])

        # Native-produced integer JSON is accepted above. Fractions must not be
        # silently converted to the same index/count and bound into a new receipt.
        for field, value in [('sourcePageIndex', .9), ('expectedPageCount', 1.9),
                             ('sourcePageIndex', False), ('expectedPageCount', True),
                             ('sourcePageIndex', '0'), ('expectedPageCount', '1'),
                             ('sourcePageIndex', -1), ('expectedPageCount', 0),
                             ('sourcePageIndex', 2**64 - 1), ('expectedPageCount', 2**64 - 1)]:
            with self.subTest(field=field, value=value):
                damaged = deepcopy(envelope)
                damaged['shards'][0][field] = value
                sentinel = b'previous complete document export'
                self.output.write_bytes(sentinel)
                outcome = run('merge', json.dumps(damaged), self.output)
                self.assertIn('error', outcome, 'identity metadata must be integer JSON')
                self.assertEqual(sentinel, self.output.read_bytes())
                self.assertFalse(Path(str(self.output) + '.preserving').exists())

        with self.subTest(field='snapshot.sourcePageIndex'):
            damaged_snapshot = deepcopy(request)
            damaged_snapshot['sourcePageIndex'] = .9
            snapshot.write_text(json.dumps(damaged_snapshot, ensure_ascii=False))
            sentinel = b'previous complete document export'
            self.output.write_bytes(sentinel)
            outcome = run('merge', json.dumps(envelope), self.output)
            self.assertIn('error', outcome, 'snapshot ownership must not round to a source page')
            self.assertEqual(sentinel, self.output.read_bytes())

    def test_source_preserving_page_rejects_coercible_envelope_identity(self):
        from copy import deepcopy

        control = self.request()
        for field, value in [('sourcePageIndex', .9), ('sourcePageIndex', False),
                             ('sourcePageIndex', '0'), ('sourcePageIndex', 2**64 - 1),
                             ('layoutVersion', 3.9), ('layoutVersion', '3')]:
            with self.subTest(field=field, value=value):
                damaged = deepcopy(control)
                damaged[field] = value
                self.assert_atomic_error(damaged)

    def test_nonbreaking_space_target_cannot_erase_source_text(self):
        request = self.request()
        for block in request['blocks']:
            block['translatedText'] = '\u00a0 \t\u00a0'
        self.assert_atomic_error(request)

    def test_matching_negative_page_identity_cannot_publish_a_preview(self):
        request = self.request()
        request['sourcePageIndex'] = -1
        sentinel = b'previous valid preview'
        self.output.write_bytes(sentinel)
        outcome = run('export', self.source, -1, json.dumps(request, ensure_ascii=False), self.output, FONT)
        self.assertIn('error', outcome)
        self.assertEqual(sentinel, self.output.read_bytes())

    def test_mixed_prose_cannot_be_replaced_by_its_icon_token_alone(self):
        pictogram_pdf(self.source, b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                      b'BT /F2 12 Tf 104 700 Td (l) Tj ET '
                      b'BT /F1 12 Tf 120 700 Td (now.) Tj ET')
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        block = blocks[0]
        self.assertIn('Press', block['sourceText'])
        self.assertEqual(1, len(block['symbols']))
        token = block['symbols'][0]['token']
        block.update(translatedText=f'Нажмите {token} сейчас.', status='translated')
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': blocks}
        control = self.export(request)
        self.assertNotIn('error', control, 'the full translated sentence has a valid fit')
        self.assertFalse(control['partial'])
        block['translatedText'] = token
        self.assert_atomic_error(request, 'no visible text')


    def test_table_source_spans_reject_coercible_and_wrapping_identity_values(self):
        from copy import deepcopy

        table_cells_pdf(self.source)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(3, len(blocks))
        for block in blocks:
            block.update(translatedText=block['sourceText'], status='translated')
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': blocks}
        valid = self.export(request)
        self.assertNotIn('error', valid)
        self.assertFalse(valid['partial'])
        span = blocks[0]['sourceSpans'][0]
        owner = span['objectIndex']
        character = span['characterIndices'][0]
        cases = [('objectIndex', owner + .9), ('objectIndex', owner + 2**32),
                 ('objectIndex', owner - 2**32), ('objectIndex', 2**64 - 1),
                 ('objectIndex', str(owner)), ('objectIndex', False),
                 ('characterIndex', character + 2**32),
                 ('characterIndex', character - 2**32), ('characterIndex', 2**64 - 1),
                 ('characterIndex', character + .9), ('characterIndex', False)]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                damaged = deepcopy(request)
                damaged_span = damaged['blocks'][0]['sourceSpans'][0]
                if field == 'objectIndex':
                    damaged_span[field] = value
                else:
                    damaged_span['characterIndices'][0] = value
                self.assert_atomic_error(damaged)


    def test_multiline_prose_cannot_publish_only_its_flowing_icon_tokens(self):
        pictogram_pdf(self.source, b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                      b'BT /F2 12 Tf 104 700 Td (l) Tj ET '
                      b'BT /F1 12 Tf 120 700 Td (now before continuing the procedure) Tj ET '
                      b'BT /F1 12 Tf 70 685 Td (and check the display carefully) Tj ET '
                      b'BT /F1 12 Tf 70 670 Td (then press ) Tj ET '
                      b'BT /F2 12 Tf 132 670 Td (l) Tj ET '
                      b'BT /F1 12 Tf 148 670 Td (again to confirm.) Tj ET')
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks))
        block = blocks[0]
        tokens = [symbol['token'] for symbol in block['symbols']]
        self.assertEqual(2, len(tokens))
        block.update(translatedText=f'Нажмите {tokens[0]}, проверьте экран и нажмите {tokens[1]} ещё раз.',
                     status='translated')
        request = {'layoutVersion': 3, 'mode': 'preserve-source',
                   'sourcePageIndex': 0, 'blocks': blocks}
        control = self.export(request)
        self.assertNotIn('error', control)
        self.assertFalse(control['partial'])
        block['translatedText'] = ' '.join(tokens)
        self.assert_atomic_error(request, 'no visible text')


if __name__ == '__main__':
    unittest.main()
