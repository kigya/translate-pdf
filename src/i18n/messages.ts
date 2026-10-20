import { localizedMessages } from './localizedMessages';

export const localeOptions = [
  ['system', 'System default'], ['en', 'English'], ['zh-Hans', '简体中文'], ['es', 'Español'], ['hi', 'हिन्दी'],
  ['ar', 'العربية'], ['pt-BR', 'Português (Brasil)'], ['bn', 'বাংলা'], ['ru', 'Русский'], ['ja', '日本語'],
  ['pa', 'ਪੰਜਾਬੀ'], ['de', 'Deutsch'], ['jv', 'Basa Jawa'], ['ko', '한국어'], ['fr', 'Français'],
  ['tr', 'Türkçe'], ['vi', 'Tiếng Việt'], ['ta', 'தமிழ்'], ['ur', 'اردو'], ['it', 'Italiano'],
  ['fa', 'فارسی'], ['mr', 'मराठी'], ['te', 'తెలుగు'], ['id', 'Bahasa Indonesia'], ['th', 'ไทย'],
  ['gu', 'ગુજરાતી'], ['pl', 'Polski'], ['uk', 'Українська'], ['ro', 'Română'], ['nl', 'Nederlands'], ['he', 'עברית'],
] as const;

export type Locale = (typeof localeOptions)[number][0];

const en = {
  appName: 'Translate PDF', tagline: 'Technical manuals, made readable.', home: 'Library', settings: 'Settings',
  welcome: 'Your manuals stay yours.', welcomeBody: 'Translate local PDF manuals between Russian, Simplified Chinese, French, English, German, and Spanish on this device. Your files stay here.',
  importPdf: 'Import a PDF', recent: 'Recent documents', emptyTitle: 'Your library is ready', emptyBody: 'Choose a technical PDF to begin. The original stays unchanged.',
  model: 'Translation model', modelReady: 'Ready on this device', modelMissing: 'Download a model to translate offline', recommended: 'Recommended for this device', qualityPending: 'Quality is not benchmarked yet. Check technical passages against the original.', qualityVerified: 'Quality checks completed',
  modelNotDownloaded: 'Not downloaded', modelDownloading: 'Downloading', modelPaused: 'Download paused', modelVerifying: 'Checking file', modelError: 'Download error', checksum: 'SHA-256', openOriginal: 'Open original PDF',
  useModel: 'Model settings', download: 'Download model', pause: 'Pause', resume: 'Resume', delete: 'Delete', verifying: 'Verifying file…',
  prepareTitle: 'Review before translating', languagePair: 'Choose a language pair', sourceLanguage: 'Source language', targetLanguage: 'Target language', swapLanguages: 'Swap languages', terms: 'Glossary', termsBody: 'Add the terms you want the translation to keep consistent.',
  sourceTerm: 'Source term', targetTerm: 'Target term', addTerm: 'Add term', translate: 'Translate PDF', chooseModel: 'Choose a model',
  noModel: 'Download a model before starting translation.', document: 'Document', pages: 'pages', progress: 'Translation progress', stageQueued: 'Waiting to start', stageCopying: 'Preparing document',
  stageExtracting: 'Reading text and diagrams', stageTranslating: 'Translating locally', stageExporting: 'Building translated PDF', stageComplete: 'Translation complete',
  pageProgress: 'Pages complete', cancel: 'Cancel translation', openResult: 'Open translated PDF', saveCopy: 'Save a copy', share: 'Share',
  eta: 'Estimated time remaining', seconds: 'sec', minutes: 'min', hours: 'h', blocksCompleted: 'Blocks translated', blocksDiscovered: 'Blocks found so far', stageOcr: 'Recognizing scanned labels', stageLayout: 'Placing translated text',
  previewTitle: 'Review translation', original: 'Original text', translated: 'Translated text', issues: 'Needs review', noIssues: 'No flagged blocks on this page',
  page: 'Page', previous: 'Previous', next: 'Next', back: 'Back', done: 'Done', skip: 'Skip', replay: 'Replay introduction',
  appearance: 'Appearance', language: 'App language', system: 'System default', light: 'Light', dark: 'Dark', privacy: 'Privacy',
  privacyBody: 'PDFs, extracted text, glossary entries and translations stay on your device. Network access is used only for public model downloads and updates.',
  licenses: 'Licenses', licensesBody: 'The translation model and bundled libraries have separate open-source terms. Review their notices before redistribution.',
  about: 'About', localOnly: 'On-device processing', localOnlyBody: 'Your document is processed locally after a model is installed.',
  errorTitle: 'Something went wrong', retry: 'Try again', loading: 'Preparing your library…', unsupportedTitle: 'Android preview',
  unsupportedBody: 'The local PDF bridge is not connected on this platform yet. Your settings and introduction are available here.',
  noDocuments: 'No documents yet', started: 'Translation started', canceled: 'Translation canceled', modelDeleted: 'Model removed',
  glossarySaved: 'Term added to glossary', saved: 'Translated copy is ready', modelStrong: 'Strong candidate', modelSmaller: 'Smaller candidates need device and quality checks',
  freeMemory: 'Available memory', totalMemory: 'Device memory', example: 'Example', exampleRecommendation: 'Example recommendation', intro1Title: 'Read the manual in your language', intro1Body: 'Compare the English page with its Russian translation while diagrams stay in view.',
  intro2Title: 'Bring labels into the translation', intro2Body: 'Text inside a scan can be recognized and translated alongside the page.',
  intro3Title: 'Choose a model for your device', intro3Body: 'See the model size and memory recommendation before downloading. Translation then works offline.',
  intro4Title: 'Keep control of long jobs', intro4Body: 'Pause or resume work and review the saved result. Background limits depend on your device and system.',
  tapOriginal: 'Tap to show original', tapTranslation: 'Tap to show translation', scanLabel: 'Cooling system', scanTranslated: 'Система охлаждения', checksumPinned: 'SHA-256 pinned',
  downloadLabel: 'Model package', downloaded: 'Downloaded', demoPaused: 'Paused', demoWorking: 'Translating page 18 of 74', demoReady: 'Example result saved',
  androidBackground: 'Android shows a foreground notification while this job runs. If notifications are disabled, progress may be less visible in the notification drawer; the job can continue.', iosBackground: 'iOS may pause longer work. Your saved progress can continue when you return.',
  onboardFailed: 'Could not save the introduction. Try again.', saveGlossary: 'Save term', editTerm: 'Edit term', removeTerm: 'Remove term', noGlossary: 'No glossary terms yet.',
  running: 'In progress', pausedStatus: 'Paused', completed: 'Complete', partial: 'Partial result', failed: 'Failed', canceledStatus: 'Canceled',
  unknownError: 'The operation did not finish. Your local files are still available.', currentDevice: 'This device', modelRevision: 'Revision', fileSize: 'Package size', freeDisk: 'Free storage',
  modelChoiceBody: 'Choose by sample result and download size.',
  modelEvidenceLine: 'Meaning preserved in {acceptable} of {total} test passages',
  modelEvidenceCaveat: 'Evidence covers only 44 technical English→Russian passages. It does not establish quality for other pairs or documents.',
  modelPairUnrated: 'Translation quality has not been rated for this language pair.',
  modelMemoryWarning: 'This model may exceed available memory. You can try it; if memory runs out, progress is saved and translation pauses.',
  modelTierCompact: 'Compact tier', modelTierBalanced: 'Balanced tier', modelTierQuality: 'Quality tier',
  modelTierTradeoffCompact: 'Smallest download; lowest result in the English→Russian sample.',
  modelTierTradeoffBalanced: 'Middle download size; cannot apply saved glossary terms.',
  modelTierTradeoffQuality: 'Largest download and highest memory requirement; highest English→Russian sample result.',
  modelMemoryEstimate: 'Full-pipeline memory estimate (reserve excluded)',
  modelResourceMemory: 'Not enough available memory for this model and pipeline.', modelResourceDisk: 'Not enough free storage for this download.',
  modelResourceLowMemory: 'The system reports low memory. Close other work and try again.', modelResourceUnsupported: 'This model is not enabled or its resource estimate is unavailable.',
  useThisModel: 'Use this model', selectedModel: 'Selected for new jobs',
  modelVerifyOriginal: 'Test passages contained technical errors. Check warnings, numbers, parts, and steps against the original.',
  modelGlossaryUnsupported: 'This model cannot use your saved glossary. Choose a model that supports it or remove the glossary terms before translating.',
  gemmaTermsNotice: 'Downloading this model accepts its license and use restrictions.', acceptAndDownload: 'Accept and download',
} as const;

type MessageKey = keyof typeof en;
export const messageKeys = Object.keys(en) as MessageKey[];
const ru: Record<MessageKey, string> = {
  appName: 'Перевод PDF', tagline: 'Технические руководства — понятнее.', home: 'Библиотека', settings: 'Настройки',
  welcome: 'Ваши руководства остаются у вас.', welcomeBody: 'Переводите локальные PDF между русским, упрощённым китайским, французским, английским, немецким и испанским на этом устройстве. Файлы остаются здесь.',
  importPdf: 'Импортировать PDF', recent: 'Недавние документы', emptyTitle: 'Библиотека готова', emptyBody: 'Выберите технический PDF. Исходный файл останется без изменений.',
  model: 'Модель перевода', modelReady: 'Готова на устройстве', modelMissing: 'Загрузите модель для офлайн-перевода', recommended: 'Рекомендация для этого устройства', qualityPending: 'Качество пока не проверено. Сверяйте технические фрагменты с оригиналом.', qualityVerified: 'Проверки качества пройдены',
  modelNotDownloaded: 'Не загружена', modelDownloading: 'Загрузка', modelPaused: 'Загрузка приостановлена', modelVerifying: 'Проверка файла', modelError: 'Ошибка загрузки', checksum: 'SHA-256', openOriginal: 'Открыть исходный PDF',
  useModel: 'Настройки модели', download: 'Загрузить модель', pause: 'Пауза', resume: 'Продолжить', delete: 'Удалить', verifying: 'Проверка файла…',
  prepareTitle: 'Проверьте перед переводом', languagePair: 'Выберите языковую пару', sourceLanguage: 'Исходный язык', targetLanguage: 'Целевой язык', swapLanguages: 'Поменять языки местами', terms: 'Глоссарий', termsBody: 'Добавьте термины, которые нужно переводить единообразно.',
  sourceTerm: 'Исходный термин', targetTerm: 'Целевой термин', addTerm: 'Добавить термин', translate: 'Перевести PDF', chooseModel: 'Выбрать модель',
  noModel: 'Перед переводом загрузите модель.', document: 'Документ', pages: 'стр.', progress: 'Ход перевода', stageQueued: 'Ожидание запуска', stageCopying: 'Подготовка документа',
  stageExtracting: 'Чтение текста и схем', stageTranslating: 'Локальный перевод', stageExporting: 'Создание переведённого PDF', stageComplete: 'Перевод завершён',
  pageProgress: 'Страниц готово', cancel: 'Отменить перевод', openResult: 'Открыть PDF', saveCopy: 'Сохранить копию', share: 'Поделиться',
  eta: 'Примерное время до завершения', seconds: 'с', minutes: 'мин', hours: 'ч', blocksCompleted: 'Фрагментов переведено', blocksDiscovered: 'Фрагментов найдено', stageOcr: 'Распознавание подписей на сканах', stageLayout: 'Размещение переведённого текста',
  previewTitle: 'Проверка перевода', original: 'Исходный текст', translated: 'Переведённый текст', issues: 'Проверить', noIssues: 'На этой странице нет отмеченных фрагментов',
  page: 'Страница', previous: 'Назад', next: 'Далее', back: 'Назад', done: 'Готово', skip: 'Пропустить', replay: 'Повторить знакомство',
  appearance: 'Оформление', language: 'Язык приложения', system: 'Как в системе', light: 'Светлая', dark: 'Тёмная', privacy: 'Приватность',
  privacyBody: 'PDF, извлечённый текст, глоссарий и переводы остаются на устройстве. Сеть нужна только для загрузки и обновления публичной модели.',
  licenses: 'Лицензии', licensesBody: 'У модели и библиотек отдельные условия открытых лицензий. Перед распространением ознакомьтесь с уведомлениями.',
  about: 'О приложении', localOnly: 'Обработка на устройстве', localOnlyBody: 'После установки модели документ обрабатывается локально.',
  errorTitle: 'Не удалось выполнить действие', retry: 'Повторить', loading: 'Подготовка библиотеки…', unsupportedTitle: 'Предпросмотр Android',
  unsupportedBody: 'Локальный модуль PDF пока не подключён на этой платформе. Настройки и знакомство доступны.',
  noDocuments: 'Документов пока нет', started: 'Перевод запущен', canceled: 'Перевод отменён', modelDeleted: 'Модель удалена',
  glossarySaved: 'Термин добавлен в глоссарий', saved: 'Переведённая копия готова', modelStrong: 'Основной кандидат', modelSmaller: 'Меньшие модели требуют проверки на устройстве и качества',
  freeMemory: 'Доступно памяти', totalMemory: 'Память устройства', example: 'Пример', exampleRecommendation: 'Пример рекомендации', intro1Title: 'Читайте руководство на своём языке', intro1Body: 'Сверяйте английскую страницу с русским переводом, сохраняя схемы перед глазами.',
  intro2Title: 'Переводите подписи на сканах', intro2Body: 'Текст внутри скана можно распознать и перевести вместе со страницей.',
  intro3Title: 'Подберите модель устройству', intro3Body: 'Проверьте размер модели и рекомендацию по памяти. После загрузки перевод работает офлайн.',
  intro4Title: 'Управляйте долгой задачей', intro4Body: 'Приостанавливайте и продолжайте работу, затем проверяйте сохранённый результат. Фоновые ограничения зависят от устройства и системы.',
  tapOriginal: 'Нажмите, чтобы увидеть оригинал', tapTranslation: 'Нажмите, чтобы увидеть перевод', scanLabel: 'Cooling system', scanTranslated: 'Система охлаждения', checksumPinned: 'SHA-256 закреплён',
  downloadLabel: 'Пакет модели', downloaded: 'Загружено', demoPaused: 'Приостановлено', demoWorking: 'Перевод страницы 18 из 74', demoReady: 'Пример результата сохранён',
  androidBackground: 'Во время работы Android показывает постоянное уведомление. Если уведомления запрещены, ход задачи будет менее заметен в шторке, но перевод продолжится.', iosBackground: 'iOS может приостановить долгую задачу. Сохранённый прогресс можно продолжить позже.',
  saveGlossary: 'Сохранить термин', editTerm: 'Изменить термин', removeTerm: 'Удалить термин', noGlossary: 'Глоссарий пока пуст.', running: 'Выполняется', pausedStatus: 'Приостановлено',
  completed: 'Готово', partial: 'Частичный результат', failed: 'Ошибка', canceledStatus: 'Отменено', unknownError: 'Не удалось завершить действие. Локальные файлы сохранены.',
  currentDevice: 'Это устройство', modelRevision: 'Ревизия', fileSize: 'Размер пакета', freeDisk: 'Свободное место', onboardFailed: 'Не удалось сохранить знакомство. Повторите попытку.',
  modelChoiceBody: 'Выберите по результату выборки и размеру загрузки.',
  modelEvidenceLine: 'Смысл сохранён в {acceptable} из {total} проверенных фрагментов',
  modelEvidenceCaveat: 'Данные охватывают только 44 технических фрагмента с английского на русский. Они не подтверждают качество для других пар и документов.',
  modelPairUnrated: 'Качество перевода для этой языковой пары не оценивалось.',
  modelMemoryWarning: 'Этой модели может не хватить доступной памяти. Её можно попробовать запустить; если памяти не хватит, прогресс сохранится, а перевод приостановится.',
  modelTierCompact: 'Компактный уровень', modelTierBalanced: 'Сбалансированный уровень', modelTierQuality: 'Уровень качества',
  modelTierTradeoffCompact: 'Самая маленькая загрузка и самый низкий результат выборки с английского на русский.',
  modelTierTradeoffBalanced: 'Средний размер загрузки; нельзя применить сохранённые термины глоссария.',
  modelTierTradeoffQuality: 'Самая большая загрузка и наибольшая потребность в памяти; лучший результат выборки с английского на русский.',
  modelMemoryEstimate: 'Оценка памяти полного процесса (без резерва)',
  modelResourceMemory: 'Недостаточно доступной памяти для этой модели и обработки.', modelResourceDisk: 'Недостаточно свободного места для загрузки.',
  modelResourceLowMemory: 'Система сообщает о нехватке памяти. Закройте другие приложения и повторите попытку.', modelResourceUnsupported: 'Модель не включена или оценка ресурсов недоступна.',
  useThisModel: 'Использовать эту модель', selectedModel: 'Выбрана для новых задач',
  modelVerifyOriginal: 'В тестовых фрагментах были технические ошибки. Сверяйте предупреждения, числа, детали и действия с оригиналом.',
  modelGlossaryUnsupported: 'Эта модель не может использовать сохранённый глоссарий. Выберите модель с его поддержкой или удалите термины перед переводом.',
  gemmaTermsNotice: 'Загрузка этой модели означает принятие лицензии и ограничений на её использование.', acceptAndDownload: 'Принять и загрузить',
};

const localeKeys = new Set(localeOptions.map(([id]) => id));
export function normalizeLocale(value: string | null | undefined): Locale {
  if (!value) return 'en';
  const normalized = value.trim().replace(/_/g, '-');
  if (normalized === 'system') return 'system';
  if (localeKeys.has(normalized as Locale)) return normalized as Locale;
  const parts = normalized.split('-');
  const language = parts[0].toLowerCase();
  if (language === 'pt') return 'pt-BR';
  if (language === 'zh') return 'zh-Hans';
  const aliases: Record<string, string> = { iw: 'he', in: 'id' };
  const canonical = aliases[language] ?? language;
  if (canonical === 'en' || canonical === 'ru') return canonical;
  if (localeKeys.has(canonical as Locale)) return canonical as Locale;
  return 'en';
}

export function isRtlLocale(locale: Locale): boolean {
  return locale === 'ar' || locale === 'fa' || locale === 'ur' || locale === 'he';
}

export function localeDirection(value: string | null | undefined): 'rtl' | 'ltr' {
  return isRtlLocale(normalizeLocale(value)) ? 'rtl' : 'ltr';
}

export function createTranslator(locale: Locale) {
  const dict: Record<MessageKey, string> = locale === 'ru' ? ru : locale === 'en' || locale === 'system' ? en : localizedMessages[locale];
  return (key: MessageKey, values?: Record<string, string | number>): string => Object.entries(values ?? {}).reduce((message, [name, value]) => message.replaceAll(`{${name}}`, String(value)), dict[key]);
}

export type Translator = ReturnType<typeof createTranslator>;
export type { MessageKey };
