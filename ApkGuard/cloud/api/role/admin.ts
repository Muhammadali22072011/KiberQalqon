import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkAdminSecret } from '../../lib/auth.js';
import { hashPassword } from '../../lib/password.js';

// Rollarni va kirish beriladigan odamlarni boshqarish (egasi uchun).
// Admin (x-admin-secret yoki panel sessiya tokeni) talab qiladi.
//   GET  → rollar + operatorlar ro'yxati (parolsiz)
//   POST { action:'create_role',     name, permissions[], components[] }
//   POST { action:'create_operator', login, password, role_name }
//   POST { action:'set_active',      login, active:boolean }
// Parol OCHIQ saqlanmaydi — scrypt bilan hash'lanadi (lib/password).
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
  const sb = db();

  if (req.method === 'GET') {
    const [rolesRes, opsRes] = await Promise.all([
      sb.from('roles').select('id, name, permissions, components, created_at').order('created_at'),
      sb.from('v_operators_safe').select('*'),
    ]);
    if (rolesRes.error) return res.status(500).json({ ok: false, error: rolesRes.error.message });
    return res.status(200).json({
      ok: true,
      roles: rolesRes.data ?? [],
      operators: opsRes.data ?? [],
    });
  }

  if (req.method === 'POST') {
    const b = (req.body ?? {}) as Record<string, unknown>;
    const action = String(b.action ?? '');

    if (action === 'create_role') {
      const name = String(b.name ?? '').trim();
      if (!name) return res.status(400).json({ ok: false, error: 'name kerak' });
      const permissions = Array.isArray(b.permissions) ? (b.permissions as string[]) : [];
      const components = Array.isArray(b.components) ? (b.components as string[]) : [];
      const { data, error } = await sb
        .from('roles')
        .upsert({ name, permissions, components }, { onConflict: 'name' })
        .select('id, name, permissions, components')
        .single();
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true, role: data });
    }

    if (action === 'create_operator') {
      const login = String(b.login ?? '').trim();
      const password = String(b.password ?? '');
      const roleName = String(b.role_name ?? '').trim();
      if (!login || !password) {
        return res.status(400).json({ ok: false, error: 'login va password kerak' });
      }
      let role_id: string | null = null;
      if (roleName) {
        const { data: role } = await sb.from('roles').select('id').eq('name', roleName).maybeSingle();
        if (!role) return res.status(400).json({ ok: false, error: 'rol topilmadi: ' + roleName });
        role_id = role.id as string;
      }
      const { hash, salt } = hashPassword(password);
      const { data, error } = await sb
        .from('operators')
        .upsert(
          { login, password_hash: hash, password_salt: salt, role_id, active: true },
          { onConflict: 'login' },
        )
        .select('id, login')
        .single();
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true, operator: data });
    }

    if (action === 'set_active') {
      const login = String(b.login ?? '').trim();
      const active = Boolean(b.active);
      if (!login) return res.status(400).json({ ok: false, error: 'login kerak' });
      const { error } = await sb.from('operators').update({ active }).eq('login', login);
      if (error) return res.status(500).json({ ok: false, error: error.message });
      return res.status(200).json({ ok: true });
    }

    return res.status(400).json({ ok: false, error: "noma'lum action" });
  }

  return res.status(405).json({ ok: false, error: 'method' });
}
