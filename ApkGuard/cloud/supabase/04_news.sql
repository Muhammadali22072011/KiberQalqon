-- KiberQalqon — 04_news.sql
-- Yangiliklar / e'lonlar paneli: egasi panelda e'lon yozadi, hammaga ko'rinadi.
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent (IF NOT EXISTS) — qayta ishga tushsa xavfsiz.

-- ============================================================================
-- NEWS — panel bosh sahifasidagi "Yangiliklar" lentasi.
--   level — e'lon darajasi: 'info' (oddiy), 'warning' (ogohlantirish), 'critical' (muhim)
--   pinned — yuqorida qotirib qo'yilgan e'lon (eng tepada chiqadi)
--   body — to'liq matn (lentada bosilganda ochiladi)
-- ============================================================================
create table if not exists news (
  id          uuid primary key default gen_random_uuid(),
  title       text not null,
  body        text not null default '',
  level       text not null default 'info',
  image_url   text,
  pinned      boolean not null default false,
  created_at  timestamptz not null default now()
);

-- Eski o'rnatishlar uchun: rasm ustunini idempotent qo'shamiz.
alter table news add column if not exists image_url text;

create index if not exists idx_news_created on news(created_at desc);

-- ============================================================================
-- RLS — boshqa jadvallar bilan bir xil: anon hech narsa ko'rmaydi,
-- server service_role kaliti bilan ishlaydi (panel /api/news orqali o'qiydi).
-- ============================================================================
alter table news enable row level security;

-- ============================================================================
-- Namuna e'lon (ixtiyoriy) — panel bo'sh ko'rinmasligi uchun.
-- ============================================================================
insert into news (title, body, level, pinned)
values (
  'KiberQalqon Cloud ishga tushdi',
  'Markaziy panel faol: xarita, jonli oqim, rollar va yangiliklar. Yangi e''lonlar shu yerda chiqadi.',
  'info',
  true
)
on conflict do nothing;
