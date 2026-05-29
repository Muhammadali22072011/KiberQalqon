import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkAdminSecret } from '../../lib/auth.js';

// Bitta qurilma + oxirgi skanlari — xaritada nuqtaga bosilganda chiqadigan oyna
// uchun. Faqat ADMIN_SECRET bilan. Kalit sifatida device id (uuid) ishlatamiz —
// device_token (yozuv kaliti) panelga umuman ochilmaydi.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const id = Array.isArray(req.query.id) ? req.query.id[0] : req.query.id;
  if (!id || !/^[0-9a-fA-F-]{36}$/.test(id)) {
    return res.status(400).json({ ok: false, error: 'bad id' });
  }

  const sb = db();

  const { data: device, error: dErr } = await sb
    .from('v_devices_with_counts')
    .select('*')
    .eq('id', id)
    .single();
  if (dErr || !device) {
    return res.status(404).json({ ok: false, error: 'not found' });
  }

  // Oxirgi skanlar — popup'dagi "Topilgan tahdidlar" va tarix uchun.
  const { data: scans, error: sErr } = await sb
    .from('scans')
    .select('id, apk_hash, package_name, app_label, verdict, risk_score, reasons, scanned_at')
    .eq('device_id', id)
    .order('scanned_at', { ascending: false })
    .limit(20);
  if (sErr) return res.status(500).json({ ok: false, error: sErr.message });

  return res.status(200).json({ ok: true, device, scans: scans ?? [] });
}
