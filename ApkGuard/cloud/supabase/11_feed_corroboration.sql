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
