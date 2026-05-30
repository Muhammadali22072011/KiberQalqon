import type { VercelRequest, VercelResponse } from '@vercel/node';
import { timingSafeEqual } from 'crypto';
import { verifyTotp } from '../../lib/totp.js';
import { issueSession, issueAdminSession } from '../../lib/session.js';

// Veb-panelga kirish — IKKI xil odam uchun:
//   • EGASI (dasturchi) — ADMIN_SECRET (master kalit) + ixtiyoriy TOTP. TO'LIQ huquq.
//   • ADMIN (bitta hisob) — login + parol (env: ADMIN_LOGIN / ADMIN_PASSWORD).
//     Cheklangan: faqat KO'RISH, EKSPORT va E'LON joylash. O'zgartira/o'chira olmaydi.
//
// So'rov tanasi qaysi maydonlarni bersa — o'sha oqim:
//   { login, password }  → admin
//   { secret, otp }      → egasi
// Muvaffaqiyatda qisqa muddatli HMAC token qaytadi (master kalit/parol brauzerda saqlanmaydi).
//
// 2FA faqat ADMIN_TOTP_SECRET env o'rnatilganda majburiy (egasini lockout qilmaslik uchun).

type Body = { secret?: string; otp?: string; login?: string; password?: string };

function safeEq(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });

  const b = (req.body ?? {}) as Body;

  // Admin (login+parol) kirishi — login yoki parol berilgan bo'lsa shu oqim.
  if ((b.login ?? '') !== '' || (b.password ?? '') !== '') {
    return loginAdmin(res, b);
  }

  // Egasi (owner) kirishi — master sir (+2FA).
  const expected = process.env.ADMIN_SECRET;
  if (!expected) return res.status(500).json({ ok: false, error: 'ADMIN_SECRET sozlanmagan' });

  const secret = b.secret ?? '';
  const otp = b.otp ?? '';

  // Bir xil umumiy xato — qaysi maydon noto'g'ri ekanini oshkor qilmaymiz.
  const FAIL = { ok: false as const, error: "Kalit yoki kod noto'g'ri" };

  if (!safeEq(secret, expected)) {
    return res.status(401).json(FAIL);
  }

  const totpSecret = process.env.ADMIN_TOTP_SECRET;
  if (totpSecret) {
    if (!verifyTotp(otp, totpSecret)) {
      return res.status(401).json(FAIL);
    }
  }

  const { token, exp } = issueSession();
  return res.status(200).json({ ok: true, token, exp, level: 'owner', twofa: Boolean(totpSecret) });
}

// --- Bitta cheklangan ADMIN (login + parol) ----------------------------------
// Hisob env'da: ADMIN_LOGIN va ADMIN_PASSWORD. Rol/baza yo'q — bitta hisob.
function loginAdmin(res: VercelResponse, b: Body) {
  const login = (b.login ?? '').trim();
  const password = b.password ?? '';
  const FAIL = { ok: false as const, error: "Login yoki parol noto'g'ri" };
  if (!login || !password) {
    return res.status(400).json({ ok: false, error: 'Login va parol kerak' });
  }

  const expLogin = process.env.ADMIN_LOGIN;
  const expPassword = process.env.ADMIN_PASSWORD;
  if (!expLogin || !expPassword) {
    return res.status(500).json({ ok: false, error: 'ADMIN_LOGIN/ADMIN_PASSWORD sozlanmagan' });
  }

  // Ikkala maydon ham doimiy-vaqtli solishtiriladi (enumeration'ga qarshi bir xil xato).
  const okLogin = safeEq(login, expLogin);
  const okPassword = safeEq(password, expPassword);
  if (!okLogin || !okPassword) {
    return res.status(401).json(FAIL);
  }

  const { token, exp } = issueAdminSession(login);
  return res.status(200).json({ ok: true, token, exp, level: 'admin', name: login });
}
