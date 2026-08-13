import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead, checkAdminSecret } from '../lib/auth.js';

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

  // ?audit=1 → panel amallari jurnali (admin_audit_log, migratsiya 15) — FAQAT EGASI.
  // Alohida funksiya emas (Vercel Hobby 12-funksiya limiti) — threats?feed=1 uslubida branch.
  if (req.query.audit === '1') {
    if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
    const { data, error } = await sb
      .from('admin_audit_log')
      .select('id, at, actor, action, detail, ip')
      .order('at', { ascending: false })
      .limit(200);
    if (error) { console.error(`[stats] audit db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, audit: data ?? [] });
  }

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

  // ?series=1 → oxirgi 14 kunlik trend (skanlar/kun, verdict bo'yicha) + tizim salomatligi.
  // Alohida funksiya EMAS (Vercel Hobby 12-funksiya limiti) — stats?perf=1 uslubidagi branch.
  // Migratsiyasiz: xom `scans` qatorlarini (scanned_at, verdict) o'qib, JS'da kun bo'yicha
  // guruhlaymiz. Bo'sh kunlar ham 0 bilan chiqadi (grafik uzuq bo'lmasligi uchun).
  if (req.query.series === '1') {
    const DAYS = 14;
    const since = new Date(Date.now() - (DAYS - 1) * 24 * 60 * 60 * 1000);
    since.setUTCHours(0, 0, 0, 0);
    const { data: rows, error: serr } = await sb
      .from('scans')
      .select('scanned_at, verdict')
      .gte('scanned_at', since.toISOString())
      .order('scanned_at', { ascending: true })
      .limit(20000);
    if (serr) { console.error(`[stats] series db error: ${serr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

    type Day = { day: string; total: number; danger: number; suspicious: number; safe: number };
    const buckets = new Map<string, Day>();
    for (let i = 0; i < DAYS; i++) {
      const key = new Date(since.getTime() + i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      buckets.set(key, { day: key, total: 0, danger: 0, suspicious: 0, safe: 0 });
    }
    for (const r of (rows ?? []) as Array<{ scanned_at?: string | null; verdict?: string | null }>) {
      if (!r.scanned_at) continue;
      const b = buckets.get(r.scanned_at.slice(0, 10));
      if (!b) continue;
      b.total++;
      if (r.verdict === 'danger') b.danger++;
      else if (r.verdict === 'suspicious') b.suspicious++;
      else if (r.verdict === 'safe') b.safe++;
    }
    const series = [...buckets.values()];

    // Tizim salomatligi kartasi: oqim yangiligi (oxirgi skan) + oxirgi qurilma aloqasi.
    // db_ok — bu javob qaytdi degani (yuqoridagi so'rovlar muvaffaqiyatli).
    const [lastScan, lastDev] = await Promise.all([
      sb.from('scans').select('scanned_at').order('scanned_at', { ascending: false }).limit(1).maybeSingle(),
      sb.from('devices').select('last_seen').order('last_seen', { ascending: false }).limit(1).maybeSingle(),
    ]);
    const health = {
      db_ok: true,
      last_scan_at: (lastScan.data as { scanned_at?: string } | null)?.scanned_at ?? null,
      last_device_seen: (lastDev.data as { last_seen?: string } | null)?.last_seen ?? null,
    };
    return res.status(200).json({ ok: true, series, health });
  }

  // ?weekly=1 → oxirgi 7 kunlik yig'ma hisobot (panel "Haftalik hisobot" + Telegram yetkazish
  // uchun). Alohida funksiya EMAS (Vercel Hobby 12-funksiya limiti) — stats?series=1 uslubidagi
  // branch. Global yig'indi + eng ko'p uchragan tahdid oilalari. FAQAT EGASI.
  if (req.query.weekly === '1') {
    if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
    const since = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString();
    const [total, danger, susp, safe, devs, fams] = await Promise.all([
      sb.from('scans').select('*', { count: 'exact', head: true }).gte('scanned_at', since),
      sb.from('scans').select('*', { count: 'exact', head: true }).eq('verdict', 'danger').gte('scanned_at', since),
      sb.from('scans').select('*', { count: 'exact', head: true }).eq('verdict', 'suspicious').gte('scanned_at', since),
      sb.from('scans').select('*', { count: 'exact', head: true }).eq('verdict', 'safe').gte('scanned_at', since),
      sb.from('scans').select('device_id').gte('scanned_at', since).not('device_id', 'is', null).limit(20000),
      sb.from('threats').select('family, app_label, seen_count').gte('last_seen', since).order('seen_count', { ascending: false }).limit(10),
    ]);
    const activeDevices = new Set(((devs.data ?? []) as Array<{ device_id: string }>).map((r) => r.device_id)).size;
    const topThreats = ((fams.data ?? []) as Array<{ family?: string | null; app_label?: string | null; seen_count?: number }>)
      .map((t) => ({ name: t.family || t.app_label || '—', count: t.seen_count ?? 0 }));
    return res.status(200).json({
      ok: true,
      weekly: {
        since,
        total: total.count ?? 0,
        danger: danger.count ?? 0,
        suspicious: susp.count ?? 0,
        safe: safe.count ?? 0,
        active_devices: activeDevices,
        top_threats: topThreats,
      },
    });
  }

  // ?tgreg=1 → TELEGRAM RO'YXATI analitikasi (foydalanuvchilar, voronka, trend).
  // Alohida funksiya EMAS (Vercel Hobby 12-funksiya limiti) — stats?series=1 uslubidagi branch.
  // Migratsiyasiz agregatsiya: xom qatorlar o'qilib JS'da hisoblanadi (jadval kichik —
  // bitta qator = bitta ro'yxatdan o'tish urinishi).
  //
  // MAXFIYLIK: telefon raqami — shaxsiy ma'lumot. To'liq raqamni FAQAT EGASI ko'radi;
  // cheklangan admin niqoblangan ko'rinishni oladi (+99890***1234). Guruh a'zolari
  // ro'yxatidan farqli o'laroq bu yerda butun foydalanuvchi bazasi turibdi — niqob arzon,
  // sizib chiqish narxi esa katta.
  if (req.query.tgreg === '1') {
    const owner = checkAdminSecret(req);
    const { data: rows, error: tErr } = await sb
      .from('tg_registrations')
      .select('chat_id, tg_username, full_name, phone, step, blocked, created_at, linked_at, done_at, device_token')
      .order('id', { ascending: false })
      .limit(20000);
    if (tErr) { console.error(`[stats] tgreg db error: ${tErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

    type Row = {
      chat_id: number | null; tg_username: string | null; full_name: string | null;
      phone: string | null; step: string; blocked: boolean;
      created_at: string; linked_at: string | null; done_at: string | null;
      device_token: string | null;
    };
    const all = (rows ?? []) as Row[];

    // Voronka — qadamlar KUMULYATIV: /start bosgan hamma "started"da, ismini yozgan
    // hamma "named"da va h.k. Aks holda "await_phone"dagi 3 kishi tugatganlardan
    // ko'proqdek ko'rinardi (ular allaqachon o'sha qadamdan o'tgan).
    const started = all.length;
    const linked = all.filter((r) => r.step !== 'new').length;                       // Telegram'ni ochdi
    const named = all.filter((r) => r.step === 'await_phone' || r.step === 'done').length;
    const done = all.filter((r) => r.step === 'done').length;
    const blocked = all.filter((r) => r.blocked).length;

    // "Osilib qolgan" — Telegram'ni ochgan, lekin 1 soatdan beri tugatmagan.
    const hourAgo = Date.now() - 3600 * 1000;
    const stuck = all.filter(
      (r) => (r.step === 'await_name' || r.step === 'await_phone') &&
        new Date(r.linked_at ?? r.created_at).getTime() < hourAgo,
    ).length;

    // Noyob odamlar (bitta odam bir necha qurilmada ro'yxatdan o'tishi mumkin).
    const uniqueChats = new Set(all.filter((r) => r.step === 'done' && r.chat_id != null).map((r) => r.chat_id)).size;

    // 14 kunlik trend: boshlanganlar va tugatganlar.
    const DAYS = 14;
    const from = new Date();
    from.setUTCHours(0, 0, 0, 0);
    from.setUTCDate(from.getUTCDate() - (DAYS - 1));
    const buckets = new Map<string, { day: string; started: number; done: number }>();
    for (let i = 0; i < DAYS; i++) {
      const key = new Date(from.getTime() + i * 86400000).toISOString().slice(0, 10);
      buckets.set(key, { day: key, started: 0, done: 0 });
    }
    for (const r of all) {
      const b1 = buckets.get(r.created_at.slice(0, 10));
      if (b1) b1.started++;
      if (r.done_at) {
        const b2 = buckets.get(r.done_at.slice(0, 10));
        if (b2) b2.done++;
      }
    }
    const today = new Date().toISOString().slice(0, 10);
    const todayRow = buckets.get(today) ?? { day: today, started: 0, done: 0 };

    // O'rtacha tugatish vaqti (linked → done), sekundlarda — median.
    const durations = all
      .filter((r) => r.done_at && r.linked_at)
      .map((r) => (new Date(r.done_at!).getTime() - new Date(r.linked_at!).getTime()) / 1000)
      .filter((s) => s >= 0 && s < 24 * 3600)
      .sort((a, b) => a - b);
    const medianSec = durations.length ? Math.round(durations[Math.floor(durations.length / 2)]) : null;

    const mask = (p: string | null): string | null => {
      if (!p) return null;
      if (owner) return p;
      return p.length > 8 ? `${p.slice(0, 6)}***${p.slice(-2)}` : '***';
    };

    const recent = all
      .filter((r) => r.step === 'done')
      .slice(0, 300)
      .map((r) => ({
        chat_id: r.chat_id,
        tg_username: r.tg_username,
        full_name: r.full_name,
        phone: mask(r.phone),
        done_at: r.done_at,
        blocked: r.blocked,
        // Qurilma tokeni panelga OCHILMAYDI (u yozuv kaliti) — faqat bog'langanligi.
        has_device: Boolean(r.device_token),
      }));

    const pending = all
      .filter((r) => r.step === 'await_name' || r.step === 'await_phone')
      .slice(0, 100)
      .map((r) => ({
        chat_id: r.chat_id,
        tg_username: r.tg_username,
        full_name: r.full_name,
        step: r.step,
        linked_at: r.linked_at,
      }));

    return res.status(200).json({
      ok: true,
      tgreg: {
        totals: { started, linked, named, done, blocked, stuck, unique_people: uniqueChats },
        today: { started: todayRow.started, done: todayRow.done },
        median_complete_sec: medianSec,
        series: [...buckets.values()],
        recent,
        pending,
        masked: !owner,
      },
    });
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
