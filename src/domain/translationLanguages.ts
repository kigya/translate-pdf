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
