import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead } from '../lib/auth.js';

// Jonli tahdidlar oqimi — oxirgi xavfli/shubhali skanlar (v_recent_threats).
// Panel buni har bir necha soniyada so'rab, yangi yozuvlarni yuqoriga qo'shadi.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const { data, error } = await db()
    .from('v_recent_threats')
    .select('*')
    .order('scanned_at', { ascending: false })
    .limit(50);

  if (error) { console.error(`[feed] db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  return res.status(200).json({ ok: true, feed: data ?? [] });
}
