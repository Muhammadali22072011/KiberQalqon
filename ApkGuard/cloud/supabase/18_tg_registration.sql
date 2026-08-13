-- 18_tg_registration.sql — TELEGRAM orqali ro'yxatdan o'tish (1-bosqich: faqat ro'yxat)
--
-- Nima uchun: ilova birinchi ochilganda foydalanuvchi bosh ekranga tushishidan OLDIN
-- "Ro'yxatdan o'tish" shlagbaumini ko'radi. Tugma bosilganda Telegram ochiladi:
--   https://t.me/<reg_bot>?start=<token>
-- Telegram START'ni O'ZI bosadi va <token> botga keladi → bot qaysi QURILMA kelganini
-- biladi. Bot ismni so'raydi, so'ng «Raqamni yuborish» tugmasi bilan telefonni oladi
-- (request_contact — raqam Telegram tomonidan TASDIQLANGAN, foydalanuvchi qo'lda
-- yozmaydi va boshqa raqamni kiritolmaydi). Tugagach ilova pollingda "done" ko'radi
-- va bosh ekranni ochadi.
--
-- Nima uchun kerak: keyinchalik shu bazadagi chat_id'larga ilova yangiliklari /
-- ogohlantirishlari yuboriladi (rassilka). Ilova ichidagi NewsNotifier'dan FARQI —
-- Telegram ilovani o'chirgan foydalanuvchiga ham yetib boradi.
--
-- MAXFIYLIK: ism + telefon — foydalanuvchi O'ZI ochiq beradigan ma'lumot; ilovadagi
-- ekranda va botning birinchi xabarida nima yig'ilishi aytiladi. Play Console'da
-- Data safety formasi + maxfiylik siyosati YANGILANISHI SHART (telefon raqami =
-- shaxsiy ma'lumot). Bu migratsiyani ishga tushirishdan oldin shuni yodda tuting.
--
-- Idempotent — Supabase SQL Editor'da qo'lda ishlatiladi (loyiha qoidasi: CLI yo'q).

-- ============================================================================
-- 1) TG_REGISTRATIONS — bitta qator = bitta ro'yxatdan o'tish urinishi
-- ============================================================================
-- Hayot sikli:
--   new         — ilova tokenni oldi, Telegram hali ochilmagan
--   await_name  — /start <token> keldi, bot ismni so'radi
--   await_phone — ism olindi, bot «Raqamni yuborish» tugmasini ko'rsatdi
--   done        — telefon olindi, ilova bosh ekranga o'tishi mumkin
create table if not exists tg_registrations (
  id            bigserial primary key,
  token         text not null unique,          -- ilovaga berilgan bir martalik chuqur-havola tokeni
  device_token  text,                          -- devices.device_token (qaysi qurilma)
  chat_id       bigint,                        -- Telegram chat (rassilka manzili)
  tg_user_id    bigint,                        -- Telegram user id (contact tekshiruvi uchun)
  tg_username   text,                          -- @username (bo'lsa)
  full_name     text,                          -- foydalanuvchi kiritgan ism-familiya
  phone         text,                          -- Telegram tasdiqlagan raqam (+998…)
  step          text not null default 'new',
  blocked       boolean not null default false, -- bot bloklangan → rassilka o'tkazib yuboradi
  created_at    timestamptz not null default now(),
  linked_at     timestamptz,                   -- /start <token> kelgan payt
  done_at       timestamptz,                   -- telefon olingan payt
  last_msg_at   timestamptz                    -- oxirgi rassilka (throttle uchun)
);

create index if not exists idx_tgreg_device  on tg_registrations(device_token);
create index if not exists idx_tgreg_chat    on tg_registrations(chat_id);
create index if not exists idx_tgreg_step    on tg_registrations(step);
create index if not exists idx_tgreg_created on tg_registrations(created_at desc);

alter table tg_registrations enable row level security;  -- anon ko'rmaydi; service_role o'tadi

-- ============================================================================
-- 2) v_tg_subscribers — rassilka ro'yxati (2-bosqich uchun tayyor)
-- Bitta odam bir necha qurilmada ro'yxatdan o'tishi mumkin → chat_id bo'yicha DISTINCT,
-- eng oxirgi profil olinadi. Bloklaganlar chiqarib tashlanadi.
-- ============================================================================
drop view if exists v_tg_subscribers;
create view v_tg_subscribers as
select distinct on (chat_id)
  chat_id,
  tg_username,
  full_name,
  phone,
  done_at,
  last_msg_at
from tg_registrations
where step = 'done' and blocked = false and chat_id is not null
order by chat_id, done_at desc nulls last;

-- ============================================================================
-- 3) Tozalash — tugallanmagan urinishlar cheksiz to'planmasin
-- Cron yo'q (Hobby): endpoint har chaqirilganda 1 kundan eski 'new' qatorlarni
-- o'chiradi. Bu yerda faqat bir martalik qo'lda tozalash uchun qoldiramiz.
-- ============================================================================
-- delete from tg_registrations where step = 'new' and created_at < now() - interval '1 day';
