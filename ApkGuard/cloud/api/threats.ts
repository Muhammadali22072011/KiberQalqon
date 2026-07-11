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
    const body = (req.body ?? {}) as {
      action?: string; domain?: unknown; category?: string; severity?: string;
      apk_hash?: unknown; family?: unknown; review_status?: unknown;
      rule_id?: unknown; target?: unknown; needles?: unknown; min_hits?: unknown; notes?: unknown; muted?: unknown;
      package_name?: unknown; cert_sha256?: unknown; label?: unknown;
    };

    // ── YARA-lite qoida paketlari — bulutdan yangilanadigan dex-satr qoidalari.
    // Ilovani QAYTA CHIQARMASDAN yangi variantni bloklash (feed 30 daqiqada yetadi).
    if (body.action === 'add_rule') {
      const rid = typeof body.rule_id === 'string' ? body.rule_id.trim().toLowerCase() : '';
      if (!/^[a-z0-9_]{2,40}$/.test(rid)) return res.status(400).json({ ok: false, error: 'rule_id: a-z0-9_ (2-40)' });
      const family = typeof body.family === 'string' ? body.family.trim().slice(0, 60) : null;
      const severity = ['low', 'medium', 'high', 'critical'].includes(String(body.severity)) ? String(body.severity) : 'high';
      const target = ['dex_string', 'manifest', 'path'].includes(String(body.target)) ? String(body.target) : 'dex_string';
      // needles — kichik harf, har biri 2..64 belgi, 1..8 ta. Bo'sh/juda uzun rad etiladi.
      const rawNeedles = Array.isArray(body.needles) ? body.needles : [];
      const needles = rawNeedles
        .map((n) => (typeof n === 'string' ? n.trim().toLowerCase() : ''))
        .filter((n) => n.length >= 2 && n.length <= 64);
      if (needles.length < 1 || needles.length > 8) return res.status(400).json({ ok: false, error: '1-8 ta needle (2-64 belgi)' });
      const minHits = Math.max(0, Math.min(needles.length, Math.round(Number(body.min_hits) || 0)));
      const notes = typeof body.notes === 'string' ? body.notes.trim().slice(0, 300) : null;
      const { error } = await db().from('threat_rules').upsert(
        { rule_id: rid, family, severity, target, needles, min_hits: minHits, enabled: true, notes, updated_at: new Date().toISOString() },
        { onConflict: 'rule_id' },
      );
      if (error) { console.error(`[threats] add_rule: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'rule_add', `${rid} (${severity}, ${needles.length} needle)`);
      return res.status(200).json({ ok: true });
    }

    if (body.action === 'delete_rule') {
      const rid = typeof body.rule_id === 'string' ? body.rule_id.trim().toLowerCase() : '';
      if (!/^[a-z0-9_]{2,40}$/.test(rid)) return res.status(400).json({ ok: false, error: 'bad rule_id' });
      const { error } = await db().from('threat_rules').delete().eq('rule_id', rid);
      if (error) { console.error(`[threats] delete_rule: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'rule_del', rid);
      return res.status(200).json({ ok: true });
    }

    // mute_rule — qoidani MASLAHAT (advisory) darajasiga tushiradi (FP-flood o'chirgichi).
    // O'chirmaydi: mijoz qoidani ishlatishda davom etadi, lekin faqat SUSPICIOUS chiqaradi,
    // hech qachon DANGER emas. RemoteConfig'ning "faqat kuchaytir" invariantiga rioya: mute
    // detektsiyani ZAIFLASHTIRISHI mumkin, lekin bu FAQAT egasi qo'lida (imzolangan feed).
    if (body.action === 'mute_rule') {
      const rid = typeof body.rule_id === 'string' ? body.rule_id.trim().toLowerCase() : '';
      if (!/^[a-z0-9_]{2,40}$/.test(rid)) return res.status(400).json({ ok: false, error: 'bad rule_id' });
      const muted = Boolean(body.muted);
      const { error } = await db().from('threat_rules').update({ muted, updated_at: new Date().toISOString() }).eq('rule_id', rid);
      if (error) { console.error(`[threats] mute_rule: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'rule_mute', `${rid} → ${muted}`);
      return res.status(200).json({ ok: true });
    }

    // ── known_good — imzolangan "yaxshi ro'yxat". DOWNGRADE-only (FP tuzatish, DANGER emas).
    if (body.action === 'add_good') {
      const pkg = typeof body.package_name === 'string' ? body.package_name.trim().toLowerCase() : '';
      if (!/^[a-z0-9_.]{3,120}$/.test(pkg) || !pkg.includes('.')) return res.status(400).json({ ok: false, error: 'paket nomi noto\'g\'ri' });
      const certRaw = typeof body.cert_sha256 === 'string' ? body.cert_sha256.trim().toLowerCase().replace(/[:\s]/g, '') : '';
      const cert = certRaw ? (/^[a-f0-9]{64}$/.test(certRaw) ? certRaw : null) : null;
      if (certRaw && !cert) return res.status(400).json({ ok: false, error: 'sert SHA-256 (64 hex) noto\'g\'ri' });
      const label = typeof body.label === 'string' ? body.label.trim().slice(0, 80) : null;
      const { error } = await db().from('known_good').upsert(
        { package_name: pkg, cert_sha256: cert, label },
        { onConflict: 'package_name,cert_sha256' },
      );
      if (error) { console.error(`[threats] add_good: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'good_add', `${pkg}${cert ? ' +cert' : ''}`);
      return res.status(200).json({ ok: true });
    }

    if (body.action === 'delete_good') {
      const pkg = typeof body.package_name === 'string' ? body.package_name.trim().toLowerCase() : '';
      if (!pkg) return res.status(400).json({ ok: false, error: 'paket kerak' });
      const certRaw = typeof body.cert_sha256 === 'string' ? body.cert_sha256.trim().toLowerCase().replace(/[:\s]/g, '') : '';
      let q = db().from('known_good').delete().eq('package_name', pkg);
      q = certRaw ? q.eq('cert_sha256', certRaw) : q.is('cert_sha256', null);
      const { error } = await q;
      if (error) { console.error(`[threats] delete_good: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'good_del', pkg);
      return res.status(200).json({ ok: true });
    }

    // ── #6: tahdid oilasi/kampaniya yorlig'ini belgilash (egasi). Bo'sh → tozalash. ──
    if (body.action === 'set_family') {
      const hash = typeof body.apk_hash === 'string' ? body.apk_hash.toLowerCase() : '';
      if (!/^[a-f0-9]{64}$/.test(hash)) return res.status(400).json({ ok: false, error: 'bad hash' });
      const family = typeof body.family === 'string' ? body.family.trim().slice(0, 60) : '';
      const { error } = await db().from('threats').update({ family: family || null }).eq('apk_hash', hash);
      if (error) { console.error(`[threats] set_family: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'threat_family', `${hash.slice(0, 12)}… → ${family || '(tozalandi)'}`);
      return res.status(200).json({ ok: true });
    }

    // ── #5: namuna vardikti — 'confirmed' (feed'da qoladi) / 'dismissed' (feed'dan chiqadi). ──
    if (body.action === 'set_review') {
      const hash = typeof body.apk_hash === 'string' ? body.apk_hash.toLowerCase() : '';
      if (!/^[a-f0-9]{64}$/.test(hash)) return res.status(400).json({ ok: false, error: 'bad hash' });
      const status = String(body.review_status || '');
      if (!['pending', 'confirmed', 'dismissed'].includes(status)) return res.status(400).json({ ok: false, error: 'bad status' });
      const { error } = await db().from('threats').update({ review_status: status }).eq('apk_hash', hash);
      if (error) { console.error(`[threats] set_review: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'threat_review', `${hash.slice(0, 12)}… → ${status}`);
      return res.status(200).json({ ok: true });
    }

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

  // ===== Panel: YARA-lite qoidalar jadvali (ko'rish/boshqarish — egasi/admin) =====
  if (req.query.rules === '1' && isAdmin) {
    const { data, error } = await sb
      .from('threat_rules')
      .select('rule_id, family, severity, target, needles, min_hits, enabled, muted, notes, created_at, updated_at')
      .order('updated_at', { ascending: false })
      .limit(500);
    if (error) { console.error(`[threats] rules db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, rules: data ?? [] });
  }

  // ===== Panel: known_good "yaxshi ro'yxat" =====
  if (req.query.good === '1' && isAdmin) {
    const { data, error } = await sb
      .from('known_good')
      .select('package_name, cert_sha256, label, created_at')
      .order('created_at', { ascending: false })
      .limit(1000);
    if (error) { console.error(`[threats] good db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, good: data ?? [] });
  }

  // ===== Panel: qoida-sifati statistikasi (FP-paneli) — ishlashlar / qurilmalar / rad etilgan =====
  if (req.query.rulestats === '1' && isAdmin) {
    const { data, error } = await sb
      .from('v_rule_stats')
      .select('rule_id, fires, devices, dismissed, last_fire')
      .order('fires', { ascending: false })
      .limit(500);
    if (error) { console.error(`[threats] rulestats db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, stats: data ?? [] });
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
    // `family` (kampaniya/oila yorlig'i) bo'lsa `f` uchun undan foydalanamiz — mijozda
    // detektsiya sababi "Ajina.Banker" kabi aniqroq ko'rinadi (yo'q bo'lsa kategoriya).
    type FeedRow = { apk_hash?: string | null; package_name?: string | null; category?: string | null; family?: string | null; last_seen?: string | null };
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
        .select('apk_hash, package_name, category, family, severity, seen_count, review_status, last_seen')
        .in('severity', ['high', 'critical'])
        .neq('review_status', 'dismissed')   // egasi rad etgan tahdid feed'ga tushmaydi
        .gte('seen_count', MIN_FEED_DEVICES)
        .order('last_seen', { ascending: false })
        .limit(2000);
      if (fb.error) { console.error(`[threats] feed fallback db error: ${fb.error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      rows = (fb.data ?? []) as FeedRow[];
    }

    const feedLabel = (t: FeedRow) => t.family || t.category || 'Cloud.feed';
    const hashes = rows
      .filter((t) => t.apk_hash)
      .map((t) => ({ h: String(t.apk_hash).toLowerCase(), f: feedLabel(t) }));
    // Paketlar: mashhur/o'zimiznikidir paketlarni HECH QACHON feed orqali bloklamaymiz
    // (korroboratsiyalangan zararli hash tasodifan benign paket nomini olib yursa ham).
    const packages = rows
      .filter((t) => t.package_name && !isNeverBlockPackage(String(t.package_name)))
      .map((t) => ({ p: String(t.package_name).toLowerCase(), f: feedLabel(t) }));

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
        // Versiya = server soati (epoch sek), monotonik. AVVAL max(last_seen) edi → egasi eng
        // yangi domenni O'CHIRSA (delete_domain) dv PASAYIB ketardi va mijozdagi rollback-guard
        // to'g'irlangan (kichraygan) domen ro'yxatini rad etib, xato bloklangan bank/gov saytini
        // butun parkda bloklab qolaverardi. Vaqt hech qachon kamaymaydi → o'chirish ham versiyani
        // oshiradi. Fail-soft SAQLANADI: jadval o'qilmasa (xato/catch) domainMaxSeen 0 bo'lib qoladi
        // va bo'sh ro'yxat mijozdagi eski domenlarni CLOBBER qilmaydi.
        domainMaxSeen = Math.floor(Date.now() / 1000);
      }
    } catch (e) {
      console.error(`[threats] threat_domains exception: ${(e as Error).message}`);
    }

    // ── YARA-lite qoida paketlari (mijoz RuleEngine ularni baked jadvallar bilan qo'shadi).
    // enabled qoidalar; muted → adv:1 (mijozda faqat SUSPICIOUS, hech qachon DANGER). FAIL-SOFT:
    // jadval yo'q bo'lsa (migratsiya ishlamagan) — qoidasiz davom etamiz.
    type RuleRow = { rule_id: string; family?: string | null; severity?: string | null; target?: string | null; needles?: unknown; min_hits?: number | null; muted?: boolean | null };
    let rules: { id: string; f: string; s: string; t: string; n: string[]; m: number; adv?: number }[] = [];
    let ruleMaxSeen = 0;
    try {
      const rq = await sb
        .from('threat_rules')
        .select('rule_id, family, severity, target, needles, min_hits, muted, updated_at')
        .eq('enabled', true)
        .limit(1000);
      if (rq.error) {
        console.error(`[threats] threat_rules skipped: ${rq.error.message}`);
      } else {
        rules = ((rq.data ?? []) as RuleRow[])
          .map((r) => {
            const needles = Array.isArray(r.needles) ? (r.needles as unknown[]).map(String).filter((s) => s.length >= 2 && s.length <= 64) : [];
            return { id: r.rule_id, f: r.family || 'Cloud.rule', s: r.severity || 'high', t: r.target || 'dex_string', n: needles, m: Math.max(0, Math.round(Number(r.min_hits) || 0)), ...(r.muted ? { adv: 1 } : {}) };
          })
          .filter((r) => r.n.length > 0);
        if (rules.length) ruleMaxSeen = Math.floor(Date.now() / 1000);
      }
    } catch (e) {
      console.error(`[threats] threat_rules exception: ${(e as Error).message}`);
    }

    // ── known_good (yaxshi ro'yxat) — mijoz DOWNGRADE-only ishonch kirishi. FAIL-SOFT.
    type GoodRow = { package_name?: string | null; cert_sha256?: string | null };
    let good: { p: string; c: string }[] = [];
    let goodMaxSeen = 0;
    try {
      const gq = await sb.from('known_good').select('package_name, cert_sha256').limit(4000);
      if (gq.error) {
        console.error(`[threats] known_good skipped: ${gq.error.message}`);
      } else {
        good = ((gq.data ?? []) as GoodRow[])
          .filter((g) => g.package_name)
          .map((g) => ({ p: String(g.package_name).toLowerCase(), c: g.cert_sha256 ? String(g.cert_sha256).toLowerCase() : '' }));
        if (good.length) goodMaxSeen = Math.floor(Date.now() / 1000);
      }
    } catch (e) {
      console.error(`[threats] known_good exception: ${(e as Error).message}`);
    }

    // Monotonik versiyalar — server soati (epoch sek). AVVAL v = max(last_seen) edi (faqat feed'ga
    // TUSHGAN satrlar bo'yicha) → egasi eng yangi tahdidni RAD ETSA (set_review → 'dismissed', feed'dan
    // chiqadi) yoki eng yangi satr yo'qolsa, v PASAYIB ketardi. Mijozdagi rollback-guard (remoteV < KEY_V)
    // esa to'g'irlangan (kichraygan) feed'ni rad etib, benign/xato-bloklangan ilovani butun parkda
    // bloklab qolaverardi. Vaqt hech qachon kamaymaydi → tahdidni olib tashlash ham versiyani oshiradi va
    // yangilangan konvert mijozda saqlanadi. Replay himoyasi saqlanadi: eski (qayta o'ynatilgan) konvert
    // past vaqt bilan keladi → past versiya → rad etiladi.
    //
    // Fail-soft SAQLANADI: feed BUTUNLAY bo'sh bo'lsa (hash ham, paket ham yo'q) v = 1 — bu holda
    // vaqtinchalik/chekka bo'sh o'qish mijozdagi keshlangan bulut-hash'larini CLOBBER qilmaydi
    // (past versiya → rad). Domen (`dv`) versiyasi ham ALOHIDA baholanadi (yuqoriga qarang).
    const nowEpoch = Math.floor(Date.now() / 1000);
    const threatVersion = (hashes.length || packages.length) ? nowEpoch : 1;
    // rv/gv — qoida va yaxshi-ro'yxat versiyalari ALOHIDA (domen dv kabi) → mijozda har ro'yxat
    // uchun mustaqil monotonik rollback-guard. Bo'sh o'qish (fail-soft, 0) mijozdagi keshni CLOBBER
    // qilmaydi (past versiya → rad).
    const payload = { v: threatVersion, dv: domainMaxSeen, rv: ruleMaxSeen, gv: goodMaxSeen, ts: Date.now(), hashes, packages, domains, rules, good };
    res.setHeader('Cache-Control', 'public, max-age=300');
    return res.status(200).json({ ok: true, feed: signEnvelope(payload) });
  }

  // ===== Panel uchun to'liq ko'rinish (avvalgidek) =====
  const { data, error } = await sb
    .from('threats')
    .select('apk_hash, package_name, app_label, category, family, review_status, severity, seen_count, first_seen, last_seen, notes')
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
