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
  en: 'ENG',
  ru: 'RU',
  fr: 'FR',
  de: 'DE',
  es: 'ES',
  'zh-Hans': 'ZH',
};

export function translationLanguageShortName(code: TranslationLanguage): string {
  return shortNames[code];
}

/** Selects the full label until its estimated natural width no longer fits. */
export function translationLanguageClosedName(code: TranslationLanguage, availableWidth: number, fontScale = 1): string {
  const full = translationLanguageName(code);
  const textWidth = Array.from(full).reduce((sum, char) => sum + (/\p{Script=Han}/u.test(char) ? 18 : 8), 0) * fontScale;
  return textWidth + 40 <= availableWidth ? full : translationLanguageShortName(code);
}
