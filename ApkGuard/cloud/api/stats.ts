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
      sbp.from('devices').select('city, lat, lng, last_verdict').limit(5000),
    ]);
    // Avval xom `city` bo'yicha guruhlanardi — natija: inglizcha exonimlar ("Tashkent" vs
    // "Toshkent"), dublikatlar va shaharlar "hudud" deb sanalardi (regionsCount oshib ketardi).
    // Endi har qurilmani VILOYATGA biriktirib guruhlaymiz (lat/lng → eng yaqin markaz, yoki city nomi).
    const regionMap: Record<string, { region: string; devices: number; danger: number }> = {};
    for (const d of (devs.data ?? []) as Array<{ city?: string | null; lat?: number | null; lng?: number | null; last_verdict?: string | null }>) {
      const r = viloyatOf(d.lat, d.lng, d.city) || 'Boshqa';
      const e = regionMap[r] ?? (regionMap[r] = { region: r, devices: 0, danger: 0 });
      e.devices++;
      if (d.last_verdict === 'danger') e.danger++;
    }
    // "Boshqa" ni hudud sifatida sanamaymiz (qamrab olingan VILOYATLAR soni).
    const namedRegions = Object.keys(regionMap).filter((k) => k !== 'Boshqa').length;
    const regions = Object.values(regionMap).sort((a, b) => b.devices - a.devices).slice(0, 8);
    res.setHeader('Cache-Control', 'public, max-age=60');
    return res.status(200).json({
      ok: true,
      pub: {
        devices: devCount.count ?? 0,
        scans: scanCount.count ?? 0,
        blocked: blockedCount.count ?? 0,
        regionsCount: namedRegions,
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
    if (statErr) { console.error(`[stats] perf db error: ${statErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

    const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
    const { data: slow, error: slowErr } = await sb
      .from('scans')
      .select('app_label, package_name, apk_size, scan_duration_ms')
      .gt('scan_duration_ms', 0)
      .gte('scanned_at', since)
      .order('scan_duration_ms', { ascending: false })
      .limit(8);
    if (slowErr) { console.error(`[stats] slow db error: ${slowErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

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
  if (error) { console.error(`[stats] db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
  return res.status(200).json({ ok: true, stats: data });
}

// 14 viloyat markazi (ommaviy landing statistikasi uchun kompakt snap; src/lib/uzRegions.ts ning
// server tomonidagi yengil nusxasi). lat/lng eng yaqin markazga ~1.5° (kvadrat 2.25) ichida bo'lsa
// shu viloyatga biriktiriladi; aks holda (chet el / rouming) null → "Boshqa".
const VILOYATLAR: { name: string; lat: number; lng: number }[] = [
  { name: 'Toshkent shahri', lat: 41.311, lng: 69.280 },
  { name: 'Toshkent viloyati', lat: 41.000, lng: 69.340 },
  { name: 'Andijon', lat: 40.783, lng: 72.344 },
  { name: "Farg'ona", lat: 40.389, lng: 71.783 },
  { name: 'Namangan', lat: 41.000, lng: 71.670 },
  { name: 'Sirdaryo', lat: 40.380, lng: 68.660 },
  { name: 'Jizzax', lat: 40.116, lng: 67.842 },
  { name: 'Samarqand', lat: 39.654, lng: 66.960 },
  { name: 'Qashqadaryo', lat: 38.860, lng: 65.790 },
  { name: 'Surxondaryo', lat: 37.940, lng: 67.570 },
  { name: 'Buxoro', lat: 39.768, lng: 64.421 },
  { name: 'Navoiy', lat: 40.104, lng: 65.373 },
  { name: 'Xorazm', lat: 41.550, lng: 60.631 },
  { name: "Qoraqalpog'iston", lat: 42.460, lng: 59.610 },
];
const MAX_SNAP_SQ = 2.25;
function viloyatOf(lat?: number | null, lng?: number | null, city?: string | null): string | null {
  if (lat != null && lng != null) {
    const cosLat = Math.cos((lat * Math.PI) / 180) || 1;
    let best: string | null = null;
    let bestD = Infinity;
    for (const v of VILOYATLAR) {
      const d = (lat - v.lat) ** 2 + ((lng - v.lng) * cosLat) ** 2;
      if (d < bestD) { bestD = d; best = v.name; }
    }
    if (bestD <= MAX_SNAP_SQ) return best;
    return null; // O'zbekistondan tashqarida — viloyatga biriktirmaymiz
  }
  // GPS yo'q: city nomi bo'lsa o'shani ko'rsatamiz (xom, lekin hech bo'lmasa dublikat-snap qilmaymiz)
  const c = (city ?? '').trim();
  return c || null;
}
