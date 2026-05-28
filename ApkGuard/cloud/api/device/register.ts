import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkDeviceSecret } from '../../lib/auth.js';

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

  const { data, error } = await db()
    .from('devices')
    .upsert(
      {
        device_token: b.device_token,
        name: b.name ?? null,
        android_ver: b.android_ver ?? null,
        app_ver: b.app_ver ?? null,
        last_seen: new Date().toISOString(),
      },
      { onConflict: 'device_token' }
    )
    .select('id')
    .single();

  if (error) return res.status(500).json({ ok: false, error: error.message });
  return res.status(200).json({ ok: true, device_id: data?.id });
}
