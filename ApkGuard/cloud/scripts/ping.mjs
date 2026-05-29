#!/usr/bin/env node
// Sanity check — Supabase va Vercel ishlayotganini tekshiradi.
// /api/stats endpointi o'qish uchun ADMIN_SECRET talab qiladi (DEVICE emas!).
// Foydalanish: VERCEL_URL=https://xxx.vercel.app ADMIN_SECRET=... node scripts/ping.mjs

const base = process.env.VERCEL_URL;
const secret = process.env.ADMIN_SECRET;

if (!base || !secret) {
  console.error('Kerak: VERCEL_URL, ADMIN_SECRET');
  process.exit(1);
}

const r = await fetch(`${base.replace(/\/$/, '')}/api/stats`, {
  headers: { 'x-admin-secret': secret },
});
const data = await r.json();
console.log(JSON.stringify(data, null, 2));
if (!data.ok) process.exit(1);
