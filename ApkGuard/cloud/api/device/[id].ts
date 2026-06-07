import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { canRead } from '../../lib/auth.js';
import { verifyDeviceWrite, issueDeviceToken } from '../../lib/devauth.js';
import { readRaw } from '../../lib/rawbody.js';
import { resolveGeoNoDowngrade, readDeviceGeo, clientIp } from '../../lib/geo.js';

// register XOM tanani o'qiydi (imzo tekshiruvi uchun). GET'da tana yo'q — ta'sir qilmaydi.
export const config = { api: { bodyParser: false } };

// Ikkita yo'l bitta dinamik route'da (Hobby 12-funksiya limiti uchun):
//   /api/device/register → handleRegister (x-device-secret, POST) — qurilma o'zini yozadi
//   /api/device/<uuid>   → bitta qurilma + skanlari (x-admin-secret, GET)
export default async function handler(req: VercelRequest, res: VercelResponse) {
  const id = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;

  if (id === 'register') return handleRegister(req, res);

  // --- /api/device/<uuid> — bitta qurilma + oxirgi skanlari (panel foydalanuvchisi) ---
  // Kalit sifatida device id (uuid). device_token (yozuv kaliti) panelga ochilmaydi.
  // #25: avval checkAdminSecret (FAQAT egasi) edi — lekin panelning Devices ro'yxati
  // cheklangan admin uchun ham ochiq va qatorga bosish shu endpointni chaqiradi → admin
  // tokeni rad etilib, admin tizimdan chiqarib yuborilardi. Bu faqat O'QISH endpointi
  // (admin baribir /api/devices va /api/feed orqali shu ma'lumotni ko'radi), shuning
  // uchun canRead (egasi YOKI admin) bilan himoyalaymiz. device-secret bu yerda ishlamaydi.
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  // Kanonik UUID (avval bo'sh `[0-9a-fA-F-]{36}` har qanday 36-belgi-aralashmasini qabul qilardi).
  if (!id || !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
    return res.status(400).json({ ok: false, error: 'bad id' });
  }

  const sb = db();

  const { data: device, error: dErr } = await sb
    .from('v_devices_with_counts')
    .select('*')
    .eq('id', id)
    .single();
  if (dErr || !device) {
    return res.status(404).json({ ok: false, error: 'not found' });
  }

  // Oxirgi skanlar — popup'dagi "Topilgan tahdidlar" va tarix uchun.
  const { data: scans, error: sErr } = await sb
    .from('scans')
    .select('id, apk_hash, package_name, app_label, verdict, risk_score, reasons, scanned_at')
    .eq('device_id', id)
    .order('scanned_at', { ascending: false })
    .limit(20);
  if (sErr) return res.status(500).json({ ok: false, error: sErr.message });

  return res.status(200).json({ ok: true, device, scans: scans ?? [] });
}

// --- /api/device/register — qurilma o'zini ro'yxatdan o'tkazadi (x-device-secret) ---
type RegisterBody = {
  device_token: string;
  name?: string;
  android_ver?: string;
  app_ver?: string;
  lat?: number | string;
  lng?: number | string;
  loc_accuracy_m?: number | string;
};

async function handleRegister(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  // Per-device imzo (yangi) YOKI eski umumiy x-device-secret (o'tish davri). Dual-accept.
  const rawBody = await readRaw(req);
  if (!(await verifyDeviceWrite(req, rawBody, 'device/register'))) {
    return res.status(401).json({ ok: false, error: 'auth' });
  }
  let b: RegisterBody;
  try { b = JSON.parse(rawBody || '{}') as RegisterBody; } catch { return res.status(400).json({ ok: false, error: 'bad json' }); }
  if (!b?.device_token || b.device_token.length < 16) {
    return res.status(400).json({ ok: false, error: 'bad token' });
  }
  // Imzolangan yo'lda sarlavha x-device-token tana device_token bilan mos kelishi shart.
  // Eski (x-device-secret) yo'lda sarlavha yo'q → tekshirilmaydi.
  const hdrTok = req.headers['x-device-token'];
  if (typeof hdrTok === 'string' && hdrTok.length > 0 && hdrTok !== b.device_token) {
    return res.status(401).json({ ok: false, error: 'token/body mismatch' });
  }

  const sb = db();

  const row: Record<string, unknown> = {
    device_token: b.device_token,
    name: b.name ?? null,
    android_ver: b.android_ver ?? null,
    app_ver: b.app_ver ?? null,
    last_seen: new Date().toISOString(),
  };

  // Geo — GPS authoritative (har doim yoziladi); GPS yo'q bo'lsa IP taxmini qurilmada
  // joylashuv allaqachon bor bo'lsa YOZILMAYDI (to'g'ri nuqtani IP shahriga sakratmaymiz).
  // Shuning uchun GPS yo'q bo'lsagina mavjud joylashuvni o'qiymiz. Null qiymatlarni
  // ustiga yozmaymiz (lokal dev'da IP sarlavhalari bo'lmaydi).
  let existingGeo: { lat: number | null; lng: number | null } | null = null;
  let skipGeo = false;
  if (!readDeviceGeo(b)) {
    const { data: cur, error: gErr } = await sb
      .from('devices')
      .select('lat, lng')
      .eq('device_token', b.device_token)
      .maybeSingle();
    if (gErr) {
      // #13: mavjud joylashuvni o'qib bo'lmadi — IP taxmini bilan to'g'ri GPS nuqtani
      // EZIB yozmaslik uchun bu register'da geo'ni umuman yangilamaymiz.
      skipGeo = true;
      console.error(`[register] existing-geo read failed: ${gErr.message}`);
    } else {
      existingGeo = cur ?? null;
    }
  }
  if (!skipGeo) {
    const geo = resolveGeoNoDowngrade(req, b, b.device_token, existingGeo);
    if (geo.country != null) row.country = geo.country;
    if (geo.city != null) row.city = geo.city;
    if (geo.lat != null) row.lat = geo.lat;
    if (geo.lng != null) row.lng = geo.lng;
  }

  // IP — egasi paneli uchun (null bo'lsa eski qiymatni o'chirmaymiz).
  const ip = clientIp(req);
  if (ip) row.ip = ip;

  const { data, error } = await sb
    .from('devices')
    .upsert(row, { onConflict: 'device_token' })
    .select('id')
    .single();

  if (error) return res.status(500).json({ ok: false, error: error.message });

  // Per-device token beramiz — qurilma keyingi yozuvlarni shu bilan IMZOLAYDI (HMAC).
  // Determinik (HMAC(device_token, DEVICE_TOKEN_SECRET)), serverda saqlanmaydi. Kalit
  // o'rnatilmagan bo'lsa null — mijoz eski x-device-secret yo'lida qoladi (buzilmaydi).
  const deviceAuthToken = issueDeviceToken(b.device_token);
  return res.status(200).json({ ok: true, device_id: data?.id, device_auth_token: deviceAuthToken });
}
