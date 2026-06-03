import type { VercelRequest } from '@vercel/node';

/**
 * So'rov tanasini XOM (raw) satr sifatida o'qiydi.
 *
 * Yozuv endpointlari `export const config = { api: { bodyParser: false } }` bilan ishlaydi —
 * shunda biz mijoz IMZOLAGAN AYNAN o'sha baytlarni o'qiymiz (HMAC body-hash mos kelishi uchun;
 * JSON kalit tartibi qayta-serializatsiyada o'zgarib imzoni buzmasligi uchun).
 *
 * MUSTAHKAMLIK: agar platforma tanani allaqachon parse qilib stream'ni bo'shatgan bo'lsa
 * (bodyParser bayrog'i e'tiborga olinmasa), stream bo'sh bo'ladi — bu holda req.body'ni
 * QAYTA serializatsiya qilamiz. Unda imzo body-hash'i mos kelmasligi mumkin → so'rov eski
 * x-device-secret yo'liga TUSHADI (buzilmaydi), lekin handler tanani BARIBIR to'g'ri oladi.
 */
export async function readRaw(req: VercelRequest): Promise<string> {
  const chunks: Buffer[] = [];
  try {
    for await (const c of req as AsyncIterable<Buffer | string>) {
      chunks.push(Buffer.isBuffer(c) ? c : Buffer.from(c));
    }
  } catch {
    /* stream o'qib bo'lmadi — quyida req.body fallback */
  }
  if (chunks.length) return Buffer.concat(chunks).toString('utf8');

  // Kuzatuv: bu yo'lga tushdik = stream bo'sh edi (bodyParser e'tiborga olinmadi). Bunda
  // imzo body-hash'i mos kelmasligi mumkin → so'rov x-device-secret yo'liga tushadi.
  // DEVICE_SHARED_SECRET'ni olib tashlashdan OLDIN bu log yo'qolishi (chunks yo'li ishlashi) kerak.
  console.warn('[rawbody] stream bo\'sh — req.body fallback ishladi; imzo body-hash mos kelmasligi mumkin');

  const b = (req as unknown as { body?: unknown }).body;
  if (b == null) return '';
  if (typeof b === 'string') return b;
  if (Buffer.isBuffer(b)) return b.toString('utf8');
  try {
    return JSON.stringify(b);
  } catch {
    return '';
  }
}
