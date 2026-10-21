import { uxLocalized } from './uxLocalized';

/** Product copy is machine translated until reviewed in each locale. */
export const uxEn = {
  modelsUnavailable: 'No model available',
  modelsUnavailableBody: 'This device cannot use the available models yet.',
  translatePdf: 'Translate PDF',
  emptyLibrary: 'Your saved documents will appear here.',
  modelCompact: 'Compact',
  modelBalanced: 'Balanced',
  modelMaximum: 'Maximum',
  capability: 'Capability rating',
  capabilityInfo:
    'A relative product rating, not measured translation accuracy.',
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
  intro1Body: 'Translate a PDF and keep its original close.',
  intro2Title: 'Read text inside scans',
  intro2Body: 'Turn scanned pages into readable translations.',
  intro3Title: 'Choose a model for your device',
  intro3Body: 'Find your balance of size and capability.',
  intro4Title: 'Leave it with us',
  intro4Body: 'Follow the progress and return to your saved document.',
};
export type UxKey = keyof typeof uxEn;
const uxRu: Record<UxKey, string> = {
  modelsUnavailable: 'Нет доступных моделей',
  modelsUnavailableBody:
    'Это устройство пока не может использовать доступные модели.',
  translatePdf: 'Перевести PDF',
  emptyLibrary: 'Здесь появятся ваши сохранённые документы.',
  modelCompact: 'Компактная',
  modelBalanced: 'Сбалансированная',
  modelMaximum: 'Максимальная',
  capability: 'Оценка возможностей',
  capabilityInfo: 'Условный рейтинг, не измеренная точность перевода.',
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
  intro1Body: 'Переводите PDF и сверяйтесь с оригиналом.',
  intro2Title: 'Читайте текст на сканах',
  intro2Body: 'Переводите отсканированные страницы.',
  intro3Title: 'Модель для вашего устройства',
  intro3Body: 'Выберите баланс размера и возможностей.',
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
