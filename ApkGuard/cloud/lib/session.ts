import { createHmac, timingSafeEqual } from 'crypto';

// Panel sessiya tokeni — kirgandan keyin master ADMIN_SECRET yoki parol brauzerda
// SAQLANMAYDI. Buning o'rniga qisqa muddatli HMAC-imzolangan token beriladi (8 soat).
// Token o'g'irlansa ham — muddati tugaydi va master kalit oshkor bo'lmaydi.
//
// Format: base64url(payloadJSON) "." base64url(HMAC-SHA256(payload)).
// Imzo kaliti: SESSION_SECRET (bo'lmasa ADMIN_SECRET) — ikkalasi ham faqat serverda.

const DEFAULT_TTL_SEC = 8 * 60 * 60;

function key(): string {
  return process.env.SESSION_SECRET || process.env.ADMIN_SECRET || '';
}

function b64urlEncode(s: string): string {
  return Buffer.from(s, 'utf8').toString('base64url');
}

function sign(payloadB64: string): string {
  return createHmac('sha256', key()).update(payloadB64).digest('base64url');
}

// Token ichidagi ma'lumot — IKKI xil foydalanuvchi:
//   kind yo'q     → EGASI (dasturchi, master ADMIN_SECRET bilan kiradi) — TO'LIQ huquq.
//   kind:'admin'  → bitta cheklangan ADMIN (login+parol bilan kiradi) — faqat KO'RISH,
//                   EKSPORT va E'LON joylash. Qurilma o'chirish kabi xavfli amallar mumkin emas.
export type SessionPayload = {
  exp: number;
  kind?: 'admin';
  login?: string;
};

// Tokenni tekshiradi (imzo + muddat) va payloadni qaytaradi. Yaroqsiz → null.
export function readSession(token: string): SessionPayload | null {
  if (!key()) return null;
  const parts = (token ?? '').split('.');
  if (parts.length !== 2) return null;
  const [payloadB64, sig] = parts;

  // Imzoni doimiy-vaqtli solishtirish.
  const expected = sign(payloadB64);
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return null;

  // Muddati.
  try {
    const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8')) as SessionPayload;
    if (typeof payload.exp !== 'number' || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

// Egasi (master panel) tokeni — kind yo'q. To'liq huquq.
export function issueSession(ttlSec: number = DEFAULT_TTL_SEC): { token: string; exp: number } {
  // #46: imzo kaliti yo'q bo'lsa token chiqarmaymiz — aks holda readSession uni HECH QACHON
  // tasdiqlay olmaydi (jim verifikatsiya qilinmaydigan token → foydalanuvchi bloklanadi).
  if (!key()) throw new Error('SESSION_SECRET/ADMIN_SECRET sozlanmagan — token imzolab bo\'lmaydi');
  const exp = Math.floor(Date.now() / 1000) + ttlSec;
  const payloadB64 = b64urlEncode(JSON.stringify({ exp }));
  const token = `${payloadB64}.${sign(payloadB64)}`;
  return { token, exp };
}

// Cheklangan admin tokeni — kind:'admin'. Faqat ko'rish + eksport + e'lon joylash.
// verifySession buni ATAYIN rad etadi: admin xavfli (egaga xos) endpointlarga kira olmaydi.
export function issueAdminSession(
  login: string,
  ttlSec: number = DEFAULT_TTL_SEC,
): { token: string; exp: number } {
  if (!key()) throw new Error('SESSION_SECRET/ADMIN_SECRET sozlanmagan — token imzolab bo\'lmaydi');
  const exp = Math.floor(Date.now() / 1000) + ttlSec;
  const payloadB64 = b64urlEncode(JSON.stringify({ exp, kind: 'admin', login }));
  const token = `${payloadB64}.${sign(payloadB64)}`;
  return { token, exp };
}

// FAQAT egasi (kind yo'q). Cheklangan admin tokenini (kind:'admin') rad etadi —
// shu sabab egaga xos hamma endpoint (scans, qurilma o'chirish) avtomatik himoyalanadi.
export function verifySession(token: string): boolean {
  const payload = readSession(token);
  if (!payload) return false;
  return payload.kind === undefined;
}
