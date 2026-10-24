import { isTranslationLanguage, resolveTranslationPair, translationLanguageName, translationLanguages } from '../src/domain/translationLanguages';

describe('document translation languages', () => {
  it('resolves all 30 distinct directions, independently from interface locale', () => {
    let count = 0;
    for (const [source] of translationLanguages) {
      expect(isTranslationLanguage(source)).toBe(true);
      expect(translationLanguageName(source).length).toBeGreaterThan(0);
      for (const [target] of translationLanguages) {
        if (source === target) continue;
        expect(resolveTranslationPair(source, target)).toEqual({ sourceLanguage: source, targetLanguage: target });
        count++;
      }
    }
    expect(count).toBe(30);
  });

  it('defaults missing, invalid, and identical saved pairs to English to Russian', () => {
    for (const [source, target] of [[undefined, undefined], ['xx', 'ru'], ['en', 'en'], ['ru', 'zh-Hant']]) {
      expect(resolveTranslationPair(source, target)).toEqual({ sourceLanguage: 'en', targetLanguage: 'ru' });
    }
    expect(isTranslationLanguage(null)).toBe(false);
  });
});
