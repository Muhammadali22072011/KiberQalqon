import { createHmac } from 'crypto';

// Soatlik kod — "4 qulf"ning 2-bosqichi.
// Bitta umumiy kod butun tashkilot uchun, har soatda yangilanadi. Egasi uni
// panelda ko'radi (/api/role/code) va kirishga ruxsat bergan odamga aytadi.
//
// Stateless: serverda saqlanmaydi — ROLE_CODE_SECRET + joriy soatdan hisoblanadi.
// HMAC-SHA256(secret, soat_raqami) → 6 xonali son. Bir xil sekret + soat →
// bir xil kod, shuning uchun panel ko'rsatgan kod login tekshiruvi bilan mos keladi.

const HOUR_MS = 3_600_000;

function secret(): string {
  return process.env.ROLE_CODE_SECRET ?? '';
}

export function hasSecret(): boolean {
  return secret().length > 0;
}

export function codeForHour(hourBucket: number): string {
  const h = createHmac('sha256', secret()).update(String(hourBucket)).digest();
  const n = h.readUInt32BE(0) % 1_000_000;
  return n.toString().padStart(6, '0');
}

export function currentCode(now: Date = new Date()): string {
  return codeForHour(Math.floor(now.getTime() / HOUR_MS));
}

/**
 * Kodni tekshiradi. Joriy soat VA oldingi soat qabul qilinadi — soat
 * chegarasida (masalan 13:59 da olib 14:01 da kiritsa) ham ishlashi uchun.
 */
export function isValidCode(code: string, now: Date = new Date()): boolean {
  const c = (code ?? '').trim();
  if (!/^\d{6}$/.test(c)) return false;
  const bucket = Math.floor(now.getTime() / HOUR_MS);
  return c === codeForHour(bucket) || c === codeForHour(bucket - 1);
}

/** Joriy kod yangilanguncha qolgan soniya (panelda taymer ko'rsatish uchun). */
export function secondsLeft(now: Date = new Date()): number {
  return Math.ceil((HOUR_MS - (now.getTime() % HOUR_MS)) / 1000);
}
