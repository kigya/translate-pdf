"""Synthetic PDFium extraction regressions; never reads private user PDFs."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SMOKE = os.environ.get('TRANSLATEPDF_NATIVE_SMOKE', str(ROOT / '.local-private/build-mac/native-smoke'))
ENV = dict(os.environ, DYLD_LIBRARY_PATH=str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib'))


def pdf_literal(text):
    """Encode a PDF literal string using WinAnsi and escape delimiters."""
    raw = text.encode('cp1252')
    return b'(' + raw.replace(b'\\', b'\\\\').replace(b'(', b'\\(').replace(b')', b'\\)') + b')'


def text_object(font, x, baseline, text, scale=9):
    """A single independently positioned text object with a scaled text matrix."""
    return (b'BT /' + font.encode('ascii') + b' 1 Tf ' + str(scale).encode() + b' 0 0 ' +
            str(scale).encode() + b' ' + str(x).encode() + b' ' + str(baseline).encode() +
            b' Tm ' + pdf_literal(text) + b' Tj ET')


def sample_pdf(path):
    objects = []
    commands = []

    def add(font, x, y, text, scale=9):
        commands.append(text_object(font, x, y, text, scale))

    # A three-line warning. The final source line is deliberately split into
    # adjacent PDF text objects so the minus sign and temperature must survive
    # same-baseline fragment grouping.
    add('F1', 70, 700, 'Do not remove the cover')
    add('F1', 70, 688, 'until the pressure gauge')
    add('F1', 70, 676, 'reads below ')
    add('F1', 125, 676, '-')
    # A small baseline jitter is common in real PDFs even for one visual row.
    add('F1', 131, 676.5, '11 °C.')

    # Interleaved lines from a second column must not be joined to the warning.
    add('F1', 350, 700, 'The passenger airbag')
    add('F1', 350, 688, 'may not deploy here.')

    # A paragraph-sized gap and a font/style transition are independent stops.
    add('F1', 70, 642, 'Always check the pressure label.')
    add('F2', 70, 604, 'WARNING:')
    add('F1', 70, 592, 'Do not stand under a raised vehicle.')

    # More than the extractor's documented byte cap. Natural line spacing
    # encourages grouping; the cap must split the source instead of truncating.
    long_lines = []
    for index in range(28):
        line = f'LONGSEGMENT{index:02d} ' + (f'component-{index:02d} ' * 5)
        long_lines.append(line.strip())
        add('F1', 70, 550 - 12 * index, line.strip())

    stream = b'\n'.join(commands)
    objects.extend([
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] '
        b'/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
        b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream',
    ])
    data = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010} 00000 n \n'.encode() for offset in offsets[1:])
    data += (f'trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n'
             f'startxref\n{xref}\n%%EOF\n').encode()
    path.write_bytes(data)
    return long_lines, len(commands)


def read_page(path):
    result = subprocess.run([SMOKE, 'page', str(path), '0'], env=ENV,
                            text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


class PdfExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.source = Path(self.temp.name) / 'synthetic.pdf'
        self.long_lines, self.object_count = sample_pdf(self.source)
        self.page = read_page(self.source)
        self.blocks = self.page['blocks']

    def tearDown(self):
        self.temp.cleanup()

    def test_scaled_font_groups_multiline_warning_and_preserves_geometry(self):
        warning = next(block for block in self.blocks if 'Do not remove the cover' in block['sourceText'])
        normalized = ' '.join(warning['sourceText'].split())
        self.assertIn('Do not remove the cover until the pressure gauge reads below', normalized)
        self.assertRegex(normalized, r'reads below -\s*11 °C\.')
        self.assertGreaterEqual(len(warning['objectIndices']), 5)
        self.assertLess(warning['left'], 71)
        self.assertGreater(warning['right'], 110)
        self.assertLess(warning['top'], 100)
        self.assertGreater(warning['bottom'], 110)
        self.assertAlmostEqual(9.0, warning['fontSize'], delta=0.5)

        # These are the minus and temperature fragments, in stream order.
        # The latter is 0.5 pt lower, yet must stay in the same visual row.
        self.assertIn(3, warning['objectIndices'])
        self.assertIn(4, warning['objectIndices'])
        self.assertLess(warning['objectIndices'].index(3), warning['objectIndices'].index(4))

        # The extraction contract keeps each source PDF object represented once,
        # including the three fragments that form the minus/temperature line.
        object_indices = [index for block in self.blocks for index in block['objectIndices']]
        self.assertEqual(self.object_count, len(object_indices))
        self.assertEqual(self.object_count, len(set(object_indices)))
        for block in self.blocks:
            self.assertEqual(block['text'], block['sourceText'])

    def test_grouping_stays_inside_a_column_and_respects_paragraph_and_style_stops(self):
        left = next(block for block in self.blocks if 'Do not remove the cover' in block['sourceText'])
        right = next(block for block in self.blocks if 'The passenger airbag' in block['sourceText'])
        self.assertIn('until the pressure gauge', left['sourceText'])
        self.assertIn('may not deploy here.', right['sourceText'])
        self.assertNotIn('passenger airbag', left['sourceText'].lower())
        self.assertNotIn('pressure gauge', right['sourceText'].lower())

        spaced = [block for block in self.blocks if 'Always check the pressure label.' in block['sourceText']]
        self.assertEqual(1, len(spaced))
        self.assertNotIn('reads below', spaced[0]['sourceText'])

        heading = next(block for block in self.blocks if 'WARNING:' in block['sourceText'])
        body = next(block for block in self.blocks if 'Do not stand under a raised vehicle.' in block['sourceText'])
        self.assertEqual('WARNING:', heading['sourceText'].strip())
        self.assertEqual('Do not stand under a raised vehicle.', body['sourceText'].strip())

    def test_long_source_is_split_at_a_bound_without_dropping_text(self):
        long_blocks = [block for block in self.blocks if 'LONGSEGMENT' in block['sourceText']]
        self.assertGreater(len(long_blocks), 1)
        for block in long_blocks:
            self.assertLessEqual(len(block['sourceText'].encode('utf-8')), 1800)
        joined = ' '.join(block['sourceText'] for block in long_blocks)
        for line in self.long_lines:
            self.assertIn(line, joined)


if __name__ == '__main__':
    unittest.main()
