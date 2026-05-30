import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { sendMessage, adminChatIds } from '../../lib/telegram.js';
import { checkDeviceSecret } from '../../lib/auth.js';
import { resolveGeo, clientIp } from '../../lib/geo.js';
import { formatThreatAlert } from '../../lib/format.js';

type Body = {
  device_token: string;
  apk_hash: string;
  package_name?: string;
  app_label?: string;
  apk_size?: number;
  verdict: 'safe' | 'suspicious' | 'danger' | 'error';
  risk_score?: number;
  reasons?: string[];
  perms?: string[];
  lat?: number | string;
  lng?: number | string;
  loc_accuracy_m?: number | string;
};

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const b = req.body as Body;
  if (!b?.device_token || !b?.apk_hash || !b?.verdict) {
    return res.status(400).json({ ok: false, error: 'missing fields' });
  }
  if (!/^[a-f0-9]{64}$/i.test(b.apk_hash)) {
    return res.status(400).json({ ok: false, error: 'bad hash' });
  }

  const sb = db();

  // 1) Device topish yoki yaratish — geo + eng oxirgi risk/verdict bilan yangilash.
  // Panelda nuqta rangi shu risk_score bo'yicha (yashil→qizil) chiziladi.
  const now = new Date().toISOString();
  const devRow: Record<string, unknown> = {
    device_token: b.device_token,
    last_seen: now,
    last_scan_at: now,
    risk_score: b.risk_score ?? 0,
    last_verdict: b.verdict,
  };
  // Qurilma GPS yuborgan bo'lsa — aniq nuqta (jittersiz); aks holda IP geo + jitter.
  const geo = resolveGeo(req, b, b.device_token);
  if (geo.country != null) devRow.country = geo.country;
  if (geo.city != null) devRow.city = geo.city;
  if (geo.lat != null) devRow.lat = geo.lat;
  if (geo.lng != null) devRow.lng = geo.lng;

  // IP — egasi paneli uchun (null bo'lsa eski qiymatni o'chirmaymiz).
  const ip = clientIp(req);
  if (ip) devRow.ip = ip;

  const { data: dev, error: devErr } = await sb
    .from('devices')
    .upsert(devRow, { onConflict: 'device_token' })
    .select('id, name')
    .single();
  if (devErr || !dev) {
    return res.status(500).json({ ok: false, error: 'device upsert', detail: devErr?.message });
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
      risk_score: b.risk_score ?? 0,
      reasons: b.reasons ?? [],
      perms: b.perms ?? [],
    })
    .select('id')
    .single();
  if (scanErr) {
    return res.status(500).json({ ok: false, error: 'scan insert', detail: scanErr.message });
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
