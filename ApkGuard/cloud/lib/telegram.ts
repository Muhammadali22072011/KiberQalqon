const API = 'https://api.telegram.org';

// Ikkita BOT bor:
//   'main' — egasi paneli (/stats, /threats…), FAQAT ADMIN_CHAT_IDS uchun.
//   'reg'  — ochiq ro'yxatdan o'tish boti (ilova ichidagi tugma shuni ochadi).
// Ular ataylab ajratilgan: ochiq bot minglab notanish chat'lardan xabar oladi, admin
// boti esa hech qachon. Bitta tokenni ikkalasiga ishlatib bo'lmaydi (Telegram bitta
// botga bitta webhook beradi).
export type BotKind = 'main' | 'reg';

function token(kind: BotKind = 'main'): string {
  const t = kind === 'reg' ? process.env.TELEGRAM_REG_BOT_TOKEN : process.env.TELEGRAM_BOT_TOKEN;
  if (!t) throw new Error(kind === 'reg' ? 'TELEGRAM_REG_BOT_TOKEN not set' : 'TELEGRAM_BOT_TOKEN not set');
  return t;
}

/** Ro'yxatdan o'tish botining @username'i (chuqur havola shundan quriladi). */
export function regBotUsername(): string | null {
  const u = (process.env.TELEGRAM_REG_BOT_USERNAME ?? '').trim().replace(/^@/, '');
  return /^[A-Za-z0-9_]{4,32}$/.test(u) ? u : null;
}

export async function sendMessage(
  chatId: number | string,
  text: string,
  opts: {
    parseMode?: 'Markdown' | 'HTML';
    silent?: boolean;
    bot?: BotKind;
    /** Telegram reply_markup (inline tugma / «Raqamni yuborish» klaviaturasi). */
    replyMarkup?: Record<string, unknown>;
  } = {}
): Promise<{ ok: boolean; error?: string }> {
  const body: Record<string, unknown> = {
    chat_id: chatId,
    text,
    disable_notification: opts.silent ?? false,
  };
  if (opts.parseMode) body.parse_mode = opts.parseMode;
  if (opts.replyMarkup) body.reply_markup = opts.replyMarkup;

  try {
    const r = await fetch(`${API}/bot${token(opts.bot ?? 'main')}/sendMessage`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = (await r.json()) as { ok: boolean; description?: string };
    return { ok: data.ok, error: data.description };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

export function adminChatIds(): number[] {
  const raw = process.env.ADMIN_CHAT_IDS ?? '';
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => Number(s))
    .filter((n) => Number.isFinite(n));
}

export function isAdmin(chatId: number): boolean {
  return adminChatIds().includes(chatId);
}
