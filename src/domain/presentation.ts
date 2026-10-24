import type { ModelSnapshot } from '../native/PdfTranslator';
import type { UxKey } from '../i18n/ux';
export const PRIVACY_URL = 'https://kigya.github.io/translate-pdf/privacy/';
export const localeFlags: Record<string, string> = {
  en: '🇬🇧',
  'zh-Hans': '🇨🇳',
  es: '🇪🇸',
  hi: '🇮🇳',
  ar: '🇸🇦',
  'pt-BR': '🇧🇷',
  bn: '🇧🇩',
  ru: '🇷🇺',
  ja: '🇯🇵',
  pa: '🇮🇳',
  de: '🇩🇪',
  jv: '🇮🇩',
  ko: '🇰🇷',
  fr: '🇫🇷',
  tr: '🇹🇷',
  vi: '🇻🇳',
  ta: '🇮🇳',
  ur: '🇵🇰',
  it: '🇮🇹',
  fa: '🇮🇷',
  mr: '🇮🇳',
  te: '🇮🇳',
  id: '🇮🇩',
  th: '🇹🇭',
  gu: '🇮🇳',
  pl: '🇵🇱',
  uk: '🇺🇦',
  ro: '🇷🇴',
  nl: '🇳🇱',
  he: '🇮🇱',
  system: '🌐',
};
export function modelPresentation(model: ModelSnapshot): {
  title: UxKey;
  score: number;
} {
  if (model.id === 'mymemory') return { title: 'modelOnline', score: 70 };
  return model.tier === 'quality'
    ? { title: 'modelMaximum', score: 95 }
    : model.tier === 'balanced'
    ? { title: 'modelBalanced', score: 85 }
    : { title: 'modelCompact', score: 70 };
}
export function formatBytes(bytes?: number | null): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(2)} GB`;
  if (bytes >= 1e6) return `${Math.round(bytes / 1e6)} MB`;
  if (bytes >= 1000) return `${Math.round(bytes / 1000)} KB`;
  return `${Math.round(bytes)} B`;
}

/** UI-only online choice; never treated as a downloaded local package. */
export const ONLINE_MODEL: ModelSnapshot = { id: 'mymemory', label: 'MyMemory', revision: 'public-api-v1', bytes: 0, sha256: '', verified: false, checksumPinned: false, translationEnabled: true, status: 'ready', downloadedBytes: 0, supportsGlossary: false, licenseUrl: 'https://mymemory.translated.net/terms-and-conditions' };
