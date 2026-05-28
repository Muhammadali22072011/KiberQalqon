#!/usr/bin/env node
// Telegram webhook'ni Vercel URL'iga ulash.
// Foydalanish:
//   TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... VERCEL_URL=https://xxx.vercel.app node scripts/set-webhook.mjs

const token = process.env.TELEGRAM_BOT_TOKEN;
const secret = process.env.TELEGRAM_WEBHOOK_SECRET;
const base = process.env.VERCEL_URL;

if (!token || !secret || !base) {
  console.error('Kerak: TELEGRAM_BOT_TOKEN, TELEGRAM_WEBHOOK_SECRET, VERCEL_URL');
  process.exit(1);
}

const url = `${base.replace(/\/$/, '')}/api/telegram/webhook`;
const params = new URLSearchParams({
  url,
  secret_token: secret,
  allowed_updates: JSON.stringify(['message']),
  drop_pending_updates: 'true',
});

const r = await fetch(`https://api.telegram.org/bot${token}/setWebhook?${params}`);
const data = await r.json();
console.log(JSON.stringify(data, null, 2));
if (!data.ok) process.exit(1);
console.log(`\n✅ Webhook o'rnatildi: ${url}`);
