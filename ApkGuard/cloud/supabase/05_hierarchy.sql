-- KiberQalqon — 05_hierarchy.sql
-- Uch pog'onali ierarxiya: EGASI → RAHBARLAR → XODIMLAR.
--   • Egasi (owner)   — master ADMIN_SECRET bilan veb-panelga kiradi. Rahbar yaratadi.
--   • Rahbar (manager)— veb-panelga login+parol bilan kiradi. O'z XODIMLARINI yaratadi
--                       va ularga huquq beradi. Faqat O'ZI yaratganlarni ko'radi.
--   • Xodim (operator)— Android ilovadan (maxfiy kirish) login+parol bilan kiradi.
--
-- operators jadvali ikkala "inson"ni ham saqlaydi (rahbar ham, xodim ham) — farqi
-- level ustunida. created_by — uni KIM yaratgani (rahbar uid'i; egasi yaratsa NULL).
--
-- Bajarish: Supabase Dashboard → SQL Editor → New query → shu faylni ishga tushirish.
-- Idempotent (IF NOT EXISTS / OR REPLACE) — qayta ishga tushsa xavfsiz. 03_roles.sql'dan keyin.

-- 1) level — 'manager' (rahbar) yoki 'operator' (xodim). Mavjud qatorlar 'operator' bo'ladi.
alter table operators
  add column if not exists level text not null default 'operator';

-- 2) created_by — uni yaratgan rahbar (operators.id). Egasi yaratsa NULL.
--    Rahbar o'chsa, uning xodimlari "egasi"ga o'tadi (NULL), o'chib ketmaydi.
alter table operators
  add column if not exists created_by uuid references operators(id) on delete set null;

create index if not exists idx_operators_level on operators(level);
create index if not exists idx_operators_created_by on operators(created_by);

-- 3) v_operators_safe — panel ro'yxati (parolsiz). Yangi ustunlar OXIRIGA qo'shiladi
--    (create or replace view mavjud ustun tartibini o'zgartirishga ruxsat bermaydi).
--    created_by_login — yaratgan rahbarning logini (egasi panelida ko'rsatish uchun).
create or replace view v_operators_safe as
select
  o.id, o.login, o.active, o.created_at, o.last_login_at,
  r.name as role_name,
  r.permissions,
  r.components,
  o.level,
  o.created_by,
  c.login as created_by_login
from operators o
left join roles r on r.id = o.role_id
left join operators c on c.id = o.created_by
order by o.created_at desc;
