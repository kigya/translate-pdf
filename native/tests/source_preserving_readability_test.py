"""Focused PDFium regressions for inline-symbol reading order and safe flow."""
import json
from pathlib import Path
import tempfile
import unittest

from pdf_export_test import ROOT, run, sample_pdf
from source_preserving_pdf_test import pictogram_pdf, pdfium_symbol_snapshot

FONT = ROOT / 'assets/fonts/NotoSans-Regular.ttf'


class SourcePreservingReadabilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'source.pdf'
        self.output = self.directory / 'translated.pdf'
        sample_pdf(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, content, target):
        pictogram_pdf(self.source, content)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(1, len(blocks), 'the fixture sentence must remain one native translation unit')
        block = blocks[0]
        expected_icons = 2 if content.count(b'(m) Tj') else 1
        self.assertEqual(expected_icons, len(block.get('symbols', [])))
        tokens = [symbol['token'] for symbol in block.get('symbols', [])]
        block['translatedText'] = target(*tokens)
        block['status'] = 'translated'
        return dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=blocks), tokens

    def export(self, request):
        return run('export', self.source, 0, json.dumps(request, ensure_ascii=False), self.output, FONT)

    def merged_request(self, blocks, target):
        self.assertTrue(blocks)
        merged = dict(blocks[0])
        for key in ('sourceText', 'modelText', 'objectIndices', 'sourceRects', 'symbols'):
            merged[key] = [] if key in ('objectIndices', 'sourceRects', 'symbols') else ''
        for block in blocks:
            merged['sourceText'] += block.get('sourceText', '')
            merged['modelText'] += block.get('modelText', '')
            merged['objectIndices'].extend(block.get('objectIndices', [block['objectIndex']]))
            merged['sourceRects'].extend(block.get('sourceRects', []))
            merged['symbols'].extend(block.get('symbols', []))
        for edge, reducer in (('left', min), ('top', min), ('right', max), ('bottom', max)):
            merged[edge] = reducer(block[edge] for block in blocks)
        merged['translatedText'] = target
        merged['status'] = 'translated'
        return dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=[merged]), merged

    def test_terminal_icon_keeps_split_period_inline_but_not_wrapped_capitalized_sentence(self):
        from reportlab.pdfbase import pdfmetrics
        prefix = 'Press '
        x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        after = x + 12 + 2
        single = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                  b'BT /F2 12 Tf ' + f'{x:.3f}'.encode() + b' 700 Td (l) Tj ET '
                  b'BT /F1 12 Tf ' + f'{after:.3f}'.encode() + b' 700 Td (.) Tj ET')
        request, tokens = self.request(single, lambda icon: f'Нажмите {icon}.')
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        moved = [p for p in result['placements'] if p.get('role') == 'inline-source-glyph']
        self.assertEqual(1, len(moved), 'a source-split terminal period is valid inline punctuation')
        self.assertEqual(tokens[0], moved[0]['symbolToken'])
        self.assertNotIn('Press', Path(self.output).read_bytes().decode('latin1', errors='ignore'))
        self.assertEqual('●', pdfium_symbol_snapshot(self.output)[0]['text'].strip())

        wrapped = (single + b' BT /F1 12 Tf 70 685 Td (Continue safely.) Tj ET')
        request, _ = self.request(wrapped, lambda icon: f'Нажмите {icon}. Продолжайте безопасно.')
        self.assertEqual(1, len(request['blocks']))
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertFalse(any(p.get('role') == 'inline-source-glyph' for p in result['placements']),
                         'an uppercase next-line sentence is not a lowercase continuation')
        source_symbol = pdfium_symbol_snapshot(self.source)[0]
        output_symbol = pdfium_symbol_snapshot(self.output)[0]
        self.assertEqual(source_symbol['matrix'], output_symbol['matrix'],
                         'the ambiguous wrapped icon stays at its exact source position')

    def test_lowercase_wrapped_continuation_can_gain_readable_flow(self):
        from reportlab.pdfbase import pdfmetrics
        prefix = 'Press '
        x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        after = x + 12 + 2
        content = (b'BT /F1 12 Tf 70 700 Td (Press ) Tj ET '
                   b'BT /F2 12 Tf ' + f'{x:.3f}'.encode() + b' 685 Td (l) Tj ET '
                   b'BT /F1 12 Tf ' + f'{after:.3f}'.encode() + b' 685 Td ( the control) Tj ET '
                   b'BT /F1 12 Tf 70 670 Td ( and verify the selection.) Tj ET')
        pictogram_pdf(self.source, content)
        blocks = run('page', self.source, 0)['blocks']
        self.assertEqual(3, len(blocks), 'source lines remain separate native runs before checkpoint grouping')
        main = dict(blocks[0])
        main.update(sourceText='', modelText='', objectIndices=[], sourceRects=[], symbols=[])
        for continuation in blocks:
            main['sourceText'] += continuation['sourceText']
            main['modelText'] += (' ' if main['modelText'] else '') + continuation['modelText']
            main['objectIndices'] += continuation['objectIndices']
            main['sourceRects'] += continuation['sourceRects']
            main['symbols'] += continuation.get('symbols', [])
        for edge, reducer in (('left', min), ('top', min), ('right', max), ('bottom', max)):
            main[edge] = reducer(block[edge] for block in blocks)
        tokens = [symbol['token'] for symbol in main['symbols']]
        self.assertEqual(1, len(tokens))
        main['translatedText'] = f'Чтобы включить управление автомобилем и подтвердить выбранную настройку, нажмите {tokens[0]}, затем дождитесь проверки и убедитесь, что система отвечает правильно.'
        main['status'] = 'translated'
        request = dict(layoutVersion=3, mode='preserve-source', sourcePageIndex=0, blocks=[main])
        before = pdfium_symbol_snapshot(self.source)[0]
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        moved = [p for p in result['placements'] if p.get('role') == 'inline-source-glyph']
        self.assertEqual(1, len(moved), 'verified lowercase continuation joins the paragraph flow')
        self.assertEqual(tokens[0], moved[0]['symbolToken'])
        after = pdfium_symbol_snapshot(self.output)[0]
        self.assertEqual(before['font'], after['font'])
        self.assertEqual(before['text'].strip(), after['text'].strip())
        self.assertEqual(before['colors'], after['colors'])
        self.assertGreater(abs(before['matrix'][5] - after['matrix'][5]), 1.0,
                           'the icon moves with the combined translated sentence')
        text_placements = [p for p in result['placements'] if p.get('role') == 'text']
        self.assertTrue(text_placements)
        self.assertTrue(all(p['fontSize'] >= 12.0 and p['widthScale'] >= 1.0 for p in text_placements),
                        'flow keeps the original font and avoids the fixed-layout width compression')

    def test_adjacent_source_icons_count_as_occupied_space_between_anchors(self):
        from reportlab.pdfbase import pdfmetrics
        prefix = 'Use '
        first_x = 70 + pdfmetrics.stringWidth(prefix, 'Helvetica', 12) + 2
        second_x = first_x + 12 + 1
        after_x = second_x + 12 + 2
        content = (b'BT /F1 12 Tf 70 700 Td (Use ) Tj ET '
                   b'BT /F2 12 Tf ' + f'{first_x:.3f}'.encode() + b' 700 Td (l) Tj ET '
                   b'BT /F2 12 Tf ' + f'{second_x:.3f}'.encode() + b' 700 Td (m) Tj ET '
                   b'BT /F1 12 Tf ' + f'{after_x:.3f}'.encode() + b' 700 Td ( now.) Tj ET')
        request, tokens = self.request(content, lambda first, second: f'Да {first}{second} сейчас.')
        self.assertEqual(2, len(tokens))
        source_symbols = pdfium_symbol_snapshot(self.source)
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        output_symbols = pdfium_symbol_snapshot(self.output)
        self.assertEqual(2, len(output_symbols))
        for before, after in zip(source_symbols, output_symbols):
            self.assertEqual(before['text'].strip(), after['text'].strip())
            self.assertEqual(before['font'], after['font'])
            self.assertEqual(before['colors'], after['colors'])
            self.assertEqual(before['render_mode'], after['render_mode'])
            self.assertEqual(before['matrix'], after['matrix'],
                             'adjacent fixed icons remain in source order and are not unnecessarily flowed')
        self.assertFalse(any(p.get('role') == 'inline-source-glyph' for p in result['placements']))

    def test_tightly_adjacent_source_icons_prevent_treating_their_gap_as_empty(self):
        # The target is short enough for fixed placement. The two source icons
        # sit between the prose runs with almost no space; the first icon must
        # count as occupied geometry when considering the second anchor.
        content = (b'BT /F1 12 Tf 20 700 Td (Use ) Tj ET '
                   b'BT /F2 12 Tf 41 700 Td (l) Tj ET '
                   b'BT /F2 12 Tf 51 700 Td (m) Tj ET '
                   b'BT /F1 12 Tf 64 700 Td ( now.) Tj ET')
        request, tokens = self.request(content, lambda first, second: f'Да {first}{second}.')
        before = pdfium_symbol_snapshot(self.source)
        self.assertEqual(2, len(before))

        result = self.export(request)

        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertFalse(any(p.get('role') == 'inline-source-glyph' for p in result['placements']),
                         'nearby source icons already occupy the apparent inter-anchor gap')
        after = pdfium_symbol_snapshot(self.output)
        self.assertEqual([glyph['text'].strip() for glyph in before],
                         [glyph['text'].strip() for glyph in after])
        for sourceGlyph, outputGlyph in zip(before, after):
            self.assertEqual(sourceGlyph['matrix'], outputGlyph['matrix'])
            self.assertEqual(sourceGlyph['font'], outputGlyph['font'])
            self.assertEqual(sourceGlyph['colors'], outputGlyph['colors'])
        self.assertEqual(2, len(tokens))

    def test_composite_overprinted_icon_rects_have_deterministic_visual_order(self):
        # Two Dingbat layers share the same origin and baseline to form one
        # composite icon. A valid saved grouping merges the adjacent native
        # runs, and the export must retain both layers in stable source order.
        content = (b'BT /F1 12 Tf 20 700 Td (Use ) Tj ET '
                   b'BT /F2 12 Tf 52 700 Td (l) Tj ET '
                   b'BT /F2 12 Tf 52 700 Td (m) Tj ET '
                   b'BT /F1 12 Tf 70 700 Td ( now.) Tj ET')
        pictogram_pdf(self.source, content)
        source = run('page', self.source, 0)
        self.assertGreaterEqual(len(source['blocks']), 2)
        sourceTokens = [symbol['token'] for block in source['blocks']
                        for symbol in block.get('symbols', [])]
        self.assertEqual(2, len(sourceTokens))
        request, merged = self.merged_request(
            source['blocks'], f'Используйте {sourceTokens[0]} вместе с {sourceTokens[1]} сейчас.')
        symbols = merged['symbols']
        self.assertEqual(2, len(symbols))
        rectByObject = {rect['objectIndex']: rect for rect in merged['sourceRects']}
        symbolRects = [rectByObject[symbol['objectIndex']] for symbol in symbols]
        self.assertEqual(symbolRects[0]['left'], symbolRects[1]['left'])
        self.assertEqual(symbolRects[0]['baseline'], symbolRects[1]['baseline'],
                         'the actual source layers tie on baseline and left edge')
        before = pdfium_symbol_snapshot(self.source)
        self.assertEqual(2, len(before))

        result = self.export(request)

        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        moved = [p for p in result['placements'] if p.get('role') == 'inline-source-glyph']
        self.assertEqual([symbol['token'] for symbol in symbols],
                         [placement['symbolToken'] for placement in moved])
        after = pdfium_symbol_snapshot(self.output)
        self.assertEqual([glyph['text'].strip() for glyph in before],
                         [glyph['text'].strip() for glyph in after])
        for sourceGlyph, outputGlyph in zip(before, after):
            self.assertEqual(sourceGlyph['font'], outputGlyph['font'])
            self.assertEqual(sourceGlyph['colors'], outputGlyph['colors'])
            self.assertEqual(sourceGlyph['render_mode'], outputGlyph['render_mode'])

    def test_baseline_jitter_and_scrambled_object_enumeration_use_visual_reading_order(self):
        # The icon is enumerated first, then the right-hand run, then the left
        # run. Their baselines straddle the old half-point bucket boundary by
        # only .02 pt, so exact geometry plus anchored row clustering is needed.
        content = (b'BT /F2 12 Tf 53.000 55.26 Td (l) Tj ET '
                   b'BT /F1 12 Tf 68.000 55.25 Td (the control now.) Tj ET '
                   b'BT /F1 12 Tf 20.000 55.24 Td (Press ) Tj ET')
        pictogram_pdf(self.source, content)
        source = run('page', self.source, 0)
        self.assertEqual(1, len(source['blocks']))
        block = dict(source['blocks'][0])
        self.assertEqual([2, 0, 1], block['objectIndices'], 'source extraction itself uses visual reading order')
        # A valid legacy checkpoint may enumerate the same source objects in a
        # different order. Keep sourceText exactly tied to that listed order;
        # flow eligibility must reread and geometrically order actual objects.
        block['objectIndices'] = [0, 1, 2]
        block['sourceRects'] = sorted(block['sourceRects'], key=lambda rect: rect['objectIndex'])
        block['sourceText'] = ''.join(rect['text'] for rect in block['sourceRects'])
        token = block['symbols'][0]['token']
        request, _ = self.merged_request(
            [block],
            f'Нажмите {token}, проверьте нужное меню и убедитесь в состоянии системы.')
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        moved = [p for p in result['placements'] if p.get('role') == 'inline-source-glyph']
        self.assertEqual(1, len(moved), 'geometry sorting recovers left-text, symbol, right-text reading order')
        self.assertEqual(token, moved[0]['symbolToken'])
        symbol = pdfium_symbol_snapshot(self.output)[0]
        self.assertEqual('●', symbol['text'].strip())
        self.assertNotIn('Press', self.output.read_bytes().decode('latin1', errors='ignore'))

    def test_internal_blank_rows_prefer_safe_same_size_flow_but_keep_short_caption_fixed(self):
        # Two short source rows carry one sentence and two anchors. Fixed
        # placement strands the second clause at its distant source baseline;
        # verified flow can put both anchors on one row with no size/scale cost.
        content = (b'BT /F1 12 Tf 20 700 Td (Use ) Tj ET '
                   b'BT /F2 12 Tf 49 700 Td (l) Tj ET '
                   b'BT /F1 12 Tf 64 700 Td (now) Tj ET '
                   b'BT /F1 12 Tf 70 660 Td (and ) Tj ET '
                   b'BT /F2 12 Tf 100 660 Td (m) Tj ET '
                   b'BT /F1 12 Tf 115 660 Td (later.) Tj ET')
        pictogram_pdf(self.source, content)
        blocks = run('page', self.source, 0)['blocks']
        self.assertGreaterEqual(len(blocks), 2)
        tokens = [symbol['token'] for block in blocks for symbol in block.get('symbols', [])]
        self.assertEqual(2, len(tokens))
        request, merged = self.merged_request(
            blocks, f'Да {tokens[0]}, затем {tokens[1]}.')
        sourceCenters = sorted((symbol['bounds']['top'] + symbol['bounds']['bottom']) / 2
                               for symbol in merged['symbols'])
        self.assertGreater(sourceCenters[1] - sourceCenters[0], 3 * 12,
                           'the source anchors intentionally straddle a large internal vertical gap')
        sourceSymbols = pdfium_symbol_snapshot(self.source)
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        moved = sorted((p for p in result['placements'] if p.get('role') == 'inline-source-glyph'),
                       key=lambda p: p['symbolToken'])
        self.assertEqual(set(tokens), {p['symbolToken'] for p in moved})
        movedCenters = sorted((p['top'] + p['bottom']) / 2 for p in moved)
        self.assertGreater(sourceCenters[1] - sourceCenters[0] - (movedCenters[1] - movedCenters[0]),
                           1.5 * 12,
                        'same-size flow removes more than 1.5 source font heights of internal whitespace')
        textPlacements = [p for p in result['placements'] if p.get('role') == 'text']
        self.assertTrue(textPlacements)
        self.assertTrue(all(p['fontSize'] >= 12 and p['widthScale'] >= 1 for p in textPlacements))
        outputSymbols = pdfium_symbol_snapshot(self.output)
        self.assertEqual(len(sourceSymbols), len(outputSymbols))
        for before, after in zip(sourceSymbols, outputSymbols):
            self.assertEqual(before['text'].strip(), after['text'].strip())
            self.assertEqual(before['font'], after['font'])
            self.assertEqual(before['colors'], after['colors'])

        # A one-line short caption has no internal vertical hole to recover.
        short = (b'BT /F1 12 Tf 20 700 Td (Use ) Tj ET '
                 b'BT /F2 12 Tf 49 700 Td (l) Tj ET '
                 b'BT /F1 12 Tf 64 700 Td (now.) Tj ET')
        request, _ = self.request(short, lambda icon: f'Да {icon}.')
        result = self.export(request)
        self.assertNotIn('error', result)
        self.assertFalse(result['partial'])
        self.assertFalse(any(p.get('role') == 'inline-source-glyph' for p in result['placements']),
                         'the vertical-gap rule does not move an ordinary short caption')


if __name__ == '__main__':
    unittest.main()
