import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead } from '../lib/auth.js';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });

  // ?public=1 → landing uchun OCHIQ (auth'siz) yig'ma raqamlar. FAQAT umumiy sonlar:
  // device token / IP / shaxsiy ma'lumot CHIQMAYDI (hudud nomi ommaviy — telemetriyada bor).
  if (req.query.public === '1') {
    const sbp = db();
    const [devCount, scanCount, blockedCount, devs] = await Promise.all([
      sbp.from('devices').select('*', { count: 'exact', head: true }),
      sbp.from('scans').select('*', { count: 'exact', head: true }),
      sbp.from('scans').select('*', { count: 'exact', head: true }).eq('verdict', 'danger'),
      sbp.from('devices').select('city, last_verdict').limit(5000),
    ]);
    const regionMap: Record<string, { region: string; devices: number; danger: number }> = {};
    for (const d of (devs.data ?? []) as Array<{ city?: string | null; last_verdict?: string | null }>) {
      const r = (d.city && d.city.trim()) || 'Boshqa';
      const e = regionMap[r] ?? (regionMap[r] = { region: r, devices: 0, danger: 0 });
      e.devices++;
      if (d.last_verdict === 'danger') e.danger++;
    }
    const regions = Object.values(regionMap).sort((a, b) => b.devices - a.devices).slice(0, 8);
    res.setHeader('Cache-Control', 'public, max-age=60');
    return res.status(200).json({
      ok: true,
      pub: {
        devices: devCount.count ?? 0,
        scans: scanCount.count ?? 0,
        blocked: blockedCount.count ?? 0,
        regionsCount: Object.keys(regionMap).length,
        regions,
      },
    });
  }

  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();

  // ?perf=1 → tekshiruv tezligi (scan-perf shu yerga birlashtirildi: Vercel Hobby 12-funksiya
  // limitidan oshib ketmaslik uchun). Panel "Tekshiruv tezligi" widjeti `/api/stats?perf=1` chaqiradi.
  if (req.query.perf === '1') {
    const { data: stat, error: statErr } = await sb
      .from('v_stats_today')
      .select('median_duration_ms, p95_duration_ms, avg_duration_ms, perf_count')
      .single();
    if (statErr) return res.status(500).json({ ok: false, error: statErr.message });

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

  const { data, error } = await sb.from('v_stats_today').select('*').single();
  if (error) return res.status(500).json({ ok: false, error: error.message });
  return res.status(200).json({ ok: true, stats: data });
}
