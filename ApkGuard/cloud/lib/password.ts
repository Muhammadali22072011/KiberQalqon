import { randomBytes, scryptSync, timingSafeEqual } from 'crypto';

// Parol xeshlash — operator paroli OCHIQ saqlanmaydi.
// scrypt (Node built-in, qo'shimcha paket kerak emas) + tasodifiy salt.
// Kam hajmli login uchun scryptSync mos (sekin, lekin brute-force'ga qarshi yaxshi).

const KEYLEN = 32;

export function hashPassword(password: string): { hash: string; salt: string } {
  const salt = randomBytes(16).toString('hex');
  const hash = scryptSync(password, salt, KEYLEN).toString('hex');
  return { hash, salt };
}

export function verifyPassword(password: string, hash: string, salt: string): boolean {
  try {
    const computed = scryptSync(password, salt, KEYLEN);
    const stored = Buffer.from(hash, 'hex');
    if (computed.length !== stored.length) return false;
    return timingSafeEqual(computed, stored);
  } catch {
    return false;
  }
}
