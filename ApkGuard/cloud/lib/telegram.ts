const API = 'https://api.telegram.org';

function token(): string {
  const t = process.env.TELEGRAM_BOT_TOKEN;
  if (!t) throw new Error('TELEGRAM_BOT_TOKEN not set');
  return t;
}

export async function sendMessage(
  chatId: number | string,
  text: string,
  opts: { parseMode?: 'Markdown' | 'HTML'; silent?: boolean } = {}
): Promise<{ ok: boolean; error?: string }> {
  const body: Record<string, unknown> = {
    chat_id: chatId,
    text,
    disable_notification: opts.silent ?? false,
  };
  if (opts.parseMode) body.parse_mode = opts.parseMode;

  try {
    const r = await fetch(`${API}/bot${token()}/sendMessage`, {
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
