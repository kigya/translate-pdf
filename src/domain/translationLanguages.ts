export const translationLanguages = [
  ['ru', 'Русский'],
  ['zh-Hans', '中文（简体）'],
  ['fr', 'Français'],
  ['en', 'English'],
  ['de', 'Deutsch'],
  ['es', 'Español'],
] as const;

export type TranslationLanguage = typeof translationLanguages[number][0];

export function isTranslationLanguage(value: unknown): value is TranslationLanguage {
  return translationLanguages.some(([code]) => code === value);
}

export function resolveTranslationPair(source: unknown, target: unknown): { sourceLanguage: TranslationLanguage; targetLanguage: TranslationLanguage } {
  if (isTranslationLanguage(source) && isTranslationLanguage(target) && source !== target) {
    return { sourceLanguage: source, targetLanguage: target };
  }
  return { sourceLanguage: 'en', targetLanguage: 'ru' };
}

export function translationLanguageName(code: TranslationLanguage): string {
  return translationLanguages.find(([id]) => id === code)![1];
}

/** Compact label for a closed language picker; expanded lists use full names. */
const shortNames: Record<TranslationLanguage, string> = {
  en: 'Eng',
  ru: 'Rus',
  fr: 'Fra',
  de: 'Deu',
  es: 'Spa',
  'zh-Hans': 'Zho',
};

export function translationLanguageShortName(code: TranslationLanguage): string {
  return shortNames[code];
}
