import type { VercelRequest, VercelResponse } from '@vercel/node';
import { canRead, checkDeviceSecret } from '../lib/auth.js';
import { createHmac } from 'crypto';

/**
 * Imzolangan masofaviy konfiguratsiya — verdikt CHEGARALARI (thresholds).
 *
 * Maqsad: bu raqamlar markazdan boshqarilsin — yangi evasion paydo bo'lsa, ilovani
 * YANGILAMASDAN detektsiyani kuchaytirish (chegarani pasaytirish) mumkin. Mijoz
 * (RemoteConfig.kt) clamp=min qiladi: masofaviy faqat KUCHAYTIRA oladi, hech qachon
 * zaiflashtira olmaydi (antivirus oltin qoidasi — soxta config ham xavf tug'dirmaydi).
 *
 * Hozircha qiymatlar APK ichidagi BAKED standartlar bilan AYNAN bir xil → xulq O'ZGARMAYDI.
 * Tightenni xohlasangiz: pastdagi sonlarni KAMAYTIRING + `v` ni oshiring + redeploy.
 *
 * Imzo: base64url(payloadJSON) "." base64url(HMAC-SHA256(payloadB64, CONFIG_SIGNING_SECRET)).
 * Kalit faqat serverda (Vercel ENV) + mijoz APK'sida Shield shifrida (RemoteConfig verify).
 */
const CONFIG = {
  v: 1,
  thresholds: {
    high: { danger: 55, suspicious: 28 },
    medium: { danger: 85, suspicious: 40 },
    low: { danger: 120, suspicious: 60 },
  },
  strongComboMin: 90,
  randomPkgFilenameMin: 40,
  randomPkgDangerousPermsMin: 3,
};

function signEnvelope(obj: unknown): string {
  const key = process.env.CONFIG_SIGNING_SECRET || '';
  const payloadB64 = Buffer.from(JSON.stringify(obj), 'utf8').toString('base64url');
  const sig = createHmac('sha256', key).update(payloadB64).digest('base64url');
  return `${payloadB64}.${sig}`;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  // O'qish: panel foydalanuvchisi YOKI qurilma kaliti (mijozlar shu bilan oladi).
  if (!(canRead(req) || checkDeviceSecret(req))) return res.status(401).json({ ok: false, error: 'auth' });
  // Imzo kaliti yo'q bo'lsa — imzolanmagan config bermaymiz (mijoz baked'da qoladi).
  if (!process.env.CONFIG_SIGNING_SECRET) return res.status(200).json({ ok: false, error: 'unconfigured' });
  return res.status(200).json({ ok: true, config: signEnvelope(CONFIG) });
}
