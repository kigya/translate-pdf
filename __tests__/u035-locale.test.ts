import {
  translationLanguageShortName,
  translationLanguages,
  translationLanguageName,
} from '../src/domain/translationLanguages';
import { localeOptions, createTranslator } from '../src/i18n/messages';
import { ux, uxDictionaries } from '../src/i18n/ux';

describe('compact document language labels', () => {
  it('uses stable three-letter labels while retaining full expanded names', () => {
    expect(translationLanguages.map(([code]) => translationLanguageShortName(code))).toEqual([
      'Rus', 'Zho', 'Fra', 'Eng', 'Deu', 'Spa',
    ]);
    expect(translationLanguages.map(([code]) => translationLanguageName(code))).toEqual([
      'Русский', '中文（简体）', 'Français', 'English', 'Deutsch', 'Español',
    ]);
  });
});

describe('approximate model rating copy', () => {
  it('has localized approximate-rating labels in every supported UI language', () => {
    for (const [locale] of localeOptions.filter(([id]) => id !== 'system')) {
      expect(ux(locale, 'approximateRating').trim()).not.toBe('');
      expect(ux(locale, 'capability')).toBe(ux(locale, 'approximateRating'));
      const capabilityInfo = uxDictionaries[locale]?.capabilityInfo ?? '';
      expect(capabilityInfo.trim()).not.toBe('');
      expect(capabilityInfo.toLowerCase()).not.toMatch(/subjective|not measured|не измеренная/);
    }
  });

  it('keeps review advice neutral and removes the unbenchmarked-quality claim', () => {
    for (const [locale] of localeOptions.filter(([id]) => id !== 'system')) {
      const advice = createTranslator(locale)('qualityPending');
      expect(advice.trim()).not.toBe('');
      expect(advice.toLowerCase()).not.toMatch(/not benchmarked|не проверено|не оценена/);
    }
  });
});
