// O'zbekiston hududlari (12 viloyat + Toshkent shahri + Qoraqalpog'iston).
// IP-geolokatsiya faqat shahar darajasida taxminiy bo'lgani uchun, qurilmani
// viloyatga ikki bosqichda biriktiramiz:
//   1) shahar nomi bo'yicha (CITY_REGION jadvali — eng ishonchli signal,
//      Toshkent shahrini viloyatdan ajratadi);
//   2) nomi topilmasa — lat/lng bo'yicha eng yaqin viloyat markaziga.
// Hammasi mijoz tomonida: bazada alohida region ustuni yo'q, mavjud
// country/city/lat/lng dan kelib chiqiladi (02_geo.sql).

export interface UzRegion { key: string; name: string; lat: number; lng: number; }

export const UZ_REGIONS: UzRegion[] = [
  { key: 'toshkent_sh',     name: 'Toshkent shahri',   lat: 41.31, lng: 69.28 },
  { key: 'toshkent_v',      name: 'Toshkent viloyati', lat: 41.00, lng: 69.60 },
  { key: 'andijon',         name: 'Andijon',           lat: 40.78, lng: 72.34 },
  { key: 'fargona',         name: 'Farg‘ona',          lat: 40.39, lng: 71.79 },
  { key: 'namangan',        name: 'Namangan',          lat: 41.00, lng: 71.67 },
  { key: 'sirdaryo',        name: 'Sirdaryo',          lat: 40.49, lng: 68.79 },
  { key: 'jizzax',          name: 'Jizzax',            lat: 40.12, lng: 67.84 },
  { key: 'samarqand',       name: 'Samarqand',         lat: 39.65, lng: 66.96 },
  { key: 'qashqadaryo',     name: 'Qashqadaryo',       lat: 38.86, lng: 65.79 },
  { key: 'surxondaryo',     name: 'Surxondaryo',       lat: 37.50, lng: 67.27 },
  { key: 'navoiy',          name: 'Navoiy',            lat: 40.10, lng: 65.38 },
  { key: 'buxoro',          name: 'Buxoro',            lat: 39.77, lng: 64.43 },
  { key: 'xorazm',          name: 'Xorazm',            lat: 41.55, lng: 60.63 },
  { key: 'qoraqalpogiston', name: 'Qoraqalpog‘iston',  lat: 42.47, lng: 59.60 },
];

export const REGION_NAME: Record<string, string> =
  Object.fromEntries(UZ_REGIONS.map((r) => [r.key, r.name]));

// Shahar nomini taqqoslash uchun normallashtirish: kichik harf, apostrof/diakritikani
// olib tashlash, ma'muriy qo'shimchalarni ("region", "tumani", "city"…) kesish.
function norm(s: string): string {
  return s
    .toLowerCase()
    .trim()
    .replace(/['’ʻ`‘ʼ]/g, '')
    .replace(/[\s\-]+/g, ' ')
    .replace(/\b(region|viloyati|viloyat|shahri|shahar|tumani|tuman|district|city|town|oblast|provinsiyasi|province)\b/g, '')
    .trim();
}

// Shahar/tuman nomi → viloyat. Kalitlar norm() bilan kiritiladi (lotin, kichik harf).
const CITY_REGION: Record<string, string> = {};
function add(region: string, ...cities: string[]): void {
  for (const c of cities) CITY_REGION[norm(c)] = region;
}

add('toshkent_sh', 'tashkent', 'toshkent');
add('toshkent_v', 'chirchiq', 'chirchik', 'angren', 'olmaliq', 'almalyk', 'bekobod', 'bekabad',
  'yangiyol', 'yangiyul', 'ohangaron', 'akhangaran', 'parkent', 'gazalkent', 'nurafshon',
  'keles', 'chinoz', 'chinaz', 'piskent', 'buka', 'boka', 'yangiobod', 'gulbahor', 'iskandar');
add('andijon', 'andijan', 'andijon', 'asaka', 'shahrixon', 'shahrikhan', 'xonobod', 'khanabad',
  'marhamat', 'qorgontepa', 'paxtaobod', 'xojaobod');
add('fargona', 'fergana', 'fargona', 'margilon', 'margilan', 'kokand', 'qoqon', 'quva', 'kuva',
  'rishton', 'rishtan', 'beshariq', 'quvasoy', 'yaypan');
add('namangan', 'namangan', 'chust', 'kosonsoy', 'pop', 'uchqorgon', 'uchkurgan', 'chortoq',
  'toraqorgon', 'haqqulobod');
add('sirdaryo', 'gulistan', 'guliston', 'yangiyer', 'shirin', 'sirdaryo', 'syrdarya', 'boyovut',
  'bayaut', 'sardoba', 'baxt');
add('jizzax', 'jizzakh', 'jizzax', 'gallaorol', 'gallaaral', 'zomin', 'dustlik', 'paxtakor',
  'pakhtakor', 'marjonbuloq');
add('samarqand', 'samarkand', 'samarqand', 'urgut', 'kattakurgan', 'kattaqorgon', 'jomboy',
  'bulungur', 'ishtixon', 'oqtosh', 'payariq');
add('qashqadaryo', 'karshi', 'qarshi', 'shahrisabz', 'kitob', 'kitab', 'guzor', 'koson', 'kasbi',
  'muborak', 'yakkabog', 'chiroqchi', 'dehqonobod');
add('surxondaryo', 'termez', 'termiz', 'denov', 'denau', 'sherobod', 'sherabad', 'boysun',
  'shorchi', 'jarqorgon', 'qumqorgon', 'sariosiyo');
add('navoiy', 'navoi', 'navoiy', 'zarafshan', 'uchquduq', 'uchkuduk', 'karmana', 'kermine',
  'gazgan', 'nurota', 'konimex', 'qiziltepa', 'gazghan');
add('buxoro', 'bukhara', 'buxoro', 'kogon', 'kagan', 'gijduvon', 'gijduvan', 'vobkent', 'gazli',
  'olot', 'romitan', 'shofirkon', 'qorakol');
add('xorazm', 'urgench', 'urganch', 'xiva', 'khiva', 'pitnak', 'xazorasp', 'hazorasp', 'shovot',
  'gurlan', 'qoshkopir', 'bogot', 'yangiariq');
add('qoraqalpogiston', 'nukus', 'nokis', 'xojayli', 'khojeyli', 'beruniy', 'chimboy', 'chimbay',
  'takhiatash', 'taxiatosh', 'qongirot', 'kungrad', 'mangit', 'turtkul', 'tortkol', 'qanlikol');

const sq = (a: number): number => a * a;

// Qurilmani viloyatga biriktirish. Avval shahar nomi, keyin eng yaqin markaz.
export function regionOf(lat?: number | null, lng?: number | null, city?: string | null): string | null {
  if (city && city.trim()) {
    const hit = CITY_REGION[norm(city)];
    if (hit) return hit;
  }
  if (lat == null || lng == null) return null;
  const cosLat = Math.cos((lat * Math.PI) / 180) || 1;
  let best: string | null = null;
  let bestD = Infinity;
  for (const r of UZ_REGIONS) {
    const d = sq(lat - r.lat) + sq((lng - r.lng) * cosLat);
    if (d < bestD) { bestD = d; best = r.key; }
  }
  return best;
}

// ── Shahar/tuman darajasi (GPS bo'lsa nomni KOORDINATADAN aniqlaymiz) ───────────
// IP shahar nomi ishonchsiz: mobil operator shlyuzi deyarli har doim "Tashkent"ga
// ishora qiladi. Qurilma lat/lng yuborsa, eng yaqin haqiqiy shahar/tuman nomini
// koordinatadan olamiz (bu nom keyin regionOf orqali to'g'ri viloyatga tushadi).
// Ro'yxat cloud/lib/geo.ts dagi UZ_CITIES bilan bir xil — server (lib/) va SPA (src/)
// modul ulasha olmaydi (build chegarasi), shuning uchun nusxa. 141 ta nuqta (GeoNames).
const UZ_CITIES: { name: string; lat: number; lng: number }[] = [
  { name: 'Amir Timur', lat: 41.0194, lng: 68.9408 },
  { name: 'Andijon', lat: 40.7834, lng: 72.3507 },
  { name: 'Angren', lat: 41.0167, lng: 70.1436 },
  { name: 'Asaka', lat: 40.6415, lng: 72.2387 },
  { name: 'Bekobod', lat: 40.2208, lng: 69.2697 },
  { name: 'Bektemir', lat: 41.2097, lng: 69.3342 },
  { name: 'Beruniy', lat: 41.6911, lng: 60.7525 },
  { name: 'Beshariq', lat: 40.4358, lng: 70.6103 },
  { name: 'Beshkent', lat: 38.8214, lng: 65.6531 },
  { name: 'Boʻka', lat: 40.8111, lng: 69.1942 },
  { name: 'Boysun', lat: 38.2084, lng: 67.2066 },
  { name: 'Bulungʻur', lat: 39.76, lng: 67.2744 },
  { name: 'Buxoro', lat: 39.7703, lng: 64.4307 },
  { name: 'Charxin', lat: 39.6941, lng: 66.827 },
  { name: 'Chelak', lat: 39.92, lng: 66.8623 },
  { name: 'Chilonzor', lat: 41.2819, lng: 69.1811 },
  { name: 'Chinobod', lat: 40.8768, lng: 71.9726 },
  { name: 'Chinoz', lat: 40.9363, lng: 68.7613 },
  { name: 'Chirchiq', lat: 41.4689, lng: 69.5822 },
  { name: 'Chiroqchi', lat: 39.0336, lng: 66.5722 },
  { name: 'Chortoq', lat: 41.0692, lng: 71.8237 },
  { name: 'Chust', lat: 41.0033, lng: 71.2379 },
  { name: 'Dahbed', lat: 39.7644, lng: 66.9163 },
  { name: 'Dardoq', lat: 40.8158, lng: 72.8317 },
  { name: 'Dashtobod', lat: 40.1269, lng: 68.4944 },
  { name: 'Denov', lat: 38.2675, lng: 67.8989 },
  { name: 'Doʻstlik', lat: 40.5288, lng: 68.0315 },
  { name: 'Druzhba', lat: 41.2222, lng: 61.3067 },
  { name: 'Eskiarab', lat: 40.3697, lng: 71.4186 },
  { name: 'Fargʻona', lat: 40.3842, lng: 71.7843 },
  { name: 'Gagarin', lat: 40.6647, lng: 68.1677 },
  { name: 'Galaosiyo', lat: 39.8572, lng: 64.4464 },
  { name: 'Gʻallaorol', lat: 40.027, lng: 67.5878 },
  { name: 'Gʻazalkent', lat: 41.5581, lng: 69.7708 },
  { name: 'Gazli', lat: 40.1311, lng: 63.4571 },
  { name: 'Gʻijduvon', lat: 40.1022, lng: 64.6823 },
  { name: 'Gʻoliblar', lat: 40.4953, lng: 67.8759 },
  { name: 'Gʻozgʻon', lat: 40.5908, lng: 65.4947 },
  { name: 'Guliston', lat: 40.4954, lng: 68.7754 },
  { name: 'Gurlan', lat: 41.8412, lng: 60.3927 },
  { name: 'Gʻuzor', lat: 38.6213, lng: 66.2515 },
  { name: 'Hamza', lat: 40.4276, lng: 71.5053 },
  { name: 'Haqqulobod', lat: 40.9167, lng: 72.1167 },
  { name: 'Hazorasp', lat: 41.3194, lng: 61.0742 },
  { name: 'Hazratishoh', lat: 41.3946, lng: 71.789 },
  { name: 'Ishtixon', lat: 39.9664, lng: 66.4861 },
  { name: 'Iskandar', lat: 41.5539, lng: 69.7008 },
  { name: 'Izboskan', lat: 41.0377, lng: 72.3366 },
  { name: 'Jalolquduq', lat: 40.6892, lng: 72.6093 },
  { name: 'Jizzax', lat: 40.1335, lng: 67.8296 },
  { name: 'Jomboy', lat: 39.6989, lng: 67.0933 },
  { name: 'Juma', lat: 39.7161, lng: 66.6642 },
  { name: 'Karakul', lat: 39.5333, lng: 63.8333 },
  { name: 'Kattaqoʻrgʻon', lat: 39.9055, lng: 66.2656 },
  { name: 'Kegeyli', lat: 42.7767, lng: 59.6078 },
  { name: 'Kirguli', lat: 40.4355, lng: 71.7672 },
  { name: 'Kitob', lat: 39.1216, lng: 66.886 },
  { name: 'Kogon', lat: 39.7275, lng: 64.5547 },
  { name: 'Koson', lat: 39.0375, lng: 65.585 },
  { name: 'Kosonsoy', lat: 41.2494, lng: 71.5474 },
  { name: 'Mangʻit', lat: 42.1156, lng: 60.0597 },
  { name: 'Margʻilon', lat: 40.4724, lng: 71.7246 },
  { name: 'Marhamat', lat: 40.4805, lng: 72.3139 },
  { name: 'Moʻynoq', lat: 43.7683, lng: 59.0214 },
  { name: 'Muborak', lat: 39.2553, lng: 65.1528 },
  { name: 'Namangan', lat: 40.9983, lng: 71.6726 },
  { name: 'Navoiy', lat: 40.0844, lng: 65.3792 },
  { name: 'Nishon', lat: 38.694, lng: 65.6751 },
  { name: 'Novyy Turtkul', lat: 41.55, lng: 61.0167 },
  { name: 'Nukus', lat: 42.4586, lng: 59.6058 },
  { name: 'Nurota', lat: 40.5614, lng: 65.6886 },
  { name: 'Ohangaron', lat: 40.9064, lng: 69.6383 },
  { name: 'Olmaliq', lat: 40.8447, lng: 69.5983 },
  { name: 'Olot', lat: 39.415, lng: 63.8033 },
  { name: 'Oltiariq', lat: 40.3919, lng: 71.4742 },
  { name: 'Oltinkoʻl', lat: 43.0687, lng: 58.9037 },
  { name: 'Oqqoʻrgʻon', lat: 40.8775, lng: 69.0458 },
  { name: 'Oqtosh', lat: 39.9214, lng: 65.9253 },
  { name: 'Oʻrtaowul', lat: 41.1867, lng: 69.1453 },
  { name: 'Oyim', lat: 40.8196, lng: 72.7423 },
  { name: 'Parkent', lat: 41.2944, lng: 69.6764 },
  { name: 'Paxtakor', lat: 40.3118, lng: 67.9567 },
  { name: 'Paxtaobod', lat: 40.9294, lng: 72.4969 },
  { name: 'Payariq', lat: 39.9925, lng: 66.8501 },
  { name: 'Payshamba', lat: 40.0114, lng: 66.2311 },
  { name: 'Piskent', lat: 40.8972, lng: 69.3506 },
  { name: 'Poʻloti', lat: 39.0341, lng: 65.7292 },
  { name: 'Pop', lat: 40.8736, lng: 71.1089 },
  { name: 'Poytug', lat: 40.8977, lng: 72.2449 },
  { name: 'Qarshi', lat: 38.8606, lng: 65.789 },
  { name: 'Qibray', lat: 41.3897, lng: 69.465 },
  { name: 'Qiziltepa', lat: 40.0331, lng: 64.85 },
  { name: 'Qoʻngʻirot', lat: 43.0433, lng: 58.8394 },
  { name: 'Qoʻqon', lat: 40.5286, lng: 70.9425 },
  { name: 'Qorasuv', lat: 40.7278, lng: 72.8858 },
  { name: 'Qoʻrgʻontepa', lat: 40.7319, lng: 72.7618 },
  { name: 'Qorovulbozor', lat: 39.5006, lng: 64.7936 },
  { name: 'Qoʻshkoʻpir', lat: 41.5328, lng: 60.347 },
  { name: 'Quva', lat: 40.522, lng: 72.0729 },
  { name: 'Quvasoy', lat: 40.2972, lng: 71.9803 },
  { name: 'Rishton', lat: 40.3567, lng: 71.2847 },
  { name: 'Romitan', lat: 39.9294, lng: 64.3794 },
  { name: 'Salor', lat: 41.375, lng: 69.352 },
  { name: 'Samarqand', lat: 39.6546, lng: 66.9644 },
  { name: 'Sergeli', lat: 41.1983, lng: 69.2222 },
  { name: 'Shahrisabz', lat: 39.0578, lng: 66.8342 },
  { name: 'Shahrixon', lat: 40.7133, lng: 72.0571 },
  { name: 'Shofirkon', lat: 40.12, lng: 64.5014 },
  { name: 'Shohimardon', lat: 39.9832, lng: 71.8051 },
  { name: 'Shoʻrchi', lat: 37.9994, lng: 67.7875 },
  { name: 'Shovot', lat: 41.6582, lng: 60.2945 },
  { name: 'Shumanay', lat: 42.6428, lng: 58.9134 },
  { name: 'Sirdaryo', lat: 40.8436, lng: 68.6617 },
  { name: 'Sultonobod', lat: 40.7646, lng: 72.9765 },
  { name: 'Termiz', lat: 37.2242, lng: 67.2783 },
  { name: 'Tinchlik', lat: 40.4759, lng: 71.5509 },
  { name: 'Toʻraqoʻrgʻon', lat: 40.9998, lng: 71.5116 },
  { name: 'Toshbuloq', lat: 40.9162, lng: 71.5782 },
  { name: 'Toshkent', lat: 41.2647, lng: 69.2163 },
  { name: 'Toshloq', lat: 40.4772, lng: 71.7678 },
  { name: 'Toʻytepa', lat: 41.0321, lng: 69.3625 },
  { name: 'Uchkuduk', lat: 41.05, lng: 62.7833 },
  { name: 'Uchqoʻrgʻon', lat: 41.1137, lng: 72.0792 },
  { name: 'Urganch', lat: 41.5518, lng: 60.6314 },
  { name: 'Urgut', lat: 39.419, lng: 67.2612 },
  { name: 'Uychi', lat: 41.029, lng: 71.85 },
  { name: 'Vobkent', lat: 40.0003, lng: 64.5021 },
  { name: 'Xiva', lat: 41.3856, lng: 60.3641 },
  { name: 'Xoʻjaobod', lat: 40.6689, lng: 72.56 },
  { name: 'Xoʻjayli', lat: 42.4088, lng: 59.4454 },
  { name: 'Xonobod', lat: 40.8026, lng: 72.975 },
  { name: 'Yangi Margʻilon', lat: 40.4272, lng: 71.7189 },
  { name: 'Yangiobod', lat: 41.1192, lng: 70.0941 },
  { name: 'Yangiqoʻrgʻon', lat: 41.1947, lng: 71.7238 },
  { name: 'Yangirabot', lat: 40.0254, lng: 65.961 },
  { name: 'Yangiyer', lat: 40.275, lng: 68.8225 },
  { name: 'Yangiyoʻl', lat: 41.112, lng: 69.0471 },
  { name: 'Yaypan', lat: 40.3758, lng: 70.8156 },
  { name: 'Yunusobod', lat: 41.3714, lng: 69.2794 },
  { name: 'Zafar', lat: 40.9833, lng: 68.9 },
  { name: 'Zomin', lat: 39.9606, lng: 68.3958 },
];

// Koordinataga eng yaqin shahar/tuman nomi (lng masshtabi kenglikka moslangan).
export function nearestCity(lat?: number | null, lng?: number | null): string | null {
  if (lat == null || lng == null) return null;
  const cosLat = Math.cos((lat * Math.PI) / 180) || 1;
  let best: string | null = null;
  let bestD = Infinity;
  for (const c of UZ_CITIES) {
    const d = sq(lat - c.lat) + sq((lng - c.lng) * cosLat);
    if (d < bestD) { bestD = d; best = c.name; }
  }
  return best;
}
