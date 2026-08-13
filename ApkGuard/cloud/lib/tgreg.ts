// tgreg.ts — RO'YXATDAN O'TISH boti (ochiq bot) suhbat mantiqi.
//
// Oqim:
//   1. Ilova /api/device/tgstart chaqiradi → server bir martalik TOKEN qaytaradi.
//   2. Ilova https://t.me/<bot>?start=<token> ochadi. Telegram START'ni O'ZI bosadi.
//   3. Bot tokendan qurilmani topadi → ism so'raydi → «Raqamni yuborish» tugmasi.
//   4. Telefon TASDIQLANGAN holda keladi (request_contact) → step='done'.
//   5. Ilova /api/device/tgstatus pollingida 'done' ko'radi va bosh ekranga o'tadi.
//
// Xavfsizlik qoidalari:
//   • Token bir martalik va 24 soatdan keyin yaroqsiz (eski token bilan begona
//     qurilmani "ro'yxatdan o'tkazib" bo'lmaydi).
//   • contact.user_id === from.id tekshiriladi — foydalanuvchi BOSHQA odamning
//     kontaktini yuborolmaydi (Telegram klaviaturasi begona kontaktni ham beradi).
//   • Foydalanuvchi matni hech qachon Markdown sifatida yuborilmaydi (parse_mode
//     yo'q) — aks holda kiritilgan `*_[` belgilar xabarni buzadi yoki inyeksiya.
//   • Guruh chatlari e'tiborsiz qoldiriladi — faqat private.
import { db } from './supabase.js';
import { sendMessage, regBotUsername } from './telegram.js';

export type TgRegUpdate = {
  update_id?: number;
  message?: {
    message_id?: number;
    chat: { id: number; type: string };
    from?: { id: number; username?: string; first_name?: string };
    text?: string;
    contact?: { phone_number?: string; user_id?: number; first_name?: string; last_name?: string };
  };
  my_chat_member?: {
    chat: { id: number; type: string };
    new_chat_member?: { status?: string };
  };
};

const TOKEN_RE = /^[a-f0-9]{32}$/;
const TOKEN_TTL_MS = 24 * 3600 * 1000;

/** Telefonni normallashtiradi: faqat + va raqamlar, 7–15 raqam. */
function normPhone(v: unknown): string {
  if (typeof v !== 'string') return '';
  let p = v.trim().replace(/[^\d+]/g, '');
  if (!p.startsWith('+')) p = `+${p}`;
  const digits = p.replace(/\D/g, '');
  if (digits.length < 7 || digits.length > 15) return '';
  return p.slice(0, 20);
}

/** «Raqamni yuborish» tugmasi — bosilganda Telegram raqamni O'ZI biriktiradi. */
const PHONE_KEYBOARD = {
  keyboard: [[{ text: '📱 Raqamni yuborish', request_contact: true }]],
  resize_keyboard: true,
  one_time_keyboard: true,
};

/** Klaviaturani olib tashlash. */
const REMOVE_KEYBOARD = { remove_keyboard: true };

/** Muvaffaqiyat xabaridagi «UzGuard'ga qaytish» tugmasi (PUBLIC_BASE_URL bo'lsa). */
function backButton(): Record<string, unknown> | undefined {
  const base = (process.env.PUBLIC_BASE_URL ?? '').trim().replace(/\/+$/, '');
  if (!base.startsWith('https://')) return undefined;
  return { inline_keyboard: [[{ text: '🛡 UzGuard\'ga qaytish', url: `${base}/back.html` }]] };
}

const send = (chatId: number, text: string, replyMarkup?: Record<string, unknown>) =>
  sendMessage(chatId, text, { bot: 'reg', replyMarkup });

/**
 * Ro'yxatdan o'tish botiga kelgan bitta update'ni qayta ishlaydi.
 * HECH QACHON otmaydi — webhook Telegram'ga doim 200 qaytarishi kerak (aks holda
 * Telegram o'sha update'ni soatlab qayta yuboraveradi).
 */
export async function handleRegUpdate(u: TgRegUpdate): Promise<void> {
  // Bot bloklandi/blokdan chiqdi → rassilka ro'yxatini yangilaymiz.
  if (u.my_chat_member) {
    const status = u.my_chat_member.new_chat_member?.status;
    const chatId = u.my_chat_member.chat?.id;
    if (chatId && (status === 'kicked' || status === 'member')) {
      const sb = db();
      const { error } = await sb
        .from('tg_registrations')
        .update({ blocked: status === 'kicked' })
        .eq('chat_id', chatId);
      if (error) console.error(`[tgreg] block flag: ${error.message}`);
    }
    return;
  }

  const msg = u.message;
  if (!msg?.chat) return;
  if (msg.chat.type !== 'private') return;   // guruhga qo'shilgan bo'lsa — jim

  const chatId = msg.chat.id;

  if (msg.contact) return handleContact(chatId, msg);
  if (typeof msg.text === 'string') return handleText(chatId, msg);
}

// --- /start <token> va oddiy matn --------------------------------------------

async function handleText(chatId: number, msg: NonNullable<TgRegUpdate['message']>): Promise<void> {
  const text = (msg.text ?? '').trim();

  if (text.startsWith('/start')) {
    const arg = text.slice('/start'.length).trim();
    return handleStart(chatId, msg, arg);
  }
  if (text === '/help') {
    await send(chatId, HELP_TEXT);
    return;
  }

  // Buyruq emas — kutilayotgan qadamga qarab javob beramiz.
  const row = await pendingRow(chatId);
  if (!row) {
    await send(chatId, HELP_TEXT);
    return;
  }
  if (row.step === 'await_name') return saveName(chatId, row.id, text);
  if (row.step === 'await_phone') {
    // Foydalanuvchi raqamni QO'LDA yozdi — qabul qilmaymiz (tasdiqlanmagan raqam).
    await send(chatId, 'Raqamni qo\'lda yozish shart emas — pastdagi «📱 Raqamni yuborish» tugmasini bosing.', PHONE_KEYBOARD);
  }
}

async function handleStart(
  chatId: number,
  msg: NonNullable<TgRegUpdate['message']>,
  arg: string,
): Promise<void> {
  const sb = db();

  if (!TOKEN_RE.test(arg)) {
    // Tokensiz kirish — ilovadan tugma orqali kelmagan.
    const done = await doneProfile(chatId);
    if (done) {
      await send(chatId, `✅ Siz allaqachon ro'yxatdan o'tgansiz.\n\nIsm: ${done.full_name ?? '—'}\nRaqam: ${done.phone ?? '—'}\n\nUzGuard ilovasini oching.`, REMOVE_KEYBOARD);
    } else {
      await send(chatId, HELP_TEXT);
    }
    return;
  }

  const { data: row, error } = await sb
    .from('tg_registrations')
    .select('id, step, created_at')
    .eq('token', arg)
    .maybeSingle();
  if (error) { console.error(`[tgreg] token lookup: ${error.message}`); await send(chatId, ERR_TEXT); return; }

  if (!row) {
    await send(chatId, '⚠️ Havola yaroqsiz. UzGuard ilovasini oching va «Ro\'yxatdan o\'tish» tugmasini qaytadan bosing.');
    return;
  }
  if (Date.now() - new Date(row.created_at as string).getTime() > TOKEN_TTL_MS) {
    await send(chatId, '⚠️ Havolaning muddati tugagan. UzGuard ilovasida tugmani qaytadan bosing.');
    return;
  }
  if (row.step === 'done') {
    await send(chatId, '✅ Bu qurilma allaqachon ro\'yxatdan o\'tgan. UzGuard ilovasiga qayting.', backButton());
    return;
  }

  // Shu odam avval boshqa qurilmada ro'yxatdan o'tgan bo'lsa — qayta so'ramaymiz
  // (ilovani qayta o'rnatgan yoki ikkinchi telefoni). Profilni ko'chirib, darhol
  // tugatamiz: bu qadam faqat "kim ekanini" bilish uchun, har safar takrorlash ortiqcha.
  const prev = await doneProfile(chatId);
  if (prev?.phone) {
    const { error: uErr } = await sb
      .from('tg_registrations')
      .update({
        chat_id: chatId,
        tg_user_id: msg.from?.id ?? null,
        tg_username: msg.from?.username ?? null,
        full_name: prev.full_name,
        phone: prev.phone,
        step: 'done',
        blocked: false,
        linked_at: new Date().toISOString(),
        done_at: new Date().toISOString(),
      })
      .eq('id', row.id);
    if (uErr) { console.error(`[tgreg] reuse profile: ${uErr.message}`); await send(chatId, ERR_TEXT); return; }
    await send(chatId, `✅ Xush kelibsiz, ${prev.full_name ?? ''}!\nQurilmangiz ulandi — UzGuard ilovasiga qayting.`.trim(), backButton());
    return;
  }

  const { error: uErr } = await sb
    .from('tg_registrations')
    .update({
      chat_id: chatId,
      tg_user_id: msg.from?.id ?? null,
      tg_username: msg.from?.username ?? null,
      step: 'await_name',
      blocked: false,
      linked_at: new Date().toISOString(),
    })
    .eq('id', row.id);
  if (uErr) { console.error(`[tgreg] link: ${uErr.message}`); await send(chatId, ERR_TEXT); return; }

  await send(chatId, WELCOME_TEXT, REMOVE_KEYBOARD);
}

async function saveName(chatId: number, rowId: number, raw: string): Promise<void> {
  const name = raw.replace(/\s+/g, ' ').trim().slice(0, 60);
  if (name.length < 2 || name.startsWith('/')) {
    await send(chatId, 'Ismingizni yozing (kamida 2 ta harf). Masalan: Alisher Usmonov');
    return;
  }
  const sb = db();
  const { error } = await sb
    .from('tg_registrations')
    .update({ full_name: name, step: 'await_phone' })
    .eq('id', rowId);
  if (error) { console.error(`[tgreg] save name: ${error.message}`); await send(chatId, ERR_TEXT); return; }

  await send(
    chatId,
    `Rahmat, ${name}!\n\nEndi telefon raqamingizni yuboring — pastdagi «📱 Raqamni yuborish» tugmasini bosing.\n\nRaqam faqat UzGuard xabarnomalari uchun ishlatiladi va uchinchi shaxslarga berilmaydi.`,
    PHONE_KEYBOARD,
  );
}

// --- Kontakt (telefon) --------------------------------------------------------

async function handleContact(chatId: number, msg: NonNullable<TgRegUpdate['message']>): Promise<void> {
  const contact = msg.contact!;
  // Begona kontakt: Telegram klaviaturasi boshqa odamning kontaktini ham yuborishga
  // imkon beradi. Faqat YUBORUVCHINING o'z raqamini qabul qilamiz.
  if (!contact.user_id || !msg.from?.id || contact.user_id !== msg.from.id) {
    await send(chatId, '⚠️ Iltimos, O\'ZINGIZNING raqamingizni yuboring — «📱 Raqamni yuborish» tugmasi orqali.', PHONE_KEYBOARD);
    return;
  }
  const phone = normPhone(contact.phone_number);
  if (!phone) {
    await send(chatId, '⚠️ Raqam o\'qilmadi. Qaytadan urinib ko\'ring.', PHONE_KEYBOARD);
    return;
  }

  const row = await pendingRow(chatId);
  if (!row) {
    await send(chatId, 'Avval UzGuard ilovasidagi «Ro\'yxatdan o\'tish» tugmasini bosing.', REMOVE_KEYBOARD);
    return;
  }

  const sb = db();
  // Ism hali yo'q bo'lsa (foydalanuvchi ismni o'tkazib yuborib raqamni bosgan) —
  // Telegram profilidagi ismni olamiz, oqim to'xtab qolmasin.
  const fallbackName = [contact.first_name, contact.last_name].filter(Boolean).join(' ').trim() || null;
  const { error } = await sb
    .from('tg_registrations')
    .update({
      phone,
      full_name: row.full_name ?? fallbackName,
      step: 'done',
      done_at: new Date().toISOString(),
    })
    .eq('id', row.id);
  if (error) { console.error(`[tgreg] save phone: ${error.message}`); await send(chatId, ERR_TEXT); return; }

  await send(chatId, '✅ Ro\'yxatdan o\'tdingiz!', REMOVE_KEYBOARD);
  await send(
    chatId,
    'Qurilmangiz UzGuard\'ga ulandi. Endi ilovaga qayting — himoya ishga tushadi.\n\nMuhim yangiliklar va virus ogohlantirishlari shu yerga keladi.',
    backButton(),
  );
}

// --- Yordamchilar -------------------------------------------------------------

type PendingRow = { id: number; step: string; full_name: string | null };

/** Shu chat uchun tugallanmagan oxirgi qator (await_name / await_phone). */
async function pendingRow(chatId: number): Promise<PendingRow | null> {
  const sb = db();
  const { data, error } = await sb
    .from('tg_registrations')
    .select('id, step, full_name')
    .eq('chat_id', chatId)
    .in('step', ['await_name', 'await_phone'])
    .order('id', { ascending: false })
    .limit(1)
    .maybeSingle();
  if (error) { console.error(`[tgreg] pending lookup: ${error.message}`); return null; }
  return (data as PendingRow | null) ?? null;
}

/** Shu chat avval to'liq ro'yxatdan o'tgan bo'lsa — profili. */
async function doneProfile(chatId: number): Promise<{ full_name: string | null; phone: string | null } | null> {
  const sb = db();
  const { data, error } = await sb
    .from('tg_registrations')
    .select('full_name, phone')
    .eq('chat_id', chatId)
    .eq('step', 'done')
    .order('done_at', { ascending: false })
    .limit(1)
    .maybeSingle();
  if (error) { console.error(`[tgreg] done lookup: ${error.message}`); return null; }
  return (data as { full_name: string | null; phone: string | null } | null) ?? null;
}

const WELCOME_TEXT = [
  '🛡 UzGuard\'ga xush kelibsiz!',
  '',
  'Bu bot ilovani ro\'yxatdan o\'tkazadi va sizga virus ogohlantirishlari yuboradi.',
  '',
  'Ismingiz va familiyangizni yozing:',
].join('\n');

const HELP_TEXT = [
  '🛡 UzGuard — ro\'yxatdan o\'tish boti',
  '',
  'Ro\'yxatdan o\'tish UzGuard ilovasidan boshlanadi:',
  'ilovani oching → «Ro\'yxatdan o\'tish» tugmasini bosing.',
  '',
  'Ilova hali o\'rnatilmagan bo\'lsa, avval uni o\'rnating.',
].join('\n');

const ERR_TEXT = '⚠️ Texnik xato. Birozdan keyin qayta urinib ko\'ring.';

/** Chuqur havola: https://t.me/<bot>?start=<token>. Username sozlanmagan bo'lsa null. */
export function regDeepLink(token: string): string | null {
  const u = regBotUsername();
  return u ? `https://t.me/${u}?start=${token}` : null;
}
