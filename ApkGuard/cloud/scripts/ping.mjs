#!/usr/bin/env node
// Sanity check — Supabase va Vercel ishlayotganini tekshiradi.
// Foydalanish: VERCEL_URL=https://xxx.vercel.app DEVICE_SHARED_SECRET=... node scripts/ping.mjs

const base = process.env.VERCEL_URL;
const secret = process.env.DEVICE_SHARED_SECRET;

if (!base || !secret) {
  console.error('Kerak: VERCEL_URL, DEVICE_SHARED_SECRET');
  process.exit(1);
}

const r = await fetch(`${base.replace(/\/$/, '')}/api/stats`, {
  headers: { 'x-device-secret': secret },
});
const data = await r.json();
console.log(JSON.stringify(data, null, 2));
if (!data.ok) process.exit(1);
