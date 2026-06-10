import type { VercelRequest } from '@vercel/node';
import { db } from './supabase.js';

/**
 * CLOUD-03: panelga kirishni brute-force'dan himoya — (scope+IP) bo'yicha muvaffaqiyatsiz
 * urinishlarni sanaydi, eksponensial backoff bilan vaqtincha bloklaydi (supabase/12_auth_rate_limit.sql).
 *
 * HAMMASI FAIL-OPEN: jadval bo'lmasa / DB xato bo'lsa hech qachon throw qilmaydi va bloklamaydi —
 * egasini DB nosozligi tufayli o'z panelidan chiqarib qo'ymaslik uchun.
 */

const MAX_FAILS = 5; // shu martadan keyin bloklash boshlanadi
const BASE_LOCK_SEC = 30; // birinchi blok 30s, keyin 60, 120, ... cap gacha
const MAX_LOCK_SEC = 3600; // maksimal 1 soat

/**
 * CL-01: ISHONCHLI klient IP. x-forwarded-for'ning CHAP qiymati klient tomonidan
 * yuborilishi mumkin (Vercel uni oxiriga qo'shadi), shuning uchun uni rate-limit kaliti
 * sifatida ishlatib BO'LMAYDI — brute-force har so'rovda yangi soxta IP yuborib chegarani
 * aylanib o'tardi. Avval x-real-ip (Vercel o'zi qo'yadi, klient o'zgartira olmaydi),
 * bo'lmasa XFF'ning OXIRGI hop'i.
 */
export function trustedIp(req: VercelRequest): string {
  const real = req.headers['x-real-ip'];
  const r = Array.isArray(real) ? real[0] : real;
  if (typeof r === 'string' && r.trim()) return r.trim();
  const xff = req.headers['x-forwarded-for'];
  const x = Array.isArray(xff) ? xff[0] : xff;
  if (typeof x === 'string' && x.trim()) {
    const parts = x.split(',').map((s) => s.trim()).filter(Boolean);
    if (parts.length) return parts[parts.length - 1];
  }
  return 'unknown';
}

export function clientKey(req: VercelRequest, scope: string): string {
  return `${scope}:${trustedIp(req)}`;
}

/** Bloklangan bo'lsa { locked:true, retryAfter } qaytaradi. DB xato → locked:false (fail-open). */
export async function checkLocked(key: string): Promise<{ locked: boolean; retryAfter: number }> {
  try {
    const sb = db();
    const { data } = await sb.from('auth_attempts').select('locked_until').eq('k', key).maybeSingle();
    const lu = data?.locked_until ? new Date(data.locked_until as string).getTime() : 0;
    const now = Date.now();
    if (lu > now) return { locked: true, retryAfter: Math.ceil((lu - now) / 1000) };
    return { locked: false, retryAfter: 0 };
  } catch {
    return { locked: false, retryAfter: 0 };
  }
}

/**
 * Muvaffaqiyatsiz urinishni qayd qiladi; chegaradan oshsa eksponensial backoff bilan bloklaydi.
 *
 * CL-01: inkremen ATOMAR bo'lishi shart. Eski read-modify-write (select keyin upsert) parallel
 * so'rovlarda barchasi fail_count=0 o'qib, hammasi 1 yozardi — lockout ishlamasdi. Endi DB tomonida
 * atomar `record_auth_failure` RPC ishlatamiz (supabase/13_*.sql). RPC bo'lmasa (migratsiya hali
 * qo'llanmagan) — eski yo'lga fail-open qaytamiz (egasini bloklamaslik uchun).
 */
export async function recordFailure(key: string): Promise<void> {
  try {
    const sb = db();
    const { error } = await sb.rpc('record_auth_failure', {
      p_key: key, p_max_fails: MAX_FAILS, p_base_lock: BASE_LOCK_SEC, p_max_lock: MAX_LOCK_SEC,
    });
    if (!error) return; // atomar yo'l muvaffaqiyatli
    // RPC topilmadi/xato → eski (atomar bo'lmagan) zaxira yo'l, best-effort.
    const { data } = await sb.from('auth_attempts').select('fail_count').eq('k', key).maybeSingle();
    const fails = (((data?.fail_count as number | undefined) ?? 0) + 1);
    let lockedUntil: string | null = null;
    if (fails >= MAX_FAILS) {
      const lockSec = Math.min(MAX_LOCK_SEC, BASE_LOCK_SEC * 2 ** (fails - MAX_FAILS));
      lockedUntil = new Date(Date.now() + lockSec * 1000).toISOString();
    }
    await sb
      .from('auth_attempts')
      .upsert({ k: key, fail_count: fails, locked_until: lockedUntil, updated_at: new Date().toISOString() }, { onConflict: 'k' });
  } catch {
    /* best-effort */
  }
}

/** Muvaffaqiyatli kirishda hisobni nolga tushiradi. */
export async function recordSuccess(key: string): Promise<void> {
  try {
    const sb = db();
    await sb
      .from('auth_attempts')
      .upsert({ k: key, fail_count: 0, locked_until: null, updated_at: new Date().toISOString() }, { onConflict: 'k' });
  } catch {
    /* best-effort */
  }
}

/** Sozlangan sirlar zaif bo'lsa loglaydi (default/qisqa). Hard-block QILMAYDI (egasini lockout qilmaslik uchun). */
export function warnWeakSecrets(): void {
  const sec = process.env.ADMIN_SECRET ?? '';
  const pw = process.env.ADMIN_PASSWORD ?? '';
  if (sec && sec.length < 32) console.warn('[auth] ADMIN_SECRET 32 belgidan qisqa — kuchaytiring');
  if (pw && pw.length < 12) console.warn('[auth] ADMIN_PASSWORD 12 belgidan qisqa — kuchaytiring');
}

/** Parol/sir aniq placeholder (default) qiymatmi? Shunday bo'lsa kirishni rad etamiz. */
export function looksLikePlaceholder(value: string): boolean {
  const v = value.trim().toLowerCase();
  return v === '' || v.startsWith('change-me') || v.startsWith('changeme') || v === 'admin' || v === 'password';
}
