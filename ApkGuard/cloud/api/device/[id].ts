import type { VercelRequest, VercelResponse } from '@vercel/node';
import { randomBytes } from 'node:crypto';
import { db } from '../../lib/supabase.js';
import { regDeepLink } from '../../lib/tgreg.js';
import { canRead, checkAdminSecret, checkDeviceSecret } from '../../lib/auth.js';
import { verifyDeviceWrite, issueDeviceToken } from '../../lib/devauth.js';
import { readRaw } from '../../lib/rawbody.js';
import { resolveGeoNoDowngrade, readDeviceGeo, clientIp } from '../../lib/geo.js';
import { audit } from '../../lib/audit.js';
import { sendMessage, adminChatIds } from '../../lib/telegram.js';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
// Panelddan qurilmaga yuboriladigan buyruq turlari:
//   rescan  — masofadan qayta skan (avvaldan bor).
//   message — egasidan 1:1 xabar (payload {title, body}) → qurilmada bildirishnoma.
// 'flag'/'unflag' buyruq EMAS — u devices.flag ustuniga BARQAROR holat yozadi (pastga qarang).
const ALLOWED_CMD_TYPES = ['rescan', 'message'];
// Qurilmani belgilash holatlari — "yo'qolgan" / "buzilgan" (poll BARQAROR qaytaradi).
const FLAG_STATES = ['lost', 'compromised'];
// Himoya-holati (protections jsonb) ichida KRITIK himoyalar — bulardan biri ON→OFF
// bo'lsa egaga Telegram ogohlantirishi (throttle bilan). a11y/vpn ataylab o'chirilishi
// mumkin (normal), shuning uchun kritik to'plamga kirmaydi.
const CRITICAL_PROTECTIONS = ['svc', 'notif'] as const;

// register XOM tanani o'qiydi (imzo tekshiruvi uchun). GET'da tana yo'q — ta'sir qilmaydi.
export const config = { api: { bodyParser: false } };

// Ikkita yo'l bitta dinamik route'da (Hobby 12-funksiya limiti uchun):
//   /api/device/register → handleRegister (x-device-secret, POST) — qurilma o'zini yozadi
//   /api/device/<uuid>   → bitta qurilma + skanlari (x-admin-secret, GET)
export default async function handler(req: VercelRequest, res: VercelResponse) {
  const id = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;

  if (id === 'register') return handleRegister(req, res);
  if (id === 'join') return handleJoin(req, res);          // qurilma guruhga qo'shiladi (device auth)
  if (id === 'poll') return handlePoll(req, res);          // #3: qurilma o'z buyruqlarini oladi (device auth)
  if (id === 'tgstart') return handleTgStart(req, res);    // Telegram ro'yxati: token + chuqur havola
  if (id === 'tgstatus') return handleTgStatus(req, res);  // Telegram ro'yxati: holat pollingi

  // #3: paneldan buyruq qo'yish — POST /api/device/<uuid> {type,payload} (FAQAT EGASI).
  if (req.method === 'POST') return handleEnqueue(req, res, id);

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
  if (!id || !UUID_RE.test(id)) {
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
  if (sErr) { console.error(`[device] scans db error: ${sErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

  // #3: shu qurilmaga yuborilgan oxirgi buyruqlar (panel holatini ko'rsatish uchun).
  const { data: cmds } = await sb
    .from('device_commands')
    .select('id, type, status, created_at, delivered_at')
    .eq('device_id', id)
    .order('created_at', { ascending: false })
    .limit(5);

  return res.status(200).json({ ok: true, device, scans: scans ?? [], commands: cmds ?? [] });
}

// --- #3: /api/device/poll — qurilma o'z pending buyruqlarini oladi (device auth) ---
// At-most-once: poll paytida buyruqlar 'done' ga o'tkaziladi (qayta yetkazilmaydi →
// masofaviy "rescan" cheksiz sikl yaratmaydi). Auth: x-device-secret (o'qish yo'li,
// config/feed kabi) + x-device-token bilan qaysi qurilma ekani aniqlanadi.
async function handlePoll(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
  const dtok = req.headers['x-device-token'];
  if (typeof dtok !== 'string' || dtok.length < 16 || dtok.length > 256) {
    return res.status(400).json({ ok: false, error: 'bad token' });
  }

  const sb = db();
  // flag/flag_note ham o'qiymiz — bayroq BARQAROR holat (bir martalik buyruq emas): belgilangan
  // qurilma har pollda uni oladi va to'liq ekranli ogohlantirishni ko'rsatib turadi (unflag → tozalanadi).
  const { data: dev, error: dErr } = await sb.from('devices').select('id, flag, flag_note').eq('device_token', dtok).maybeSingle();
  if (dErr) { console.error(`[device] poll device lookup: ${dErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  res.setHeader('Cache-Control', 'no-store');
  if (!dev) return res.status(200).json({ ok: true, commands: [], flag: null }); // noma'lum token — bo'sh (xato bermaymiz)
  const flag = dev.flag && FLAG_STATES.includes(dev.flag)
    ? { state: dev.flag as string, note: typeof dev.flag_note === 'string' ? dev.flag_note : '' }
    : null;

  const { data: cmds, error: cErr } = await sb
    .from('device_commands')
    .select('id, type, payload')
    .eq('device_id', dev.id)
    .eq('status', 'pending')
    .order('created_at', { ascending: true })
    .limit(20);
  if (cErr) { console.error(`[device] poll commands: ${cErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

  const list = (cmds ?? []) as Array<{ id: number; type: string; payload: unknown }>;
  if (list.length) {
    const ids = list.map((c) => c.id);
    const { error: uErr } = await sb
      .from('device_commands')
      .update({ status: 'done', delivered_at: new Date().toISOString() })
      .in('id', ids);
    if (uErr) console.error(`[device] poll mark done: ${uErr.message}`); // yetkazildi deb belgilay olmadik — keyingi pollda qayta keladi
  }
  return res.status(200).json({ ok: true, commands: list.map((c) => ({ id: c.id, type: c.type, payload: c.payload })), flag });
}

// --- #3: paneldan buyruq qo'yish (FAQAT EGASI) — POST /api/device/<uuid> {type,payload} ---
async function handleEnqueue(req: VercelRequest, res: VercelResponse, id?: string) {
  if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
  if (!id || !UUID_RE.test(id)) return res.status(400).json({ ok: false, error: 'bad id' });
  // bodyParser o'chirilgan (config.api.bodyParser=false) → xom tanani o'zimiz o'qiymiz.
  const raw = await readRaw(req);
  let b: { type?: string; payload?: unknown };
  try { b = JSON.parse(raw || '{}'); } catch { return res.status(400).json({ ok: false, error: 'bad json' }); }
  const type = String(b.type || '');
  const payload = (b.payload && typeof b.payload === 'object') ? b.payload as Record<string, unknown> : {};

  const sb = db();
  const { data: dev, error: dErr } = await sb.from('devices').select('id').eq('id', id).maybeSingle();
  if (dErr) { console.error(`[device] enqueue lookup: ${dErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  if (!dev) return res.status(404).json({ ok: false, error: 'not found' });

  // ── flag/unflag — BARQAROR holat (device_commands EMAS): devices.flag ustuniga yozamiz.
  // Belgilangan qurilma har pollda bayroqni oladi (bir martalik buyruq emas). MDM emas:
  // faqat to'liq-ekran ogohlantirish + kuchli heartbeat, masofaviy o'chirish/qulflash YO'Q.
  if (type === 'flag' || type === 'unflag') {
    let row: Record<string, unknown>;
    if (type === 'unflag') {
      row = { flag: null, flag_at: null, flag_note: null };
    } else {
      const state = String(payload.state || '');
      if (!FLAG_STATES.includes(state)) return res.status(400).json({ ok: false, error: 'bad state' });
      const note = typeof payload.note === 'string' ? payload.note.trim().slice(0, 300) : null;
      row = { flag: state, flag_at: new Date().toISOString(), flag_note: note };
    }
    const { error } = await sb.from('devices').update(row).eq('id', id);
    if (error) { console.error(`[device] flag update: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    await audit(req, 'device_flag', `${type} → ${id.slice(0, 8)}…`);
    return res.status(200).json({ ok: true, flag: type === 'unflag' ? null : row.flag });
  }

  if (!ALLOWED_CMD_TYPES.includes(type)) return res.status(400).json({ ok: false, error: 'bad type' });

  // message — payload {title, body} tekshiruvi (bo'sh xabar yubormaymiz).
  if (type === 'message') {
    const title = typeof payload.title === 'string' ? payload.title.trim().slice(0, 120) : '';
    const body = typeof payload.body === 'string' ? payload.body.trim().slice(0, 1000) : '';
    if (!title && !body) return res.status(400).json({ ok: false, error: 'bo\'sh xabar' });
    b.payload = { title, body };
  }

  const { data, error } = await sb
    .from('device_commands')
    .insert({ device_id: id, type, payload: b.payload && typeof b.payload === 'object' ? b.payload : {}, created_by: 'owner' })
    .select('id, type, status, created_at')
    .single();
  if (error) { console.error(`[device] enqueue insert: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  await audit(req, 'device_command', `${type} → ${id.slice(0, 8)}…`);
  return res.status(200).json({ ok: true, command: data });
}

// --- /api/device/join — qurilma GURUHga qo'shiladi (x-device-secret + HMAC) ---
// Body: { device_token, code, first, last, phone }. Kod → device_groups.join_code (katta
// harfga normallashtiriladi). A'zo ism/familiya/telefon — foydalanuvchi O'ZI kiritadi
// (ilova formasi "guruh egasiga ko'rinadi" deb ogohlantiradi). Register YO'Q qurilma bo'lsa
// ham ishlaydi (upsert onConflict device_token — group_id + a'zo maydonlarini yozadi).
type JoinBody = { device_token?: string; code?: string; first?: string; last?: string; phone?: string };

function trimField(v: unknown, max: number): string {
  return typeof v === 'string' ? v.trim().replace(/\s+/g, ' ').slice(0, max) : '';
}
// Telefon — faqat + (boshida) va raqamlar. Kamida 7 raqam bo'lsin (aks holda yaroqsiz).
function normPhone(v: unknown): string {
  if (typeof v !== 'string') return '';
  let p = v.trim().replace(/[^\d+]/g, '');
  if (p.indexOf('+') > 0) p = p.replace(/\+/g, '');       // + faqat boshida
  const digits = p.replace(/\D/g, '');
  if (digits.length < 7 || digits.length > 15) return '';
  return p.slice(0, 20);
}

async function handleJoin(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  const rawBody = await readRaw(req);
  // Register bilan bir xil yozuv-darvozasi: per-device HMAC imzo YOKI (o'tish davri) x-device-secret.
  if (!(await verifyDeviceWrite(req, rawBody, 'device/join'))) {
    return res.status(401).json({ ok: false, error: 'auth' });
  }
  let b: JoinBody;
  try { b = JSON.parse(rawBody || '{}') as JoinBody; } catch { return res.status(400).json({ ok: false, error: 'bad json' }); }

  const token = typeof b.device_token === 'string' ? b.device_token : '';
  if (token.length < 16) return res.status(400).json({ ok: false, error: 'bad token' });
  // Imzolangan yo'lda sarlavha token tanaga mos kelishi shart (register bilan bir xil qoida).
  const hdrTok = req.headers['x-device-token'];
  if (typeof hdrTok === 'string' && hdrTok.length > 0 && hdrTok !== token) {
    return res.status(401).json({ ok: false, error: 'token/body mismatch' });
  }

  const code = trimField(b.code, 16).toUpperCase();
  if (!code) return res.status(400).json({ ok: false, error: 'code' });

  const first = trimField(b.first, 40);
  const last = trimField(b.last, 40);
  const phone = normPhone(b.phone);
  if (!first || !last || !phone) return res.status(400).json({ ok: false, error: 'fields' });

  const sb = db();
  const { data: group, error: gErr } = await sb
    .from('device_groups')
    .select('id, name, color')
    .eq('join_code', code)
    .maybeSingle();
  if (gErr) { console.error(`[join] group lookup: ${gErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  if (!group) return res.status(200).json({ ok: false, error: 'code' });   // kod topilmadi — ilova "Kod noto'g'ri" ko'rsatadi

  // Register bo'lmagan qurilma ham qo'shila olsin — upsert onConflict device_token.
  // Faqat guruh + a'zo maydonlarini yozamiz (geo/nom register'da yoziladi).
  const { error: uErr } = await sb
    .from('devices')
    .upsert(
      {
        device_token: token,
        group_id: group.id,
        member_first: first,
        member_last: last,
        member_phone: phone,
        last_seen: new Date().toISOString(),
      },
      { onConflict: 'device_token' },
    );
  if (uErr) { console.error(`[join] device upsert: ${uErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

  return res.status(200).json({ ok: true, group: { name: group.name, color: group.color } });
}

// --- /api/device/tgstart — Telegram ro'yxati uchun bir martalik token + havola ---
// Body: { device_token }. Javob: { ok, status, token?, url? }.
//   status='done'    — bu qurilma allaqachon ro'yxatdan o'tgan (ilova darhol o'tadi)
//   status='pending' — token berildi, ilova url'ni ochadi
// Token 24 soat yashaydi. 30 daqiqadan yangi tugallanmagan urinish bo'lsa — QAYTA
// ishlatiladi (foydalanuvchi tugmani ikki marta bossa ikkita "osilgan" qator qolmasin;
// bot esa qaysi qatorni to'ldirishni bilmay qolardi).
const TG_REUSE_MS = 30 * 60 * 1000;

async function handleTgStart(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  const rawBody = await readRaw(req);
  if (!(await verifyDeviceWrite(req, rawBody, 'device/tgstart'))) {
    return res.status(401).json({ ok: false, error: 'auth' });
  }
  let b: { device_token?: string };
  try { b = JSON.parse(rawBody || '{}'); } catch { return res.status(400).json({ ok: false, error: 'bad json' }); }
  const token = typeof b.device_token === 'string' ? b.device_token : '';
  if (token.length < 16) return res.status(400).json({ ok: false, error: 'bad token' });
  const hdrTok = req.headers['x-device-token'];
  if (typeof hdrTok === 'string' && hdrTok.length > 0 && hdrTok !== token) {
    return res.status(401).json({ ok: false, error: 'token/body mismatch' });
  }

  const sb = db();
  res.setHeader('Cache-Control', 'no-store');

  // Allaqachon tugagan bo'lsa — ilova shlagbaumni umuman ko'rsatmaydi.
  const { data: done, error: dErr } = await sb
    .from('tg_registrations')
    .select('id')
    .eq('device_token', token)
    .eq('step', 'done')
    .limit(1)
    .maybeSingle();
  if (dErr) { console.error(`[tgstart] done lookup: ${dErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  if (done) return res.status(200).json({ ok: true, status: 'done' });

  // Yaqinda boshlangan urinish bo'lsa — o'sha tokenni qaytaramiz.
  const since = new Date(Date.now() - TG_REUSE_MS).toISOString();
  const { data: recent, error: rErr } = await sb
    .from('tg_registrations')
    .select('token')
    .eq('device_token', token)
    .neq('step', 'done')
    .gte('created_at', since)
    .order('id', { ascending: false })
    .limit(1)
    .maybeSingle();
  if (rErr) { console.error(`[tgstart] recent lookup: ${rErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

  let regToken = recent?.token as string | undefined;
  if (!regToken) {
    regToken = randomBytes(16).toString('hex');
    const { error: iErr } = await sb
      .from('tg_registrations')
      .insert({ token: regToken, device_token: token, step: 'new' });
    if (iErr) { console.error(`[tgstart] insert: ${iErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

    // Cron yo'q (Hobby) — tashlab ketilgan urinishlarni shu yerda tozalaymiz.
    // Fail-soft: tozalash ishlamasa ham ro'yxatdan o'tish buzilmaydi.
    const dayAgo = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
    const { error: cErr } = await sb
      .from('tg_registrations')
      .delete()
      .eq('step', 'new')
      .lt('created_at', dayAgo);
    if (cErr) console.error(`[tgstart] cleanup: ${cErr.message}`);
  }

  const url = regDeepLink(regToken);
  if (!url) {
    // TELEGRAM_REG_BOT_USERNAME sozlanmagan — ilova "bulut sozlanmagan" deb ko'rsatadi.
    console.error('[tgstart] TELEGRAM_REG_BOT_USERNAME not set');
    return res.status(200).json({ ok: false, error: 'unconfigured' });
  }
  return res.status(200).json({ ok: true, status: 'pending', token: regToken, url });
}

// --- /api/device/tgstatus?token=… — ilova ro'yxat holatini so'raydi (polling) ---
// Auth: x-device-secret (o'qish yo'li, poll/config kabi). Tokenning o'zi ham sir —
// uni faqat shu qurilma va Telegram ko'rgan. Javob: { ok, status, name? }.
async function handleTgStatus(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
  const t = Array.isArray(req.query.token) ? req.query.token[0] : req.query.token;
  if (typeof t !== 'string' || !/^[a-f0-9]{32}$/.test(t)) {
    return res.status(400).json({ ok: false, error: 'bad token' });
  }

  const sb = db();
  res.setHeader('Cache-Control', 'no-store');
  const { data, error } = await sb
    .from('tg_registrations')
    .select('step, full_name')
    .eq('token', t)
    .maybeSingle();
  if (error) { console.error(`[tgstatus] lookup: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  if (!data) return res.status(200).json({ ok: true, status: 'unknown' });
  return res.status(200).json({ ok: true, status: data.step, name: data.full_name ?? null });
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
  // "Himoya batareyasi": qaysi himoyalar haqiqatan YOQILGAN (mijoz SecurityScore'dan yig'adi).
  protections?: Record<string, unknown>;
};

// protections jsonb'ni normallashtiramiz — faqat kutilgan kalitlar, boolean/int, ishonchsiz
// JSON'dan kelgani uchun (kirish-qattiqlash falsafasi: scan/upload.ts kabi).
const PROT_BOOL_KEYS = ['svc', 'a11y', 'notif', 'postN', 'linkH', 'apkH', 'vpn', 'batt'];
function normProtections(raw: unknown): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object') return null;
  const src = raw as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const k of PROT_BOOL_KEYS) {
    if (typeof src[k] === 'boolean') out[k] = src[k];
  }
  const age = Number(src.scanAgeH);
  if (Number.isFinite(age) && age >= 0) out.scanAgeH = Math.min(100000, Math.round(age));
  out.ts = Math.floor(Date.now() / 1000);
  return Object.keys(out).length > 1 ? out : null; // ts'dan tashqari kamida bitta signal
}

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

  // Himoya-holati (protections) — kelsa yozamiz. Watchdog uchun AVVALGI holatni o'qib olamiz
  // (kritik himoya ON→OFF bo'lsa egaga ogohlantirish). Null qiymat eskini o'chirmaydi.
  const prot = normProtections(b.protections);
  let prevProt: Record<string, unknown> | null = null;
  let prevName: string | null = null;
  if (prot) {
    row.protections = prot;
    const { data: cur } = await sb
      .from('devices')
      .select('protections, name')
      .eq('device_token', b.device_token)
      .maybeSingle();
    prevProt = (cur?.protections as Record<string, unknown> | null) ?? null;
    prevName = (cur?.name as string | null) ?? null;
  }

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

  if (error) { console.error(`[register] device upsert db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

  // Himoya-tushishi qorovuli (watchdog): kritik himoya AVVAL ON edi, ENDI OFF bo'lsa —
  // egaga bir qatorli Telegram ogohlantirishi (throttle 6 soat, alert_state orqali). Bolalar
  // va OEM batareya-o'ldirgichlari himoyani jimgina o'chiradi; alertsiz egasi buni faqat
  // hodisa paytida bilib qoladi. Fail-soft: har qanday xato → faqat log, register buzilmaydi.
  if (prot && prevProt && data?.id) {
    try {
      const dropped = CRITICAL_PROTECTIONS.filter((k) => prevProt![k] === true && prot[k] === false);
      if (dropped.length) {
        const key = `proto_drop:${data.id}`;
        const { data: st } = await sb.from('alert_state').select('last_at').eq('key', key).maybeSingle();
        const lastMs = st?.last_at ? new Date(st.last_at).getTime() : 0;
        if (Date.now() - lastMs > 6 * 3600 * 1000) {
          await sb.from('alert_state').upsert({ key, last_at: new Date().toISOString() }, { onConflict: 'key' });
          const label = prevName || b.name || data.id.slice(0, 8);
          const names: Record<string, string> = { svc: 'Himoya xizmati', notif: 'Bildirishnoma ruxsati' };
          const list = dropped.map((k) => names[k] || k).join(', ');
          const text = `⚠️ *Himoya o'chdi*\n"${label}" qurilmasida: *${list}* — endi o'chiq.\nPanel orqali tekshiring.`;
          await Promise.all(adminChatIds().map((chatId) => sendMessage(chatId, text, { parseMode: 'Markdown' })));
        }
      }
    } catch (e) {
      console.error(`[register] protection watchdog failed: ${(e as Error).message}`);
    }
  }

  // Per-device token beramiz — qurilma keyingi yozuvlarni shu bilan IMZOLAYDI (HMAC).
  // Determinik (HMAC(device_token, DEVICE_TOKEN_SECRET)), serverda saqlanmaydi. Kalit
  // o'rnatilmagan bo'lsa null — mijoz eski x-device-secret yo'lida qoladi (buzilmaydi).
  const deviceAuthToken = issueDeviceToken(b.device_token);
  return res.status(200).json({ ok: true, device_id: data?.id, device_auth_token: deviceAuthToken });
}
