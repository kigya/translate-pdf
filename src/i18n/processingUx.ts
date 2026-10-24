import { createTranslator, normalizeLocale, type Locale } from './messages';
import { ux } from './ux';

const en = {
  pausing: 'Pausing…', loadingModel: 'Preparing translation model', elapsed: 'Time in this stage',
  activity: 'Tokens processed', currentBlock: 'Block', notifications: 'Keep track of this large document',
  notificationsBody: 'Enable notifications to follow progress while Folivela is in the background.',
  enable: 'Enable', remove: 'Delete document',
  removeBody: 'Delete this translation and its saved progress from Folivela? The original file and exported copies are kept.',
  removing: 'Waiting for the translation to finish pausing.',
} as const;
export type ProcessingUxKey = keyof typeof en;
type ExtraKey = 'pausing' | 'loadingModel' | 'elapsed' | 'activity' | 'currentBlock' | 'notificationsBody' | 'removeBody' | 'removing';
type OtherLocale = Exclude<Locale, 'system' | 'en' | 'ru'>;
type ExtraCopyIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7;
const extraOrder: readonly ExtraKey[] = ['pausing','loadingModel','elapsed','activity','currentBlock','notificationsBody','removeBody','removing'];
const translated: Record<OtherLocale, readonly [string, string, string, string, string, string, string, string]> = {
  'zh-Hans': ['正在暂停…','正在准备翻译模型','此阶段用时','已处理令牌','区块','开启通知，以便在 Folivela 处于后台时查看进度。','删除此翻译及其保存的进度？原始文件和导出的副本会保留。','正在等待翻译暂停完成。'],
  es: ['Pausando…','Preparando el modelo de traducción','Tiempo en esta etapa','Tokens procesados','Bloque','Activa las notificaciones para seguir el progreso cuando Folivela esté en segundo plano.','¿Eliminar esta traducción y su progreso guardado? Se conservarán el archivo original y las copias exportadas.','Esperando a que la traducción termine de pausarse.'],
  hi: ['रोका जा रहा है…','अनुवाद मॉडल तैयार हो रहा है','इस चरण में लगा समय','प्रोसेस किए गए टोकन','ब्लॉक','Folivela के बैकग्राउंड में होने पर प्रगति देखने के लिए सूचनाएँ चालू करें।','क्या यह अनुवाद और इसकी सहेजी गई प्रगति हटाएँ? मूल फ़ाइल और निर्यात की गई प्रतियाँ सुरक्षित रहेंगी।','अनुवाद के रुकने की प्रतीक्षा है।'],
  ar: ['جارٍ الإيقاف مؤقتًا…','جارٍ تجهيز نموذج الترجمة','الوقت في هذه المرحلة','الرموز المعالجة','كتلة','فعّل الإشعارات لمتابعة التقدم أثناء عمل Folivela في الخلفية.','هل تريد حذف هذه الترجمة وتقدمها المحفوظ؟ سيبقى الملف الأصلي والنسخ المصدّرة.','بانتظار اكتمال إيقاف الترجمة مؤقتًا.'],
  'pt-BR': ['Pausando…','Preparando o modelo de tradução','Tempo nesta etapa','Tokens processados','Bloco','Ative as notificações para acompanhar o progresso quando o Folivela estiver em segundo plano.','Excluir esta tradução e o progresso salvo? O arquivo original e as cópias exportadas serão mantidos.','Aguardando a tradução terminar de pausar.'],
  bn: ['বিরতি দেওয়া হচ্ছে…','অনুবাদ মডেল প্রস্তুত হচ্ছে','এই ধাপে সময়','প্রক্রিয়াকৃত টোকেন','ব্লক','Folivela ব্যাকগ্রাউন্ডে থাকলে অগ্রগতি দেখতে বিজ্ঞপ্তি চালু করুন।','এই অনুবাদ ও সংরক্ষিত অগ্রগতি মুছবেন? মূল ফাইল ও রপ্তানি করা কপি থাকবে।','অনুবাদ থামা পর্যন্ত অপেক্ষা করা হচ্ছে।'],
  ja: ['一時停止中…','翻訳モデルを準備中','この段階の所要時間','処理したトークン','ブロック','Folivelaがバックグラウンドにある間も進捗を確認できるよう通知を有効にします。','この翻訳と保存済みの進捗を削除しますか？元のファイルと書き出したコピーは保持されます。','翻訳の一時停止完了を待っています。'],
  pa: ['ਰੋਕਿਆ ਜਾ ਰਿਹਾ ਹੈ…','ਅਨੁਵਾਦ ਮਾਡਲ ਤਿਆਰ ਕੀਤਾ ਜਾ ਰਿਹਾ ਹੈ','ਇਸ ਪੜਾਅ ਵਿੱਚ ਲੱਗਿਆ ਸਮਾਂ','ਪ੍ਰਕਿਰਿਆ ਕੀਤੇ ਟੋਕਨ','ਬਲਾਕ','Folivela ਦੇ ਬੈਕਗ੍ਰਾਊਂਡ ਵਿੱਚ ਹੋਣ ਦੌਰਾਨ ਤਰੱਕੀ ਵੇਖਣ ਲਈ ਸੂਚਨਾਵਾਂ ਚਾਲੂ ਕਰੋ।','ਕੀ ਇਹ ਅਨੁਵਾਦ ਅਤੇ ਇਸਦੀ ਸੰਭਾਲੀ ਤਰੱਕੀ ਮਿਟਾਉਣੀ ਹੈ? ਅਸਲ ਫ਼ਾਈਲ ਅਤੇ ਨਿਰਯਾਤ ਕੀਤੀਆਂ ਕਾਪੀਆਂ ਰਹਿਣਗੀਆਂ।','ਅਨੁਵਾਦ ਰੁਕਣ ਦੀ ਉਡੀਕ ਹੈ।'],
  de: ['Wird pausiert …','Übersetzungsmodell wird vorbereitet','Zeit in dieser Phase','Verarbeitete Tokens','Block','Aktiviere Mitteilungen, um den Fortschritt zu sehen, während Folivela im Hintergrund läuft.','Diese Übersetzung und ihren gespeicherten Fortschritt löschen? Originaldatei und exportierte Kopien bleiben erhalten.','Warte, bis die Übersetzung pausiert wurde.'],
  jv: ['Ngaso…','Nyiyapake model terjemahan','Wektu ing tahap iki','Token sing diproses','Blok','Uripake kabar supaya bisa ngawasi proses nalika Folivela mlaku ing latar mburi.','Busak terjemahan lan kemajuan sing disimpen iki? File asli lan salinan ekspor tetep ana.','Nunggu terjemahan rampung mandheg.'],
  ko: ['일시 중지 중…','번역 모델 준비 중','이 단계에 걸린 시간','처리한 토큰','블록','Folivela가 백그라운드에서 실행되는 동안 진행 상황을 보려면 알림을 켜세요.','이 번역과 저장된 진행 상황을 삭제할까요? 원본 파일과 내보낸 사본은 유지됩니다.','번역이 일시 중지될 때까지 기다리는 중입니다.'],
  fr: ['Mise en pause…','Préparation du modèle de traduction','Durée de cette étape','Jetons traités','Bloc','Activez les notifications pour suivre la progression lorsque Folivela est en arrière-plan.','Supprimer cette traduction et sa progression enregistrée ? Le fichier original et les copies exportées seront conservés.','En attente de la mise en pause de la traduction.'],
  tr: ['Duraklatılıyor…','Çeviri modeli hazırlanıyor','Bu aşamadaki süre','İşlenen belirteçler','Blok','Folivela arka plandayken ilerlemeyi izlemek için bildirimleri açın.','Bu çeviri ve kayıtlı ilerleme silinsin mi? Özgün dosya ve dışa aktarılan kopyalar korunur.','Çevirinin duraklatılması bekleniyor.'],
  vi: ['Đang tạm dừng…','Đang chuẩn bị mô hình dịch','Thời gian ở giai đoạn này','Token đã xử lý','Khối','Bật thông báo để theo dõi tiến độ khi Folivela chạy nền.','Xóa bản dịch và tiến độ đã lưu? Tệp gốc và các bản xuất vẫn được giữ lại.','Đang chờ bản dịch tạm dừng xong.'],
  ta: ['இடைநிறுத்தப்படுகிறது…','மொழிபெயர்ப்பு மாதிரி தயாராகிறது','இந்தப் படிநிலையில் எடுத்த நேரம்','செயலாக்கிய டோக்கன்கள்','தொகுதி','Folivela பின்னணியில் இயங்கும்போதும் முன்னேற்றத்தைக் காண அறிவிப்புகளை இயக்குங்கள்.','இந்த மொழிபெயர்ப்பையும் சேமித்த முன்னேற்றத்தையும் நீக்கவா? அசல் கோப்பும் ஏற்றுமதி நகல்களும் பாதுகாக்கப்படும்.','மொழிபெயர்ப்பு இடைநிறுத்தப்படும் வரை காத்திருக்கிறது.'],
  ur: ['روکا جا رہا ہے…','ترجمے کا ماڈل تیار ہو رہا ہے','اس مرحلے میں گزرا وقت','پروسیس کردہ ٹوکن','بلاک','Folivela کے پس منظر میں چلنے کے دوران پیش رفت دیکھنے کے لیے نوٹیفکیشن آن کریں۔','یہ ترجمہ اور محفوظ پیش رفت حذف کریں؟ اصل فائل اور برآمد شدہ نقول محفوظ رہیں گی۔','ترجمہ رکنے کا انتظار ہے۔'],
  it: ['Pausa in corso…','Preparazione del modello di traduzione','Tempo in questa fase','Token elaborati','Blocco','Attiva le notifiche per seguire i progressi quando Folivela è in background.','Eliminare questa traduzione e i progressi salvati? Il file originale e le copie esportate saranno conservati.','In attesa che la traduzione venga messa in pausa.'],
  fa: ['در حال مکث…','آماده‌سازی مدل ترجمه','زمان این مرحله','توکن‌های پردازش‌شده','بخش','برای پیگیری پیشرفت هنگام اجرای Folivela در پس‌زمینه، اعلان‌ها را فعال کنید.','این ترجمه و پیشرفت ذخیره‌شده آن حذف شود؟ فایل اصلی و نسخه‌های خروجی حفظ می‌شوند.','در انتظار توقف موقت ترجمه.'],
  mr: ['थांबवले जात आहे…','भाषांतर मॉडेल तयार होत आहे','या टप्प्यात लागलेला वेळ','प्रक्रिया केलेले टोकन','ब्लॉक','Folivela पार्श्वभूमीत असताना प्रगती पाहण्यासाठी सूचना सुरू करा.','हे भाषांतर आणि जतन केलेली प्रगती हटवायची? मूळ फाइल आणि निर्यात केलेल्या प्रती ठेवल्या जातील.','भाषांतर थांबेपर्यंत प्रतीक्षा सुरू आहे.'],
  te: ['పాజ్ అవుతోంది…','అనువాద నమూనా సిద్ధమవుతోంది','ఈ దశలో పట్టిన సమయం','ప్రాసెస్ చేసిన టోకెన్లు','బ్లాక్','Folivela నేపథ్యంలో ఉన్నప్పుడు పురోగతిని చూడటానికి నోటిఫికేషన్‌లను ప్రారంభించండి.','ఈ అనువాదం మరియు సేవ్ చేసిన పురోగతిని తొలగించాలా? అసలు ఫైల్, ఎగుమతి చేసిన కాపీలు అలాగే ఉంటాయి.','అనువాదం పాజ్ అయ్యే వరకు వేచి ఉంది.'],
  id: ['Menjeda…','Menyiapkan model terjemahan','Waktu pada tahap ini','Token diproses','Blok','Aktifkan notifikasi untuk mengikuti progres saat Folivela berjalan di latar belakang.','Hapus terjemahan dan progres tersimpan ini? File asli dan salinan ekspor akan tetap ada.','Menunggu terjemahan selesai dijeda.'],
  th: ['กำลังหยุดชั่วคราว…','กำลังเตรียมโมเดลแปล','เวลาในขั้นตอนนี้','โทเค็นที่ประมวลผล','บล็อก','เปิดการแจ้งเตือนเพื่อติดตามความคืบหน้าเมื่อ Folivela ทำงานอยู่เบื้องหลัง','ลบคำแปลและความคืบหน้าที่บันทึกไว้นี้หรือไม่ ไฟล์ต้นฉบับและสำเนาที่ส่งออกจะยังคงอยู่','กำลังรอให้การแปลหยุดชั่วคราว'],
  gu: ['થોભાવવામાં આવી રહ્યું છે…','અનુવાદ મોડેલ તૈયાર થઈ રહ્યું છે','આ તબક્કામાં લાગેલો સમય','પ્રક્રિયા કરેલા ટોકન','બ્લોક','Folivela પૃષ્ઠભૂમિમાં હોય ત્યારે પ્રગતિ જોવા સૂચનાઓ ચાલુ કરો.','આ અનુવાદ અને તેની સાચવેલી પ્રગતિ કાઢી નાખવી છે? મૂળ ફાઇલ અને નિકાસ કરેલી નકલો રહેશે.','અનુવાદ થોભે તેની રાહ જોવાઈ રહી છે.'],
  pl: ['Wstrzymywanie…','Przygotowywanie modelu tłumaczenia','Czas na tym etapie','Przetworzone tokeny','Blok','Włącz powiadomienia, aby śledzić postęp, gdy Folivela działa w tle.','Usunąć to tłumaczenie i zapisany postęp? Oryginał i wyeksportowane kopie zostaną zachowane.','Oczekiwanie na wstrzymanie tłumaczenia.'],
  uk: ['Призупинення…','Підготовка моделі перекладу','Час на цьому етапі','Оброблені токени','Блок','Увімкніть сповіщення, щоб стежити за перебігом, коли Folivela працює у фоні.','Видалити цей переклад і збережений перебіг? Оригінал та експортовані копії залишаться.','Очікування завершення призупинення перекладу.'],
  ro: ['Se întrerupe…','Se pregătește modelul de traducere','Timpul din această etapă','Tokenuri procesate','Bloc','Activează notificările pentru a urmări progresul când Folivela rulează în fundal.','Ștergi această traducere și progresul salvat? Fișierul original și copiile exportate se păstrează.','Se așteaptă întreruperea traducerii.'],
  nl: ['Pauzeren…','Vertaalmodel voorbereiden','Tijd in deze stap','Verwerkte tokens','Blok','Schakel meldingen in om de voortgang te volgen wanneer Folivela op de achtergrond draait.','Deze vertaling en de opgeslagen voortgang verwijderen? Het origineel en geëxporteerde kopieën blijven behouden.','Wachten tot de vertaling is gepauzeerd.'],
  he: ['מושהה…','מכינים את מודל התרגום','משך השלב הזה','אסימונים שעובדו','מקטע','הפעילו התראות כדי לעקוב אחר ההתקדמות בזמן ש-Folivela פועלת ברקע.','למחוק את התרגום ואת ההתקדמות השמורה? הקובץ המקורי והעותקים שיוצאו יישארו.','ממתינים לסיום השהיית התרגום.'],
};

export function processingUx(locale: string, key: ProcessingUxKey): string {
  const language = normalizeLocale(locale);
  if (language === 'en' || language === 'system') return en[key] ?? 'Not available';
  if (language === 'ru') return {
    pausing: 'Приостанавливаем…', loadingModel: 'Подготовка модели перевода', elapsed: 'Время на этом этапе',
    activity: 'Обработано токенов', currentBlock: 'Блок', notifications: 'Следите за большим документом',
    notificationsBody: 'Включите уведомления, чтобы видеть прогресс, пока Folivela работает в фоне.',
    enable: 'Включить', remove: 'Удалить документ',
    removeBody: 'Удалить этот перевод и сохранённый прогресс из Folivela? Исходный файл и экспортированные копии сохранятся.',
    removing: 'Ожидание завершения паузы перевода.',
  }[key] ?? 'Not available';
  if (extraOrder.includes(key as ExtraKey)) return translated[language as OtherLocale][extraOrder.indexOf(key as ExtraKey) as ExtraCopyIndex];
  if (key === 'notifications') return ux(language, 'notifications');
  if (key === 'enable') return ux(language, 'allowNotifications');
  if (key === 'remove') return createTranslator(language)('delete');
  return en[key] ?? 'Not available';
}
