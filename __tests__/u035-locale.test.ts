import {
  translationLanguageShortName,
  translationLanguages,
  translationLanguageName,
  translationLanguageClosedName,
} from '../src/domain/translationLanguages';
import { localeOptions, createTranslator } from '../src/i18n/messages';
import { ux, uxDictionaries } from '../src/i18n/ux';

describe('compact document language labels', () => {
  it('uses the requested compact codes while retaining full expanded names', () => {
    expect(translationLanguages.map(([code]) => translationLanguageShortName(code))).toEqual([
      'RU', 'ZH', 'FR', 'ENG', 'DE', 'ES',
    ]);
    expect(translationLanguages.map(([code]) => translationLanguageName(code))).toEqual([
      'Русский', '中文（简体）', 'Français', 'English', 'Deutsch', 'Español',
    ]);
  });

  it('uses full names whenever they fit and compact labels only in narrow controls', () => {
    expect(translationLanguageClosedName('en', 180)).toBe('English');
    expect(translationLanguageClosedName('ru', 180)).toBe('Русский');
    expect(translationLanguageClosedName('zh-Hans', 200)).toBe('中文（简体）');
    expect(translationLanguageClosedName('zh-Hans', 90)).toBe('ZH');
    expect(translationLanguageClosedName('en', 80)).toBe('ENG');
    expect(translationLanguageClosedName('en', 200, 3)).toBe('ENG');
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
