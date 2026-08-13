#!/usr/bin/env node
// RO'YXATDAN O'TISH botining webhook'ini ulash (ochiq bot — ilovadagi tugma shuni ochadi).
// Egasi paneli boti uchun alohida skript bor: set-webhook.mjs.
//
// Foydalanish:
//   TELEGRAM_REG_BOT_TOKEN=... TELEGRAM_REG_WEBHOOK_SECRET=... VERCEL_URL=https://xxx.vercel.app \
//     node scripts/set-webhook-reg.mjs
//
// DIQQAT: URL'da ?bot=reg bor — ikkala bot bitta serverless funksiyada yashaydi
// (Vercel Hobby 12-funksiya limiti). Shu parametrsiz update'lar egasi paneliga
// tushadi va jimgina tashlab yuboriladi.

const token = process.env.TELEGRAM_REG_BOT_TOKEN;
const secret = process.env.TELEGRAM_REG_WEBHOOK_SECRET;
const base = process.env.VERCEL_URL;

if (!token || !secret || !base) {
  console.error('Kerak: TELEGRAM_REG_BOT_TOKEN, TELEGRAM_REG_WEBHOOK_SECRET, VERCEL_URL');
  process.exit(1);
}

const url = `${base.replace(/\/$/, '')}/api/telegram/webhook?bot=reg`;
const params = new URLSearchParams({
  url,
  secret_token: secret,
  // my_chat_member — foydalanuvchi botni BLOKLAGANDA keladi; shu bilan rassilka
  // ro'yxatidan chiqaramiz (aks holda har rassilkada Telegram'dan xato olaverardik).
  allowed_updates: JSON.stringify(['message', 'my_chat_member']),
  drop_pending_updates: 'true',
});

const r = await fetch(`https://api.telegram.org/bot${token}/setWebhook?${params}`);
const data = await r.json();
console.log(JSON.stringify(data, null, 2));
if (!data.ok) process.exit(1);
console.log(`\n✅ Ro'yxat boti webhook'i o'rnatildi: ${url}`);
