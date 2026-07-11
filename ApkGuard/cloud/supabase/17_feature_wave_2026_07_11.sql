-- 17_feature_wave_2026_07_11.sql — "hammasini qo'sh" to'lqini (2026-07-11)
--
-- Idempotent — Supabase SQL Editor'da QO'LDA ishlatiladi (loyiha qoidasi: CLI yo'q).
-- Bir necha marta ishga tushirsa ham xavfsiz.
--
-- Qo'shiladi:
--   1) devices.protections  — qurilmada QAYSI himoyalar haqiqatan YOQILGANligi (jsonb).
--      "Himoya batareyasi": panel "200 ta o'rnatilgan" ni "haqiqatan 140 himoyalangan" ga aylantiradi.
--   2) devices.flag/.flag_at/.flag_note — egasi qurilmani "yo'qolgan"/"buzilgan" deb belgilaydi.
--   3) threat_rules — YARA-lite: bulutdan yangilanadigan dex-satr qoidalari (ilovani qayta chiqarmasdan).
--   4) known_good    — imzolangan "yaxshi ro'yxat" (paket+sert) — SUSPICIOUS'ni pasaytiradi, DANGER'ni EMAS.
--   5) rule_hits     — qoida-sifati sikli: qaysi qoida qancha ishladi / rad etildi (paneldan "mute").
--   6) news.group_id — guruhga yo'naltirilgan e'lonlar (butun flotni spamlamaslik uchun).
--   7) v_devices_with_counts — protections/flag ustunlarini qo'shib qayta yaratamiz.

-- ============================================================================
-- 1) DEVICES — himoya-holati snapshoti + yo'qolgan/buzilgan bayrog'i
-- ============================================================================
alter table devices add column if not exists protections jsonb;         -- {svc,a11y,notif,postN,linkH,apkH,vpn,batt,scanAgeH,ts}
alter table devices add column if not exists flag        text;          -- null | 'lost' | 'compromised'
alter table devices add column if not exists flag_at     timestamptz;
alter table devices add column if not exists flag_note   text;

-- ============================================================================
-- 2) THREAT_RULES — YARA-lite dex-satr qoidalari (imzolangan feed orqali tarqaladi)
-- needles = kichik-harf satrlar massivi (AND mantiq). min_hits=0 → HAMMA needle topilsin.
-- muted=true → qoida mijozda MASLAHAT (advisory) darajasiga tushadi (hech qachon DANGER emas).
-- ============================================================================
create table if not exists threat_rules (
  id          bigserial primary key,
  rule_id     text unique not null,                       -- barqaror slug: "ajina_sms_v3"
  family      text,                                       -- "Ajina.Banker"
  severity    text not null default 'high' check (severity in ('low','medium','high','critical')),
  target      text not null default 'dex_string' check (target in ('dex_string','manifest','path')),
  needles     jsonb not null default '[]'::jsonb,         -- ["encryptsms","c2.telegram"] (kichik harf)
  min_hits    int not null default 0,                     -- 0 = hammasi; N = kamida N ta needle
  enabled     boolean not null default true,
  muted       boolean not null default false,             -- true → advisory (FP-flood o'chirgichi)
  notes       text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists idx_threat_rules_active on threat_rules(enabled) where enabled;
alter table threat_rules enable row level security;       -- anon ko'rmaydi; service_role o'tadi

-- ============================================================================
-- 3) KNOWN_GOOD — egasi tasdiqlagan "yaxshi ro'yxat" (paket + sert). DOWNGRADE-only.
-- Mijoz buni ishonch-qalqoni kirishi sifatida ishlatadi: SUSPICIOUS'ni bostiradi,
-- DANGER'ni (hash/sert qora ro'yxati) HECH QACHON bostirmaydi (skan tartibi buni ta'minlaydi).
-- ============================================================================
create table if not exists known_good (
  id            bigserial primary key,
  package_name  text not null,
  cert_sha256   text,                                     -- null = faqat paket; yoki 64-hex (kichik)
  label         text,
  created_at    timestamptz not null default now()
);
create unique index if not exists uq_known_good on known_good(package_name, coalesce(cert_sha256, ''));
alter table known_good enable row level security;

-- ============================================================================
-- 4) RULE_HITS — qoida-sifati telemetriyasi (mute-qaror uchun)
-- Mijoz reasons ichida "rule:<rule_id>" prefiksi bilan yuboradi → upload.ts bu yerga yozadi.
-- dismissed — namuna sharhida (set_review 'dismissed') FP deb belgilanganda true bo'ladi.
-- ============================================================================
create table if not exists rule_hits (
  id          bigserial primary key,
  rule_id     text not null,
  device_id   uuid references devices(id) on delete cascade,
  apk_hash    text,
  verdict     text,
  created_at  timestamptz not null default now()
);
create index if not exists idx_rule_hits_rule on rule_hits(rule_id, created_at desc);
create index if not exists idx_rule_hits_hash on rule_hits(apk_hash);
alter table rule_hits enable row level security;

-- Qoida statistikasi (panel FP-paneli): ishlashlar, har xil qurilmalar, rad etilgan %.
create or replace view v_rule_stats as
select
  h.rule_id,
  count(*)                                    as fires,
  count(distinct h.device_id)                 as devices,
  count(*) filter (where t.review_status = 'dismissed') as dismissed,
  max(h.created_at)                           as last_fire
from rule_hits h
left join threats t on t.apk_hash = h.apk_hash
group by h.rule_id;
alter view v_rule_stats set (security_invoker = on);
revoke all on v_rule_stats from anon, authenticated;

-- ============================================================================
-- 5) NEWS — guruhga yo'naltirish (null = global, hamma qurilmaga)
-- Guruh o'chirilsa e'lon global bo'lib qolmasin → on delete cascade.
-- ============================================================================
alter table news add column if not exists group_id uuid references device_groups(id) on delete cascade;
create index if not exists idx_news_group on news(group_id);

-- ============================================================================
-- 6) v_devices_with_counts — protections/flag ustunlarini qo'shib QAYTA yaratamiz
-- 16_groups.sql dagi TO'LIQ ustun ro'yxatini saqlab, oxiriga yangilarini qo'shamiz.
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
  d.protections, d.flag, d.flag_at, d.flag_note,
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

alter view v_devices_with_counts set (security_invoker = on);
revoke all on v_devices_with_counts from anon, authenticated;

-- ============================================================================
-- 7) v_fleet_health — "himoya batareyasi" flot bo'yicha (panel sog'liq strip)
-- protections jsonb ichidan har himoya OCHIQ qurilmalar sonini sanaymiz.
-- ============================================================================
create or replace view v_fleet_health as
select
  count(*)                                                          as total,
  count(*) filter (where protections is not null)                  as reporting,
  count(*) filter (where (protections->>'svc')   = 'false')        as svc_off,
  count(*) filter (where (protections->>'a11y')  = 'false')        as a11y_off,
  count(*) filter (where (protections->>'notif') = 'false')        as notif_off,
  count(*) filter (where (protections->>'vpn')   = 'false')        as vpn_off,
  count(*) filter (where (protections->>'linkH') = 'false')        as linkh_off,
  count(*) filter (where last_seen < now() - interval '3 days')    as stale
from devices;
alter view v_fleet_health set (security_invoker = on);
revoke all on v_fleet_health from anon, authenticated;
