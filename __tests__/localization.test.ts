import {
  createTranslator,
  isRtlLocale,
  localeDirection,
  localeOptions,
  messageKeys,
  normalizeLocale,
} from '../src/i18n/messages';
import { localizedMessages, localizedTranslationQaStatus } from '../src/i18n/localizedMessages';

const productLocales = localeOptions.map(([id]) => id).filter((id) => id !== 'system');

describe('30-locale interface catalogs', () => {
  it('provides every message key exactly once in every non-English catalog', () => {
    const expectedKeys = [...messageKeys].sort();
    const expectedLocales = productLocales.filter((locale) => locale !== 'en' && locale !== 'ru').sort();
    expect(Object.keys(localizedMessages).sort()).toEqual(expectedLocales);

    for (const locale of expectedLocales) {
      const catalog = localizedMessages[locale];
      expect(Object.keys(catalog).sort()).toEqual(expectedKeys);
      for (const value of Object.values(catalog)) {
        expect(value.trim()).not.toBe('');
      }
      const localized = createTranslator(locale);
      for (const key of ['welcome', 'importPdf', 'retry', 'termsBody'] as const) {
        expect(localized(key)).not.toBe(createTranslator('en')(key));
      }
    }
  });

  it('keeps the authored English/Russian sample labels fixed and records translation QA status', () => {
    expect(localizedTranslationQaStatus).toBe('machine-translated-unreviewed');
    for (const locale of productLocales) {
      const t = createTranslator(locale);
      expect(t('scanLabel')).toBe('Cooling system');
      expect(t('scanTranslated')).toBe('Система охлаждения');
    }
  });

  it('formats optional model evaluation counts in the selected interface language', () => {
    expect(createTranslator('en')('modelEvidenceLine', { acceptable: 17, total: 44 })).toBe('Meaning preserved in 17 of 44 test passages');
    expect(createTranslator('ru')('modelEvidenceLine', { acceptable: 17, total: 44 })).toBe('Смысл сохранён в 17 из 44 проверенных фрагментов');
  });

  it('normalizes BCP-47 and legacy native locale tags', () => {
    expect(normalizeLocale(undefined)).toBe('en');
    expect(normalizeLocale(null)).toBe('en');
    expect(normalizeLocale('pt_BR')).toBe('pt-BR');
    expect(normalizeLocale('zh-Hant-TW')).toBe('zh-Hans');
    expect(normalizeLocale('ar-EG')).toBe('ar');
    expect(normalizeLocale('he-IL')).toBe('he');
    expect(normalizeLocale('iw_IL')).toBe('he');
    expect(normalizeLocale('in-ID')).toBe('id');
    expect(normalizeLocale('xx-YY')).toBe('en');
    expect(normalizeLocale('system')).toBe('system');
    expect(createTranslator('system')('settings')).toBe('Settings');
  });

  it('maps supported native right-to-left languages to RTL presentation', () => {
    expect(isRtlLocale('ar')).toBe(true);
    expect(isRtlLocale('fa')).toBe(true);
    expect(isRtlLocale('ur')).toBe(true);
    expect(isRtlLocale('he')).toBe(true);
    expect(localeDirection('ar-EG')).toBe('rtl');
    expect(localeDirection('fa_IR')).toBe('rtl');
    expect(localeDirection('ur-PK')).toBe('rtl');
    expect(localeDirection('he-IL')).toBe('rtl');
    expect(localeDirection('ru-RU')).toBe('ltr');
  });
});
