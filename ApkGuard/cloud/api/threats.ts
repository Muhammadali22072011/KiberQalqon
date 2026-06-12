import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead, checkAdminSecret, checkDeviceSecret } from '../lib/auth.js';
import { audit } from '../lib/audit.js';
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

// B1 (link checker): domen feed allowlist'i — gov.uz, *.gov.uz va ma'lum O'zbekiston bank/
// fintech domenlari HECH QACHON qora ro'yxatga tushmaydi (bitta soxta "device_report" butun
// parkning bank saytini bloklab qo'ymasligi uchun — CLOUD-01 mantig'ining domen oynasi).
// KnownBanks.kt + AppReputation.TRUSTED_EXACT bilan mosligi: payme/click/uzcard/kapitalbank...
const NEVER_FEED_DOMAINS = new Set<string>([
  'gov.uz', 'soliq.uz', 'my.gov.uz',
  'payme.uz', 'click.uz', 'uzcard.uz', 'humo.uz', 'oson.uz', 'paynet.uz', 'apelsin.uz',
  'kapitalbank.uz', 'uzumbank.uz', 'tbcbank.uz', 'hamkorbank.uz', 'agrobank.uz',
  'ipakyulibank.uz', 'infinbank.uz', 'davrbank.uz', 'anorbank.uz', 'asakabank.uz',
  'qishloqqurilishbank.uz',
]);
const NEVER_FEED_DOMAIN_SUFFIXES = ['.gov.uz'];
function isNeverFeedDomain(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, '');
  if (NEVER_FEED_DOMAINS.has(h)) return true;
  // Suffiks: bank/gov domenining istalgan subdomeni ham himoyalangan.
  if (NEVER_FEED_DOMAIN_SUFFIXES.some((suf) => h.endsWith(suf))) return true;
  for (const d of NEVER_FEED_DOMAINS) {
    if (h.endsWith(`.${d}`)) return true;
  }
  return false;
}

// Ommaviy suffikslar (eTLD) + ko'p-ijarali bepul hosting zonalari. Bularning O'ZINI qora
// ro'yxatga qo'shib bo'lmaydi — telefonda suffiks-yurish (MaliciousDomains/VpnFilterService)
// butun zonani bloklab qo'yardi (har *.netlify.app DANGER). Mijozdagi PUBLIC_SUFFIXES bilan mos.
const PUBLIC_SUFFIXES = new Set<string>([
  'com.uz', 'co.uz', 'org.uz', 'net.uz', 'gov.uz', 'mil.uz', 'ac.uz', 'edu.uz',
  'co.ru', 'com.ru', 'co.uk', 'org.uk', 'gov.uk', 'com.tr', 'co.jp',
  'github.io', 'netlify.app', 'vercel.app', 'web.app', 'firebaseapp.com',
  'blogspot.com', 'telegra.ph', 'pages.dev', 'workers.dev', 'glitch.me',
  'herokuapp.com', 'repl.co', '000webhostapp.com', 'weebly.com', 'wixsite.com',
]);

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
// Domen validatsiyasi: kichik harf, sxema/yo'l/portsiz, kamida bitta nuqta, faqat
// [a-z0-9.-] (punycode xn-- ham shu alifboda). Bo'sh yorliq/chetki defis rad etiladi.
function normalizeDomain(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  let d = raw.trim().toLowerCase();
  d = d.replace(/^https?:\/\//, '').replace(/^\/\//, '');
  d = d.split('/')[0].split('?')[0].split('#')[0].split('@').pop() || '';
  d = d.split(':')[0].replace(/\.$/, '');
  if (d.length < 4 || d.length > 253) return null;
  if (!/^[a-z0-9.-]+$/.test(d) || !d.includes('.')) return null;
  if (d.split('.').some((l) => !l || l.startsWith('-') || l.endsWith('-'))) return null;
  return d;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  // ===== POST — domen qora ro'yxatini boshqarish (FAQAT EGASI) =====
  // Alohida funksiya emas (Vercel Hobby 12-funksiya limiti) — stats?audit=1 uslubida branch.
  if (req.method === 'POST') {
    if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
    const body = (req.body ?? {}) as { action?: string; domain?: unknown; category?: string; severity?: string };
    const domain = normalizeDomain(body.domain);
    if (!domain) return res.status(400).json({ ok: false, error: 'domen noto\'g\'ri' });

    if (body.action === 'add_domain') {
      // Allowlist (gov.uz / bank domenlari) hech qachon qora ro'yxatga tushmaydi — xato
      // bosish butun parkning bank saytini bloklab qo'ymasin.
      if (isNeverFeedDomain(domain)) {
        return res.status(400).json({ ok: false, error: 'himoyalangan domen (bank/gov)' });
      }
      // Ommaviy suffiks / bepul-hosting zonasining O'ZINI bloklab bo'lmaydi (co.uz, netlify.app…)
      // — aks holda telefonda butun zona (har subdomen) bloklanardi. Aniq saytni kiriting.
      if (PUBLIC_SUFFIXES.has(domain)) {
        return res.status(400).json({ ok: false, error: 'butun zonani bloklab bo\'lmaydi — aniq domen kiriting' });
      }
      const severity = ['low', 'medium', 'high', 'critical'].includes(body.severity || '')
        ? (body.severity as string) : 'high';
      const category = (body.category || 'phishing').slice(0, 40);
      const { error } = await db().from('threat_domains').upsert(
        { domain, category, severity, source: 'owner', last_seen: new Date().toISOString() },
        { onConflict: 'domain' },
      );
      if (error) { console.error(`[threats] domain upsert: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'domain_add', `${domain} (${severity}/${category})`);
      return res.status(200).json({ ok: true });
    }

    if (body.action === 'delete_domain') {
      const { error } = await db().from('threat_domains').delete().eq('domain', domain);
      if (error) { console.error(`[threats] domain delete: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'domain_del', domain);
      return res.status(200).json({ ok: true });
    }

    return res.status(400).json({ ok: false, error: 'action' });
  }

  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });

  const isAdmin = canRead(req);
  const isDevice = checkDeviceSecret(req);
  if (!isAdmin && !isDevice) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();

  // ===== Panel: domen qora ro'yxati jadvali (ko'rish — egasi ham, admin ham) =====
  if (req.query.domains === '1' && isAdmin) {
    const { data, error } = await sb
      .from('threat_domains')
      .select('domain, category, severity, source, first_seen, last_seen')
      .order('last_seen', { ascending: false })
      .limit(500);
    if (error) { console.error(`[threats] domains db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, domains: data ?? [] });
  }
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
      if (fb.error) { console.error(`[threats] feed fallback db error: ${fb.error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
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

    // B1 — domen feed'i (URL/link checker uchun). threat_domains jadvalidan high/critical
    // yozuvlar. Allowlist (gov.uz/bank) chiqarib tashlanadi. FAIL-SOFT: jadval yo'q bo'lsa
    // (migratsiya hali ishlamagan) — try/catch domenlarni shunchaki o'tkazib yuboradi, feed
    // hash/paket bilan ishlayveradi.
    //
    // CLOUD-01 (domen oynasi): domenlar QASDDAN faqat EGA tomonidan qo'lda kiritiladi — hozircha
    // hech bir qurilma-yo'nalishli endpoint threat_domains'ga YOZMAYDI (upload.ts faqat devices/
    // scans/threats'ga yozadi), shuning uchun hash/paketdagi kabi korroboratsiya gate'i shart emas.
    // Lekin himoyaga: kelajakda "device_report" yozish yo'li qo'shilsa, korroboratsiyasiz zaharlangan
    // satr DANGER feed'ga oqib ketmasligi uchun selekt EGA-manbasi bilan cheklanadi (source owner/null).
    type DomainRow = { domain?: string | null; category?: string | null; last_seen?: string | null };
    let domains: { d: string; f: string }[] = [];
    let domainMaxSeen = 0;
    try {
      const dq = await sb
        .from('threat_domains')
        .select('domain, category, severity, last_seen')
        .in('severity', ['high', 'critical'])
        .or('source.is.null,source.eq.owner')
        .order('last_seen', { ascending: false })
        .limit(2000);
      if (dq.error) {
        // Jadval yo'q yoki o'qib bo'lmadi — domensiz davom etamiz (fail-soft).
        console.error(`[threats] threat_domains skipped: ${dq.error.message}`);
      } else {
        const drows = (dq.data ?? []) as DomainRow[];
        domains = drows
          .filter((t) => t.domain && !isNeverFeedDomain(String(t.domain)))
          .map((t) => ({ d: String(t.domain).toLowerCase().replace(/\.$/, ''), f: t.category || 'Cloud.feed' }));
        domainMaxSeen = drows.reduce((m, t) => {
          const sec = t.last_seen ? Math.floor(new Date(t.last_seen).getTime() / 1000) : 0;
          return sec > m ? sec : m;
        }, 0);
      }
    } catch (e) {
      console.error(`[threats] threat_domains exception: ${(e as Error).message}`);
    }

    // Monotonik versiyalar — feed mazmuniga bog'liq (eng katta last_seen, sekundlarda). Avval qattiq
    // `v:1` edi → mijozdagi rollback-guard (remoteV < KEY_V) hech qachon ishlamasdi. Endi yangi tahdid
    // kelsa versiya oshadi; eski (replay) feed esa past versiya bilan kelib rad etiladi.
    //
    // MUHIM: hash/paket (`v`) va domen (`dv`) versiyalari ALOHIDA. Ilgari ular bitta `max`'ga
    // qo'shilardi → threat_domains o'qishi VAQTINCHA xato bersa (fail-soft), domainMaxSeen 0 ga
    // tushib `v` regress bo'lardi va mijozdagi rollback-guard BUTUN konvertni (yangi hash/paket
    // bilan birga) rad etardi. Endi har bir manba o'z versiyasi bilan mustaqil baholanadi:
    // bir jadvalning vaqtinchalik nosozligi ikkinchisining yangilanishini bloklamaydi.
    const threatMaxSeen = rows.reduce((m, t) => {
      const sec = t.last_seen ? Math.floor(new Date(t.last_seen).getTime() / 1000) : 0;
      return sec > m ? sec : m;
    }, 0);
    const payload = { v: threatMaxSeen || 1, dv: domainMaxSeen, ts: Date.now(), hashes, packages, domains };
    res.setHeader('Cache-Control', 'public, max-age=300');
    return res.status(200).json({ ok: true, feed: signEnvelope(payload) });
  }

  // ===== Panel uchun to'liq ko'rinish (avvalgidek) =====
  const { data, error } = await sb
    .from('threats')
    .select('apk_hash, package_name, app_label, category, severity, seen_count, first_seen, last_seen, notes')
    .order('last_seen', { ascending: false })
    .limit(100);

  if (error) { console.error(`[threats] db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }

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
