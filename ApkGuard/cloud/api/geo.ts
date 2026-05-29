import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { checkAdminSecret } from '../lib/auth.js';

// Xarita nuqtalari — har bir qurilma bitta nuqta.
// Faqat ADMIN_SECRET bilan (panel). Qurilma siri bilan bu yerga kirilmaydi.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const { data, error } = await db()
    .from('v_map_points')
    .select('*')
    .order('risk_score', { ascending: false })
    .limit(2000);

  if (error) return res.status(500).json({ ok: false, error: error.message });
  return res.status(200).json({ ok: true, points: data ?? [] });
}
