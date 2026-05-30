-- KiberQalqon — 07_drop_legacy_roles.sql
-- Rollar / ierarxiya tizimi OLIB TASHLANDI. Endi veb-panelga kirish faqat env orqali:
--   • EGASI  — ADMIN_SECRET (master kalit) + ixtiyoriy TOTP. To'liq huquq.
--   • ADMIN  — ADMIN_LOGIN + ADMIN_PASSWORD. Faqat ko'rish + eksport + e'lon.
-- Hech qaysi hisob bazada saqlanmaydi — login/parol env'da, sessiya HMAC token.
--
-- Shu sababli quyidagi jadval/ko'rinishlar endi HECH QAYERDA ishlatilmaydi (kod
-- ularni o'qimaydi ham, yozmaydi ham). 03_roles.sql va 05_hierarchy.sql fayllari
-- repodan o'chirildi — yangi o'rnatishda bu obyektlar umuman yaratilmaydi.
-- Bu migratsiya esa ALLAQACHON yaratilgan bazani tozalaydi.
--
-- Bajarish: Supabase Dashboard → SQL Editor → New query → shu faylni ishga tushirish.
-- Idempotent (IF EXISTS) — qayta ishga tushsa xavfsiz. Yangi bazada — no-op.
-- DIQQAT: bu jadvallardagi ma'lumotlar (agar bo'lsa) butunlay o'chiriladi.

drop view  if exists v_operators_safe;
drop table if exists role_login_audit cascade;
drop table if exists operators        cascade;
drop table if exists roles            cascade;
