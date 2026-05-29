import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { checkDeviceSecret } from '../../lib/auth.js';
import { isValidCode, hasSecret } from '../../lib/rolecode.js';
import { verifyPassword } from '../../lib/password.js';

// Maxfiy kirishning 4-qulfi: server rolni tasdiqlaydi.
// Android (SecretAccessActivity → RoleAccessClient) shu yerga POST qiladi.
// Tartib: soatlik kod → login → parol. Hammasi to'g'ri bo'lsa rol qaytadi.

type Body = { code?: string; login?: string; password?: string };

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'method' });
  // Qo'pol darvoza: haqiqiy KiberQalqon ilovasidanmi (APK ichidagi device-secret).
  if (!checkDeviceSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });

  if (!hasSecret()) {
    return res.status(500).json({ ok: false, error: 'ROLE_CODE_SECRET sozlanmagan' });
  }

  const b = (req.body ?? {}) as Body;
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
