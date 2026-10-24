import { normalizeLocale, type Locale } from './messages';

export const libraryUxEn = {
  searchDocuments: 'Search documents',
  noDocumentsFound: 'No documents found',
  clearSearch: 'Clear search',
  documentColor: 'Color label',
  noColor: 'No color',
  red: 'Red',
  orange: 'Orange',
  yellow: 'Yellow',
  green: 'Green',
  blue: 'Blue',
  purple: 'Purple',
  sortDocuments: 'Sort documents',
  sortRecent: 'Newest first',
  sortColor: 'By color',
  readMore: 'Read more',
  readLess: 'Show less',
  rateApp: 'Rate Folivela',
  ratingLocal: 'Saved on this device. It is not sent to a store.',
  colorHelp: 'Mark translated documents with a color, then sort them by color.',
  expand: 'Expand',
  collapse: 'Collapse',
  remaining: 'Free',
  overLimit: 'Over capacity',
} as const;

export type LibraryUxKey = keyof typeof libraryUxEn;
type LocalizedLocale = Exclude<Locale, 'system' | 'en' | 'ru'>;

export const libraryUxRu: Record<LibraryUxKey, string> = {
  searchDocuments: 'Поиск документов',
  noDocumentsFound: 'Документы не найдены',
  clearSearch: 'Очистить поиск',
  documentColor: 'Цветная метка',
  noColor: 'Без цвета',
  red: 'Красный',
  orange: 'Оранжевый',
  yellow: 'Жёлтый',
  green: 'Зелёный',
  blue: 'Синий',
  purple: 'Фиолетовый',
  sortDocuments: 'Сортировка документов',
  sortRecent: 'Сначала новые',
  sortColor: 'По цвету',
  readMore: 'Читать далее',
  readLess: 'Свернуть',
  rateApp: 'Оценить Folivela',
  ratingLocal: 'Оценка сохранена на устройстве и не отправляется в магазин.',
  colorHelp: 'Отмечайте переведённые документы цветом, а затем сортируйте их по цвету.',
  expand: 'Развернуть',
  collapse: 'Свернуть',
  remaining: 'Свободно',
  overLimit: 'Сверх вместимости',
};

/** Machine-translated UI copy; all non-English locales need native-speaker review. */
export const libraryUxQaStatus = 'machine-translated-unreviewed' as const;

// Values follow the LibraryUxKey order above. Kept local and independent of contacts or services.
const translated: Record<LocalizedLocale, readonly string[]> = {
  'zh-Hans': ['搜索文档','未找到文档','清除搜索','颜色标签','无颜色','红色','橙色','黄色','绿色','蓝色','紫色','文档排序','最新优先','按颜色','阅读更多','收起','为 Folivela 评分','评分保存在此设备上，不会发送到应用商店。','为已翻译的文档添加颜色标记，然后按颜色排序。','展开','收起','可用','超出容量'],
  es: ['Buscar documentos','No se encontraron documentos','Borrar búsqueda','Etiqueta de color','Sin color','Rojo','Naranja','Amarillo','Verde','Azul','Morado','Ordenar documentos','Más recientes primero','Por color','Leer más','Mostrar menos','Valorar Folivela','Se guarda en este dispositivo. No se envía a ninguna tienda.','Marca los documentos traducidos con un color y luego ordénalos por color.','Ampliar','Contraer','Libre','Por encima de la capacidad'],
  hi: ['दस्तावेज़ खोजें','कोई दस्तावेज़ नहीं मिला','खोज मिटाएँ','रंग लेबल','कोई रंग नहीं','लाल','नारंगी','पीला','हरा','नीला','बैंगनी','दस्तावेज़ क्रमित करें','सबसे नए पहले','रंग के अनुसार','और पढ़ें','कम दिखाएँ','Folivela को रेट करें','यह रेटिंग इसी डिवाइस पर सहेजी जाती है। इसे किसी स्टोर पर नहीं भेजा जाता।','अनुवादित दस्तावेज़ों को रंग से चिह्नित करें, फिर रंग के अनुसार क्रमित करें।','बड़ा करें','समेटें','उपलब्ध','क्षमता से अधिक'],
  ar: ['البحث في المستندات','لم يتم العثور على مستندات','مسح البحث','تصنيف لوني','بلا لون','أحمر','برتقالي','أصفر','أخضر','أزرق','بنفسجي','ترتيب المستندات','الأحدث أولاً','حسب اللون','اقرأ المزيد','عرض أقل','قيّم Folivela','يُحفظ التقييم على هذا الجهاز ولا يُرسل إلى أي متجر.','ضع علامة لونية على المستندات المترجمة ثم رتّبها حسب اللون.','توسيع','طيّ','متاح','تجاوز السعة'],
  'pt-BR': ['Pesquisar documentos','Nenhum documento encontrado','Limpar pesquisa','Etiqueta de cor','Sem cor','Vermelho','Laranja','Amarelo','Verde','Azul','Roxo','Ordenar documentos','Mais recentes primeiro','Por cor','Ler mais','Mostrar menos','Avaliar Folivela','A avaliação fica salva neste dispositivo. Ela não é enviada a uma loja.','Marque documentos traduzidos com uma cor e depois ordene por cor.','Expandir','Recolher','Disponível','Acima da capacidade'],
  bn: ['নথি খুঁজুন','কোনো নথি পাওয়া যায়নি','অনুসন্ধান মুছুন','রঙের লেবেল','কোনো রং নয়','লাল','কমলা','হলুদ','সবুজ','নীল','বেগুনি','নথি সাজান','নতুন আগে','রং অনুযায়ী','আরও পড়ুন','কম দেখান','Folivela-কে রেট দিন','রেটিংটি এই ডিভাইসে সংরক্ষিত হয়। কোনো স্টোরে পাঠানো হয় না।','অনূদিত নথিতে রঙের চিহ্ন দিন, তারপর রং অনুযায়ী সাজান।','বড় করুন','গুটিয়ে নিন','খালি','ধারণক্ষমতার বেশি'],
  ja: ['ドキュメントを検索','ドキュメントが見つかりません','検索をクリア','カラーラベル','色なし','赤','オレンジ','黄','緑','青','紫','ドキュメントを並べ替え','新しい順','色順','続きを読む','折りたたむ','Folivelaを評価','評価はこの端末に保存され、ストアには送信されません。','翻訳したドキュメントに色を付けて、色ごとに並べ替えます。','展開','折りたたむ','空き','容量超過'],
  pa: ['ਦਸਤਾਵੇਜ਼ ਖੋਜੋ','ਕੋਈ ਦਸਤਾਵੇਜ਼ ਨਹੀਂ ਮਿਲਿਆ','ਖੋਜ ਸਾਫ਼ ਕਰੋ','ਰੰਗ ਲੇਬਲ','ਕੋਈ ਰੰਗ ਨਹੀਂ','ਲਾਲ','ਸੰਤਰੀ','ਪੀਲਾ','ਹਰਾ','ਨੀਲਾ','ਜਾਮਨੀ','ਦਸਤਾਵੇਜ਼ ਕ੍ਰਮਬੱਧ ਕਰੋ','ਨਵੇਂ ਪਹਿਲਾਂ','ਰੰਗ ਅਨੁਸਾਰ','ਹੋਰ ਪੜ੍ਹੋ','ਘੱਟ ਦਿਖਾਓ','Folivela ਨੂੰ ਰੇਟ ਕਰੋ','ਰੇਟਿੰਗ ਇਸ ਡਿਵਾਈਸ ਤੇ ਸੁਰੱਖਿਅਤ ਹੁੰਦੀ ਹੈ। ਇਹ ਕਿਸੇ ਸਟੋਰ ਨੂੰ ਨਹੀਂ ਭੇਜੀ ਜਾਂਦੀ।','ਅਨੁਵਾਦ ਕੀਤੇ ਦਸਤਾਵੇਜ਼ਾਂ ਨੂੰ ਰੰਗ ਨਾਲ ਨਿਸ਼ਾਨ ਲਗਾਓ, ਫਿਰ ਰੰਗ ਅਨੁਸਾਰ ਕ੍ਰਮਬੱਧ ਕਰੋ।','ਫੈਲਾਓ','ਸਮੇਟੋ','ਉਪਲਬਧ','ਸਮਰੱਥਾ ਤੋਂ ਵੱਧ'],
  de: ['Dokumente suchen','Keine Dokumente gefunden','Suche löschen','Farbetikett','Keine Farbe','Rot','Orange','Gelb','Grün','Blau','Lila','Dokumente sortieren','Neueste zuerst','Nach Farbe','Mehr lesen','Weniger anzeigen','Folivela bewerten','Die Bewertung bleibt auf diesem Gerät und wird nicht an einen Store gesendet.','Markiere übersetzte Dokumente mit einer Farbe und sortiere sie anschließend danach.','Erweitern','Zuklappen','Frei','Über der Kapazität'],
  jv: ['Goleki dokumen','Dokumen ora ditemokake','Busak telusuran','Label warna','Tanpa warna','Abang','Oranye','Kuning','Ijo','Biru','Ungu','Urutna dokumen','Sing paling anyar dhisik','Miturut warna','Waca liyane','Tampilake luwih sithik','Wenehi rating Folivela','Rating disimpen ing piranti iki lan ora dikirim menyang toko.','Wenehi tandha warna ing dokumen sing wis diterjemahake, banjur urutna miturut warna.','Jembarake','Ciutake','Kasedhiya','Ngluwihi kapasitas'],
  ko: ['문서 검색','문서를 찾을 수 없음','검색 지우기','색상 라벨','색상 없음','빨강','주황','노랑','초록','파랑','보라','문서 정렬','최신순','색상순','더 보기','접기','Folivela 평가하기','평가는 이 기기에 저장되며 스토어로 전송되지 않습니다.','번역한 문서에 색상 표시를 추가하고 색상별로 정렬하세요.','펼치기','접기','여유','용량 초과'],
  fr: ['Rechercher des documents','Aucun document trouvé','Effacer la recherche','Étiquette de couleur','Sans couleur','Rouge','Orange','Jaune','Vert','Bleu','Violet','Trier les documents','Plus récents d’abord','Par couleur','Lire la suite','Afficher moins','Évaluer Folivela','La note est enregistrée sur cet appareil et n’est envoyée à aucun store.','Ajoutez une couleur aux documents traduits, puis triez-les par couleur.','Développer','Réduire','Disponible','Capacité dépassée'],
  tr: ['Belgeleri ara','Belge bulunamadı','Aramayı temizle','Renk etiketi','Renk yok','Kırmızı','Turuncu','Sarı','Yeşil','Mavi','Mor','Belgeleri sırala','En yeni önce','Renge göre','Devamını oku','Daha az göster','Folivela’yı puanla','Puan bu cihazda saklanır ve bir mağazaya gönderilmez.','Çevrilen belgeleri renkle işaretleyin, ardından renge göre sıralayın.','Genişlet','Daralt','Boş','Kapasite aşıldı'],
  vi: ['Tìm tài liệu','Không tìm thấy tài liệu','Xóa tìm kiếm','Nhãn màu','Không màu','Đỏ','Cam','Vàng','Xanh lá','Xanh dương','Tím','Sắp xếp tài liệu','Mới nhất trước','Theo màu','Đọc thêm','Thu gọn','Đánh giá Folivela','Đánh giá được lưu trên thiết bị này và không gửi đến cửa hàng.','Đánh dấu tài liệu đã dịch bằng màu, sau đó sắp xếp theo màu.','Mở rộng','Thu gọn','Còn trống','Vượt quá dung lượng'],
  ta: ['ஆவணங்களைத் தேடு','ஆவணங்கள் எதுவும் இல்லை','தேடலை அழி','வண்ணக் குறிச்சொல்','வண்ணம் இல்லை','சிவப்பு','ஆரஞ்சு','மஞ்சள்','பச்சை','நீலம்','ஊதா','ஆவணங்களை வரிசைப்படுத்து','புதியவை முதலில்','வண்ணப்படி','மேலும் படிக்க','குறைவாகக் காட்டு','Folivela-ஐ மதிப்பிடு','மதிப்பீடு இந்தச் சாதனத்தில் சேமிக்கப்படும்; கடைக்கு அனுப்பப்படாது.','மொழிபெயர்த்த ஆவணங்களுக்கு வண்ணக் குறி வைத்து, வண்ணப்படி வரிசைப்படுத்துங்கள்.','விரிவாக்கு','சுருக்கு','காலி','கொள்ளளவை மீறியது'],
  ur: ['دستاویزات تلاش کریں','کوئی دستاویز نہیں ملی','تلاش صاف کریں','رنگ کا لیبل','کوئی رنگ نہیں','سرخ','نارنجی','پیلا','سبز','نیلا','جامنی','دستاویزات ترتیب دیں','تازہ ترین پہلے','رنگ کے مطابق','مزید پڑھیں','کم دکھائیں','Folivela کی درجہ بندی کریں','درجہ بندی اسی ڈیوائس پر محفوظ ہوتی ہے، کسی اسٹور کو نہیں بھیجی جاتی۔','ترجمہ شدہ دستاویزات پر رنگ لگائیں، پھر انہیں رنگ کے مطابق ترتیب دیں۔','پھیلائیں','سمیٹیں','دستیاب','گنجائش سے زیادہ'],
  it: ['Cerca documenti','Nessun documento trovato','Cancella ricerca','Etichetta colore','Nessun colore','Rosso','Arancione','Giallo','Verde','Blu','Viola','Ordina documenti','Prima i più recenti','Per colore','Leggi altro','Mostra meno','Valuta Folivela','La valutazione resta su questo dispositivo e non viene inviata a uno store.','Contrassegna i documenti tradotti con un colore e poi ordina per colore.','Espandi','Comprimi','Disponibile','Oltre la capacità'],
  fa: ['جستجوی اسناد','سندی پیدا نشد','پاک کردن جستجو','برچسب رنگی','بدون رنگ','قرمز','نارنجی','زرد','سبز','آبی','بنفش','مرتب‌سازی اسناد','جدیدترین در ابتدا','بر اساس رنگ','بیشتر بخوانید','نمایش کمتر','به Folivela امتیاز دهید','امتیاز روی همین دستگاه ذخیره می‌شود و به فروشگاهی ارسال نمی‌شود.','برای اسناد ترجمه‌شده رنگ انتخاب کنید و سپس بر اساس رنگ مرتب کنید.','باز کردن','جمع کردن','خالی','بیش از ظرفیت'],
  mr: ['दस्तऐवज शोधा','कोणतेही दस्तऐवज सापडले नाहीत','शोध पुसा','रंग लेबल','रंग नाही','लाल','नारिंगी','पिवळा','हिरवा','निळा','जांभळा','दस्तऐवज क्रमवारीत लावा','नवीन आधी','रंगानुसार','अधिक वाचा','कमी दाखवा','Folivela ला रेट करा','रेटिंग या डिव्हाइसवर जतन होते आणि स्टोअरला पाठवले जात नाही.','भाषांतरित दस्तऐवजांना रंगाने चिन्हांकित करा आणि रंगानुसार क्रमवारी लावा.','विस्तृत करा','आवरा','उपलब्ध','क्षमता ओलांडली'],
  te: ['పత్రాలను వెతకండి','పత్రాలు ఏవీ కనబడలేదు','వెతుకులాటను తొలగించండి','రంగు గుర్తు','రంగు లేదు','ఎరుపు','నారింజ','పసుపు','ఆకుపచ్చ','నీలం','ఊదా','పత్రాలను క్రమబద్ధీకరించండి','కొత్తవి ముందు','రంగు ప్రకారం','మరింత చదవండి','తక్కువ చూపించండి','Folivelaకు రేటింగ్ ఇవ్వండి','రేటింగ్ ఈ పరికరంలోనే సేవ్ అవుతుంది; స్టోర్‌కు పంపబడదు.','అనువదించిన పత్రాలకు రంగు గుర్తు పెట్టి, తర్వాత రంగు ప్రకారం క్రమబద్ధీకరించండి.','విస్తరించండి','మూసివేయండి','ఖాళీ','సామర్థ్యాన్ని మించింది'],
  id: ['Cari dokumen','Dokumen tidak ditemukan','Hapus pencarian','Label warna','Tanpa warna','Merah','Oranye','Kuning','Hijau','Biru','Ungu','Urutkan dokumen','Terbaru dahulu','Berdasarkan warna','Baca selengkapnya','Tampilkan lebih sedikit','Beri rating Folivela','Rating disimpan di perangkat ini dan tidak dikirim ke toko aplikasi.','Tandai dokumen terjemahan dengan warna, lalu urutkan berdasarkan warna.','Perluas','Ciutkan','Tersedia','Melebihi kapasitas'],
  th: ['ค้นหาเอกสาร','ไม่พบเอกสาร','ล้างการค้นหา','ป้ายสี','ไม่มีสี','แดง','ส้ม','เหลือง','เขียว','น้ำเงิน','ม่วง','จัดเรียงเอกสาร','ใหม่ที่สุดก่อน','ตามสี','อ่านเพิ่มเติม','แสดงน้อยลง','ให้คะแนน Folivela','คะแนนจะบันทึกไว้ในอุปกรณ์นี้และไม่ส่งไปยังร้านค้า','ทำเครื่องหมายสีให้เอกสารที่แปลแล้ว จากนั้นจัดเรียงตามสี','ขยาย','ย่อ','ว่าง','เกินความจุ'],
  gu: ['દસ્તાવેજો શોધો','કોઈ દસ્તાવેજ મળ્યો નથી','શોધ સાફ કરો','રંગ લેબલ','રંગ નથી','લાલ','નારંગી','પીળો','લીલો','વાદળી','જાંબલી','દસ્તાવેજો ગોઠવો','નવા પહેલા','રંગ પ્રમાણે','વધુ વાંચો','ઓછું બતાવો','Folivela ને રેટ કરો','રેટિંગ આ ઉપકરણ પર સાચવાય છે અને સ્ટોરને મોકલાતું નથી.','અનુવાદિત દસ્તાવેજોને રંગથી ચિહ્નિત કરો, પછી રંગ પ્રમાણે ગોઠવો.','વિસ્તૃત કરો','સંકુચિત કરો','ખાલી','ક્ષમતા કરતાં વધુ'],
  pl: ['Szukaj dokumentów','Nie znaleziono dokumentów','Wyczyść wyszukiwanie','Etykieta koloru','Bez koloru','Czerwony','Pomarańczowy','Żółty','Zielony','Niebieski','Fioletowy','Sortuj dokumenty','Najnowsze najpierw','Według koloru','Czytaj dalej','Pokaż mniej','Oceń Folivela','Ocena jest zapisana na tym urządzeniu i nie jest wysyłana do sklepu.','Oznacz przetłumaczone dokumenty kolorem, a następnie sortuj je według koloru.','Rozwiń','Zwiń','Wolne','Przekroczono pojemność'],
  uk: ['Пошук документів','Документів не знайдено','Очистити пошук','Кольорова мітка','Без кольору','Червоний','Помаранчевий','Жовтий','Зелений','Синій','Фіолетовий','Сортування документів','Спочатку нові','За кольором','Читати далі','Згорнути','Оцінити Folivela','Оцінка зберігається на цьому пристрої та не надсилається до магазину.','Позначайте перекладені документи кольором, а потім сортуйте їх за кольором.','Розгорнути','Згорнути','Вільно','Перевищено місткість'],
  ro: ['Caută documente','Nu s-au găsit documente','Șterge căutarea','Etichetă de culoare','Fără culoare','Roșu','Portocaliu','Galben','Verde','Albastru','Mov','Sortează documentele','Cele mai noi primele','După culoare','Citește mai mult','Arată mai puțin','Evaluează Folivela','Evaluarea se salvează pe acest dispozitiv și nu este trimisă unui magazin.','Marchează documentele traduse cu o culoare, apoi sortează-le după culoare.','Extinde','Restrânge','Liber','Peste capacitate'],
  nl: ['Documenten zoeken','Geen documenten gevonden','Zoekopdracht wissen','Kleurlabel','Geen kleur','Rood','Oranje','Geel','Groen','Blauw','Paars','Documenten sorteren','Nieuwste eerst','Op kleur','Lees meer','Minder tonen','Folivela beoordelen','De beoordeling wordt op dit apparaat opgeslagen en niet naar een winkel verzonden.','Geef vertaalde documenten een kleur en sorteer ze daarna op kleur.','Uitvouwen','Invouwen','Vrij','Capaciteit overschreden'],
  he: ['חיפוש מסמכים','לא נמצאו מסמכים','ניקוי החיפוש','תווית צבע','ללא צבע','אדום','כתום','צהוב','ירוק','כחול','סגול','מיון מסמכים','החדשים ביותר תחילה','לפי צבע','קרא עוד','הצג פחות','דרגו את Folivela','הדירוג נשמר במכשיר הזה ואינו נשלח לחנות.','סמנו מסמכים מתורגמים בצבע ואז מיינו אותם לפי צבע.','הרחבה','צמצום','פנוי','מעבר לקיבולת'],
};

const keyOrder = Object.keys(libraryUxEn) as LibraryUxKey[];
const localized = Object.fromEntries(
  Object.entries(translated).map(([locale, values]) => [
    locale,
    Object.fromEntries(keyOrder.map((key, index) => [key, values[index]])),
  ]),
) as Record<LocalizedLocale, Record<LibraryUxKey, string>>;

export function libraryUx(locale: string, key: LibraryUxKey): string {
  const normalized = normalizeLocale(locale);
  if (normalized === 'ru') return libraryUxRu[key];
  if (normalized === 'en' || normalized === 'system') return libraryUxEn[key];
  // normalizeLocale returns a supported locale; all remaining catalogs are complete.
  return localized[normalized as LocalizedLocale][key];
}
