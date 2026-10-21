import { libraryUx, libraryUxEn, libraryUxQaStatus, libraryUxRu, type LibraryUxKey } from '../src/i18n/libraryUx';
import { localeOptions } from '../src/i18n/messages';

const keys = Object.keys(libraryUxEn) as LibraryUxKey[];
const locales = localeOptions.map(([locale]) => locale).filter((locale) => locale !== 'system');

describe('library UX catalog', () => {
  it('contains readable, non-empty copy for all keys in all 30 product locales', () => {
    expect(locales).toHaveLength(30);
    expect(Object.keys(libraryUxRu).sort()).toEqual([...keys].sort());
    expect(libraryUxQaStatus).toBe('machine-translated-unreviewed');

    for (const locale of locales) {
      for (const key of keys) {
        const value = libraryUx(locale, key);
        expect(value.trim()).not.toBe('');
      }
      if (locale !== 'en') expect(libraryUx(locale, 'searchDocuments')).not.toBe(libraryUxEn.searchDocuments);
    }
  });

  it('falls back to English for unknown and system locales', () => {
    for (const key of keys) {
      expect(libraryUx('xx-YY', key)).toBe(libraryUxEn[key]);
      expect(libraryUx('system', key)).toBe(libraryUxEn[key]);
    }
  });

  it('keeps app rating local and includes no fake contact or aggregate strings', () => {
    const allText = locales.flatMap((locale) => keys.map((key) => libraryUx(locale, key)));
    expect(libraryUx('en', 'ratingLocal')).toContain('not sent');
    expect(libraryUx('ru', 'ratingLocal')).toContain('не отправляется');
    for (const value of allText) {
      expect(value).not.toMatch(/https?:\/\/|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|\b\d{7,}\b/i);
      expect(value).not.toMatch(/^(all|все|todos|全部|すべて)$/i);
    }
  });
});
