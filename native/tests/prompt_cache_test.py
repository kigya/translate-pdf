"""Real-model semantic check for Hy-MT2 prompt-prefix reuse.

Requires the private pinned model via TRANSLATEPDF_TEST_MODEL. Compares two
same-process cached translations with an independent fresh-context translation;
model weights and inputs stay outside the repository.
"""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SMOKE = os.environ.get('TRANSLATEPDF_NATIVE_SMOKE', str(ROOT / '.local-private/build-mac/native-smoke'))
MODEL = os.environ.get('TRANSLATEPDF_TEST_MODEL')
ENV = dict(os.environ, DYLD_LIBRARY_PATH=str(ROOT / '.local-private/deps/pdfium/mac-arm64/lib'))
CASE = {
    'sourceLanguage': 'en',
    'targetLanguage': 'ru',
    'text': 'Keep the radiator cap closed while the engine is hot.',
}


def translate_batch(cases):
    result = subprocess.run(
        [SMOKE, 'translate-batch', MODEL, 'hy-mt2', '-'],
        input=json.dumps(cases, ensure_ascii=False), text=True,
        capture_output=True, env=ENV, timeout=300,
    )
    if result.returncode:
        raise AssertionError(f'native-smoke failed ({result.returncode}): {result.stderr[-2000:]} {result.stdout[-1000:]}')
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if len(rows) != len(cases):
        raise AssertionError(f'expected {len(cases)} results, received {len(rows)}: {result.stdout[-2000:]}')
    return [row['result'] for row in rows]


@unittest.skipUnless(MODEL and Path(MODEL).is_file(), 'Supply the private pinned Hy-MT2 model')
class PromptCacheModelTest(unittest.TestCase):
    def test_reused_prompt_matches_a_fresh_context_and_redecodes_last_token(self):
        cached_first, cached_second = translate_batch([CASE, CASE])
        fresh, = translate_batch([CASE])
        for result in (cached_first, cached_second, fresh):
            self.assertNotIn('error', result, result)
            self.assertTrue(result['text'].strip())
        self.assertEqual(fresh['text'], cached_first['text'])
        self.assertEqual(fresh['text'], cached_second['text'])
        self.assertEqual(0, cached_first['cachedInputTokens'])
        self.assertGreater(cached_second['cachedInputTokens'], 0)
        self.assertEqual(cached_second['inputTokens'] - 1, cached_second['cachedInputTokens'])


if __name__ == '__main__':
    unittest.main()
