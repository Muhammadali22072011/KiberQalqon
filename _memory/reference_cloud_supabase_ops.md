---
name: cloud-supabase-ops
description: "How KiberQalqon cloud DB migrations and secrets actually work — Supabase schema changes are hand-run in SQL Editor; real secrets live in Vercel, not .env.local."
metadata: 
  node_type: memory
  type: reference
  originSessionId: e3dd08c9-aab2-48d6-b5bf-c4ef9f94acbc
---

KiberQalqon cloud (`ApkGuard/cloud/`) operational facts:

- **Supabase schema changes are applied BY HAND in the Supabase Dashboard → SQL Editor.**
  There is no Supabase CLI link (no `config.toml`). The `supabase/*.sql` files (`schema.sql`,
  `02_geo.sql`, `06_ip.sql`, …) are idempotent (`add column if not exists`, `drop+create view`)
  and must be pasted+Run manually. A migration file existing in the repo does NOT mean it ran
  on the live DB — verify before assuming a column/view exists.

- **`cloud/.env.local` holds only PLACEHOLDERS** (`SUPABASE_URL=https://XXXXX...`,
  `SERVICE_KEY=PASTE_...`). The real `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, and
  `DEVICE_SHARED_SECRET` live ONLY in Vercel env vars. So from this machine you cannot connect
  to Supabase / run DDL directly — needs the Supabase dashboard or a pulled env.

- **The live `DEVICE_SHARED_SECRET` matches `ApkGuard/local.properties` `cloud.device.secret`**
  (used to test write endpoints with `x-device-secret`), NOT the stale value in `.env.local`.
  Live base URL: `https://kiberqalqon-cloud.vercel.app`.

- Write endpoints (`/api/scan/upload`, `/api/device/register`) auth with `x-device-secret`;
  panel read endpoints (`/api/devices`, `/api/geo`, `/api/stats`) auth with `x-admin-secret`
  (owner master key OR admin session token) — see [[access-model-simplification]].

Vercel account + deploy details: [[vercel-deploy]].
