import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead, checkDeviceSecret } from '../lib/auth.js';
import { createHmac } from 'crypto';

/**
 * Imzo: base64url(payloadJSON) "." base64url(HMAC-SHA256(payloadB64, CONFIG_SIGNING_SECRET)).
 * Mijozda CloudBlacklist.kt AYNAN shu formatni tekshiradi (RemoteConfig.kt bilan bir xil).
 */
function signEnvelope(obj: unknown): string {
  const key = process.env.CONFIG_SIGNING_SECRET || '';
  const payloadB64 = Buffer.from(JSON.stringify(obj), 'utf8').toString('base64url');
  const sig = createHmac('sha256', key).update(payloadB64).digest('base64url');
  return `${payloadB64}.${sig}`;
}

// CLOUD-01: mashhur/tizim/o'zimiznikidir paketlar feed orqali HECH QACHON bloklanmaydi.
// Ro'yxat IconImpersonationDetector.PROTECTED_PACKAGES bilan bir xil (kanonik nomlar).
const NEVER_BLOCK_PACKAGES = new Set<string>([
  'com.kiberqalqon', 'com.kiberqalqon.debug',
  'org.telegram.messenger', 'org.thunderdog.challegram', 'com.whatsapp',
  'uz.click.evo', 'uz.dida.payme', 'uz.uzcard.uzcard', 'uz.dida.smartbank',
  'uz.mobiuz.android', 'uz.beeline.odp', 'uz.ums.tenge',
  'com.google.android.apps.nbu.paisa.user', 'com.android.chrome', 'com.google.android.gm',
]);
const NEVER_BLOCK_PREFIXES = [
  'com.google.android', 'com.android.', 'com.samsung.', 'com.sec.', 'com.miui.', 'com.apkguard',
];
function isNeverBlockPackage(pkg: string): boolean {
  const p = pkg.toLowerCase();
  if (NEVER_BLOCK_PACKAGES.has(p)) return true;
  return NEVER_BLOCK_PREFIXES.some((pre) => p.startsWith(pre));
}

/**
 * GET /api/threats
 *
 *  • Panel (canRead) → to'liq ko'rinish (sample_url bilan), admin jadvali uchun.
 *  • Qurilma (x-device-secret, yoki ?feed=1) → IMZOLANGAN minimal blacklist "feed"
 *    {v, ts, hashes:[{h,f}], packages:[{p,f}]}. CloudBlacklist.kt uni ThreatDb'ga
 *    qo'shadi → yangi troyan ilovani YANGILAMASDAN bloklanadi. Faqat yuqori ishonchli
 *    (severity high/critical) yozuvlar. Qurilma HECH QACHON sample_url'larni ko'rmaydi.
 *
 * threats jadvalida sertifikat ustuni YO'Q → feed faqat hash + package beradi
 * (sertifikatlar mijozda assets/malicious_certs.txt orqali qoladi).
 */
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });

  const isAdmin = canRead(req);
  const isDevice = checkDeviceSecret(req);
  if (!isAdmin && !isDevice) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();
  // CLOUD-01: bitta soxta "danger" yuklama butun parkni bloklab qo'ymasligi uchun feed'ga
  // faqat KAMIDA shuncha HAR XIL qurilmada tasdiqlangan tahdid tushadi.
  const MIN_FEED_DEVICES = 2;

  // ===== Qurilma uchun imzolangan blacklist feed =====
  // Qurilma (admin emas) DOIM shu yerga tushadi — sample_url'li to'liq ko'rinishga kira olmaydi.
  if (req.query.feed === '1' || (isDevice && !isAdmin)) {
    // Imzo kaliti yo'q bo'lsa — imzolanmagan feed bermaymiz (mijoz assets bazasida qoladi).
    if (!process.env.CONFIG_SIGNING_SECRET) {
      return res.status(200).json({ ok: false, error: 'unconfigured' });
    }
    // Korroboratsiya: kamida MIN_FEED_DEVICES ta HAR XIL qurilmada tasdiqlangan tahdidlar
    // (supabase/11_feed_corroboration.sql dagi RPC). seen_count YARAMAYDI — u har yuklamada
    // oshadi va soxtalashtirilishi mumkin; distinct device_id esa haqiqiy korroboratsiya.
    type FeedRow = { apk_hash?: string | null; package_name?: string | null; category?: string | null; last_seen?: string | null };
    let rows: FeedRow[] = [];
    const rpc = await sb.rpc('corroborated_threats', { min_devices: MIN_FEED_DEVICES });
    if (!rpc.error) {
      rows = (rpc.data ?? []) as FeedRow[];
    } else {
      // RPC hali migratsiya qilinmagan bo'lsa — xavfsizroq degradatsiya: kamida 2 marta
      // ko'rilgan (seen_count>=2) yozuvlar. To'liq korroboratsiya emas, lekin "1 yuklama=blok" emas.
      console.error(`[threats] corroborated_threats RPC failed, degraded fallback: ${rpc.error.message}`);
      const fb = await sb
        .from('threats')
        .select('apk_hash, package_name, category, severity, seen_count, last_seen')
        .in('severity', ['high', 'critical'])
        .gte('seen_count', MIN_FEED_DEVICES)
        .order('last_seen', { ascending: false })
        .limit(2000);
      if (fb.error) return res.status(500).json({ ok: false, error: fb.error.message });
      rows = (fb.data ?? []) as FeedRow[];
    }

    const hashes = rows
      .filter((t) => t.apk_hash)
      .map((t) => ({ h: String(t.apk_hash).toLowerCase(), f: t.category || 'Cloud.feed' }));
    // Paketlar: mashhur/o'zimiznikidir paketlarni HECH QACHON feed orqali bloklamaymiz
    // (korroboratsiyalangan zararli hash tasodifan benign paket nomini olib yursa ham).
    const packages = rows
      .filter((t) => t.package_name && !isNeverBlockPackage(String(t.package_name)))
      .map((t) => ({ p: String(t.package_name).toLowerCase(), f: t.category || 'Cloud.feed' }));
    // Monotonik versiya — feed mazmuniga bog'liq (eng katta last_seen, sekundlarda). Avval qattiq
    // `v:1` edi → mijozdagi rollback-guard (remoteV < KEY_V) hech qachon ishlamasdi. Endi yangi tahdid
    // kelsa v oshadi; eski (replay) feed esa past v bilan kelib rad etiladi.
    const maxSeen = rows.reduce((m, t) => {
      const sec = t.last_seen ? Math.floor(new Date(t.last_seen).getTime() / 1000) : 0;
      return sec > m ? sec : m;
    }, 0);
    const payload = { v: maxSeen || 1, ts: Date.now(), hashes, packages };
    res.setHeader('Cache-Control', 'public, max-age=300');
    return res.status(200).json({ ok: true, feed: signEnvelope(payload) });
  }

  // ===== Panel uchun to'liq ko'rinish (avvalgidek) =====
  const { data, error } = await sb
    .from('threats')
    .select('apk_hash, package_name, app_label, category, severity, seen_count, first_seen, last_seen, notes')
    .order('last_seen', { ascending: false })
    .limit(100);

  if (error) return res.status(500).json({ ok: false, error: error.message });

  const threats = data ?? [];

  // APK namunasi serverda bor bo'lsa — yuklab olish uchun imzolangan URL qo'shamiz.
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
