import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead } from '../lib/auth.js';

// Tekshiruv tezligi (scan-speed) — panel'dagi "Tekshiruv tezligi" widjeti uchun.
// Mediana/p95/o'rtacha v_stats_today view'idan o'qiladi (percentile_cont SQL ichida
// hisoblanadi — bu yerda emas), eng sekin ilovalar alohida so'rov bilan olinadi.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();

  // 1) Bugungi tezlik statistikasi (view allaqachon scan_duration_ms > 0 bo'yicha filtrlaydi).
  const { data: stat, error: statErr } = await sb
    .from('v_stats_today')
    .select('median_duration_ms, p95_duration_ms, avg_duration_ms, perf_count')
    .single();
  if (statErr) return res.status(500).json({ ok: false, error: statErr.message });

  // 2) Eng sekin 8 ta tekshiruv — oxirgi 24 soat, faqat o'lchangan skanlar.
  const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const { data: slow, error: slowErr } = await sb
    .from('scans')
    .select('app_label, package_name, apk_size, scan_duration_ms')
    .gt('scan_duration_ms', 0)
    .gte('scanned_at', since)
    .order('scan_duration_ms', { ascending: false })
    .limit(8);
  if (slowErr) return res.status(500).json({ ok: false, error: slowErr.message });

  const perf = {
    median_ms: Math.round(Number(stat?.median_duration_ms) || 0),
    p95_ms: Math.round(Number(stat?.p95_duration_ms) || 0),
    avg_ms: Math.round(Number(stat?.avg_duration_ms) || 0),
    count: Number(stat?.perf_count) || 0,
    slowest: (slow ?? []).map((r) => ({
      app_label: r.app_label,
      package_name: r.package_name,
      apk_size: r.apk_size,
      duration_ms: r.scan_duration_ms,
    })),
  };

  return res.status(200).json({ ok: true, perf });
}
