import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { sendMessage, adminChatIds } from '../../lib/telegram.js';
import { verifyDeviceWrite } from '../../lib/devauth.js';
import { readRaw } from '../../lib/rawbody.js';
import { resolveGeoNoDowngrade, readDeviceGeo, clientIp } from '../../lib/geo.js';
import { formatThreatAlert } from '../../lib/format.js';

// XOM tanani o'qish uchun (imzo body-hash'i AYNAN yuborilgan baytlardan hisoblansin).
export const config = { api: { bodyParser: false } };

type Body = {
  device_token: string;
  apk_hash: string;
  package_name?: string;
  app_label?: string;
  apk_size?: number;
  verdict: 'safe' | 'suspicious' | 'danger' | 'error';
  risk_score?: number;
  scan_duration_ms?: number;
  reasons?: string[];
  perms?: string[];
  lat?: number | string;
  lng?: number | string;
};

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  // Per-device imzo (yangi) YOKI eski umumiy x-device-secret (o'tish davri). Dual-accept.
  const rawBody = await readRaw(req);
  if (!(await verifyDeviceWrite(req, rawBody, 'scan/upload'))) {
    return res.status(401).json({ ok: false, error: 'auth' });
  }
  let b: Body;
  try { b = JSON.parse(rawBody || '{}') as Body; } catch { return res.status(400).json({ ok: false, error: 'bad json' }); }
  // Imzolangan yo'lda sarlavha x-device-token tana device_token bilan mos kelishi shart —
  // imzolangan qurilma yozuvni BOSHQA anonim id'ga biriktira olmasin. Eski (x-device-secret)
  // yo'lda sarlavha yo'q → tekshirilmaydi (eski qurilmalar buzilmaydi).
  const hdrTok = req.headers['x-device-token'];
  if (typeof hdrTok === 'string' && hdrTok.length > 0 && hdrTok !== b.device_token) {
    return res.status(401).json({ ok: false, error: 'token/body mismatch' });
  }
  if (!b?.device_token || !b?.apk_hash || !b?.verdict) {
    return res.status(400).json({ ok: false, error: 'missing fields' });
  }
  // CLOUD-02: eski (x-device-secret) yo'lda device_token faqat tana JSON'idan keladi va hech
  // narsaga bog'lanmaydi — soxtalashtirilishi (boshqa qurilma yozuvini ezish) mumkin. Per-device
  // imzoga (x-device-token) o'tilgach bu yo'l YOPILADI. Hozircha sunset tayyorligini va
  // suiiste'molni kuzatish uchun loglaymiz.
  const legacyAuth = !(typeof hdrTok === 'string' && hdrTok.length > 0);
  if (legacyAuth) {
    console.warn(`[upload] legacy device-secret write token=${String(b.device_token).slice(0, 8)}… ip=${clientIp(req) ?? '?'}`);
  }
  if (!/^[a-f0-9]{64}$/i.test(b.apk_hash)) {
    return res.status(400).json({ ok: false, error: 'bad hash' });
  }
  // #24: verdict — ishonchsiz JSON'dan keladi. DB CHECK faqat shu 4 qiymatga ruxsat beradi;
  // boshqasi (masalan "DANGER") scans INSERT'ni buzib, butun yuklashni yiqitardi (alert ham
  // ketmasdi). Yozishdan oldin enum bo'yicha tekshiramiz.
  const ALLOWED_VERDICTS = ['safe', 'suspicious', 'danger', 'error'];
  if (!ALLOWED_VERDICTS.includes(b.verdict)) {
    return res.status(400).json({ ok: false, error: 'bad verdict' });
  }
  // #43: risk_score — ishonchsiz JSON'dan; chegaralanmagan qiymat (>2^31-1) Postgres int
  // ustunini buzib, butun yuklashni 500 bilan yiqitardi. 0..100 oralig'iga clamp qilamiz.
  const risk = Math.max(0, Math.min(100, Math.round(Number(b.risk_score) || 0)));
  // scan_duration_ms — ishonchsiz JSON'dan; chegaralanmagan/manfiy qiymat int ustunini
  // buzishi mumkin. 0..600000 ms (0..10 daqiqa) oralig'iga clamp qilamiz (risk_score kabi).
  const scanDurationMs = Math.max(0, Math.min(600000, Math.round(Number(b.scan_duration_ms) || 0)));

  const sb = db();

  // 1) Device topish yoki yaratish — geo + eng oxirgi risk/verdict bilan yangilash.
  // Panelda nuqta rangi shu risk_score bo'yicha (yashil→qizil) chiziladi.
  const now = new Date().toISOString();
  const devRow: Record<string, unknown> = {
    device_token: b.device_token,
    last_seen: now,
    last_scan_at: now,
    risk_score: risk,
    last_verdict: b.verdict,
  };
  // GPS authoritative (har doim yoziladi → nuqta telefon bilan birga yuradi); GPS yo'q
  // bo'lsa IP taxmini qurilmada joylashuv allaqachon bor bo'lsa YOZILMAYDI (to'g'ri
  // nuqtani noto'g'ri operator shahriga sakratmaymiz). Shuning uchun GPS yo'qdagina
  // mavjud joylashuvni o'qiymiz.
  let existingGeo: { lat: number | null; lng: number | null } | null = null;
  let skipGeo = false;
  if (!readDeviceGeo(b)) {
    const { data: cur, error: gErr } = await sb
      .from('devices')
      .select('lat, lng')
      .eq('device_token', b.device_token)
      .maybeSingle();
    if (gErr) {
      // #12: mavjud joylashuvni o'qib bo'lmadi (transient DB xato). IP taxmini bilan
      // to'g'ri GPS nuqtani EZIB yozib qo'ymaslik uchun bu so'rovda geo'ni umuman
      // yangilamaymiz (lat/lng/city/country tegmaydi).
      skipGeo = true;
      console.error(`[upload] existing-geo read failed: ${gErr.message}`);
    } else {
      existingGeo = cur ?? null;
    }
  }
  if (!skipGeo) {
    const geo = resolveGeoNoDowngrade(req, b, b.device_token, existingGeo);
    if (geo.country != null) devRow.country = geo.country;
    if (geo.city != null) devRow.city = geo.city;
    if (geo.lat != null) devRow.lat = geo.lat;
    if (geo.lng != null) devRow.lng = geo.lng;
  }

  // IP — egasi paneli uchun (null bo'lsa eski qiymatni o'chirmaymiz).
  const ip = clientIp(req);
  if (ip) devRow.ip = ip;

  const { data: dev, error: devErr } = await sb
    .from('devices')
    .upsert(devRow, { onConflict: 'device_token' })
    .select('id, name')
    .single();
  if (devErr || !dev) {
    console.error(`[upload] device upsert db error: ${devErr?.message ?? 'no row'}`);
    return res.status(500).json({ ok: false, error: 'device upsert' });
  }

  // 2) Scan yozish
  const { data: scan, error: scanErr } = await sb
    .from('scans')
    .insert({
      device_id: dev.id,
      apk_hash: b.apk_hash.toLowerCase(),
      package_name: b.package_name ?? null,
      app_label: b.app_label ?? null,
      apk_size: b.apk_size ?? null,
      verdict: b.verdict,
      risk_score: risk,
      scan_duration_ms: scanDurationMs,
      reasons: b.reasons ?? [],
      perms: b.perms ?? [],
    })
    .select('id')
    .single();
  if (scanErr) {
    console.error(`[upload] scan insert db error: ${scanErr.message}`);
    return res.status(500).json({ ok: false, error: 'scan insert' });
  }

  // Xavfli/shubhali bo'lsa — qurilma APK namunasini Storage'ga yuklashi uchun
  // imzolangan (signed) URL beramiz. Qurilma faylni TO'G'RIDAN-TO'G'RI Storage'ga
  // yuklaydi (Vercel ~4.5MB body chegarasini chetlab o'tadi, 50MB gacha APK uchun).
  // Yo'l = "<hash>.apk" → bir xil fayl bir marta saqlanadi (dedup); upsert bilan
  // qayta yozilaveradi (xavfsiz, bayt-baytma bir xil). Xavfsiz APK'lar yuborilmaydi.
  let sampleUpload: { url: string } | null = null;

  // 3) Agar xavfli — threats jadvalini upsert qilamiz va Telegramga yuboramiz
  if (b.verdict === 'danger' || b.verdict === 'suspicious') {
    const severity = b.verdict === 'danger' ? 'high' : 'medium';
    const rpcRes = await sb.rpc('upsert_threat', {
      p_hash: b.apk_hash.toLowerCase(),
      p_package: b.package_name ?? null,
      p_label: b.app_label ?? null,
      p_category: classify(b.reasons ?? []),
      p_severity: severity,
    });
    // Avval xato e'tiborsiz qoldirilardi — agar upsert_threat RPC bo'lmasa yoki
    // signaturasi noto'g'ri bo'lsa, threats jadvali bo'sh qolardi va biz buni hech
    // qachon bilmas edik. Endi logga yozamiz, lekin 500 qaytarmaymiz (alert yuborilishi
    // davom etadi — bu eng muhim narsa).
    if (rpcRes.error) {
      console.error(`[upload] upsert_threat RPC failed: ${rpcRes.error.message}`);
    }

    // APK namunasi uchun imzolangan yuklash URL'i. Bucket bo'lmasa / xato bo'lsa —
    // log qoldiramiz, lekin upload'ni yiqitmaymiz (telemetriya muhimroq).
    try {
      const { data: signed, error: upErr } = await sb.storage
        .from('malware-samples')
        .createSignedUploadUrl(`${b.apk_hash.toLowerCase()}.apk`, { upsert: true });
      if (upErr) {
        console.error(`[upload] createSignedUploadUrl failed: ${upErr.message}`);
      } else if (signed?.signedUrl) {
        sampleUpload = { url: signed.signedUrl };
      }
    } catch (e) {
      console.error(`[upload] sample url exception: ${(e as Error).message}`);
    }

    const alertText = formatThreatAlert({
      app_label: b.app_label ?? null,
      package_name: b.package_name ?? null,
      apk_hash: b.apk_hash,
      verdict: b.verdict,
      reasons: b.reasons ?? [],
      device_name: dev.name,
    });

    const admins = adminChatIds();
    await Promise.all(
      admins.map(async (chatId) => {
        const r = await sendMessage(chatId, alertText, { parseMode: 'Markdown' });
        await sb.from('notifications').insert({
          chat_id: chatId,
          scan_id: scan?.id ?? null,
          text: alertText,
          ok: r.ok,
          error: r.error ?? null,
        });
      })
    );
  }

  return res.status(200).json({ ok: true, scan_id: scan?.id, sample_upload: sampleUpload });
}

function classify(reasons: string[]): string {
  const joined = reasons.join(' ').toLowerCase();
  if (joined.includes('sms')) return 'sms_stealer';
  if (joined.includes('accessibility')) return 'spyware';
  if (joined.includes('install_packages')) return 'dropper';
  return 'suspicious';
}
