import { createTranslator, normalizeLocale, type Locale } from './messages';

const en = {
  all: 'All documents', documents: 'documents', imports: 'Ready to translate', created: 'Added',
  deleteJob: 'Delete translation', deleteImport: 'Delete document',
  inProgress: 'In progress', completed: 'Completed', review: 'Needs review', empty: 'No documents yet',
  settings: 'Settings', localLicense: 'Apache License 2.0', gemmaLicense: 'Gemma Terms of Use',
  onlineLicense: 'MyMemory Terms', online: 'Online', followSystem: 'Use system appearance',
  lightMode: 'Light', darkMode: 'Dark',
} as const;
export type Library050Key = keyof typeof en;
type NewCopyKey = 'all' | 'documents' | 'imports' | 'created' | 'deleteJob' | 'deleteImport';
type OtherLocale = Exclude<Locale, 'system' | 'en' | 'ru'>;
type CopyIndex = 0 | 1 | 2 | 3 | 4 | 5;
const copyOrder: readonly NewCopyKey[] = ['all', 'documents', 'imports', 'created', 'deleteJob', 'deleteImport'];
const translated: Record<OtherLocale, readonly [string, string, string, string, string, string]> = {
  'zh-Hans': ['全部文档','文档','待翻译','添加时间','删除翻译','删除文档'],
  es: ['Todos los documentos','documentos','Listos para traducir','Añadido','Eliminar traducción','Eliminar documento'],
  hi: ['सभी दस्तावेज़','दस्तावेज़','अनुवाद के लिए तैयार','जोड़ा गया','अनुवाद हटाएँ','दस्तावेज़ हटाएँ'],
  ar: ['كل المستندات','مستندات','جاهزة للترجمة','تاريخ الإضافة','حذف الترجمة','حذف المستند'],
  'pt-BR': ['Todos os documentos','documentos','Prontos para traduzir','Adicionado','Excluir tradução','Excluir documento'],
  bn: ['সব নথি','নথি','অনুবাদের জন্য প্রস্তুত','যোগ করা হয়েছে','অনুবাদ মুছুন','নথি মুছুন'],
  ja: ['すべてのドキュメント','件','翻訳待ち','追加日時','翻訳を削除','ドキュメントを削除'],
  pa: ['ਸਾਰੇ ਦਸਤਾਵੇਜ਼','ਦਸਤਾਵੇਜ਼','ਅਨੁਵਾਦ ਲਈ ਤਿਆਰ','ਸ਼ਾਮਲ ਕੀਤਾ','ਅਨੁਵਾਦ ਮਿਟਾਓ','ਦਸਤਾਵੇਜ਼ ਮਿਟਾਓ'],
  de: ['Alle Dokumente','Dokumente','Übersetzungsbereit','Hinzugefügt','Übersetzung löschen','Dokument löschen'],
  jv: ['Kabeh dokumen','dokumen','Siap diterjemahake','Ditambahake','Busak terjemahan','Busak dokumen'],
  ko: ['모든 문서','개','번역 준비 완료','추가됨','번역 삭제','문서 삭제'],
  fr: ['Tous les documents','documents','Prêts à traduire','Ajouté','Supprimer la traduction','Supprimer le document'],
  tr: ['Tüm belgeler','belge','Çevrilmeye hazır','Eklendi','Çeviriyi sil','Belgeyi sil'],
  vi: ['Tất cả tài liệu','tài liệu','Sẵn sàng dịch','Đã thêm','Xóa bản dịch','Xóa tài liệu'],
  ta: ['அனைத்து ஆவணங்கள்','ஆவணங்கள்','மொழிபெயர்க்கத் தயார்','சேர்க்கப்பட்டது','மொழிபெயர்ப்பை நீக்கு','ஆவணத்தை நீக்கு'],
  ur: ['تمام دستاویزات','دستاویزات','ترجمے کے لیے تیار','شامل کیا گیا','ترجمہ حذف کریں','دستاویز حذف کریں'],
  it: ['Tutti i documenti','documenti','Pronti da tradurre','Aggiunto','Elimina traduzione','Elimina documento'],
  fa: ['همه اسناد','سند','آماده ترجمه','زمان افزودن','حذف ترجمه','حذف سند'],
  mr: ['सर्व दस्तऐवज','दस्तऐवज','भाषांतरासाठी तयार','जोडले','भाषांतर हटवा','दस्तऐवज हटवा'],
  te: ['అన్ని పత్రాలు','పత్రాలు','అనువాదానికి సిద్ధం','చేర్చిన సమయం','అనువాదాన్ని తొలగించండి','పత్రాన్ని తొలగించండి'],
  id: ['Semua dokumen','dokumen','Siap diterjemahkan','Ditambahkan','Hapus terjemahan','Hapus dokumen'],
  th: ['เอกสารทั้งหมด','เอกสาร','พร้อมแปล','เพิ่มเมื่อ','ลบคำแปล','ลบเอกสาร'],
  gu: ['બધા દસ્તાવેજો','દસ્તાવેજો','અનુવાદ માટે તૈયાર','ઉમેર્યું','અનુવાદ કાઢી નાખો','દસ્તાવેજ કાઢી નાખો'],
  pl: ['Wszystkie dokumenty','dokumenty','Gotowe do tłumaczenia','Dodano','Usuń tłumaczenie','Usuń dokument'],
  uk: ['Усі документи','документи','Готові до перекладу','Додано','Видалити переклад','Видалити документ'],
  ro: ['Toate documentele','documente','Gata de tradus','Adăugat','Șterge traducerea','Șterge documentul'],
  nl: ['Alle documenten','documenten','Klaar om te vertalen','Toegevoegd','Vertaling verwijderen','Document verwijderen'],
  he: ['כל המסמכים','מסמכים','מוכנים לתרגום','נוסף','מחיקת התרגום','מחיקת המסמך'],
};

export function library050Ux(locale: Locale | string, key: Library050Key): string {
  const language = normalizeLocale(locale);
  if (language === 'en' || language === 'system') return en[key] ?? 'Not available';
  if (language === 'ru') return {
    all: 'Все документы', documents: 'документов', imports: 'Готовы к переводу', created: 'Добавлен',
    deleteJob: 'Удалить перевод', deleteImport: 'Удалить документ', inProgress: 'В процессе',
    completed: 'Завершённые', review: 'Требует проверки', empty: 'Документов пока нет', settings: 'Настройки',
    localLicense: 'Apache License 2.0', gemmaLicense: 'Условия использования Gemma', onlineLicense: 'Условия MyMemory',
    online: 'Online', followSystem: 'Как в системе', lightMode: 'Светлая', darkMode: 'Тёмная',
  }[key] ?? 'Not available';
  if (copyOrder.includes(key as NewCopyKey)) {
    const index = copyOrder.indexOf(key as NewCopyKey) as CopyIndex;
    return translated[language as OtherLocale][index];
  }
  const t = createTranslator(language);
  const mapped: Partial<Record<Library050Key, string>> = {
    inProgress: t('running'), completed: t('completed'), review: t('issues'), empty: t('noDocuments'),
    settings: t('settings'), followSystem: t('system'), lightMode: t('light'), darkMode: t('dark'),
  };
  return mapped[key] ?? en[key] ?? 'Not available';
}
