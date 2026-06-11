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
