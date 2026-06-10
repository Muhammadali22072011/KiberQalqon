-- ============================================================
-- KiberQalqon — 10_device_auth.sql
-- Per-device YOZUV autentifikatsiyasi: nonce takror-himoya (replay) jadvali.
--
-- Yangi sxema QO'SHIMCHA: eski qurilmalar (faqat x-device-secret) ishlashda DAVOM ETADI.
-- Imzolangan yozuvlar (x-device-token / x-signature / x-timestamp / x-nonce) bu jadvalga
-- nonce yozadi; takroriy nonce = replay → rad etiladi (PRIMARY KEY unikalligi orqali).
--
-- Bajarish: Supabase Dashboard → SQL Editor → New query → shu faylni ishga tushiring.
-- Idempotent: bir necha marta ishga tushsa ham xavfsiz.
--
-- TOZALASH (jadval o'smasligi uchun vaqti-vaqti bilan SQL Editor'da ishga tushiring;
-- 15 daqiqadan eski nonce'lar foydasiz — imzo oynasi ±5 daq):
--   delete from device_nonces where created_at < now() - interval '15 minutes';
-- ============================================================

create table if not exists device_nonces (
  nonce        text primary key,
  device_token text not null,
  created_at   timestamptz not null default now()
);

create index if not exists device_nonces_created_at_idx on device_nonces (created_at);

-- Boshqa jadvallar kabi RLS yoqamiz: anon mijoz ko'rmaydi/yozmaydi; server service_role
-- kaliti bilan ishlaydi (RLS'ni chetlab o'tadi). Hech qanday public policy QO'SHILMAYDI —
-- nonce jadvaliga faqat backend yozadi.
alter table device_nonces enable row level security;
