import type { VercelRequest } from '@vercel/node';
import { createHmac, createHash, timingSafeEqual } from 'crypto';
import { db } from './supabase.js';
import { checkDeviceSecret } from './auth.js';

/**
 * Per-device YOZUV autentifikatsiyasi (anti-RE backend himoyasi).
 *
 * MUAMMO: ilgari har bir yozuv endpointi (scan/upload, device/register) FAQAT bitta umumiy
 * DEVICE_SHARED_SECRET bilan himoyalangan edi — u APK ichida (Shield shifrida bo'lsa ham
 * reverse qilsa bo'ladi). Kim sirni qo'lga kiritsa: soxta skan/qurilma yuborib xaritani
 * "zaharlashi", egaga spam-alert yog'dirishi, Storage'ga axlat yuklashi mumkin edi.
 *
 * YECHIM (QO'SHIMCHA, ESKI MIJOZLARNI BUZMAYDI):
 *   1) register paytida server qurilmaga PER-DEVICE token beradi:
 *        deviceAuthToken = HMAC-SHA256(device_token, DEVICE_TOKEN_SECRET)   // serverda saqlanmaydi, qayta hisoblanadi
 *   2) keyingi yozuvlar shu token bilan IMZOLANADI:
 *        sig = HMAC-SHA256(deviceAuthToken, "<label>\n<ts>\n<nonce>\n<sha256hex(body)>")
 *      + timestamp yangiligi (±5 daq) + nonce takror-himoyasi (device_nonces) + body-hash.
 *   3) Eski mijoz (faqat x-device-secret) O'TISH davrida ishlashda davom etadi (dual-accept).
 *
 * IMZO `req.url` ga BOG'LIQ EMAS — har bir handler o'zining qat'iy `label`ini beradi
 * ("scan/upload" / "device/register"), shunda Vercel rewrite/trailing-slash imzoni buzmaydi.
 */

const SKEW_SEC = 300; // ±5 daqiqa soat farqi oynasi

function tokenKey(): string {
  // DEVICE_TOKEN_SECRET bo'lmasa SESSION_SECRET/ADMIN_SECRET'ga qaytadi (alohida o'rnatish tavsiya etiladi).
  return process.env.DEVICE_TOKEN_SECRET || process.env.SESSION_SECRET || process.env.ADMIN_SECRET || '';
}

function eq(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ba.length !== bb.length) return false;
  return timingSafeEqual(ba, bb);
}

/**
 * Per-device token = base64url(HMAC-SHA256(device_token) keyed by DEVICE_TOKEN_SECRET).
 * Determinik — server saqlamaydi, tekshirish uchun qayta hisoblaydi. Yangilik/replay
 * himoyasini har-so'rovli timestamp+nonce beradi (exp kerak emas). Kalit yo'q → null.
 */
export function issueDeviceToken(deviceToken: string): string | null {
  const k = tokenKey();
  if (!k) return null;
  return createHmac('sha256', k).update(deviceToken).digest('base64url');
}

function canonical(label: string, ts: string, nonce: string, bodyHashHex: string): string {
  return `${label}\n${ts}\n${nonce}\n${bodyHashHex}`;
}

/**
 * Yaroqli per-device imzo bormi? Har qanday yetishmovchilik/xato → false (TASHLAMAYDI),
 * shunda chaqiruvchi o'tish davrida eski x-device-secret'ga qaytishi mumkin.
 */
async function verifyDeviceSignature(req: VercelRequest, rawBody: string, label: string): Promise<boolean> {
  const k = tokenKey();
  if (!k) return false;

  const deviceToken = req.headers['x-device-token'];
  const sig = req.headers['x-signature'];
  const tsRaw = req.headers['x-timestamp'];
  const nonce = req.headers['x-nonce'];
  if (typeof deviceToken !== 'string' || typeof sig !== 'string' ||
      typeof tsRaw !== 'string' || typeof nonce !== 'string') return false;
  if (deviceToken.length < 16 || deviceToken.length > 256) return false;
  if (nonce.length < 8 || nonce.length > 128) return false;

  const ts = Number(tsRaw);
  if (!Number.isFinite(ts)) return false;
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > SKEW_SEC) return false; // yangilik (freshness)

  const dtok = issueDeviceToken(deviceToken);
  if (!dtok) return false;

  const bodyHashHex = createHash('sha256').update(rawBody, 'utf8').digest('hex');
  const expectedSig = createHmac('sha256', dtok)
    .update(canonical(label, tsRaw, nonce, bodyHashHex))
    .digest('base64url');
  if (!eq(sig, expectedSig)) return false;

  // Replay himoyasi — FAIL-CLOSED: agar nonce jadvali yo'q/xato bo'lsa, imzoni QABUL
  // QILMAYMIZ (antivirus oltin qoidasi: himoyasiz ishlamaymiz). Bunday holatda mijoz
  // baribir x-device-secret ham yuboradi → dual-accept eski yo'l bilan o'tadi (buzilmaydi).
  try {
    const sb = db();
    const { error } = await sb.from('device_nonces').insert({ nonce, device_token: deviceToken });
    if (error) return false; // unique violation = replay; boshqa xato ham → rad (fail-closed)
  } catch {
    return false;
  }

  // Jadval cheksiz o'smasligi uchun ehtimollik bilan eski nonce'larni tozalaymiz
  // (fire-and-forget — auth natijasiga ta'sir qilmaydi). 10 daq = 2x SKEW_SEC, shuning
  // uchun tozalangan nonce hech qachon qayta yaroqli bo'lmaydi. (pg_cron muqobili README'da.)
  if (Math.random() < 0.02) {
    try {
      const cutoff = new Date(Date.now() - 10 * 60 * 1000).toISOString();
      void db().from('device_nonces').delete().lt('created_at', cutoff).then(() => {}, () => {});
    } catch {
      /* tozalash muvaffaqiyatsiz bo'lsa ham e'tiborsiz */
    }
  }
  return true;
}

/**
 * Yozuv endpointlari uchun YAGONA darvoza: per-device imzo YOKI (o'tish davri) eski
 * umumiy x-device-secret. Yangi sxema QAT'IY QO'SHIMCHA — dala yangilanguncha eski
 * qurilmalar ishlashda davom etadi.
 */
export async function verifyDeviceWrite(req: VercelRequest, rawBody: string, label: string): Promise<boolean> {
  if (await verifyDeviceSignature(req, rawBody, label)) return true;
  return checkDeviceSecret(req); // legacy yo'l — dala yangilangach olib tashlanadi
}
