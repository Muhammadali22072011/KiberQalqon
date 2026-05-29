# KiberQalqon Backends & Telegram Integration — Reference

KiberQalqon has **two live backends** plus two dormant older generations. Read this before
changing anything network-related.

- **LIVE — in-app Telegram command panel** (the phone *is* the bot server; long-polls `getUpdates`).
- **LIVE — Gen-3 cloud** (`ApkGuard/cloud/`): a deployed Vite+React panel + Vercel functions +
  Supabase + Telegram webhook, **now wired into the Android app** via `CloudTelemetry.kt`.
- **Dormant** — Gen-1 local Flask sink and Gen-2 standalone Python intake bot.

## Contents
1. Backend evolution (which gen is live)
2. Cloud backend (Gen-3, LIVE) — SPA, API, schema, libs, deploy
3. **Cloud access model (owner vs admin)** — important, don't re-add roles
4. Telegram bot commands (cloud webhook vs in-app panel)
5. Python polling bot (Gen-2, dormant)
6. Flask server (Gen-1, dormant)
7. Android ↔ backend contract (the networking paths)
8. File inventory
9. Secrets & config

---

## 1. Backend evolution

**Gen-1 — Local Flask server** (`ApkGuard/server/app.py`, `ZAPUSK_SERVERA.bat`). Flask on the
user's PC that received dangerous APK *files* via `POST /upload`. **Dormant** — only fires if a
user manually sets a server URL; `DEFAULT_SERVER_URL` ships empty. Doesn't work off-LAN.

**Gen-2 — Python polling bot** (`telegram_bot/bot.py`, python-telegram-bot 21.6). A standalone
sample-intake bot. **Auxiliary; not the app's command channel.**

**Gen-2.5 (LIVE) — in-app Telegram command panel.** The command-panel role runs *inside the
Android app*: the phone long-polls `getUpdates` directly against the Telegram Bot API
(`TelegramBot.kt` + `TelegramCommandPoller.kt` + `CommandRouter.kt`). **The phone is the bot
server.** This + opt-in telemetry to the user's personal bot is a production backend.

**Gen-3 — Cloud serverless (LIVE).** `ApkGuard/cloud/` — a real Vite+React single-page panel on
top of Vercel serverless functions + Supabase, with a Telegram webhook for alerts. The
architecture `📱 Android → ☁️ Vercel API → 🗄️ Supabase → 🔔 Telegram alerts` is fully
realized: **deployed** (placeholder/expected URL `https://kiberqalqon-cloud.vercel.app`) and
**wired into Android** — `CloudTelemetry.kt` POSTs to `/api/device/register` and
`/api/scan/upload`. The panel's monitoring map + stats fill with real device data. (The old
"code-complete but not deployed / Android client missing" note is obsolete.)

---

## 2. Cloud backend (Gen-3, LIVE)

Stack: TypeScript, Node ≥20. **Front end is a Vite + React 18 SPA** (`src/`, react-router) built
to `dist/` and served statically; the API is `@vercel/node` serverless functions under `api/`.
Key deps: `@supabase/supabase-js`, `leaflet` + `react-leaflet` (the live threat map), `xlsx`
(Excel export), `react-router-dom`, `ws`. The old static `public/index.html` dashboard is retired
to `_legacy/index.html`.

### SPA pages (`src/pages/`)
`Landing.tsx` (public pitch/advantages), `Login.tsx` (owner **or** admin login), `Overview.tsx`
(stats), `MapPage.tsx` (Leaflet map of devices, colored by risk), `Devices.tsx`, `Threats.tsx`,
`Feed.tsx` (activity feed), `News.tsx` (announcements CRUD), `Profile.tsx`. Components:
`Layout.tsx`, `NewsCarousel.tsx`, `Toast.tsx`, `ui.tsx`. Region mapping helper:
`src/lib/uzRegions.ts` (maps a coordinate to the correct Uzbek viloyat).

### API endpoints (`api/`)
Consolidated to stay under Vercel Hobby's **12-function limit** — note dynamic routes do double duty.

| Path | Method | Auth | Notes |
|---|---|---|---|
| `/api/scan/upload` | POST | `x-device-secret` | core ingest: upsert device (+geo+ip), insert scan, on danger/suspicious upsert_threat + Telegram alert |
| `/api/device/register` | POST | `x-device-secret` | device self-registers (served by `device/[id].ts`, `id==='register'` branch) |
| `/api/device/<uuid>` | GET | owner (`x-admin-secret`) | one device + last 20 scans (same `device/[id].ts` file) |
| `/api/devices` | GET | panel user (`canRead`) | device list (for map/table) |
| `/api/scans` | GET | owner | scan dump — owner-only |
| `/api/threats` | GET | panel user | threat blacklist |
| `/api/stats` | GET | panel user | today's counters |
| `/api/feed` | GET | panel user | recent activity feed |
| `/api/geo` | GET | panel user | map points (country/city/lat/lng/risk) |
| `/api/news` | GET/POST/DELETE | GET public · write = `canManageNews` | announcements (shown in app + panel) |
| `/api/admin/login` | POST | — | issues a session token (owner or admin — see §3) |
| `/api/telegram/webhook` | POST | `x-telegram-bot-api-secret-token` | Telegram Update |

`api/scan/upload.ts` is the core: upserts the device (with geo + IP + latest risk/verdict so the
map can color the point), inserts the scan, and on `danger`/`suspicious` calls the `upsert_threat`
RPC, formats an Uzbek alert (`formatThreatAlert`), and fans it out to every admin chat (logging
each send to `notifications`). RPC failures are logged but still return 200 so the alert always
sends. `classify()` maps reasons → category (sms_stealer / spyware / dropper / suspicious).

### Geo (`lib/geo.ts`)
Two sources, merged by `resolveGeo(req, body, seed)`:
1. **Device GPS** — if the phone has location permission it sends exact `lat`/`lng`
   (`loc_accuracy_m`) in the body → used verbatim (**no jitter**); city name derived from the
   nearest of **141 hardcoded Uzbek city/district centers** (`UZ_CITIES`), because the mobile-IP
   city is almost always wrongly "Tashkent". Country still comes from IP.
2. **IP geo fallback** — Vercel `x-vercel-ip-{country,city,latitude,longitude}` headers, plus a
   **deterministic ~3 km jitter** seeded by `device_token` (so multiple phones in one city don't
   stack on a single pixel, and a point doesn't jump between scans). Empty in local dev.
`clientIp()` records the real client IP (x-forwarded-for) for the owner panel.

### Supabase schema (`supabase/`)
Migrations are **hand-run in the Supabase SQL Editor** (no CLI): `schema.sql`, then `02_geo.sql`,
`03_roles.sql`, `04_news.sql`, `05_hierarchy.sql`, `06_ip.sql`, `07_drop_legacy_roles.sql`.
**Note `03`/`05` added a roles/hierarchy system and `07` dropped it again** — the live model has
**no roles** (see §3). Tables: `devices` (token, versions, last_seen, geo: country/city/lat/lng,
`ip`, `risk_score`, `last_verdict`), `scans` (FK device, apk_hash, verdict CHECK, risk, reasons/perms
jsonb), `threats` (blacklist), `admin_chats`, `notifications`, `news`. RLS on; server uses the
`service_role` key.

### lib helpers
`lib/supabase.ts` (cached `db()`), `lib/telegram.ts` (`sendMessage`, `adminChatIds`, `isAdmin`),
`lib/format.ts` (Uzbek `formatThreatAlert`/`formatStats`), `lib/geo.ts` (above), **`lib/auth.ts`**
(authorization gates — §3), **`lib/session.ts`** (HMAC session tokens — §3), **`lib/password.ts`**,
**`lib/totp.ts`** (owner 2FA). *(`lib/rolecode.ts` and `api/role/[action].ts` were **deleted** when
roles were removed — do not resurrect them.)*

### Deploy + webhook
- `npm run build` (Vite) → `dist/`; `npm run deploy` → `vercel --prod`; `deploy-prod.bat` wraps it.
- Deploy under the **`muhammadali22072011`** Vercel account (not `akobirturgunov`). Real secrets
  live in **Vercel env vars**, not `.env.local`.
- `scripts/set-webhook.mjs` registers the Telegram webhook; `scripts/ping.mjs` health-checks
  `/api/stats` with the raw device secret.

---

## 3. Cloud access model (owner vs admin) — don't re-add roles

The panel has **exactly two kinds of human user**. There is **no role/permission hierarchy** — a
roles system was tried (`03_roles.sql`, `05_hierarchy.sql`) and **deliberately removed**
(`07_drop_legacy_roles.sql`, deleted `rolecode.ts` + `api/role/`). Do not reintroduce it.

| | **Owner (egasi / developer)** | **Admin (single restricted account)** |
|---|---|---|
| Logs in with | master `ADMIN_SECRET` (+ optional TOTP if `ADMIN_TOTP_SECRET` set) | `ADMIN_LOGIN` + `ADMIN_PASSWORD` (env, one account, no DB) |
| Login body to `/api/admin/login` | `{ secret, otp? }` | `{ login, password }` |
| Session token | no `kind` field → full rights | `kind:'admin'` |
| Can do | **everything** incl. delete devices, dump all scans | **view + export + post news only** |

**Sessions (`lib/session.ts`).** After login the master key/password is **never stored in the
browser** — the server returns a short-lived (8 h) **HMAC-SHA256 signed token**
(`base64url(payload).base64url(sig)`, signed with `SESSION_SECRET` || `ADMIN_SECRET`). If stolen it
expires and never exposes the master key. `issueSession()` = owner; `issueAdminSession(login)` =
admin.

**Authorization gates (`lib/auth.ts`).** Endpoints call one of:
- `checkDeviceSecret` — device→cloud writes (`x-device-secret`). Does **not** grant panel read.
- `checkAdminSecret` — **owner only**: raw `ADMIN_SECRET` **or** an owner session token. Rejects
  admin tokens. Guards dangerous/owner endpoints (device delete, `/api/scans` dump).
- `canRead` — **any logged-in panel user** (owner or admin) for read + export endpoints. The
  device secret does **not** work here (you can't dump the fleet from outside the panel).
- `canManageNews` (= `canRead`) — the admin's *only* write capability: posting/deleting news.
- `checkTelegramSecret` — webhook.

All comparisons are constant-time; login failures return one generic error (no field enumeration).

---

## 4. Telegram bot commands

**Cloud webhook bot** (`api/telegram/webhook.ts`) — text slash-commands: `/start`, `/help`,
`/stats`, `/last [N]`, `/threats`, `/devices`, `/id`. Whitelist via `isAdmin(chatId)` against
`ADMIN_CHAT_IDS`; unauthorized chats get a **silent 200** (so an attacker can't burn the bot's quota).

**In-app command panel** (`CommandRouter.kt`, served by the phone) — **inline-button driven**.
Text commands `/start` / `/panel` / `/menu` open the panel. Fixed callbacks: `stats`, `scan_now`,
`history`, `danger_list`, `clear_hist`, `diag`, `version`, `last_inst`, `logcat`, `toggle_apk`,
`panel`. Per-APK callbacks: `del:<token>`, `rescan:<token>`, `info:<token>` (token = first 12 hex
of SHA-256(path), 7-day TTL via `ThreatActions.kt`). Whitelist enforced in `TelegramUpdate.parse`.

---

## 5. Python polling bot (Gen-2, dormant)

`telegram_bot/bot.py` — handlers `/start`, `/help` and a `Document.ALL` handler `on_apk` that
accepts a forwarded `.apk` (≤20 MB), runs `apk_analyzer.py`, and replies with an Uzbek verdict.
Independent intake tool; **not** part of the app runtime or the cloud.

---

## 6. Flask server (Gen-1, dormant)

`ApkGuard/server/app.py` — Flask + Werkzeug. `POST /upload` (multipart `apk`, ≤100 MB), `GET /`,
`GET /download/<filename>`. Auth: `KIBERQALQON_API_KEY`. Binds `127.0.0.1:5000`.
`test_server/test_apk_server.py` is unrelated — it *serves* test malware APKs to an emulator
(`http://10.0.2.2:8000`) for QA. **Legacy, superseded by the cloud; only active if a user sets a
server URL.**

---

## 7. Android ↔ backend contract

There are **four independent Android networking paths**:

1. **Cloud telemetry → Vercel** (`CloudTelemetry.kt`) — **the wired Gen-3 path.**
   `registerDevice()` POSTs `{device_token, name?, android_ver?, app_ver?, lat?, lng?,
   loc_accuracy_m?}` to `<base>/api/device/register`; each scan POSTs the scan body (see §2 table)
   to `<base>/api/scan/upload`. **HTTPS-only**, header `x-device-secret`. Reads
   `BuildConfig.CLOUD_BASE_URL` + `CLOUD_DEVICE_SECRET` (from `local.properties`) — **empty by
   default, so forks/debug builds no-op silently.** Registration is throttled/deduped per app
   version (`last_register_ts` / `last_register_ver`). GPS coords sent only if location permission
   granted. Call sites: `App.onCreate` (register on startup), `ConsentActivity` (register after
   consent), `ApkScanner.scan()` (upload **every** scan, including SAFE).
2. **Telemetry → personal Telegram** (`TelemetryReporter.kt`). Reads `tg_bot_token`/`tg_chat_id`/
   `tg_enabled` from `kiberqalqon_telemetry`. ~35–40 event categories as POST JSON to
   `api.telegram.org/bot<token>/sendMessage`. 1.1 s throttle.
3. **Community sharing → dev's Telegram** (`CommunityReportClient.kt`). Strict opt-in. Uses
   `BuildConfig.DEV_TG_BOT_TOKEN`/`DEV_TG_CHAT_ID` (empty by default → off). Text report (SHA-256,
   package, verdict, reason — **no PII**) + APK via `sendDocument` (≤50 MB).
4. **Dangerous APK file → Flask** (`ServerUpload.kt`, called by `GuardWorker`). Multipart
   `POST <serverUrl>/upload`, ≤50 MB, **HTTPS-only**. Only when `Config.isUploadEnabled` AND a
   server URL is set. Dormant by default.

Command reception (`TelegramCommandPoller.kt`): a WorkManager OneTimeWork chain long-polls
`getUpdates` (25 s) with `allowed_updates=["message","callback_query"]`, persisting the offset;
each update goes to `CommandRouter`. `NetworkInfo.kt` supplies IP + connection type for diagnostics.

---

## 8. File inventory

**Cloud SPA (`ApkGuard/cloud/src/`):** `App.tsx`, `main.tsx`, `pages/*` (Landing, Login, Overview,
MapPage, Devices, Threats, Feed, News, Profile), `components/*` (Layout, NewsCarousel, Toast, ui),
`context/`, `hooks/`, `lib/uzRegions.ts`, `styles.css`.
**Cloud API (`ApkGuard/cloud/api/`):** `scan/upload.ts`, `device/[id].ts` (register + one-device),
`devices.ts`, `scans.ts`, `threats.ts`, `stats.ts`, `feed.ts`, `geo.ts`, `news.ts`,
`admin/login.ts`, `telegram/webhook.ts`.
**Cloud lib:** `supabase.ts`, `telegram.ts`, `format.ts`, `geo.ts`, `auth.ts`, `session.ts`,
`password.ts`, `totp.ts`. **Supabase:** `schema.sql` + `02_geo`…`07_drop_legacy_roles.sql`.
**Build/deploy:** `package.json`, `vite.config.ts`, `tsconfig*.json`, `vercel.json`,
`deploy-prod.bat`, `scripts/{set-webhook,ping}.mjs`, `dist/`, `_legacy/index.html`.
**Android networking:** `CloudTelemetry.kt`, `TelegramBot.kt`, `CommandRouter.kt`,
`TelegramCommandPoller.kt`, `TelemetryReporter.kt`, `ThreatActions.kt`, `CommunityReportClient.kt`,
`ServerUpload.kt`, `NetworkInfo.kt`, `DeviceLocation.kt` (GPS for geo), `GuardWorker.kt`, `Config.kt`.
**Polling bot (`telegram_bot/`):** `bot.py`, `requirements.txt`. **Flask (`ApkGuard/server/`):**
`app.py`. **Test server (`ApkGuard/test_server/`):** `test_apk_server.py`.

---

## 9. Secrets & config

| Secret | Where set | Notes |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | Vercel env | service_role bypasses RLS — never expose to client |
| `DEVICE_SHARED_SECRET` | Vercel env | device→cloud auth (`x-device-secret`) |
| `ADMIN_SECRET` | Vercel env | **owner** master key (panel login + `x-admin-secret`) |
| `SESSION_SECRET` | Vercel env | signs panel session tokens (falls back to `ADMIN_SECRET`) |
| `ADMIN_TOTP_SECRET` | Vercel env | optional owner 2FA; if unset, owner login is secret-only |
| `ADMIN_LOGIN`, `ADMIN_PASSWORD` | Vercel env | the single restricted admin account |
| `TELEGRAM_BOT_TOKEN` | Vercel env (cloud); SharedPrefs `tg_bot_token` (personal); `local.properties` `dev.tg.bot.token` (community) | **never hardcode in the distributed APK** |
| `TELEGRAM_WEBHOOK_SECRET` | Vercel env | validates inbound webhook |
| `ADMIN_CHAT_IDS` | Vercel env | Telegram alert/whitelist chats |
| `CLOUD_BASE_URL`, `CLOUD_DEVICE_SECRET` | `local.properties` → `BuildConfig` | Android→cloud; empty by default → telemetry no-ops |
| `DEV_TG_BOT_TOKEN`, `DEV_TG_CHAT_ID` | `local.properties` → `BuildConfig` | community-share; empty by default |
| `KIBERQALQON_API_KEY` | Flask env | dormant Gen-1 |

Real secrets live in **Vercel env vars**, not committed `.env*` files; the live device secret on
the phone is set in **`local.properties`**. Gitignored: `node_modules/`, `.vercel/`, `dist/`,
`.env*`. Standing policy: the Telegram bot stays **personal** — token never baked into the shipped
APK, chat_id whitelisted, shared telemetry opt-in only.

**For the skill: when adding/altering Android→cloud networking,** the targets are
`POST /api/device/register` and `POST /api/scan/upload` with header `x-device-secret` (HTTPS-only);
danger/suspicious scans auto-notify `ADMIN_CHAT_IDS` server-side (no separate notify call). Geo is
optional `lat`/`lng`/`loc_accuracy_m` in the body. Never weaken the owner-vs-admin split in §3.
