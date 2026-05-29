-- KiberQalqon — 02_geo.sql
-- Geo + risk migratsiyasi: xaritada qurilma nuqtalarini ko'rsatish uchun.
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent: bir necha marta ishga tushirsa ham xavfsiz (IF NOT EXISTS / OR REPLACE).

-- ============================================================================
-- 1) DEVICES jadvaliga geo + risk ustunlari
-- Geo — Vercel IP sarlavhalaridan (GPS emas): shahar darajasida, taxminiy.
-- risk_score / last_verdict — eng oxirgi skan natijasidan yangilanadi (panelda
-- nuqta rangi yashildan qizilgacha shu ball bo'yicha chiziladi).
-- ============================================================================
alter table devices add column if not exists country     text;
alter table devices add column if not exists city        text;
alter table devices add column if not exists lat         double precision;
alter table devices add column if not exists lng         double precision;
alter table devices add column if not exists risk_score  int  not null default 0;
alter table devices add column if not exists last_verdict text;
alter table devices add column if not exists last_scan_at timestamptz;

create index if not exists idx_devices_geo  on devices(lat, lng);
create index if not exists idx_devices_risk on devices(risk_score desc);

-- ============================================================================
-- 2) v_devices_with_counts — geo + risk maydonlarini qo'shamiz
-- (Panel qurilmalar ro'yxatini shu ko'rinishdan oladi.)
-- DIQQAT: yangi ustunlar OLDINGI ustunlar ORASIGA qo'shilgani uchun
-- "create or replace view" xato beradi (faqat oxiriga qo'shishga ruxsat).
-- Shuning uchun avval drop qilamiz — bu view'ga boshqa hech narsa bog'lanmagan.
-- ============================================================================
drop view if exists v_devices_with_counts;
create view v_devices_with_counts as
select
  d.id, d.name, d.android_ver, d.app_ver, d.created_at, d.last_seen,
  d.country, d.city, d.lat, d.lng, d.risk_score, d.last_verdict, d.last_scan_at,
  coalesce(c.scan_count, 0)    as scan_count,
  coalesce(c.danger_count, 0)  as danger_count
from devices d
left join (
  select
    device_id,
    count(*) as scan_count,
    count(*) filter (where verdict = 'danger') as danger_count
  from scans
  group by device_id
) c on c.device_id = d.id;

-- ============================================================================
-- 3) v_map_points — xarita uchun yengil ko'rinish (faqat koordinatasi borlar)
-- Panel /api/geo shu yerdan o'qiydi: minimal maydonlar, tez yuklanadi.
-- ============================================================================
drop view if exists v_map_points;
create view v_map_points as
select
  d.id,
  d.name,
  d.city,
  d.country,
  d.lat,
  d.lng,
  d.risk_score,
  d.last_verdict,
  d.last_seen,
  d.last_scan_at,
  coalesce(c.scan_count, 0)   as scan_count,
  coalesce(c.danger_count, 0) as danger_count
from devices d
left join (
  select device_id,
         count(*) as scan_count,
         count(*) filter (where verdict = 'danger') as danger_count
  from scans
  group by device_id
) c on c.device_id = d.id
where d.lat is not null and d.lng is not null;

-- ============================================================================
-- 4) v_recent_threats — jonli oqim uchun city + risk_score qo'shamiz.
-- Bu yerda ham drop+create — qayta ishga tushirishda ustun tartibi muammosi bo'lmasin.
-- ============================================================================
drop view if exists v_recent_threats;
create view v_recent_threats as
select s.scanned_at, d.name as device_name, s.app_label, s.package_name,
       s.apk_hash, s.verdict, s.reasons,
       d.city, s.risk_score
from scans s
left join devices d on d.id = s.device_id
where s.verdict in ('danger','suspicious')
order by s.scanned_at desc
limit 50;
