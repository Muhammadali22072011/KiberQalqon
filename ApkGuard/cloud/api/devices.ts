import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead } from '../lib/auth.js';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  // CL-02: ilgari .limit(50) jim 50-tadan keyingisini yo'qotardi (panel "50 ta qurilma" deb
  // butun flotni shu deb ko'rsatardi, Excel ham). Endi /api/geo bilan bir xil 2000 limit +
  // aniq count(*) qaytaramiz, panel "ko'rsatilgan / jami" ko'rsata olsin.
  const { data, error, count } = await db()
    .from('v_devices_with_counts')
    .select('*', { count: 'exact' })
    .order('last_seen', { ascending: false })
    .limit(2000);

  if (error) {
    console.error(`[devices] db error: ${error.message}`);
    return res.status(500).json({ ok: false, error: 'db' });
  }
  return res.status(200).json({ ok: true, devices: data ?? [], total: count ?? (data?.length ?? 0) });
}
