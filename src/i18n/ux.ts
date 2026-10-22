import { uxLocalized } from './uxLocalized';

/** Product copy is machine translated until reviewed in each locale. */
export const uxEn = {
  modelOnline: "MyMemory · Online",
  onlineAvailable: "Without a model download",
  onlineQuota: "Free public API · up to 5,000 characters/day",
  onlineConsentTitle: "Translate online with MyMemory?",
  onlineConsentBody: "Extracted text from this document will be sent to MyMemory and may be stored long term or processed by other translation providers. Avoid confidential documents. The anonymous limit is 5,000 characters/day; a shared network may affect availability. Your document stays on this device.",
  onlineContinue: "Agree and translate",
  onlineProcessing: "Text is sent to MyMemory with your consent",
  followSystem: "Follow system appearance",
  viewTutorial: "View tutorial",
  segments: "segments",
  exportDocx: "Save Word (.docx)",
  exportPdf: "Save PDF",
  wordPreviewNotice: "Word layout and page breaks may differ from Microsoft Word. Images are kept in their original form.",

  modelsUnavailable: 'No model available',
  modelsUnavailableBody: 'This device cannot use the available models yet.',
  translatePdf: 'Translate documents',
  emptyLibrary: 'Your saved documents will appear here.',
  modelCompact: 'Compact',
  modelBalanced: 'Balanced',
  modelMaximum: 'Maximum',
  capability: 'Approximate rating',
  approximateRating: 'Approximate rating',
  capabilityInfo:
    'An approximate product rating; results vary by language and text.',
  details: 'Details',
  modelDetails: 'Model details',
  storage: 'Storage',
  modelsStored: 'Models on this device',
  partialDownloads: 'Unfinished downloads',
  freeSpace: 'Free storage',
  availableRam: 'Available memory',
  storageInfo:
    'Models contain the data needed to translate on your device. Once downloaded, your documents can be translated offline without being uploaded. Storage holds the model files; memory is used while translating.',
  clearModels: 'Remove all models',
  removeModel: 'Remove model',
  deleteModelInfo:
    'The model can be downloaded again. Your documents and saved results will stay on this device. Pause active work before removing its model.',
  confirm: 'Remove',
  cancel: 'Cancel',
  search: 'Search',
  systemDefault: 'System default',
  glossary: 'Dictionary',
  dictionaryEmpty: 'Save a term to use your preferred translation.',
  glossaryOff: 'This model translates without a dictionary.',
  translationOptions: 'Translation Options',
  previewSource: 'Preview original',
  sourcePdf: 'Original PDF',
  useDictionary: 'Use dictionary',
  termsCount: '{count} terms',
  dictionaryPair: 'Language pair',
  notificationTitle: 'Keep track of your download',
  notificationTranslationTitle: 'Keep track of your translation',
  notificationBody:
    'You can leave this screen. Allow notifications to see progress and find out when the work is ready.',
  allowNotifications: 'Allow notifications',
  notNow: 'Not now',
  notifications: 'Notifications',
  notificationUnavailable:
    'Notifications are off. You can enable them in system settings.',
  openSettings: 'Open settings',
  privacyOpenError:
    'Could not open the privacy policy. Check your connection and try again.',
  licensesEmpty: 'No bundled notices are available.',
  pageOf: '{page} / {total}',
  zoomIn: 'Zoom in',
  zoomOut: 'Zoom out',
  original: 'Original',
  target: 'Translation',
  localProcessing: 'Processed on your device',
  intro1Title: 'Documents, in your language',
  intro1Body: 'Translate PDF and Word, with the original close at hand.',
  intro2Title: 'Online or on your device',
  intro2Body: 'Start without a download or choose a local model.',
  intro3Title: 'Compare and save',
  intro3Body: 'Compare with the original and save as PDF or Word.',
  intro4Title: 'Leave it with us',
  intro4Body: 'Follow the progress and return to your saved document.',
};
export type UxKey = keyof typeof uxEn;
const uxRu: Record<UxKey, string> = {
  modelOnline: "MyMemory · Онлайн",
  onlineAvailable: "Без скачивания модели",
  onlineQuota: "Бесплатный API · до 5 000 символов в день",
  onlineConsentTitle: "Перевести через MyMemory?",
  onlineConsentBody: "Извлечённый текст этого документа будет отправлен MyMemory. Сервис может хранить его длительное время и передавать другим провайдерам перевода. Не отправляйте конфиденциальные документы. Лимит без ключа — 5 000 символов в день; общая сеть может влиять на доступность. Сам файл остаётся на устройстве.",
  onlineContinue: "Согласиться и перевести",
  onlineProcessing: "Текст отправляется MyMemory с вашего согласия",
  followSystem: "Следовать теме системы",
  viewTutorial: "Посмотреть обучение",
  segments: "сегментов",
  exportDocx: "Сохранить Word (.docx)",
  exportPdf: "Сохранить PDF",
  wordPreviewNotice: "Переносы и страницы Word могут отличаться от Microsoft Word. Изображения сохраняются в исходном виде.",

  modelsUnavailable: 'Нет доступных моделей',
  modelsUnavailableBody:
    'Это устройство пока не может использовать доступные модели.',
  translatePdf: 'Перевести документ',
  emptyLibrary: 'Здесь появятся ваши сохранённые документы.',
  modelCompact: 'Компактная',
  modelBalanced: 'Сбалансированная',
  modelMaximum: 'Максимальная',
  capability: 'Приблизительный рейтинг',
  approximateRating: 'Приблизительный рейтинг',
  capabilityInfo: 'Приблизительная оценка возможностей; результат зависит от языка и текста.',
  details: 'Подробнее',
  modelDetails: 'О модели',
  storage: 'Хранилище',
  modelsStored: 'Модели на устройстве',
  partialDownloads: 'Незавершённые загрузки',
  freeSpace: 'Свободное место',
  availableRam: 'Доступная память',
  storageInfo:
    'Модели содержат данные для перевода на вашем устройстве. После загрузки документы можно переводить без интернета и отправки файлов на сервер. Файлы модели занимают место в хранилище, а во время перевода используется оперативная память.',
  clearModels: 'Удалить все модели',
  removeModel: 'Удалить модель',
  deleteModelInfo:
    'Модель можно скачать снова. Документы и сохранённые результаты останутся на устройстве. Перед удалением остановите активную работу с этой моделью.',
  confirm: 'Удалить',
  cancel: 'Отмена',
  search: 'Поиск',
  systemDefault: 'Язык системы',
  glossary: 'Словарь',
  dictionaryEmpty: 'Сохраните термин, чтобы использовать свой перевод.',
  glossaryOff: 'Эта модель переводит без словаря.',
  translationOptions: 'Параметры перевода',
  previewSource: 'Посмотреть оригинал',
  sourcePdf: 'Исходный PDF',
  useDictionary: 'Применять словарь',
  termsCount: 'Терминов: {count}',
  dictionaryPair: 'Языковая пара',
  notificationTitle: 'Следите за загрузкой',
  notificationTranslationTitle: 'Следите за переводом',
  notificationBody:
    'Вы можете покинуть этот экран. Разрешите уведомления, чтобы видеть прогресс и узнать о завершении.',
  allowNotifications: 'Разрешить уведомления',
  notNow: 'Не сейчас',
  notifications: 'Уведомления',
  notificationUnavailable:
    'Уведомления отключены. Их можно включить в системных настройках.',
  openSettings: 'Открыть настройки',
  privacyOpenError:
    'Не удалось открыть политику конфиденциальности. Проверьте подключение и повторите попытку.',
  licensesEmpty: 'Лицензии пока недоступны.',
  pageOf: '{page} / {total}',
  zoomIn: 'Приблизить',
  zoomOut: 'Отдалить',
  original: 'Оригинал',
  target: 'Перевод',
  localProcessing: 'Обработка на устройстве',
  intro1Title: 'Документы на вашем языке',
  intro1Body: 'Открывайте PDF и Word на телефоне.',
  intro2Title: 'Онлайн или на устройстве',
  intro2Body: 'Начните без скачивания или выберите локальную модель.',
  intro3Title: 'Сравните и сохраните',
  intro3Body: 'Сверяйтесь с оригиналом и сохраняйте PDF или Word.',
  intro4Title: 'Займитесь своими делами',
  intro4Body: 'Следите за прогрессом и возвращайтесь к готовому документу.',
};
export const uxDictionaries: Record<string, Partial<Record<UxKey, string>>> = {
  ...uxLocalized,
  en: uxEn,
  ru: uxRu,
};
export function ux(
  locale: string,
  key: UxKey,
  values?: Record<string, string | number>,
): string {
  let text = uxDictionaries[locale]?.[key] ?? uxEn[key];
  for (const [name, value] of Object.entries(values ?? {}))
    text = text.replaceAll(`{${name}}`, String(value));
  return text;
}
export function productCopy(locale: string, key: string): string | undefined {
  if (key === 'appName') return 'Folivela';
  const mapped =
    key === 'importPdf'
      ? 'translatePdf'
      : key === 'prepareTitle'
      ? 'translationOptions'
      : key;
  if (
    mapped in uxEn &&
    (mapped.startsWith('intro') ||
      key === 'importPdf' ||
      key === 'prepareTitle')
  )
    return ux(locale, mapped as UxKey);
  return undefined;
}
