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
