-- ============================================================================
-- 13_audit_2026_06_10.sql — 2026-06-10 audit remediatsiyasi (SQL qismi)
-- Qo'lda Supabase SQL Editor'da bajariladi (loyiha CLI ishlatmaydi).
-- O'z ichiga oladi: CL-01 (atomar login-hisoblagich), CL-05 (vyuxalar RLS'ni
-- aylanmasin), CL-06 (bugungi statistika Asia/Tashkent kuni bo'yicha).
-- ============================================================================

-- --- CL-01: ATOMAR muvaffaqiyatsiz-login hisoblagichi -----------------------
-- Eski kod select keyin upsert qilardi (atomar emas) — parallel brute-force
-- fail_count=0 o'qib hammasi 1 yozardi, lockout ishlamasdi. Bu RPC bitta atomar
-- insert ... on conflict do update bilan inkrement qiladi va locked_until'ni
-- to'g'ridan-to'g'ri SQL'da hisoblaydi.
create or replace function record_auth_failure(
  p_key text, p_max_fails int, p_base_lock int, p_max_lock int
) returns void
language plpgsql
as $$
declare
  new_count int;
  lock_sec int;
begin
  insert into auth_attempts (k, fail_count, locked_until, updated_at)
    values (p_key, 1, null, now())
  on conflict (k) do update
    set fail_count = auth_attempts.fail_count + 1,
        updated_at = now()
  returning fail_count into new_count;

  if new_count >= p_max_fails then
    lock_sec := least(p_max_lock, p_base_lock * (2 ^ (new_count - p_max_fails))::int);
    update auth_attempts
      set locked_until = now() + make_interval(secs => lock_sec)
      where k = p_key;
  end if;
end;
$$;

-- auth_attempts cheksiz o'smasin: bloklanmagan eski yozuvlarni tozalovchi yordamchi
-- (pg_cron bilan kunlik chaqirish tavsiya etiladi; qo'lda ham bajarsa bo'ladi).
create or replace function cleanup_auth_attempts() returns void
language sql
as $$
  delete from auth_attempts
   where (locked_until is null or locked_until < now())
     and updated_at < now() - interval '1 day';
$$;

-- --- CL-05: vyuxalar RLS'ni AYLANIB O'TMASIN + anon ko'rmasin ----------------
-- Postgres 15+ da view default security_invoker=false → egasidan (postgres) ishlaydi
-- va asosiy jadval RLS'ini chetlab o'tadi. anon esa public sxemada yangi relation'larga
-- default SELECT oladi. Ikkalasini ham yopamiz: invoker=on (so'rovchi roli RLS'iga
-- bo'ysunadi) + aniq revoke. API service_role bilan ishlaydi (RLS'ni baribir aylanadi),
-- shu sabab panel buzilmaydi.
do $$
declare v text;
begin
  foreach v in array array['v_map_points','v_recent_threats','v_devices_with_counts','v_stats_today']
  loop
    execute format('alter view %I set (security_invoker = on)', v);
    execute format('revoke all on %I from anon, authenticated', v);
  end loop;
end $$;

-- --- CL-06: "bugungi" statistika Asia/Tashkent kuni bo'yicha -----------------
-- Supabase default UTC. O'zbekiston UTC+5 — eski `scanned_at >= current_date` bugungi
-- hisoblagichni mahalliy 05:00 da nolga tushirardi va tunги skanni "kechaga" qo'shardi.
--
-- AVVAL: prod scans jadvalida scan_duration_ms ustuni bo'lmasligi mumkin (prod eski sxemadan
-- yaratilgan, schema.sql:40 dagi add-column qo'lда prod'ga qo'llanmagan). Vyuxa unga tayanadi,
-- shuning uchun ustun+indeksni shu yerda IDEMPOTENT qo'shamiz (schema.sql:40,45 bilan bir xil).
alter table scans add column if not exists scan_duration_ms int default 0;
create index if not exists idx_scans_duration on scans(scan_duration_ms) where scan_duration_ms > 0;

create or replace view v_stats_today as
select
  count(*)                                       as total_scans,
  count(*) filter (where verdict = 'danger')     as danger_count,
  count(*) filter (where verdict = 'suspicious') as suspicious_count,
  count(*) filter (where verdict = 'safe')       as safe_count,
  count(distinct device_id)                      as active_devices,
  count(*) filter (where scan_duration_ms > 0)   as perf_count,
  coalesce(round(avg(scan_duration_ms) filter (where scan_duration_ms > 0))::int, 0) as avg_duration_ms,
  coalesce(round(percentile_cont(0.5) within group (order by scan_duration_ms)
           filter (where scan_duration_ms > 0))::int, 0) as median_duration_ms,
  coalesce(round(percentile_cont(0.95) within group (order by scan_duration_ms)
           filter (where scan_duration_ms > 0))::int, 0) as p95_duration_ms
from scans
where (scanned_at at time zone 'Asia/Tashkent')::date
      = (now() at time zone 'Asia/Tashkent')::date;

-- v_stats_today qayta yaratildi — RLS/anon yopilishini takrorlaymiz.
alter view v_stats_today set (security_invoker = on);
revoke all on v_stats_today from anon, authenticated;
