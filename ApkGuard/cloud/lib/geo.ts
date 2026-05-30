import type { VercelRequest } from '@vercel/node';

// Geo manbai ikki xil: (1) qurilma o'z aniq GPS koordinatasini (lat/lng) bodyda
// yuboradi — joylashuv ruxsati bo'lsa (jitter QILINMAYDI); (2) aks holda Vercel IP
// sarlavhalari (shahar darajasi, taxminiy). resolveGeo() shu ikkisini birlashtiradi.
// Lokal dev'da IP sarlavhalari bo'sh bo'ladi.
//   x-vercel-ip-country    "UZ"
//   x-vercel-ip-city       "Tashkent"  (URL-encoded bo'lishi mumkin)
//   x-vercel-ip-latitude   "41.3111"
//   x-vercel-ip-longitude  "69.2797"

export type Geo = {
  country: string | null;
  city: string | null;
  lat: number | null;
  lng: number | null;
};

export function readGeo(req: VercelRequest): Geo {
  const h = req.headers;
  const cityRaw = str(h['x-vercel-ip-city']);
  let city: string | null = cityRaw;
  if (cityRaw) {
    try { city = decodeURIComponent(cityRaw); } catch { city = cityRaw; }
  }
  return {
    country: str(h['x-vercel-ip-country']),
    city,
    lat: num(h['x-vercel-ip-latitude']),
    lng: num(h['x-vercel-ip-longitude']),
  };
}

// Qurilmaning real IP-manzili (Vercel proxy orqali). x-forwarded-for birinchi hop
// mijozning IP'si bo'ladi; bo'lmasa x-real-ip. Lokal dev'da null. Egasi paneliga
// ko'rsatish uchun saqlanadi (maxfiylik siyosatida oshkor qilingan).
export function clientIp(req: VercelRequest): string | null {
  const xff = req.headers['x-forwarded-for'];
  const raw = Array.isArray(xff) ? xff[0] : xff;
  if (typeof raw === 'string' && raw.trim()) {
    const first = raw.split(',')[0].trim();
    if (first) return first;
  }
  const real = req.headers['x-real-ip'];
  const r = Array.isArray(real) ? real[0] : real;
  if (typeof r === 'string' && r.trim()) return r.trim();
  return null;
}

// Bir shahardagi bir nechta qurilma bitta pikselга to'planib qolmasligi uchun
// device_token'dan deterministik kichik siljish beramiz (~0..3 km radius).
// Deterministik bo'lgani uchun nuqta har skanda sakramaydi — joyida turadi.
export function jitterGeo(geo: Geo, seed: string): Geo {
  if (geo.lat == null || geo.lng == null) return geo;
  const hsh = hash(seed);
  const a = (hsh % 100000) / 100000;             // 0..1
  const b = ((hsh >>> 7) % 100000) / 100000;     // 0..1
  const r = 0.028 * Math.sqrt(a);                // ~3 km gacha radius
  const theta = 2 * Math.PI * b;
  // lng siljishini kenglikка qarab kichraytiramiz (xarita proporsiyasi uchun)
  const latRad = (geo.lat * Math.PI) / 180;
  const cosLat = Math.max(0.2, Math.cos(latRad));
  return {
    ...geo,
    lat: round6(geo.lat + r * Math.cos(theta)),
    lng: round6(geo.lng + (r * Math.sin(theta)) / cosLat),
  };
}

// Qurilma yuborgan aniq koordinatani o'qiydi va tekshiradi (chegara + 0,0 rad etish).
// Body — JSON (lat/lng son yoki son-satr bo'lishi mumkin). Yaroqsiz bo'lsa null.
export function readDeviceGeo(body: unknown): { lat: number; lng: number } | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  const lat = toNum(b.lat);
  const lng = toNum(b.lng);
  if (lat == null || lng == null) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  if (lat === 0 && lng === 0) return null; // "Null orol" — odatda haqiqiy emas
  return { lat: round6(lat), lng: round6(lng) };
}

// O'zbekiston shahar/tuman markazlari (141 ta; GeoNames cities5000 asosida, Uzbek-lotin
// nomlari tozalangan) — qurilma aniq GPS yuborganda joylashuv nomini KOORDINATADAN
// aniqlaymiz. IP shahar nomi ko'pincha mobil operator shlyuziga (deyarli har doim
// Toshkent) bog'lanadi; shuning uchun GPS bo'lsa IP nomiga ishonmaymiz. Eng yaqin
// shahar nomi qaytadi; panel src/lib/uzRegions.ts uni koordinata bo'yicha to'g'ri
// viloyatga bog'laydi (nomdan topa olmasa eng yaqin viloyat markaziga tushadi).
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
function nearestCity(lat: number, lng: number): string | null {
  const cosLat = Math.cos((lat * Math.PI) / 180) || 1;
  let best: string | null = null;
  let bestD = Infinity;
  for (const c of UZ_CITIES) {
    const dLat = lat - c.lat;
    const dLng = (lng - c.lng) * cosLat;
    const d = dLat * dLat + dLng * dLng;
    if (d < bestD) { bestD = d; best = c.name; }
  }
  return best;
}

// Geo manbasini hal qiladi:
//   • qurilma GPS bersa — aniq nuqta (jittersiz); joylashuv nomi GPS'dan aniqlangan
//     eng yaqin shahar/tuman (IP shahar nomi noto'g'ri — mobil IP odatda Toshkentga
//     ishora qiladi); davlat esa IP'dan.
//   • aks holda — IP geo + deterministik jitter (eski xatti-harakat).
export function resolveGeo(req: VercelRequest, body: unknown, seed: string): Geo {
  const ip = readGeo(req);
  const dev = readDeviceGeo(body);
  if (dev) {
    return {
      country: ip.country,
      city: nearestCity(dev.lat, dev.lng) ?? ip.city,
      lat: dev.lat,
      lng: dev.lng,
    };
  }
  return jitterGeo(ip, seed);
}

// IP geo qurilmaning mavjud (ehtimol GPS) joylashuvini ALMASHTIRMASLIGI uchun.
//   • Qurilma GPS yuborgan bo'lsa — authoritative: har doim yoziladi (xaritadagi
//     nuqta telefon bilan birga harakatlanadi — egasi shuni xohladi).
//   • GPS yo'q, lekin qurilmada joylashuv allaqachon bor — TEGMAYMIZ. Aks holda
//     joylashuvsiz ping (IP fallback) to'g'ri qo'yilgan nuqtani noto'g'ri operator
//     shahriga (deyarli har doim Toshkent/eng yaqin shlyuz) "sakratardi" — aynan shu
//     "qurilma boshqa shaharda ko'rinib qoldi" xatosi shundan kelib chiqardi.
//   • GPS ham, eski joylashuv ham yo'q (birinchi sezish) — IP geo + jitter (eski yo'l).
// existing — devices jadvalidagi joriy {lat,lng} (yo'q bo'lsa null/undefined).
export function resolveGeoNoDowngrade(
  req: VercelRequest,
  body: unknown,
  seed: string,
  existing: { lat: number | null; lng: number | null } | null | undefined,
): Geo {
  if (readDeviceGeo(body)) return resolveGeo(req, body, seed); // GPS — authoritative
  const hasLoc = existing != null && existing.lat != null && existing.lng != null;
  if (hasLoc) return { country: null, city: null, lat: null, lng: null }; // eski joylashuv saqlanadi
  return resolveGeo(req, body, seed); // birinchi sezish — IP darajasidagi taxmin
}

function toNum(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function str(v: string | string[] | undefined): string | null {
  if (Array.isArray(v)) v = v[0];
  if (typeof v !== 'string') return null;
  const t = v.trim();
  return t.length ? t : null;
}

function num(v: string | string[] | undefined): number | null {
  const s = str(v);
  if (s == null) return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

function round6(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}

// djb2 — kichik, deterministik string hash → musbat int
function hash(s: string): number {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return h >>> 0;
}
