import type { VercelRequest } from '@vercel/node';
import { db } from './supabase.js';
import { readSession } from './session.js';
import { clientIp } from './geo.js';

// Panel amallari jurnali (admin_audit_log, migratsiya 15) — kim, qachon, nima qildi.
//
// FAIL-SOFT qoidasi: jurnal yozilmasa (jadval hali yaratilmagan / DB nosoz) amalning
// O'ZI hech qachon to'xtamaydi — faqat console.error. Shu sabab migratsiya 15
// qo'llanmaguncha ham prod buzilmaydi; qo'llangach jurnal o'z-o'zidan to'la boshlaydi.
export async function audit(
  req: VercelRequest,
  action: string,
  detail?: string,
  actorOverride?: string,
): Promise<void> {
  try {
    await db().from('admin_audit_log').insert({
      actor: actorOverride ?? actorOf(req),
      action,
      detail: detail ? detail.slice(0, 500) : null,
      ip: clientIp(req) || null,
    });
  } catch (e) {
    console.error(`[audit] yozilmadi (${action}): ${(e as Error).message}`);
  }
}

// Sessiya tokenidan kim ekanini aniqlaymiz. Xom ADMIN_SECRET bilan kelgan so'rov ham
// "owner" (skript/curl — faqat egada bor). Token yaroqsiz/yo'q bo'lsa 'anon'.
export function actorOf(req: VercelRequest): string {
  const tok = req.headers['x-admin-secret'];
  if (typeof tok !== 'string' || tok.length === 0) return 'anon';
  const s = readSession(tok);
  if (s) return s.kind === 'admin' ? `admin:${s.login ?? '?'}` : 'owner';
  // Sessiya emas — demak xom secret (auth allaqachon checkAdminSecret/canRead'dan o'tgan).
  return 'owner';
}
