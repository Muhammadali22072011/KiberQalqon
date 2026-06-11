-- ============================================================
-- KiberQalqon: миграции 11–15 ОДНИМ файлом (2026-06-12)
-- Куда: Supabase Dashboard → SQL Editor → New query → вставить ВСЁ → Run
-- Безопасно запускать повторно (if not exists / create or replace).
-- ============================================================

-- ──────────── 11_feed_corroboration.sql ────────────
-- ============================================================================
-- 11_feed_corroboration.sql  — CLOUD-01: zaharlangan blacklist-feed himoyasi
-- ============================================================================
-- Muammo: bitta qurilmaning bitta soxta "danger" yuklamasi (ixtiyoriy package_name
-- bilan) imzolangan `?feed=1` ga tushib, butun parkda legit ilovani bloklab qo'yardi
-- (fleet-wide false-DANGER / DoS).
--
-- Yechim: qurilma feed'iga faqat KAMIDA `min_devices` ta HAR XIL qurilmada
-- (count(distinct device_id)) xavfli/shubhali ko'rilgan tahdid tushadi. seen_count
-- yaramaydi — u har yuklamada oshadi (bitta tajovuzkor N marta yuklab soxtalashtirishi
-- mumkin); distinct device_id esa korroboratsiyani talab qiladi.
--
-- Bu funksiyani SUPABASE SQL EDITOR'da bir marta ishga tushiring (boshqa migratsiyalar
-- kabi qo'lda). threats.ts feed shoxobchasi shu RPC'ni chaqiradi; RPC bo'lmasa —
-- xavfsizroq degradatsiya (seen_count>=2) ishlaydi.
-- ============================================================================

create or replace function corroborated_threats(min_devices int default 2)
returns table (apk_hash text, package_name text, category text, severity text, last_seen timestamptz)
language sql
stable
as $$
  select t.apk_hash, t.package_name, t.category, t.severity, t.last_seen
  from threats t
  where t.severity in ('high', 'critical')
    and (
      select count(distinct s.device_id)
      from scans s
      where s.apk_hash = t.apk_hash
        and s.verdict in ('danger', 'suspicious')
    ) >= greatest(min_devices, 1)
  order by t.last_seen desc
  limit 2000;
$$;

-- Server service_role kaliti bilan chaqiradi (grant/RLS'ni aylanib o'tadi). anon/authenticated
-- to'g'ridan-to'g'ri PostgREST orqali rpc/corroborated_threats ni chaqirib, imzolangan feed'ni
-- chetlab o'tib, tahdid ro'yxatini olmasin — execute huquqini olib tashlaymiz.
revoke execute on function corroborated_threats(int) from public, anon, authenticated;

-- ──────────── 12_auth_rate_limit.sql ────────────
-- ============================================================================
-- 12_auth_rate_limit.sql  — CLOUD-03: panelga kirishni brute-force'dan himoya
-- ============================================================================
-- /api/admin/login constant-time solishtiradi, lekin tezlik cheklovi (throttling) YO'Q edi —
-- admin parolini (ayniqsa default "change-me...") to'liq tezlikda onlayn brute-force qilsa bo'lardi.
--
-- Bu jadval har (scope+IP) bo'yicha muvaffaqiyatsiz urinishlarni sanaydi va eksponensial
-- backoff bilan vaqtincha bloklaydi. lib/ratelimit.ts shu jadval bilan ishlaydi.
-- Server service_role bilan yozadi (RLS aylanib o'tiladi); anon kaliti ko'rmaydi.
--
-- Eslatma: ratelimit FAIL-OPEN — bu jadval bo'lmasa (migratsiya qilinmagan) yoki DB xato bo'lsa,
-- login ishlayveradi (egasini DB nosozligi tufayli lockout qilmaymiz), faqat himoya aktiv bo'lmaydi.
-- ============================================================================

create table if not exists auth_attempts (
  k             text primary key,         -- "owner:<ip>" yoki "admin:<ip>"
  fail_count    int not null default 0,
  locked_until  timestamptz,
  updated_at    timestamptz not null default now()
);

alter table auth_attempts enable row level security;
-- anon'ga policy berilmaydi → faqat service_role ko'radi/yozadi.

-- ──────────── 13_audit_2026_06_10.sql ────────────
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

-- ──────────── 14_threat_domains.sql ────────────
-- 14_threat_domains.sql — domen qora ro'yxati (URL/link checker feed'i uchun)
--
-- Bu jadval `threats` (apk_hash PK) bilan ARALASHTIRILMAYDI: domen string'ini apk_hash PK'siga
-- yuklash upload.ts'dagi ^[a-f0-9]{64}$ va threats.ts'dagi `${apk_hash}.apk` join'ini buzgan bo'lardi.
-- Shuning uchun alohida jadval. RLS yoqilgan: anon/authenticated ko'rmaydi; faqat service_role
-- (api/threats.ts feed branch) o'qiydi. Idempotent — Supabase SQL Editor'da qo'lda ishlatish uchun
-- (loyiha qoidasi: CLI yo'q, migratsiyalar qo'lda).
--
-- Feed: api/threats.ts severity in ('high','critical') yozuvlarni oladi va imzolangan payloadga
-- "domains":[{d,f}] sifatida qo'shadi → mijozda CloudBlacklist → ThreatDb.mergeCloudDomains →
-- MaliciousDomains.maliciousFamily(host) → LinkScanner DANGER.
--
-- KORROBORATSIYA QOIDASI (CLOUD-01): hash/paket'lardan farqli o'laroq, domenlar QASDDAN faqat EGA
-- tomonidan qo'lda kiritiladi (qurilma-yo'nalishli yozish yo'li YO'Q — upload.ts threat_domains'ga
-- tegmaydi). Shuning uchun min-devices korroboratsiya gate'i shart emas. Himoya uchun threats.ts
-- selektni `source is null or source = 'owner'` bilan cheklaydi — kelajakda device_report yozuvi
-- qo'shilsa, u korroboratsiyasiz DANGER feed'ga TUSHMAYDI (egadan tasdiq talab qiladi).

create table if not exists threat_domains (
  domain      text primary key,                                          -- kichik harf, punycode, trailing nuqtasiz
  category    text,                                                      -- "phishing", "c2", "scam", ...
  severity    text check (severity in ('low','medium','high','critical')) default 'high',
  source      text,                                                      -- 'owner' | 'device_report' | ...
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  seen_count  int not null default 1,
  notes       text
);

create index if not exists idx_threat_domains_last_seen on threat_domains(last_seen desc);

alter table threat_domains enable row level security;  -- anon ko'rmaydi; service_role o'tadi

-- ──────────── 15_admin_audit_log.sql ────────────
-- 15_admin_audit_log.sql — panel amallari jurnali (kim, qachon, nima qildi)
--
-- Nima uchun: panelda ikki kishi bor (EGASI + bitta cheklangan ADMIN). Inson xatosi yoki
-- token o'g'irlanishi holatida "kim e'lon joyladi / kim qachon kirdi / kimga login
-- bo'lmadi" savollariga javob shu jadvalda. Yozish — lib/audit.ts (fail-soft: jadval
-- bo'lmasa amal TO'XTAMAYDI, faqat console.error). O'qish — FAQAT EGASI:
-- /api/stats?audit=1 (checkAdminSecret). Idempotent — Supabase SQL Editor'da qo'lda
-- ishlatish uchun (loyiha qoidasi: CLI yo'q, migratsiyalar qo'lda).

create table if not exists admin_audit_log (
  id      bigint generated always as identity primary key,
  at      timestamptz not null default now(),
  actor   text not null,   -- 'owner' | 'admin:<login>' | 'anon'
  action  text not null,   -- 'login' | 'login_fail' | 'news_create' | 'news_delete' | 'news_pin'
  detail  text,            -- qisqa kontekst (sarlavha, id, sabab) — sir/parol YOZILMAYDI
  ip      text
);

create index if not exists idx_admin_audit_at on admin_audit_log(at desc);

alter table admin_audit_log enable row level security;  -- anon ko'rmaydi; service_role o'tadi

-- ──────────── ПРОВЕРКА: все 5 колонок должны быть true ────────────
select
  exists(select 1 from pg_proc where proname='corroborated_threats') as m11_ok,
  to_regclass('public.auth_attempts') is not null                    as m12_ok,
  exists(select 1 from information_schema.columns where table_name='scans' and column_name='scan_duration_ms') as m13_ok,
  to_regclass('public.threat_domains') is not null                   as m14_ok,
  to_regclass('public.admin_audit_log') is not null                  as m15_ok;
