import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkDeviceSecret } from '../../lib/auth.js';
import { readGeo, jitterGeo } from '../../lib/geo.js';

type Body = {
  device_token: string;
  name?: string;
  android_ver?: string;
  app_ver?: string;
};

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const b = req.body as Body;
  if (!b?.device_token || b.device_token.length < 16) {
    return res.status(400).json({ ok: false, error: 'bad token' });
  }

  const row: Record<string, unknown> = {
    device_token: b.device_token,
    name: b.name ?? null,
    android_ver: b.android_ver ?? null,
    app_ver: b.app_ver ?? null,
    last_seen: new Date().toISOString(),
  };

  // Geo — Vercel IP sarlavhalaridan (mavjud bo'lsa). Null bo'lsa eski
  // qiymatni ustiga yozmaymiz (lokal dev'da sarlavhalar bo'lmaydi).
  const geo = jitterGeo(readGeo(req), b.device_token);
  if (geo.country != null) row.country = geo.country;
  if (geo.city != null) row.city = geo.city;
  if (geo.lat != null) row.lat = geo.lat;
  if (geo.lng != null) row.lng = geo.lng;

  const { data, error } = await db()
    .from('devices')
    .upsert(row, { onConflict: 'device_token' })
    .select('id')
    .single();

  if (error) return res.status(500).json({ ok: false, error: error.message });
  return res.status(200).json({ ok: true, device_id: data?.id });
}
