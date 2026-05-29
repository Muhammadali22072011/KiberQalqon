import { createHmac, randomBytes } from 'crypto';

// TOTP (RFC 6238) — autentifikator ilovasi (Google Authenticator, Authy, ...) bilan
// 2-bosqichli kirish. Panelga kirish "qiyin" bo'lsin: ADMIN_SECRET (parol) + 6 xonali
// kod (telefondagi ilovadan). Kod har 30 soniyada yangilanadi.
//
// Faqat Node built-in 'crypto' — qo'shimcha paket kerak emas.

const DIGITS = 6;
const PERIOD = 30; // soniya

// ── base32 (RFC 4648) ──────────────────────────────────────────────────────
const B32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function base32Decode(input: string): Buffer {
  const clean = input.replace(/=+$/,'').replace(/\s+/g, '').toUpperCase();
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = B32_ALPHABET.indexOf(ch);
    if (idx === -1) continue; // noto'g'ri belgini tashlab ketamiz
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out.push((value >>> bits) & 0xff);
    }
  }
  return Buffer.from(out);
}

function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += B32_ALPHABET[(value >>> bits) & 31];
    }
  }
  if (bits > 0) out += B32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

// ── TOTP hisoblash ──────────────────────────────────────────────────────────
function hotp(secret: Buffer, counter: number): string {
  const buf = Buffer.alloc(8);
  // 64-bit big-endian counter (yuqori 32 bit deyarli har doim 0).
  buf.writeUInt32BE(Math.floor(counter / 0x100000000), 0);
  buf.writeUInt32BE(counter >>> 0, 4);
  const hmac = createHmac('sha1', secret).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const bin =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);
  return (bin % 10 ** DIGITS).toString().padStart(DIGITS, '0');
}

/**
 * Kiritilgan kodni tekshiradi. window=1 → joriy ±1 qadam (soat farqiga chidamli).
 */
export function verifyTotp(token: string, base32Secret: string, window = 1): boolean {
  const t = (token ?? '').replace(/\s+/g, '');
  if (!/^\d{6}$/.test(t)) return false;
  const secret = base32Decode(base32Secret);
  if (secret.length === 0) return false;
  const counter = Math.floor(Date.now() / 1000 / PERIOD);
  for (let w = -window; w <= window; w++) {
    if (hotp(secret, counter + w) === t) return true;
  }
  return false;
}

// ── Sozlash yordamchilari (bir martalik) ────────────────────────────────────
/** Yangi tasodifiy base32 sekret — autentifikator ilovasiga kiritiladi. */
export function generateBase32Secret(bytes = 20): string {
  return base32Encode(randomBytes(bytes));
}

/** otpauth:// URI — QR yoki qo'lda kiritish uchun. */
export function otpauthUri(secret: string, label = 'panel', issuer = 'KiberQalqon'): string {
  const enc = encodeURIComponent;
  return `otpauth://totp/${enc(issuer)}:${enc(label)}?secret=${secret}&issuer=${enc(issuer)}&digits=${DIGITS}&period=${PERIOD}`;
}
