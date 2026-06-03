-- KiberQalqon — 09_totp_replay.sql  (#44: TOTP replay himoyasi)
-- 2FA kodi (TOTP) bir martagina ishlatilsin: o'g'irlangan/kuzatilgan kod ~90s ichida
-- qayta yuborilib kira olmasligi uchun eng katta QABUL QILINGAN qadam (counter)ni saqlaymiz.
-- api/admin/login.ts: yangi kod counter'i <= saqlangani bo'lsa rad etiladi, aks holda yangilanadi.
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent: bir necha marta ishga tushirsa ham xavfsiz.

create table if not exists auth_totp (
  id            text primary key,        -- bitta egasi → 'owner'
  last_counter  bigint not null default 0,
  used_at       timestamptz not null default now()
);

-- RLS: boshqa jadvallar kabi — anon hech narsa ko'rmaydi, server service_role bilan ishlaydi.
alter table auth_totp enable row level security;
