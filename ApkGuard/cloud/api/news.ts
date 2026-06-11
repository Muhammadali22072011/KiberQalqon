import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canManageNews, canRead, checkAdminSecret, checkDeviceSecret } from '../lib/auth.js';
import { audit } from '../lib/audit.js';

// Yangiliklar / e'lonlar — panel bosh sahifasidagi lenta + APK bosh ekrani.
//   GET  → o'qish: panel (x-admin-secret) YOKI qurilma (x-device-secret).
//          APK lentani ko'rsatadi, ammo ADMIN_SECRET'ni o'z ichiga olmaydi.
//   POST → yozish faqat admin: e'lon qo'shish/o'chirish/qotirish.
//   GET  → e'lonlar ro'yxati (pinned yuqorida, keyin yangi → eski)
//   POST { action:'create',     title, body?, level?, image_url? }
//   POST { action:'delete',     id }
//   POST { action:'toggle_pin', id, pinned:boolean }
const LEVELS = ['info', 'warning', 'critical'];

export default async function handler(req: VercelRequest, res: VercelResponse) {
  const sb = db();

  if (req.method === 'GET') {
    // O'qish: kirgan panel foydalanuvchisi (egasi yoki admin) YOKI qurilma (APK,
    // x-device-secret). E'lonlar hamma uchun — APK bosh ekranda lentani ko'rsatadi.
    if (!canRead(req) && !checkDeviceSecret(req)) {
      return res.status(401).json({ ok: false, error: 'auth' });
    }
    const { data, error } = await sb
      .from('news')
      .select('id, title, body, level, image_url, pinned, created_at')
      .order('pinned', { ascending: false })
      .order('created_at', { ascending: false })
      .limit(100);
    if (error) { console.error(`[news] list db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, news: data ?? [] });
  }

  if (req.method === 'POST') {
    // Yozish: egasi YOKI cheklangan admin (admin uchun yagona "yozish" huquqi — e'lonlar).
    // Qurilma siri e'lon qo'sha olmaydi.
    if (!canManageNews(req)) return res.status(401).json({ ok: false, error: 'auth' });
    const b = (req.body ?? {}) as Record<string, unknown>;
    const action = String(b.action ?? '');

    if (action === 'create') {
      const title = String(b.title ?? '').trim();
      if (!title) return res.status(400).json({ ok: false, error: 'sarlavha kerak' });
      const body = String(b.body ?? '').trim();
      const lvl = String(b.level ?? 'info');
      const level = LEVELS.includes(lvl) ? lvl : 'info';
      // Faqat http(s) havola — javascript:/data: kabi xavfli URI'larni rad etamiz.
      const rawImg = String(b.image_url ?? '').trim();
      const image_url = /^https?:\/\//i.test(rawImg) ? rawImg : null;
      const { data, error } = await sb
        .from('news')
        .insert({ title, body, level, image_url })
        .select('id, title, body, level, image_url, pinned, created_at')
        .single();
      if (error) { console.error(`[news] create db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'news_create', `${data?.id ?? '?'}: ${title.slice(0, 80)}`);
      return res.status(200).json({ ok: true, item: data });
    }

    if (action === 'delete') {
      // O'chirish — FAQAT egasi (admin joylaydi, lekin o'chira/o'zgartira olmaydi; Profil shuni va'da qiladi).
      if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
      const id = String(b.id ?? '').trim();
      if (!id) return res.status(400).json({ ok: false, error: 'id kerak' });
      const { error } = await sb.from('news').delete().eq('id', id);
      if (error) { console.error(`[news] delete db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'news_delete', id);
      return res.status(200).json({ ok: true });
    }

    if (action === 'toggle_pin') {
      // Qadab qo'yish/o'zgartirish — FAQAT egasi (admin faqat joylaydi).
      if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
      const id = String(b.id ?? '').trim();
      if (!id) return res.status(400).json({ ok: false, error: 'id kerak' });
      const { error } = await sb.from('news').update({ pinned: Boolean(b.pinned) }).eq('id', id);
      if (error) { console.error(`[news] toggle_pin db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'news_pin', `${id} → ${Boolean(b.pinned)}`);
      return res.status(200).json({ ok: true });
    }

    // Rasmni serverda Supabase Storage'ga yuklab, ochiq https havola qaytaramiz.
    // Brauzer SERVICE_KEY ko'rmaydi — yuklash shu yerda (serverda) bo'ladi. Qaytgan
    // https havola create action'dagi /^https?:\/\// filtridan o'tadi.
    if (action === 'upload_image') {
      const dataUrl = String(b.data ?? '');
      const m = /^data:(image\/(?:png|jpe?g|gif|webp));base64,([a-z0-9+/=\s]+)$/i.exec(dataUrl);
      if (!m) return res.status(400).json({ ok: false, error: "rasm formati noto'g'ri (png/jpg/gif/webp)" });
      const contentType = m[1].toLowerCase();
      const buf = Buffer.from(m[2].replace(/\s/g, ''), 'base64');
      if (buf.length === 0) return res.status(400).json({ ok: false, error: "bo'sh rasm" });
      if (buf.length > 3_500_000) return res.status(400).json({ ok: false, error: 'rasm 3MB dan katta' });
      const ext = contentType.replace('image/', '').replace('jpeg', 'jpg');
      const name = `${Date.now()}-${Math.random().toString(36).slice(2, 9)}.${ext}`;
      // Bucket bo'lmasa — yaratamiz (idempotent; mavjud bo'lsa xatoni yutamiz).
      await sb.storage.createBucket('news', { public: true }).catch(() => undefined);
      const up = await sb.storage.from('news').upload(name, buf, { contentType, upsert: false });
      if (up.error) { console.error(`[news] image upload error: ${up.error.message}`); return res.status(500).json({ ok: false, error: 'upload' }); }
      const { data: pub } = sb.storage.from('news').getPublicUrl(name);
      return res.status(200).json({ ok: true, url: pub.publicUrl });
    }

    return res.status(400).json({ ok: false, error: "noma'lum action" });
  }

  return res.status(405).json({ ok: false, error: 'method' });
}
