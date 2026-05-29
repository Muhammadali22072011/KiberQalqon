import { createHmac, timingSafeEqual } from 'crypto';

// Panel sessiya tokeni — kirgandan keyin master ADMIN_SECRET brauzerda SAQLANMAYDI.
// Buning o'rniga qisqa muddatli HMAC-imzolangan token beriladi (masalan 8 soat).
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

export function issueSession(ttlSec: number = DEFAULT_TTL_SEC): { token: string; exp: number } {
  const exp = Math.floor(Date.now() / 1000) + ttlSec;
  const payloadB64 = b64urlEncode(JSON.stringify({ exp }));
  const token = `${payloadB64}.${sign(payloadB64)}`;
  return { token, exp };
}

export function verifySession(token: string): boolean {
  if (!key()) return false;
  const parts = (token ?? '').split('.');
  if (parts.length !== 2) return false;
  const [payloadB64, sig] = parts;

  // Imzoni doimiy-vaqtli solishtirish.
  const expected = sign(payloadB64);
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return false;

  // Muddati.
  try {
    const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
    return typeof payload.exp === 'number' && payload.exp > Math.floor(Date.now() / 1000);
  } catch {
    return false;
  }
}
