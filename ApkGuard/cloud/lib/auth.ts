import type { VercelRequest } from '@vercel/node';
import { timingSafeEqual as cryptoTimingSafeEqual } from 'crypto';
import { verifySession, readSession } from './session.js';

export function checkDeviceSecret(req: VercelRequest): boolean {
  const expected = process.env.DEVICE_SHARED_SECRET;
  if (!expected) return false;
  const got = req.headers['x-device-secret'];
  return typeof got === 'string' && timingSafeEqual(got, expected);
}

// EGASI (to'liq huquq): master ADMIN_SECRET YOKI egasi sessiya tokeni (kind yo'q).
// Xavfli/egaga xos endpointlar (qurilma o'chirish, scans dump) shunga tayanadi —
// cheklangan admin (kind:'admin') bu yerdan O'TMAYDI.
//
// x-admin-secret sarlavhasi ikki qiymatni qabul qiladi:
//   1) xom ADMIN_SECRET — skriptlar/curl uchun (ping.mjs).
//   2) egasi sessiya tokeni — panel /api/admin/login'dan oladi (master kalit brauzerda saqlanmaydi).
export function checkAdminSecret(req: VercelRequest): boolean {
  const got = req.headers['x-admin-secret'];
  if (typeof got !== 'string' || got.length === 0) return false;

  const expected = process.env.ADMIN_SECRET;
  if (expected && timingSafeEqual(got, expected)) return true;

  return verifySession(got); // faqat egasi tokeni (admin tokenini rad etadi)
}

// Kirgan HAR QANDAY panel foydalanuvchisi (EGASI yoki cheklangan ADMIN) — o'qish va
// eksport endpointlari uchun. Ikkalasi ham hamma ma'lumotni ko'radi (rol/perms yo'q).
// Qurilma siri (x-device-secret) bu yerda ISHLAMAYDI: flotni paneldan tashqari dump
// qilib bo'lmaydi.
export function canRead(req: VercelRequest): boolean {
  const got = req.headers['x-admin-secret'];
  if (typeof got !== 'string' || got.length === 0) return false;

  const expected = process.env.ADMIN_SECRET;
  if (expected && timingSafeEqual(got, expected)) return true;

  return readSession(got) !== null; // egasi (kind yo'q) yoki admin (kind:'admin')
}

// E'lon (yangilik) joylash/o'chirish — egasi YOKI cheklangan admin qila oladi.
// (Admin uchun yagona "yozish" huquqi shu — e'lonlar.)
export function canManageNews(req: VercelRequest): boolean {
  return canRead(req);
}

export function checkTelegramSecret(req: VercelRequest): boolean {
  const expected = process.env.TELEGRAM_WEBHOOK_SECRET;
  if (!expected) return false;
  const got = req.headers['x-telegram-bot-api-secret-token'];
  return typeof got === 'string' && timingSafeEqual(got, expected);
}

// Ro'yxatdan o'tish boti — ALOHIDA sirdan foydalanadi. Ikkalasi bitta webhook
// faylida (Hobby 12-funksiya limiti) yashaydi, shuning uchun sirlarni ham ajratamiz:
// ochiq botning sirri sizib chiqsa, egasi paneliga kirish yo'li ochilmaydi.
export function checkTelegramRegSecret(req: VercelRequest): boolean {
  const expected = process.env.TELEGRAM_REG_WEBHOOK_SECRET;
  if (!expected) return false;
  const got = req.headers['x-telegram-bot-api-secret-token'];
  return typeof got === 'string' && timingSafeEqual(got, expected);
}

// #45: avval bu yerda charCodeAt bilan o'z-o'zidan yozilgan solishtirish bor edi —
// u UTF-16 kod birliklari bo'yicha ishlardi (baytma bayt emas) va login.ts/session.ts
// ishlatadigan crypto.timingSafeEqual'dan farq qilardi. Endi hamma joyda bir xil,
// baytma bayt doimiy-vaqtli solishtirish ishlatamiz (uzunlik guard'i login.ts kabi).
function timingSafeEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ba.length !== bb.length) return false;
  return cryptoTimingSafeEqual(ba, bb);
}
