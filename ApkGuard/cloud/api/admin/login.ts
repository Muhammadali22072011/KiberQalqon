import type { VercelRequest, VercelResponse } from '@vercel/node';
import { timingSafeEqual } from 'crypto';
import { verifyTotp } from '../../lib/totp.js';
import { issueSession } from '../../lib/session.js';

// Panelga "qiyin" kirish: ADMIN_SECRET (parol) + TOTP 6 xonali kod (autentifikator
// ilovasidan). Muvaffaqiyatli bo'lsa qisqa muddatli sessiya tokeni qaytadi — keyingi
// so'rovlar shu token bilan ketadi, master kalit brauzerda saqlanmaydi.
//
// 2FA faqat ADMIN_TOTP_SECRET env o'rnatilganda majburiy bo'ladi (aks holda
// faqat parol — egasini lockout qilmaslik uchun, 2FA'ni keyin yoqadi).

type Body = { secret?: string; otp?: string };

function safeEq(a: string, b: string): boolean {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });

  const expected = process.env.ADMIN_SECRET;
  if (!expected) return res.status(500).json({ ok: false, error: 'ADMIN_SECRET sozlanmagan' });

  const b = (req.body ?? {}) as Body;
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
  return res.status(200).json({ ok: true, token, exp, twofa: Boolean(totpSecret) });
}
