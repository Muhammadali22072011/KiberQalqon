import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkAdminSecret, checkDeviceSecret } from '../../lib/auth.js';
import { hashPassword, verifyPassword } from '../../lib/password.js';
import { currentCode, secondsLeft, isValidCode, hasSecret } from '../../lib/rolecode.js';

// Rollar tizimining uchta endpointi bitta dinamik route'da (Hobby 12-funksiya
// limiti uchun). Yo'llar o'zgarmaydi:
//   /api/role/admin → handleAdmin   (x-admin-secret)
//   /api/role/code  → handleCode    (x-admin-secret)
//   /api/role/login → handleLogin   (x-device-secret)
export default async function handler(req: VercelRequest, res: VercelResponse) {
  const action = Array.isArray(req.query.action) ? req.query.action[0] : req.query.action;
  if (action === 'admin') return handleAdmin(req, res);
  if (action === 'code') return handleCode(req, res);
  if (action === 'login') return handleLogin(req, res);
  return res.status(404).json({ ok: false, error: 'not found' });
}

// --- /api/role/admin -------------------------------------------------------
// Rollarni va kirish beriladigan odamlarni boshqarish (egasi uchun).
//   GET  → rollar + operatorlar ro'yxati (parolsiz)
//   POST { action:'create_role',     name, permissions[], components[] }
//   POST { action:'create_operator', login, password, role_name }
//   POST { action:'set_active',      login, active:boolean }
async function handleAdmin(req: VercelRequest, res: VercelResponse) {
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
    const op = String(b.action ?? '');

    if (op === 'create_role') {
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

    if (op === 'create_operator') {
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

    if (op === 'set_active') {
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

// --- /api/role/code --------------------------------------------------------
// Egasi panelda joriy soatlik kodni ko'radi (maxfiy kirishning 2-qulfi).
async function handleCode(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
  if (!hasSecret()) return res.status(500).json({ ok: false, error: 'ROLE_CODE_SECRET sozlanmagan' });

  return res.status(200).json({
    ok: true,
    code: currentCode(),
    seconds_left: secondsLeft(),
  });
}

// --- /api/role/login -------------------------------------------------------
// Maxfiy kirishning 4-qulfi: server rolni tasdiqlaydi (soatlik kod → login → parol).
type LoginBody = { code?: string; login?: string; password?: string };

async function handleLogin(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  // Qo'pol darvoza: haqiqiy KiberQalqon ilovasidanmi (APK ichidagi device-secret).
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  if (!hasSecret()) {
    return res.status(500).json({ ok: false, error: 'ROLE_CODE_SECRET sozlanmagan' });
  }

  const b = (req.body ?? {}) as LoginBody;
  const code = (b.code ?? '').trim();
  const login = (b.login ?? '').trim();
  const password = b.password ?? '';
  if (!code || !login || !password) {
    return res.status(400).json({ ok: false, error: 'Kod, login va parol kerak' });
  }

  const sb = db();
  const ip = clientIp(req);

  // 1) Soatlik kod (eng arzon tekshiruv — DB'ga tegmasdan).
  if (!isValidCode(code)) {
    await audit(sb, login, false, 'bad_code', ip);
    return res.status(200).json({ ok: false, error: "Kod noto'g'ri yoki eskirgan" });
  }

  // 2) Operatorni topish (faol) + roli bilan.
  const { data: op, error } = await sb
    .from('operators')
    .select('id, password_hash, password_salt, active, roles(name, permissions, components)')
    .eq('login', login)
    .eq('active', true)
    .maybeSingle();

  if (error) {
    return res.status(500).json({ ok: false, error: error.message });
  }
  // Login topilmasa ham, parol noto'g'ri bo'lsa ham — bir xil xabar (enumeration'ga qarshi).
  if (!op) {
    await audit(sb, login, false, 'no_user', ip);
    return res.status(200).json({ ok: false, error: "Login yoki parol noto'g'ri" });
  }

  // 3) Parol.
  if (!verifyPassword(password, op.password_hash as string, op.password_salt as string)) {
    await audit(sb, login, false, 'bad_password', ip);
    return res.status(200).json({ ok: false, error: "Login yoki parol noto'g'ri" });
  }

  // role embed — many-to-one: obyekt (ba'zi versiyalarda massiv bo'lishi mumkin).
  const roleRaw = (op as Record<string, unknown>).roles;
  const role = Array.isArray(roleRaw) ? roleRaw[0] : roleRaw;
  if (!role) {
    await audit(sb, login, false, 'no_role', ip);
    return res.status(200).json({ ok: false, error: 'Rol biriktirilmagan' });
  }

  await sb.from('operators').update({ last_login_at: new Date().toISOString() }).eq('id', op.id);
  await audit(sb, login, true, null, ip);

  const r = role as { name?: string; permissions?: string[]; components?: string[] };
  return res.status(200).json({
    ok: true,
    role: {
      name: r.name ?? 'Xodim',
      permissions: r.permissions ?? [],
      components: r.components ?? [],
    },
  });
}

function clientIp(req: VercelRequest): string | null {
  const xff = req.headers['x-forwarded-for'];
  if (typeof xff === 'string') return xff.split(',')[0].trim();
  const real = req.headers['x-real-ip'];
  return typeof real === 'string' ? real : null;
}

async function audit(
  sb: ReturnType<typeof db>,
  login: string,
  ok: boolean,
  reason: string | null,
  ip: string | null,
) {
  try {
    await sb.from('role_login_audit').insert({ login, ok, reason, ip });
  } catch {
    /* audit yozuvi muvaffaqiyatsiz bo'lsa ham login javobini buzmaymiz */
  }
}
