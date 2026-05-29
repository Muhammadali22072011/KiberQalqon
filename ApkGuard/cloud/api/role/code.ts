import type { VercelRequest, VercelResponse } from '@vercel/node';
import { checkAdminSecret } from '../../lib/auth.js';
import { currentCode, secondsLeft, hasSecret } from '../../lib/rolecode.js';

// Egasi panelda joriy soatlik kodni ko'radi (rollar tizimi — maxfiy kirishning
// 2-qulfi). Faqat admin (x-admin-secret / sessiya tokeni). Kodni ko'rib, kirishga
// ruxsat bergan odamga aytadi. Har soatda yangilanadi.
export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'method' });
  if (!checkAdminSecret(req)) return res.status(401).json({ ok: false, error: 'auth' });
  if (!hasSecret()) return res.status(500).json({ ok: false, error: 'ROLE_CODE_SECRET sozlanmagan' });

  return res.status(200).json({
    ok: true,
    code: currentCode(),
    seconds_left: secondsLeft(),
  });
}
