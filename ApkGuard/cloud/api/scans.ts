import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { checkAdminSecret } from '../lib/auth.js';

// O'qish endpointi — barcha qurilmalarning skan tarixini ko'rsatadi, shuning uchun
// ADMIN_SECRET talab qiladi (DEVICE_SHARED_SECRET har bir APK ichida — u bilan
// boshqalarning ma'lumotini ko'rib bo'lmasligi kerak). Panel/admin uchun.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const limit = clamp(Number(req.query.limit ?? 50), 1, 200);
  const verdict = String(req.query.verdict ?? '');

  let q = db()
    .from('scans')
    .select('id, scanned_at, verdict, app_label, package_name, apk_hash, risk_score, reasons, device_id')
    .order('scanned_at', { ascending: false })
    .limit(limit);

  if (['safe', 'suspicious', 'danger', 'error'].includes(verdict)) {
    q = q.eq('verdict', verdict);
  }

  const { data, error } = await q;
  if (error) { console.error(`[scans] db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  return res.status(200).json({ ok: true, scans: data ?? [] });
}

function clamp(n: number, lo: number, hi: number): number {
  if (!Number.isFinite(n)) return lo;
  return Math.min(Math.max(n, lo), hi);
}
