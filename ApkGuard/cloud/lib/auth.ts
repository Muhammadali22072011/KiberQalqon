import type { VercelRequest } from '@vercel/node';
import { verifySession } from './session.js';

export function checkDeviceSecret(req: VercelRequest): boolean {
  const expected = process.env.DEVICE_SHARED_SECRET;
  if (!expected) return false;
  const got = req.headers['x-device-secret'];
  return typeof got === 'string' && timingSafeEqual(got, expected);
}

// Panel (o'qish/admin) endpointlari uchun — bu sirni APK ichiga QO'YMAYMIZ.
// Shu sabab qurilma siri (x-device-secret) bilan butun flotni dump qilib
// bo'lmaydi: panel alohida ADMIN_SECRET talab qiladi.
//
// x-admin-secret sarlavhasi IKKI xil qiymatni qabul qiladi:
//   1) xom ADMIN_SECRET — skriptlar/curl uchun (ping.mjs), eski usul.
//   2) qisqa muddatli sessiya tokeni — panel /api/admin/login'dan oladi (2FA'dan
//      keyin). Shunda master kalit brauzerda saqlanmaydi.
// Ikkalasi ham shu bitta funksiyada tekshiriladi → boshqa endpointlar o'zgarmaydi.
export function checkAdminSecret(req: VercelRequest): boolean {
  const got = req.headers['x-admin-secret'];
  if (typeof got !== 'string' || got.length === 0) return false;

  const expected = process.env.ADMIN_SECRET;
  if (expected && timingSafeEqual(got, expected)) return true;

  // Aks holda — sessiya tokeni bo'lishi mumkin (panel kiritadi).
  return verifySession(got);
}

export function checkTelegramSecret(req: VercelRequest): boolean {
  const expected = process.env.TELEGRAM_WEBHOOK_SECRET;
  if (!expected) return false;
  const got = req.headers['x-telegram-bot-api-secret-token'];
  return typeof got === 'string' && timingSafeEqual(got, expected);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
