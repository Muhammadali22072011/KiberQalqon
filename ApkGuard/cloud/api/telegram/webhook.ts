import type { VercelRequest, VercelResponse } from '@vercel/node';
import { db } from '../../lib/supabase.js';
import { sendMessage, isAdmin } from '../../lib/telegram.js';
import { checkTelegramSecret, checkTelegramRegSecret } from '../../lib/auth.js';
import { handleRegUpdate, type TgRegUpdate } from '../../lib/tgreg.js';
import { formatStats, verdictLabel, escape } from '../../lib/format.js';

type TgUpdate = {
  update_id: number;
  message?: {
    message_id: number;
    chat: { id: number; type: string; username?: string };
    from?: { id: number; username?: string };
    text?: string;
  };
};

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'POST') return res.status(405).end();

  // Ikkita bot bitta funksiyada (Hobby 12-funksiya limiti — yangi fayl qo'sha olmaymiz).
  // Ajratuvchi — webhook URL'idagi ?bot=reg. Sirlar ham ALOHIDA (auth.ts izohiga qarang),
  // shuning uchun ochiq botning webhook'i egasi buyruqlariga umuman tega olmaydi.
  if (req.query.bot === 'reg') {
    if (!checkTelegramRegSecret(req)) return res.status(401).end();
    try {
      await handleRegUpdate(req.body as TgRegUpdate);
    } catch (e) {
      // Telegram 200'dan boshqa javobni "yetkazilmadi" deb biladi va update'ni
      // soatlab qayta yuboraveradi → xatoni yutamiz, faqat loglaymiz.
      console.error(`[webhook:reg] ${(e as Error).message}`);
    }
    return res.status(200).json({ ok: true });
  }

  if (!checkTelegramSecret(req)) return res.status(401).end();

  const u = req.body as TgUpdate;
  const msg = u?.message;
  if (!msg?.text) return res.status(200).json({ ok: true });

  const chatId = msg.chat.id;
  const text = msg.text.trim();

  // Whitelist tekshiruvi — faqat ENV ADMIN_CHAT_IDS ichidagilar.
  // MUHIM: avval ruxsatsizga "Ruxsat yo'q" javob yuborardik — bu hujumchi har
  // bir chat_id'ga spam qilib Telegram API kvotamizni tugatishi mumkin edi.
  // Endi JIM 200 qaytaramiz: hujumchi qaysi ID'lar haqiqiy ekanini bilmaydi.
  if (!isAdmin(chatId)) {
    console.warn(`[webhook] unauthorized chat_id=${chatId} text="${text.slice(0, 50)}"`);
    return res.status(200).json({ ok: true });
  }

  try {
    if (text.startsWith('/start') || text.startsWith('/help')) {
      await sendMessage(chatId, HELP, { parseMode: 'Markdown' });
    } else if (text.startsWith('/stats')) {
      await handleStats(chatId);
    } else if (text.startsWith('/last')) {
      await handleLast(chatId, parseLimit(text, 5));
    } else if (text.startsWith('/threats')) {
      await handleThreats(chatId);
    } else if (text.startsWith('/devices')) {
      await handleDevices(chatId);
    } else if (text.startsWith('/id')) {
      await sendMessage(chatId, `Chat ID: \`${chatId}\``, { parseMode: 'Markdown' });
    } else {
      await sendMessage(chatId, "❓ Buyruq tanilmadi. /help yozing.");
    }
  } catch (e) {
    // Xom istisnani chatga aks ettirmaymiz (jadval nomlari va h.k. oshkor bo'lishi mumkin) —
    // serverga loglaymiz, foydalanuvchiga umumiy xabar.
    console.error(`[webhook] handler error: ${(e as Error).message}`);
    await sendMessage(chatId, '⚠️ Ichki xato. Keyinroq urinib ko\'ring.');
  }

  return res.status(200).json({ ok: true });
}

const HELP = [
  '*🛡 KiberQalqon — boshqaruv paneli*',
  '',
  '*Buyruqlar:*',
  '/stats — bugungi statistika',
  '/last [N] — oxirgi N skan (default 5)',
  '/threats — oxirgi xavf belgilangan APKlar',
  '/devices — ulangan qurilmalar',
  '/id — sizning chat ID',
  '/help — shu xabar',
].join('\n');

async function handleStats(chatId: number) {
  const sb = db();
  const { data, error } = await sb.from('v_stats_today').select('*').single();
  if (error || !data) {
    await sendMessage(chatId, `⚠️ Statistika olinmadi: ${error?.message ?? 'noma\'lum'}`);
    return;
  }
  await sendMessage(chatId, formatStats(data as never), { parseMode: 'Markdown' });
}

async function handleLast(chatId: number, n: number) {
  const sb = db();
  const { data, error } = await sb
    .from('scans')
    .select('scanned_at, verdict, app_label, package_name, apk_hash')
    .order('scanned_at', { ascending: false })
    .limit(n);

  if (error) {
    await sendMessage(chatId, `⚠️ ${error.message}`);
    return;
  }
  if (!data?.length) {
    await sendMessage(chatId, 'Hali skanlar yo\'q.');
    return;
  }
  const lines = [`*Oxirgi ${data.length} ta skan:*`, ''];
  for (const s of data) {
    const t = new Date(s.scanned_at).toISOString().replace('T', ' ').slice(0, 16);
    const label = escape(s.app_label || s.package_name || s.apk_hash.slice(0, 12));
    lines.push(`${verdictLabel(s.verdict)} \`${t}\` — ${label}`);
  }
  await sendMessage(chatId, lines.join('\n'), { parseMode: 'Markdown' });
}

async function handleThreats(chatId: number) {
  const sb = db();
  const { data, error } = await sb
    .from('threats')
    .select('apk_hash, app_label, package_name, severity, seen_count, last_seen')
    .order('last_seen', { ascending: false })
    .limit(10);

  if (error) {
    await sendMessage(chatId, `⚠️ ${error.message}`);
    return;
  }
  if (!data?.length) {
    await sendMessage(chatId, '✅ Hozircha xavfli APK topilmagan.');
    return;
  }
  const sevEmoji: Record<string, string> = { low: '🟡', medium: '🟠', high: '🔴', critical: '⛔' };
  const lines = ['*🚨 Xavfli APKlar:*', ''];
  for (const t of data) {
    const label = escape(t.app_label || t.package_name || t.apk_hash.slice(0, 12));
    lines.push(`${sevEmoji[t.severity] ?? '⚪'} ${label} — *${t.seen_count}x*`);
  }
  await sendMessage(chatId, lines.join('\n'), { parseMode: 'Markdown' });
}

async function handleDevices(chatId: number) {
  const sb = db();
  const { data, error } = await sb
    .from('devices')
    .select('name, android_ver, app_ver, last_seen')
    .order('last_seen', { ascending: false })
    .limit(10);

  if (error) {
    await sendMessage(chatId, `⚠️ ${error.message}`);
    return;
  }
  if (!data?.length) {
    await sendMessage(chatId, 'Hech qanday qurilma ulanmagan.');
    return;
  }
  const lines = ['*📱 Qurilmalar:*', ''];
  for (const d of data) {
    const t = new Date(d.last_seen).toISOString().replace('T', ' ').slice(0, 16);
    lines.push(`• ${escape(d.name ?? 'Nomsiz')} — Android ${escape(d.android_ver ?? '?')} (oxirgi: ${t})`);
  }
  await sendMessage(chatId, lines.join('\n'), { parseMode: 'Markdown' });
}

function parseLimit(text: string, def: number): number {
  const m = text.match(/\/last\s+(\d+)/);
  if (!m) return def;
  const n = Number(m[1]);
  return Math.min(Math.max(n, 1), 20);
}
