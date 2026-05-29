import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { checkAdminSecret, checkDeviceSecret } from '../lib/auth.js';

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
    // O'qish: panel (admin) yoki qurilma (APK) — ikkalasi ham ko'ra oladi.
    if (!checkAdminSecret(req) && !checkDeviceSecret(req)) {
      return res.status(401).json({ ok: false, error: 'auth' });
    }
    const { data, error } = await sb
      .from('news')
      .select('id, title, body, level, image_url, pinned, created_at')
      .order('pinned', { ascending: false })
      .order('created_at', { ascending: false })
      .limit(100);
    if (error) return res.status(500).json({ ok: false, error: error.message });
    return res.status(200).json({ ok: true, news: data ?? [] });
  }

  if (req.method === 'POST') {
    // Yozish faqat admin panelida — qurilma siri e'lon qo'sha olmaydi.
    if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
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
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true, item: data });
    }

    if (action === 'delete') {
      const id = String(b.id ?? '').trim();
      if (!id) return res.status(400).json({ ok: false, error: 'id kerak' });
      const { error } = await sb.from('news').delete().eq('id', id);
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true });
    }

    if (action === 'toggle_pin') {
      const id = String(b.id ?? '').trim();
      if (!id) return res.status(400).json({ ok: false, error: 'id kerak' });
      const { error } = await sb.from('news').update({ pinned: Boolean(b.pinned) }).eq('id', id);
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true });
    }

    return res.status(400).json({ ok: false, error: "noma'lum action" });
  }

  return res.status(405).json({ ok: false, error: 'method' });
}
