import { localeOptions, type Locale } from '../src/i18n/messages';
import { library050Ux, type Library050Key } from '../src/i18n/library050Ux';
import { processingUx, type ProcessingUxKey } from '../src/i18n/processingUx';

const libraryKeys = [
  'all', 'documents', 'imports', 'created', 'deleteJob', 'deleteImport', 'inProgress', 'completed',
  'review', 'empty', 'settings', 'localLicense', 'gemmaLicense', 'onlineLicense', 'online',
  'followSystem', 'lightMode', 'darkMode',
] as const satisfies readonly Library050Key[];
const processingKeys = [
  'pausing', 'loadingModel', 'elapsed', 'activity', 'currentBlock', 'notifications',
  'notificationsBody', 'enable', 'remove', 'removeBody', 'removing',
] as const satisfies readonly ProcessingUxKey[];
const userLocales = localeOptions.filter(([locale]) => locale !== 'system');

describe('Folivela 0.5 UI catalogs', () => {
  it('provides usable library copy for every supported UI locale and every library key', () => {
    expect(userLocales).toHaveLength(30);
    for (const [locale] of userLocales) {
      for (const key of libraryKeys) {
        const copy = library050Ux(locale, key);
        expect(typeof copy).toBe('string');
        expect(copy.trim()).not.toBe('');
      }
    }
  });

  it('provides usable processing copy for every supported UI locale and every processing key', () => {
    for (const [locale] of userLocales) {
      for (const key of processingKeys) {
        const copy = processingUx(locale, key);
        expect(typeof copy).toBe('string');
        expect(copy.trim()).not.toBe('');
      }
    }
  });

  it('uses English fallback for unsupported locales and normalizes regional aliases', () => {
    for (const key of libraryKeys) {
      expect(library050Ux('xx-Unknown', key)).toBe(library050Ux('en', key));
      expect(library050Ux('pt_BR', key)).toBe(library050Ux('pt-BR', key));
    }
    for (const key of processingKeys) {
      expect(processingUx('xx-Unknown', key)).toBe(processingUx('en', key));
      expect(processingUx('zh-CN', key)).toBe(processingUx('zh-Hans', key));
      expect(processingUx('ru-RU', key)).toBe(processingUx('ru', key));
    }
    expect(library050Ux('system', 'online')).toBe(library050Ux('en', 'online'));
    expect(processingUx('', 'notifications')).toBe(processingUx('en', 'notifications'));
    const nullLocale = null as unknown as Locale;
    expect(library050Ux(nullLocale, 'settings')).toBe(library050Ux('en', 'settings'));
  });

  it('returns a visible fallback for malformed persisted catalog keys', () => {
    const badLibraryKey = 'documments' as Library050Key;
    const badProcessingKey = 'notifcations' as ProcessingUxKey;
    for (const locale of ['en', 'ru', 'fr']) {
      expect(library050Ux(locale, badLibraryKey)).toBe('Not available');
      expect(processingUx(locale, badProcessingKey)).toBe('Not available');
    }
  });

  it('keeps license and provider names explicit where the app uses legal proper names', () => {
    for (const [locale] of userLocales) {
      expect(library050Ux(locale, 'online')).toBe('Online');
      expect(library050Ux(locale, 'onlineLicense').toLowerCase()).toContain('mymemory');
      expect(library050Ux(locale, 'localLicense')).toContain('Apache');
    }
  });
});
