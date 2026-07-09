-- 16_groups.sql — qurilma GURUHLARI (rang-yorliq segmentatsiya) + a'zo profili
--
-- Nima uchun: egasi panelda butun flotni bitta ro'yxatda ko'radi. Bu migratsiya
-- qurilmalarni GURUHlarga (nom + rang) bo'lish imkonini beradi. Qurilma guruhga
-- ILOVADAN qo'shiladi: foydalanuvchi qisqa KOD kiritadi yoki QR skanerlaydi, so'ng
-- o'z ism/familiya/telefonini yozadi. Guruh MAJBURIY EMAS — group_id NULL bo'lsa
-- qurilma avvalgidek anonim ishlaydi (hech narsa buzilmaydi).
--
-- Modeli (foydalanuvchi tanlovi): guruh = rang-YORLIQ. Egasi HAMMA qurilmani ko'radi
-- (ko'p-ijara/rol EMAS — loyiha qoidasi "owner + 1 admin, rolsiz"). Bitta qurilma =
-- bitta guruh (yangi kod eskisini almashtiradi).
--
-- Maxfiylik: member_first/last/phone — foydalanuvchi guruhga qo'shilishda O'ZI ochiq
-- kiritadigan ma'lumot (jim yig'ilmaydi; CloudTelemetry hech qachon telefon raqamini
-- yubormaydi). Ilova formasida "bu ma'lumot guruh egasiga ko'rinadi" deb ogohlantiriladi.
--
-- Idempotent — Supabase SQL Editor'da qo'lda ishlatish uchun (loyiha qoidasi: CLI yo'q,
-- migratsiyalar qo'lda). Bir necha marta ishga tushirsa ham xavfsiz.

-- ============================================================================
-- 1) DEVICE_GROUPS — guruhlar (nom + rang + qo'shilish kodi)
-- ============================================================================
create table if not exists device_groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,                        -- "Navoiy maktabi"
  color       text not null default '#C2143D',      -- #RRGGBB (brend palitrasidan)
  join_code   text unique not null,                 -- 6-8 belgi (A-Z2-9), server generatsiya qiladi
  created_at  timestamptz not null default now()
);

create index if not exists idx_device_groups_code on device_groups(join_code);

alter table device_groups enable row level security;  -- anon ko'rmaydi; service_role o'tadi

-- ============================================================================
-- 2) DEVICES — guruh bog'lanishi + a'zo profili
-- Guruh o'chirilsa qurilma o'chmaydi — group_id NULL bo'ladi (on delete set null).
-- ============================================================================
alter table devices add column if not exists group_id     uuid references device_groups(id) on delete set null;
alter table devices add column if not exists member_first  text;   -- Ism
alter table devices add column if not exists member_last   text;   -- Familiya
alter table devices add column if not exists member_phone  text;   -- +998…

create index if not exists idx_devices_group on devices(group_id);

-- ============================================================================
-- 3) v_devices_with_counts — guruh + a'zo maydonlarini qo'shamiz
-- DIQQAT: 06_ip.sql'dagi to'liq ustun ro'yxatini SAQLAB, oxiriga yangilarini qo'shamiz.
-- Yangi ustunlar oxiriga tushsa ham "create or replace" ustun tartibida xato berishi
-- mumkin (join tartibi), shuning uchun 06_ip.sql uslubida drop+create ishlatamiz.
-- ============================================================================
drop view if exists v_devices_with_counts;
create view v_devices_with_counts as
select
  d.id, d.name, d.android_ver, d.app_ver, d.created_at, d.last_seen,
  d.country, d.city, d.lat, d.lng, d.ip, d.risk_score, d.last_verdict, d.last_scan_at,
  d.group_id,
  g.name  as group_name,
  g.color as group_color,
  d.member_first, d.member_last, d.member_phone,
  coalesce(c.scan_count, 0)    as scan_count,
  coalesce(c.danger_count, 0)  as danger_count
from devices d
left join device_groups g on g.id = d.group_id
left join (
  select
    device_id,
    count(*) as scan_count,
    count(*) filter (where verdict = 'danger') as danger_count
  from scans
  group by device_id
) c on c.device_id = d.id;

-- CL-05 (13_audit): drop+create vyuxa security_invoker + revoke'ni NOLGA tushiradi —
-- qayta qo'llaymiz, aks holda anon/authenticated view'ni o'qib qolishi mumkin.
alter view v_devices_with_counts set (security_invoker = on);
revoke all on v_devices_with_counts from anon, authenticated;

-- ============================================================================
-- 4) v_group_members — panel/Excel "rostri" uchun: kim qaysi guruhda
-- Egasi guruh bo'yicha a'zolarni (ism/familiya/telefon + qurilma) ko'radi/eksport qiladi.
-- ============================================================================
drop view if exists v_group_members;
create view v_group_members as
select
  d.id            as device_id,
  d.group_id,
  g.name          as group_name,
  g.color         as group_color,
  d.member_first,
  d.member_last,
  d.member_phone,
  d.name          as device_name,
  d.android_ver,
  d.app_ver,
  d.city,
  d.risk_score,
  d.last_verdict,
  d.last_seen,
  coalesce(c.scan_count, 0)   as scan_count,
  coalesce(c.danger_count, 0) as danger_count
from devices d
join device_groups g on g.id = d.group_id
left join (
  select device_id,
         count(*) as scan_count,
         count(*) filter (where verdict = 'danger') as danger_count
  from scans
  group by device_id
) c on c.device_id = d.id;

alter view v_group_members set (security_invoker = on);
revoke all on v_group_members from anon, authenticated;
