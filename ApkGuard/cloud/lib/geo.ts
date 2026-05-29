import type { VercelRequest } from '@vercel/node';

// Vercel IP geolokatsiya sarlavhalari — GPS EMAS, ruxsat so'ralmaydi.
// Shahar darajasida taxminiy: maxfiylikka mos. Lokal dev'da bo'sh bo'ladi.
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
