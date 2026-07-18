import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../lib/supabase.js';
import { canRead, checkAdminSecret, checkDeviceSecret } from '../lib/auth.js';
import { audit } from '../lib/audit.js';
import { randomInt } from 'crypto';

// Guruh kodi alifbosi — chalkash belgilar CHIQARIB TASHLANGAN (0/O, 1/I/L) shunda
// bosma QR/kodni qo'lda kiritishda xato bo'lmaydi. Faqat katta harf + 2-9 raqam.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LEN = 7;

function makeCode(): string {
  let s = '';
  for (let i = 0; i < CODE_LEN; i++) s += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return s;
}

// Rang faqat #RRGGBB (panel color-picker shu formatni yuboradi). Boshqasi rad etiladi.
function normColor(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const c = raw.trim().toUpperCase();
  return /^#[0-9A-F]{6}$/.test(c) ? c : null;
}

function normName(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const n = raw.trim().replace(/\s+/g, ' ');
  return n.length >= 2 && n.length <= 40 ? n : null;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  // ===== POST — guruhlarni boshqarish (FAQAT EGASI) =====
  // Alohida funksiya emas (Vercel Hobby 12-funksiya limiti) — threats.ts uslubida branch.
  if (req.method === 'POST') {
    if (!checkAdminSecret(req)) return res.status(403).json({ ok: false, error: 'faqat egasi' });
    const body = (req.body ?? {}) as { action?: string; name?: unknown; color?: unknown; id?: unknown };
    const sb = db();

    if (body.action === 'create_group') {
      const name = normName(body.name);
      const color = normColor(body.color) ?? '#C2143D';
      if (!name) return res.status(400).json({ ok: false, error: 'nom 2-40 belgi bo\'lsin' });

      // Unikal kod — juda kam ehtimolli to'qnashuvda bir necha marta urinamiz.
      let group: { id: string; name: string; color: string; join_code: string; created_at: string } | null = null;
      for (let attempt = 0; attempt < 6 && !group; attempt++) {
        const code = makeCode();
        const { data, error } = await sb
          .from('device_groups')
          .insert({ name, color, join_code: code })
          .select('id, name, color, join_code, created_at')
          .single();
        if (!error) { group = data; break; }
        // 23505 = unique violation (kod to'qnashdi) — qayta urinamiz. Boshqa xato → to'xtaymiz.
        if (error.code !== '23505') {
          console.error(`[devices] group insert: ${error.message}`);
          return res.status(500).json({ ok: false, error: 'db' });
        }
      }
      if (!group) return res.status(500).json({ ok: false, error: 'kod generatsiya qilinmadi' });
      await audit(req, 'group_create', `${group.name} (${group.join_code})`);
      return res.status(200).json({ ok: true, group });
    }

    if (body.action === 'delete_group') {
      const id = typeof body.id === 'string' ? body.id : '';
      if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
        return res.status(400).json({ ok: false, error: 'bad id' });
      }
      // Qurilmalar o'chmaydi — FK on delete set null ularning group_id'sini bo'shatadi.
      const { error } = await sb.from('device_groups').delete().eq('id', id);
      if (error) { console.error(`[devices] group delete: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
      await audit(req, 'group_delete', id);
      return res.status(200).json({ ok: true });
    }

    return res.status(400).json({ ok: false, error: 'action' });
  }

  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });

  // ===== Oila qalqoni (family guard): qurilma O'Z guruhi a'zolarining SOG'LIG'ini ko'radi =====
  // Auth: x-device-secret (admin EMAS) + ?family=1&code=<join_code>. FARZAND ilovani o'rnatadi,
  // OTA-ONA xavf ostida — farzand guruh kodi bilan oilaning himoya-holatini ko'radi. Maxfiylik:
  // FAQAT ism/familiya + oxirgi ko'rinish + xavf darajasi + bayroq qaytariladi (telefon/IP/GPS EMAS).
  if (req.query.family === '1') {
    if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
    const code = typeof req.query.code === 'string' ? req.query.code.trim().toUpperCase().slice(0, 16) : '';
    if (!code) return res.status(400).json({ ok: false, error: 'code' });
    const sbf = db();
    const { data: g, error: gErr } = await sbf.from('device_groups').select('id, name, color').eq('join_code', code).maybeSingle();
    if (gErr) { console.error(`[devices] family group: ${gErr.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    if (!g) return res.status(200).json({ ok: false, error: 'code' });
    const { data, error } = await sbf
      .from('v_devices_with_counts')
      .select('member_first, member_last, name, last_seen, risk_score, last_verdict, danger_count, flag')
      .eq('group_id', g.id)
      .order('risk_score', { ascending: false })
      .limit(500);
    if (error) { console.error(`[devices] family members: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, group: { name: g.name, color: g.color }, members: data ?? [] });
  }

  if (!canRead(req)) return res.status(401).json({ ok: false, error: 'auth' });

  const sb = db();

  // ===== Himoya batareyasi — flot sog'lig'i (qaysi himoyalar OFF, nechta qurilma) =====
  if (req.query.health === '1') {
    const { data, error } = await sb.from('v_fleet_health').select('*').single();
    if (error) { console.error(`[devices] health db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, health: data ?? {} });
  }

  // ===== Guruhlar ro'yxati (egasi ham, admin ham ko'radi) =====
  // Har guruhdagi qurilma soni — PostgREST embedded count (devices.group_id FK orqali).
  if (req.query.groups === '1') {
    const { data, error } = await sb
      .from('device_groups')
      .select('id, name, color, join_code, created_at, devices(count)')
      .order('created_at', { ascending: true });
    if (error) { console.error(`[devices] groups db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    const groups = (data ?? []).map((g: any) => ({
      id: g.id, name: g.name, color: g.color, join_code: g.join_code, created_at: g.created_at,
      device_count: Array.isArray(g.devices) ? (g.devices[0]?.count ?? 0) : 0,
    }));
    return res.status(200).json({ ok: true, groups });
  }

  // ===== Guruh a'zolari rostri (egasi/admin) — Excel eksport + guruh tafsiloti uchun =====
  if (req.query.members === '1') {
    const { data, error } = await sb
      .from('v_group_members')
      .select('*')
      .order('group_name', { ascending: true })
      .order('member_last', { ascending: true })
      .limit(5000);
    if (error) { console.error(`[devices] members db error: ${error.message}`); return res.status(500).json({ ok: false, error: 'db' }); }
    return res.status(200).json({ ok: true, members: data ?? [] });
  }

  // CL-02: ilgari .limit(50) jim 50-tadan keyingisini yo'qotardi (panel "50 ta qurilma" deb
  // butun flotni shu deb ko'rsatardi, Excel ham). Endi /api/geo bilan bir xil 2000 limit +
  // aniq count(*) qaytaramiz, panel "ko'rsatilgan / jami" ko'rsata olsin.
  const { data, error, count } = await db()
    .from('v_devices_with_counts')
    .select('*', { count: 'exact' })
    .order('last_seen', { ascending: false })
    .limit(2000);

  if (error) {
    console.error(`[devices] db error: ${error.message}`);
    return res.status(500).json({ ok: false, error: 'db' });
  }
  return res.status(200).json({ ok: true, devices: data ?? [], total: count ?? (data?.length ?? 0) });
}
