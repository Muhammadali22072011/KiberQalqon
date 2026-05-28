import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { checkDeviceSecret } from '../lib/auth.js';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const { data, error } = await db()
    .from('threats')
    .select('apk_hash, package_name, app_label, category, severity, seen_count, first_seen, last_seen, notes')
    .order('last_seen', { ascending: false })
    .limit(100);

  if (error) return res.status(500).json({ ok: false, error: error.message });
  return res.status(200).json({ ok: true, threats: data ?? [] });
}
