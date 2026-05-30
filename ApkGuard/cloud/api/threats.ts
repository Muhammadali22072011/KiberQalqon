import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead } from '../lib/auth.js';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();
  const { data, error } = await sb
    .from('threats')
    .select('apk_hash, package_name, app_label, category, severity, seen_count, first_seen, last_seen, notes')
    .order('last_seen', { ascending: false })
    .limit(100);

  if (error) return res.status(500).json({ ok: false, error: error.message });

  const threats = data ?? [];

  // APK namunasi serverda bor bo'lsa — yuklab olish uchun imzolangan URL qo'shamiz.
  // Bitta so'rovda hammasiga (createSignedUrls); namuna yo'q hash'lar uchun signedUrl=null
  // qaytadi va sample_url null bo'ladi. ?download — brauzer ko'rsatmasdan yuklab oladi.
  const urlByHash: Record<string, string> = {};
  if (threats.length) {
    const paths = threats.map((t) => `${t.apk_hash}.apk`);
    const { data: signed, error: sErr } = await sb.storage
      .from('malware-samples')
      .createSignedUrls(paths, 3600, { download: true });
    if (sErr) {
      console.error(`[threats] createSignedUrls failed: ${sErr.message}`);
    } else {
      for (const s of signed ?? []) {
        if (s.signedUrl && !s.error && s.path) {
          urlByHash[s.path.replace(/\.apk$/i, '')] = s.signedUrl;
        }
      }
    }
  }

  const out = threats.map((t) => ({ ...t, sample_url: urlByHash[t.apk_hash] ?? null }));
  return res.status(200).json({ ok: true, threats: out });
}
