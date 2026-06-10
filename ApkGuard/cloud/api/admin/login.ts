import type { VercelRequest, VercelResponse } from '@vercel/node';
import { timingSafeEqual } from 'crypto';
import { verifyTotpCounter } from '../../lib/totp.js';
import { issueSession, issueAdminSession } from '../../lib/session.js';
import { db } from '../../lib/supabase.js';
import {
  clientKey, checkLocked, recordFailure, recordSuccess, warnWeakSecrets, looksLikePlaceholder,
} from '../../lib/ratelimit.js';

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
  warnWeakSecrets();

  const b = (req.body ?? {}) as Body;

  // CLOUD-03: (scope+IP) bo'yicha brute-force tezlik cheklovi. Bloklangan bo'lsa — 429.
  const adminFlow = (b.login ?? '') !== '' || (b.password ?? '') !== '';
  const rlKey = clientKey(req, adminFlow ? 'admin' : 'owner');
  const lock = await checkLocked(rlKey);
  if (lock.locked) {
    res.setHeader('Retry-After', String(lock.retryAfter));
    return res.status(429).json({ ok: false, error: `Juda ko'p urinish. ${lock.retryAfter}s dan keyin qayta urining.` });
  }

  // Admin (login+parol) kirishi — login yoki parol berilgan bo'lsa shu oqim.
  if (adminFlow) {
    return loginAdmin(res, b, rlKey);
  }

  // Egasi (owner) kirishi — master sir (+2FA).
  const expected = process.env.ADMIN_SECRET;
  if (!expected) return res.status(500).json({ ok: false, error: 'ADMIN_SECRET sozlanmagan' });
  // Default/placeholder kalit bilan kirishga ruxsat bermaymiz — haqiqiy maxfiy kalit o'rnatilsin.
  if (looksLikePlaceholder(expected)) {
    return res.status(500).json({ ok: false, error: 'ADMIN_SECRET hali sozlanmagan (default qiymat)' });
  }

  const secret = b.secret ?? '';
  const otp = b.otp ?? '';

  // Bir xil umumiy xato — qaysi maydon noto'g'ri ekanini oshkor qilmaymiz.
  const FAIL = { ok: false as const, error: "Kalit yoki kod noto'g'ri" };

  if (!safeEq(secret, expected)) {
    await recordFailure(rlKey);
    return res.status(401).json(FAIL);
  }

  const totpSecret = process.env.ADMIN_TOTP_SECRET;
  if (totpSecret) {
    const counter = verifyTotpCounter(otp, totpSecret);
    if (counter == null) {
      await recordFailure(rlKey);
      return res.status(401).json(FAIL);
    }
    // #44 replay himoyasi: bir xil (yoki undan eski) qadamdagi kod qayta ishlatilmasin.
    // Eng katta qabul qilingan counter'ni Supabase'da saqlaymiz. Best-effort: jadval
    // bo'lmasa / DB xato bo'lsa egasini bloklamaymiz (migratsiyasiz ham TOTP ishlayveradi,
    // migratsiya qo'llanilgach himoya aktiv bo'ladi).
    try {
      const sb = db();
      const { data: st } = await sb
        .from('auth_totp').select('last_counter').eq('id', 'owner').maybeSingle();
      const last = (st?.last_counter as number | undefined) ?? 0;
      if (counter <= last) {
        await recordFailure(rlKey);
        return res.status(401).json({ ok: false, error: 'Kod allaqachon ishlatilgan' });
      }
      await sb.from('auth_totp').upsert(
        { id: 'owner', last_counter: counter, used_at: new Date().toISOString() },
        { onConflict: 'id' },
      );
    } catch (e) {
      console.error(`[login] totp replay-guard skipped: ${(e as Error).message}`);
    }
  }

  await recordSuccess(rlKey);
  const { token, exp } = issueSession();
  return res.status(200).json({ ok: true, token, exp, level: 'owner', twofa: Boolean(totpSecret) });
}

// --- Bitta cheklangan ADMIN (login + parol) ----------------------------------
// Hisob env'da: ADMIN_LOGIN va ADMIN_PASSWORD. Rol/baza yo'q — bitta hisob.
async function loginAdmin(res: VercelResponse, b: Body, rlKey: string) {
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
  // Default/placeholder parol bilan kirishga ruxsat bermaymiz — haqiqiy parol o'rnatilsin.
  if (looksLikePlaceholder(expPassword)) {
    return res.status(500).json({ ok: false, error: 'ADMIN_PASSWORD hali sozlanmagan (default qiymat)' });
  }
  // #46: imzo kaliti (SESSION_SECRET yoki ADMIN_SECRET) bo'lmasa, chiqarilgan admin tokeni
  // HECH QACHON tasdiqlanmaydi (admin har doim 401 oladi). Aniq xato bilan to'xtatamiz.
  if (!process.env.SESSION_SECRET && !process.env.ADMIN_SECRET) {
    return res.status(500).json({ ok: false, error: 'SESSION_SECRET/ADMIN_SECRET sozlanmagan' });
  }

  // Ikkala maydon ham doimiy-vaqtli solishtiriladi (enumeration'ga qarshi bir xil xato).
  const okLogin = safeEq(login, expLogin);
  const okPassword = safeEq(password, expPassword);
  if (!okLogin || !okPassword) {
    await recordFailure(rlKey);
    return res.status(401).json(FAIL);
  }

  await recordSuccess(rlKey);
  const { token, exp } = issueAdminSession(login);
  return res.status(200).json({ ok: true, token, exp, level: 'admin', name: login });
}
