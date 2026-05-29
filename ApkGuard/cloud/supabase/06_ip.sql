-- KiberQalqon — 06_ip.sql
-- Qurilmaning IP-manzilini saqlash + panelda ko'rsatish (egasi/admin uchun).
-- IP server tomonda x-forwarded-for'dan olinadi (qurilma yubormaydi).
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent: bir necha marta ishga tushirsa ham xavfsiz.

-- ============================================================================
-- 1) DEVICES jadvaliga ip ustuni
-- ============================================================================
alter table devices add column if not exists ip text;

-- ============================================================================
-- 2) v_devices_with_counts — ip qo'shamiz (drop+create, ustun tartibi uchun)
-- ============================================================================
drop view if exists v_devices_with_counts;
create view v_devices_with_counts as
select
  d.id, d.name, d.android_ver, d.app_ver, d.created_at, d.last_seen,
  d.country, d.city, d.lat, d.lng, d.ip, d.risk_score, d.last_verdict, d.last_scan_at,
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
-- 3) v_map_points — ip qo'shamiz (xarita popup'ida ko'rsatish uchun)
-- ============================================================================
drop view if exists v_map_points;
create view v_map_points as
select
  d.id,
  d.name,
  d.city,
  d.country,
  d.ip,
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
