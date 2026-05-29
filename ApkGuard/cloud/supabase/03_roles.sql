-- KiberQalqon — 03_roles.sql
-- Rollar tizimi: maxfiy kirish (4 qulf) — login → server rol qaytaradi.
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent (IF NOT EXISTS / OR REPLACE) — qayta ishga tushsa xavfsiz.

-- ============================================================================
-- 1) ROLES — rol (teg) + huquqlar + ko'rsatiladigan komponentlar
-- Egasi panelda yaratadi: masalan "Tergovchi" → permissions/components.
--   permissions — nimaga ruxsat (masalan: "view_map", "delete_threat", "export")
--   components  — qaysi panel bo'limlari ko'rsatiladi (masalan: "map", "feed")
-- ============================================================================
create table if not exists roles (
  id           uuid primary key default gen_random_uuid(),
  name         text not null unique,              -- teg/nom: "Tergovchi", "Nazoratchi"
  permissions  text[] not null default '{}',
  components   text[] not null default '{}',
  created_at   timestamptz not null default now()
);

-- ============================================================================
-- 2) OPERATORS — kirish berilgan odamlar (login/parol → rol)
-- "Operator" = egasi kirishga ruxsat bergan inson (xodim). Login/parol uni
-- aniqlaydi, role_id esa qaysi rol (huquqlar) berilganini bildiradi.
-- Parol OCHIQ saqlanmaydi: scrypt(salt+parol) → password_hash (Node tomonda).
-- ============================================================================
create table if not exists operators (
  id             uuid primary key default gen_random_uuid(),
  login          text not null unique,
  password_hash  text not null,
  password_salt  text not null,
  role_id        uuid references roles(id) on delete set null,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  last_login_at  timestamptz
);

create index if not exists idx_operators_login on operators(login);

-- ============================================================================
-- 3) ROLE_LOGIN_AUDIT — kim qachon kirishga urindi (xavfsizlik jurnali)
-- Parol/kod hech qachon yozilmaydi — faqat login + natija + sabab.
-- ============================================================================
create table if not exists role_login_audit (
  id      bigserial primary key,
  login   text,
  ok      boolean not null,
  reason  text,
  ip      text,
  at      timestamptz not null default now()
);

create index if not exists idx_role_audit_at on role_login_audit(at desc);

-- ============================================================================
-- RLS — boshqa jadvallar bilan bir xil: anon hech narsa ko'rmaydi,
-- server service_role kaliti bilan ishlaydi (hamma policiyani aylanib o'tadi).
-- ============================================================================
alter table roles            enable row level security;
alter table operators        enable row level security;
alter table role_login_audit enable row level security;

-- ============================================================================
-- 4) v_operators_safe — panel uchun (parol hash/salt'siz!)
-- Egasi panelda operatorlar ro'yxatini shu ko'rinishdan oladi.
-- ============================================================================
create or replace view v_operators_safe as
select
  o.id, o.login, o.active, o.created_at, o.last_login_at,
  r.name as role_name,
  r.permissions,
  r.components
from operators o
left join roles r on r.id = o.role_id
order by o.created_at desc;

-- ============================================================================
-- 5) Namuna rol (ixtiyoriy) — panel UI tayyor bo'lguncha sinash uchun.
-- Operatorni /api/role/admin orqali yaratasiz (parol server tomonda hash'lanadi).
-- ============================================================================
insert into roles (name, permissions, components)
values ('Boshqaruvchi', array['view_all','manage_roles'], array['map','feed','devices','threats'])
on conflict (name) do nothing;
