-- ============================================================================
-- 16_panel_wave2_2026_07_09.sql — panel 2-to'lqin: kampaniya teglari, namuna
-- navbati, masofaviy buyruqlar, spike-alert throttle.
-- ============================================================================
-- Loyiha qoidasi: CLI yo'q — bu faylni Supabase SQL Editor'da BIR MARTA ishga
-- tushiring (11–15 kabi qo'lda). Hammasi IDEMPOTENT (qayta ishlatish xavfsiz).
--
-- Nima qo'shiladi:
--   #6  threats.family        — egasi qo'ygan kampaniya/oila yorlig'i (Ajina.Banker…)
--   #5  threats.review_status — 'pending' | 'confirmed' | 'dismissed'
--                               ('dismissed' → imzolangan feed'dan CHIQARILADI)
--   #6/#5 corroborated_threats RPC — family qaytaradi + 'dismissed' ni chiqaradi
--   #3  device_commands       — paneldan qurilmaga buyruq (masofadan qayta skan)
--   #9  alert_state           — spike-alert (ko'p DANGER qisqa oynada) throttle'i
-- ============================================================================

-- ── #6 + #5: threats jadvaliga yangi ustunlar (create table if not exists eski
--    jadvalga ustun QO'SHMAYDI — shuning uchun alter ... if not exists). ──────
alter table threats add column if not exists family text;
alter table threats add column if not exists review_status text
  check (review_status in ('pending', 'confirmed', 'dismissed')) default 'pending';

create index if not exists idx_threats_review on threats(review_status);
create index if not exists idx_threats_family on threats(family) where family is not null;

-- ── #6/#5: feed RPC yangilanishi — family qaytaradi + 'dismissed' ni chiqaradi.
--    MUHIM: migratsiya 11'dagi RPC 5 ustun qaytaradi; biz `family` qo'shib 6 ustunga
--    o'zgartiramiz. Postgres `create or replace` FUNKSIYA QAYTARISH TIPINI o'zgartira
--    OLMAYDI (ERROR: cannot change return type of existing function) → avval DROP shart. ──
drop function if exists corroborated_threats(int);
create or replace function corroborated_threats(min_devices int default 2)
returns table (apk_hash text, package_name text, category text, family text, severity text, last_seen timestamptz)
language sql
stable
as $$
  select t.apk_hash, t.package_name, t.category, t.family, t.severity, t.last_seen
  from threats t
  where t.severity in ('high', 'critical')
    and coalesce(t.review_status, 'pending') <> 'dismissed'   -- egasi rad etgan → feed'ga tushmaydi
    and (
      select count(distinct s.device_id)
      from scans s
      where s.apk_hash = t.apk_hash
        and s.verdict in ('danger', 'suspicious')
    ) >= greatest(min_devices, 1)
  order by t.last_seen desc
  limit 2000;
$$;
revoke execute on function corroborated_threats(int) from public, anon, authenticated;

-- ── #3: device_commands — paneldan (EGASI) qurilmaga buyruq. Qurilma /api/device/poll
--    orqali o'z buyruqlarini oladi (at-most-once: poll paytida 'done' ga o'tkaziladi).
--    type hozircha 'rescan' (masofadan to'liq qayta skan). payload — kelajak uchun. ──
create table if not exists device_commands (
  id           bigserial primary key,
  device_id    uuid not null references devices(id) on delete cascade,
  type         text not null,                                          -- 'rescan' | (kelajak: 'news' | 'self_update')
  payload      jsonb default '{}'::jsonb,
  status       text not null check (status in ('pending', 'done', 'failed')) default 'pending',
  created_by   text,                                                   -- audit: 'owner'
  created_at   timestamptz not null default now(),
  delivered_at timestamptz
);

create index if not exists idx_device_commands_pending
  on device_commands(device_id, created_at) where status = 'pending';

alter table device_commands enable row level security;  -- anon ko'rmaydi; service_role o'tadi

-- ── #9: alert_state — spike-alert throttle (bitta kalit = bitta alert turi). ──
create table if not exists alert_state (
  key      text primary key,
  last_at  timestamptz not null default now()
);
alter table alert_state enable row level security;
