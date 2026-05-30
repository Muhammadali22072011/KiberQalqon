-- KiberQalqon — 07_samples.sql
-- Xavfli/shubhali APK NAMUNALARINI serverda yig'ish (panelda yuklab olib o'rganish uchun).
-- Fayllar Supabase Storage'dagi PRIVATE 'malware-samples' bucketda saqlanadi, yo'l = "<sha256>.apk".
-- Bir xil hash bir martagina saqlanadi (dedup); necha marta uchragani threats.seen_count'da.
-- Toifa (category) ham threats jadvalida — bu migratsiya faqat bucket yaratadi.
-- Bajarish: Supabase Dashboard → SQL Editor → New query → bu faylni ishga tushirish.
-- Idempotent: bir necha marta ishga tushirsa ham xavfsiz.

-- ============================================================================
-- PRIVATE bucket — faqat service_role (server) yoza/o'qiy oladi; panel imzolangan
-- (signed) URL orqali yuklab oladi. 50 MB chegara + APK mime turlari bilan cheklangan.
-- ============================================================================
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'malware-samples',
  'malware-samples',
  false,
  52428800,  -- 50 MB (ConsentActivity'dagi va'da bilan bir xil)
  array[
    'application/vnd.android.package-archive',
    'application/octet-stream',
    'application/zip'
  ]
)
on conflict (id) do update set
  public             = false,
  file_size_limit    = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;
