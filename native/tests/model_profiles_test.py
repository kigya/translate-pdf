"""Real pinned-model contracts. Weights stay private and are explicitly supplied.

These short cases validate routing/error contracts, not translation quality.
The separately reviewed technical corpus remains the quality gate.
"""
import json
import os
import subprocess
import unittest

from pdf_export_test import ENV, SMOKE


class ModelProfilesTest(unittest.TestCase):
    def batch(self, model, profile, cases):
        result = subprocess.run(
            [SMOKE, 'translate-batch', model, profile, '-'],
            input=json.dumps(cases, ensure_ascii=False), text=True,
            capture_output=True, env=ENV, timeout=360,
        )
        self.assertEqual(0, result.returncode, result.stderr[-2000:])
        rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        self.assertEqual(len(cases), len(rows))
        return [row['result'] for row in rows]

    @unittest.skipUnless(os.environ.get('TRANSLATEPDF_TEST_MODEL'), 'Supply the private pinned Hy-MT2 model')
    def test_hy_rejects_invalid_pairs_and_skips_empty_text_before_inference(self):
        cases = [
            {'sourceLanguage': 'xx', 'targetLanguage': 'ru', 'text': 'Hello'},
            {'sourceLanguage': 'en', 'targetLanguage': 'xx', 'text': 'Hello'},
            {'sourceLanguage': 'xx', 'targetLanguage': 'xx', 'text': ''},
            {'sourceLanguage': 'xx', 'targetLanguage': 'ru', 'text': '', 'glossary': 'Hello = Привет'},
            {'sourceLanguage': 'en', 'targetLanguage': 'en', 'text': 'Hello'},
            {'sourceLanguage': 'en', 'targetLanguage': 'ru', 'text': ''},
            {'sourceLanguage': 'en', 'targetLanguage': 'fr', 'text': 'Hello.', 'context': 'A short greeting.'},
            {'sourceLanguage': 'zh-Hans', 'targetLanguage': 'en', 'text': '你好。'},
            {'sourceLanguage': 'de', 'targetLanguage': 'es', 'text': 'Hallo.'},
        ]
        rows = self.batch(os.environ['TRANSLATEPDF_TEST_MODEL'], 'hy-mt2', cases)
        self.assertEqual('Unsupported language pair', rows[0]['error'])
        self.assertEqual('Unsupported language pair', rows[1]['error'])
        self.assertEqual('Unsupported language pair', rows[2]['error'])
        self.assertEqual('Unsupported language pair', rows[3]['error'])
        self.assertEqual('Source and target languages must differ', rows[4]['error'])
        self.assertEqual({'text': '', 'tokens': 0, 'seconds': 0, 'skipped': True}, rows[5])
        for row in rows[6:]:
            self.assertNotIn('error', row)
            self.assertTrue(row['text'].strip())
            self.assertGreater(row['tokens'], 0)

    @unittest.skipUnless(os.environ.get('TRANSLATEPDF_TEST_HY7_MODEL'), 'Supply the private pinned Hy-MT2 7B model')
    def test_maximum_model_honors_its_assistant_end_token(self):
        rows = self.batch(os.environ['TRANSLATEPDF_TEST_HY7_MODEL'], 'hy-mt2', [
            {'sourceLanguage': 'en', 'targetLanguage': 'de', 'text': 'Hello.'},
        ])
        self.assertNotIn('error', rows[0])
        self.assertTrue(rows[0]['text'].strip())
        self.assertGreater(rows[0]['tokens'], 0)
        self.assertNotIn('<|eos|>', rows[0]['text'])

    @unittest.skipUnless(os.environ.get('TRANSLATEPDF_TEST_GEMMA_MODEL'), 'Supply the private pinned TranslateGemma model')
    def test_gemma_language_metadata_and_glossary_contract(self):
        cases = [
            {'sourceLanguage': 'en', 'targetLanguage': 'ru', 'text': 'Hello.', 'glossary': 'Hello = Привет'},
            {'sourceLanguage': 'en', 'targetLanguage': 'zh-Hans', 'text': 'Hello.'},
            {'sourceLanguage': 'zh-Hans', 'targetLanguage': 'en', 'text': '你好。'},
        ]
        rows = self.batch(os.environ['TRANSLATEPDF_TEST_GEMMA_MODEL'], 'translategemma', cases)
        self.assertIn('without a glossary', rows[0]['error'])
        self.assertNotIn('text', rows[0])
        for row in rows[1:]:
            self.assertNotIn('error', row)
            self.assertTrue(row['text'].strip())
            self.assertGreater(row['tokens'], 0)


if __name__ == '__main__':
    unittest.main()
