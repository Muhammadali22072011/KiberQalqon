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
