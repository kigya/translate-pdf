import {
  formatBytes,
  localeFlags,
  modelPresentation,
  PRIVACY_URL,
} from '../src/domain/presentation';
import { createTranslator, localeOptions } from '../src/i18n/messages';
import { ux, uxDictionaries, uxEn } from '../src/i18n/ux';
import type { ModelSnapshot } from '../src/native/PdfTranslator';

describe('concise product presentation', () => {
  it('keeps every interface language named and accompanied by a flag, with a globe for system matching', () => {
    expect(localeOptions).toHaveLength(31);
    for (const [code, name] of localeOptions) {
      expect(name.length).toBeGreaterThan(0);
      expect(localeFlags[code]).toBeTruthy();
    }
    expect(localeFlags.system).toBe('🌐');
  });
  it('provides translated new copy and preserves formatting placeholders across all 30 locales', () => {
    for (const [code] of localeOptions.filter(([id]) => id !== 'system')) {
      expect(Object.keys(uxDictionaries[code]).sort()).toEqual(
        Object.keys(uxEn).sort(),
      );
      for (const key of Object.keys(uxEn) as Array<keyof typeof uxEn>) {
        expect(ux(code, key).length).toBeGreaterThan(0);
        expect((ux(code, key).match(/\{\w+\}/g) ?? []).sort()).toEqual(
          (uxEn[key].match(/\{\w+\}/g) ?? []).sort(),
        );
      }
    }
    expect(ux('system', 'translatePdf')).toBe('Translate PDF');
    expect(ux('ru', 'termsCount', { count: 3 })).toContain('3');
  });
  it('keeps capability scores independent of measured sample evaluation', () => {
    const model = {
      tier: 'quality',
      evaluation: { acceptableCases: 0, totalCases: 44 },
    } as ModelSnapshot;
    expect(modelPresentation(model)).toEqual({
      title: 'modelMaximum',
      score: 95,
    });
    expect(modelPresentation({ ...model, tier: 'balanced' }).score).toBe(85);
    expect(modelPresentation({ ...model, tier: undefined }).score).toBe(70);
    expect(ux('en', 'capabilityInfo')).toContain(
      'not measured translation accuracy',
    );
  });
  it('formats measured bytes without confusing storage and RAM or invalid values', () => {
    expect([undefined, null, -1, Infinity, NaN].map(formatBytes)).toEqual([
      '—',
      '—',
      '—',
      '—',
      '—',
    ]);
    expect([0, 400, 1500, 1e6, 4.62e9].map(formatBytes)).toEqual([
      '0 B',
      '400 B',
      '2 KB',
      '1 MB',
      '4.62 GB',
    ]);
    expect(PRIVACY_URL).toBe('https://kigya.github.io/translate-pdf/privacy/');
  });
});

it('keeps the renamed product brand stable in all locales and localizes the existing preparation label',()=>{
 for(const [locale] of localeOptions) expect(createTranslator(locale)('appName')).toBe('Folivela');
 expect(createTranslator('en')('prepareTitle')).toBe('Translation Options');
});
