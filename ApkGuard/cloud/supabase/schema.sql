-- KiberQalqon — Supabase schema
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni qo'yib ishga tushirish

-- ============================================================================
-- 1) DEVICES — telefonlar ro'yxati
-- ============================================================================
create table if not exists devices (
  id            uuid primary key default gen_random_uuid(),
  device_token  text unique not null,            -- Android tomonda saqlanadigan secret
  name          text,                            -- "Mening Samsung S23"
  android_ver   text,
  app_ver       text,
  created_at    timestamptz not null default now(),
  last_seen     timestamptz not null default now()
);

create index if not exists idx_devices_last_seen on devices(last_seen desc);

-- ============================================================================
-- 2) SCANS — har bir APK skan tarixi
-- ============================================================================
create table if not exists scans (
  id            bigserial primary key,
  device_id     uuid references devices(id) on delete cascade,
  apk_hash      text not null,                   -- SHA-256 hex (64 char)
  package_name  text,
  app_label     text,
  apk_size      bigint,
  verdict       text not null check (verdict in ('safe','suspicious','danger','error')),
  risk_score    int default 0,
  scan_duration_ms int default 0,                -- skan davomiyligi (ms); 0 = eski/noma'lum
  reasons       jsonb default '[]'::jsonb,       -- ["READ_SMS", "BIND_ACCESSIBILITY_SERVICE", ...]
  perms         jsonb default '[]'::jsonb,
  scanned_at    timestamptz not null default now()
);

-- Migratsiya: mavjud bazada scans jadvali bo'lsa, ustunni idempotent qo'shamiz
-- (create table if not exists eski jadvalga yangi ustun QO'SHMAYDI). SQL Editor'da
-- butun faylni qayta ishga tushirsangiz ham xavfsiz.
alter table scans add column if not exists scan_duration_ms int default 0;

create index if not exists idx_scans_device on scans(device_id, scanned_at desc);
create index if not exists idx_scans_hash on scans(apk_hash);
create index if not exists idx_scans_verdict on scans(verdict, scanned_at desc);
create index if not exists idx_scans_duration on scans(scan_duration_ms) where scan_duration_ms > 0;

-- ============================================================================
-- 3) THREATS — topilgan vrias xeshlari (qora ro'yxat)
-- Bir xil hash bir necha telefonda topilsa, count oshadi
-- ============================================================================
create table if not exists threats (
  apk_hash      text primary key,
  package_name  text,
  app_label     text,
  category      text,                            -- "trojan", "spyware", "sms_stealer", ...
  severity      text check (severity in ('low','medium','high','critical')),
  first_seen    timestamptz not null default now(),
  last_seen     timestamptz not null default now(),
  seen_count    int not null default 1,
  notes         text
);

create index if not exists idx_threats_last_seen on threats(last_seen desc);
create index if not exists idx_threats_severity on threats(severity);

-- ============================================================================
-- 4) ADMIN_CHATS — bot kim bilan gaplasha oladi (whitelist)
-- ============================================================================
create table if not exists admin_chats (
  chat_id       bigint primary key,
  username      text,
  role          text default 'admin' check (role in ('admin','viewer')),
  added_at      timestamptz not null default now()
);

-- ============================================================================
-- 5) NOTIFICATIONS — Telegramga yuborilgan xabarlar tarixi (debug uchun)
-- ============================================================================
create table if not exists notifications (
  id            bigserial primary key,
  chat_id       bigint,
  scan_id       bigint references scans(id) on delete set null,
  text          text,
  sent_at       timestamptz not null default now(),
  ok            boolean default true,
  error         text
);

create index if not exists idx_notif_sent on notifications(sent_at desc);

-- ============================================================================
-- RLS — Row Level Security
-- Serverdan service_role key ishlatamiz, shuning uchun RLS yoqilgan, lekin
-- service_role hamma policiyani aylanib o'tadi. anon kalitiga hech narsa berilmagan.
-- ============================================================================
alter table devices         enable row level security;
alter table scans           enable row level security;
alter table threats         enable row level security;
alter table admin_chats     enable row level security;
alter table notifications   enable row level security;

-- Default: anon kaliti hech narsa ko'rmaydi. service_role hamma narsani ko'radi.

-- ============================================================================
-- HELPER FUNCTIONS
-- ============================================================================

-- Threat ko'rilganda count va last_seen ni yangilash
create or replace function upsert_threat(
  p_hash text,
  p_package text,
  p_label text,
  p_category text,
  p_severity text
) returns void as $$
begin
  insert into threats (apk_hash, package_name, app_label, category, severity)
  values (p_hash, p_package, p_label, p_category, p_severity)
  on conflict (apk_hash) do update set
    last_seen = now(),
    seen_count = threats.seen_count + 1,
    severity = greatest_severity(threats.severity, excluded.severity);
end;
$$ language plpgsql;

create or replace function greatest_severity(a text, b text) returns text as $$
declare
  rank_a int;
  rank_b int;
begin
  rank_a := case a when 'low' then 1 when 'medium' then 2 when 'high' then 3 when 'critical' then 4 else 0 end;
  rank_b := case b when 'low' then 1 when 'medium' then 2 when 'high' then 3 when 'critical' then 4 else 0 end;
  return case when rank_a >= rank_b then a else b end;
end;
$$ language plpgsql immutable;

-- ============================================================================
-- VIEWS — statistika uchun qulay
-- ============================================================================
create or replace view v_stats_today as
select
  count(*)                                       as total_scans,
  count(*) filter (where verdict = 'danger')     as danger_count,
  count(*) filter (where verdict = 'suspicious') as suspicious_count,
  count(*) filter (where verdict = 'safe')       as safe_count,
  count(distinct device_id)                      as active_devices,
  -- Tekshiruv tezligi (faqat o'lchangan skanlar; eski 0-davomiyli yozuvlar tashlanadi).
  count(*) filter (where scan_duration_ms > 0)   as perf_count,
  coalesce(round(avg(scan_duration_ms) filter (where scan_duration_ms > 0))::int, 0) as avg_duration_ms,
  coalesce(round(percentile_cont(0.5) within group (order by scan_duration_ms)
           filter (where scan_duration_ms > 0))::int, 0) as median_duration_ms,
  coalesce(round(percentile_cont(0.95) within group (order by scan_duration_ms)
           filter (where scan_duration_ms > 0))::int, 0) as p95_duration_ms
from scans
where scanned_at >= current_date;

create or replace view v_devices_with_counts as
select
  d.id, d.name, d.android_ver, d.app_ver, d.created_at, d.last_seen,
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

create or replace view v_recent_threats as
select s.scanned_at, d.name as device_name, s.app_label, s.package_name,
       s.apk_hash, s.verdict, s.reasons
from scans s
left join devices d on d.id = s.device_id
where s.verdict in ('danger','suspicious')
order by s.scanned_at desc
limit 50;
