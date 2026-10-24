"""PDF metadata context stays separate from extracted page headings."""
from pathlib import Path
import tempfile
import unittest

from pdf_export_test import run, sample_pdf


class DocumentContextTest(unittest.TestCase):
    def read_heading_page(self, lines, title=None):
        """Read actual styled PDF text, including navigation and body decoys."""
        from reportlab.pdfgen import canvas
        from pypdf import PdfReader, PdfWriter

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'headings.pdf'
            drawing = canvas.Canvas(str(source), pagesize=(612, 792))
            for text, size, x, y in lines:
                drawing.setFont('Helvetica', size)
                drawing.drawString(x, y, text)
            # Keep the measured body median independent of the larger headings.
            for index in range(8):
                drawing.setFont('Helvetica', 9)
                drawing.drawString(70, 300 - index * 18, f'Ordinary body paragraph number {index}.')
            drawing.save()
            if title is not None:
                writer = PdfWriter(PdfReader(source))
                writer.add_metadata({'/Title': title})
                with source.open('wb') as output:
                    writer.write(output)
            return run('page', source, 0)

    def read_with_title(self, title):
        from pypdf import PdfReader, PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'metadata.pdf'
            sample_pdf(source)
            writer = PdfWriter(PdfReader(source))
            if title is not None:
                writer.add_metadata({'/Title': title})
            # Deliberately different XMP metadata must not override Info Title.
            xmp = DecodedStreamObject()
            xmp.set_data(b'''<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">Different XMP title</rdf:li>
</rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta>''')
            xmp[NameObject('/Type')] = NameObject('/Metadata')
            xmp[NameObject('/Subtype')] = NameObject('/XML')
            writer._root_object[NameObject('/Metadata')] = writer._add_object(xmp)
            with source.open('wb') as output:
                writer.write(output)
            return run('page', source, 0)

    def test_info_title_is_normalized_and_never_taken_from_xmp_or_page_text(self):
        page = self.read_with_title('\ufeff \tExample product manual\n ')
        self.assertNotIn('error', page)
        self.assertEqual('Example product manual', page['documentTitle'])
        self.assertIn('Example product manual', page['context'])
        self.assertNotIn('Different XMP title', page['context'])
        self.assertTrue(page['blocks'])
        self.assertNotEqual(page['blocks'][0]['sourceText'], page['documentTitle'])

    def test_absent_or_short_info_title_does_not_fall_back_to_xmp(self):
        for title in (None, '', '  ', 'ab'):
            with self.subTest(title=title):
                page = self.read_with_title(title)
                self.assertNotIn('error', page)
                self.assertEqual('', page['documentTitle'])
                self.assertNotIn('Different XMP title', page['context'])

    def test_metadata_bound_is_utf8_bytes_without_truncating_a_title(self):
        for title, expected in (('x' * 400, 'x' * 400),
                                ('я' * 200, 'я' * 200),
                                ('x' * 401, ''),
                                ('я' * 201, '')):
            with self.subTest(bytes=len(title.encode('utf-8'))):
                page = self.read_with_title(title)
                self.assertNotIn('error', page)
                self.assertEqual(expected, page['documentTitle'])
                self.assertLessEqual(len(page['documentTitle'].encode('utf-8')), 400)

    def test_context_omits_running_page_numbers_and_lower_body_headings(self):
        page = self.read_heading_page([
            ('Safety 7', 12, 70, 775),
            ('8 Controls', 12, 350, 775),
            ('132', 16, 70, 700),
            ('... ---', 16, 350, 700),
            ('Vehicle Safety', 16, 70, 640),
            ('Footer Reference', 16, 70, 80),
        ], title='Product Manual')
        self.assertNotIn('error', page)
        self.assertEqual('Product Manual | Vehicle Safety', page['context'])
        # Filtering context never removes visible text from the source IR.
        extracted = ' '.join(block['sourceText'] for block in page['blocks'])
        for original in ('Safety 7', '8 Controls', '132', 'Footer Reference'):
            self.assertIn(original, extracted)

    def test_context_deduplicates_separate_identical_headings(self):
        page = self.read_heading_page([
            ('Vehicle Safety', 16, 70, 670),
            ('Vehicle Safety', 16, 70, 610),
            ('Control Checks', 14, 70, 550),
        ])
        self.assertNotIn('error', page)
        self.assertEqual(1, page['context'].count('Vehicle Safety'))
        self.assertTrue(page['context'].endswith('Vehicle Safety | Control Checks'))

    def test_context_byte_limit_keeps_complete_heading_codepoints(self):
        # 394 title bytes + three separator bytes leave three bytes. The first
        # two-byte accent fits; a second must not be split to consume the budget.
        title = 'T' * 394
        page = self.read_heading_page([('ééé', 16, 70, 640)], title=title)
        self.assertNotIn('error', page)
        self.assertEqual(title + ' | é', page['context'])
        self.assertEqual(399, len(page['context'].encode('utf-8')))


if __name__ == '__main__':
    unittest.main()
