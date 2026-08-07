# KiberQalqon Backends & Telegram Integration — Reference

KiberQalqon's "backend" is fundamentally a **Telegram bot used as a private remote command
panel + telemetry sink** — never a public multi-tenant service. Three generations exist in the
repo; only one is live. Read this before changing anything network-related.

## Contents
1. Backend evolution (which gen is live)
2. Cloud backend (Gen-3, current/intended)
3. Telegram bot commands (cloud webhook vs in-app panel)
4. Python polling bot (Gen-2)
5. Flask server (Gen-1)
6. Android ↔ backend contract (the 3 networking paths)
7. File inventory
8. Secrets & config

---

## 1. Backend evolution

**Gen-1 — Local Flask server** (`ApkGuard/server/app.py`, `ZAPUSK_SERVERA.bat`). Flask on the
user's PC (`127.0.0.1:5000`, LAN via `KIBERQALQON_BIND=0.0.0.0`) that received dangerous APK
*files* via `POST /upload` and served a listing page. Hardened 2026-05-26 (API key,
`secure_filename`). **Legacy/optional** — only fires if the user manually sets a server URL;
`DEFAULT_SERVER_URL` ships empty, so it's dormant. Doesn't work off-LAN.

**Gen-2 — Python polling bot** (`telegram_bot/bot.py`, python-telegram-bot 21.6). A
sample-intake bot: users *forward* a suspicious `.apk`; the bot downloads it (≤20 MB Telegram
getFile limit), runs `apk_analyzer.py`, replies with an Uzbek verdict (🟢/🟠/🔴). Collected
`samples/` are mined for cert fingerprints that feed the signature DB. **Standalone/auxiliary;
not the app's command channel.**

**Gen-2.5 (LIVE) — in-app Telegram command panel.** The command-panel role is actually
implemented *inside the Android app*: the phone long-polls `getUpdates` directly against the
Telegram Bot API (`TelegramBot.kt` + `TelegramCommandPoller.kt` + `CommandRouter.kt`). **The
phone is the bot server.** This + opt-in telemetry is the production backend today.

**Gen-3 — Cloud serverless** (`ApkGuard/cloud/`, Vercel + Supabase + Telegram webhook,
TypeScript). The intended architecture: `📱 Android → ☁️ Vercel API → 🗄️ Supabase`, threat
alerts pushed to a Telegram group, commands served via webhook (push) instead of polling.
Replaces the Flask sink (durable Postgres) and centralizes telemetry across devices.
**Code-complete but NOT deployed and NOT wired into Android.** No Kotlin file references
`/api/scan/upload`, `vercel.app`, or `x-device-secret` yet — the Android client is the
explicit missing piece (README: "hali yozilmagan — alohida task").

---

## 2. Cloud backend (Gen-3)

Stack: Node ≥20, TypeScript, `@vercel/node@3.2.0` functions (maxDuration 30s),
`@supabase/supabase-js ^2.45.0`. A static dashboard exists at `public/index.html` (login by
`DEVICE_SHARED_SECRET`) but its `/app.js` + `/styles.css` are **missing**, so it's
non-functional as committed.

### API endpoints

| Path | Method | Auth header | Body / query | Success |
|---|---|---|---|---|
| `/api/scan/upload` | POST | `x-device-secret` | `{device_token, apk_hash(64-hex), package_name?, app_label?, apk_size?, verdict('safe'\|'suspicious'\|'danger'\|'error'), risk_score?, reasons?[], perms?[]}` | `{ok, scan_id}` |
| `/api/device/register` | POST | `x-device-secret` | `{device_token(≥16), name?, android_ver?, app_ver?}` | `{ok, device_id}` |
| `/api/stats` | GET | `x-device-secret` | — | `{ok, stats}` (v_stats_today) |
| `/api/scans` | GET | `x-device-secret` | `limit`(1–200, def 50), `verdict` | `{ok, scans[]}` |
| `/api/threats` | GET | `x-device-secret` | — | `{ok, threats[]}` (≤100) |
| `/api/devices` | GET | `x-device-secret` | — | `{ok, devices[]}` (≤50) |
| `/api/telegram/webhook` | POST | `x-telegram-bot-api-secret-token` | Telegram Update | `{ok}` |

`api/scan/upload.ts` is the core: upserts the device, inserts the scan, and on
`danger`/`suspicious` calls the `upsert_threat` RPC, formats an Uzbek alert
(`formatThreatAlert`), and fans it out to every admin chat, logging each send to
`notifications`. `classify()` maps reasons → category (sms_stealer/spyware/dropper/suspicious).
RPC failures are logged but still return 200 so the alert always sends.

### Supabase schema (`supabase/schema.sql`)
- **Tables:** `devices` (uuid, unique `device_token`, versions, last_seen), `scans` (bigserial,
  FK device, apk_hash, verdict CHECK, risk_score, `reasons`/`perms` jsonb), `threats`
  (apk_hash PK blacklist, category, severity, `seen_count`, first/last_seen), `admin_chats`
  (chat_id PK whitelist, role), `notifications` (sent-message audit log).
- **RPCs:** `upsert_threat(p_hash,p_package,p_label,p_category,p_severity)` (insert-or-bump),
  `greatest_severity(a,b)` (severity-rank max).
- **Views:** `v_stats_today`, `v_devices_with_counts`, `v_recent_threats`.
- **RLS** enabled on all tables; the server uses the `service_role` key (bypasses RLS), `anon`
  is granted nothing.

### lib helpers
- `lib/supabase.ts` — cached `db()` client from `SUPABASE_URL` + `SUPABASE_SERVICE_KEY`.
- `lib/telegram.ts` — `sendMessage()`, `adminChatIds()` (CSV parse), `isAdmin()`.
- `lib/auth.ts` — `checkDeviceSecret()` / `checkTelegramSecret()`, constant-time compare.
- `lib/format.ts` — Uzbek `verdictLabel`, `formatThreatAlert`, `formatStats`, Markdown `escape()`.

### Deploy + webhook (`package.json` scripts)
- `npm run deploy` → `vercel --prod`.
- `scripts/set-webhook.mjs` — Telegram `setWebhook` → `<VERCEL_URL>/api/telegram/webhook` with
  `secret_token`, `allowed_updates:["message"]`, `drop_pending_updates`.
- `scripts/ping.mjs` — GETs `/api/stats` with the device secret as a health check.
- Not deployed; README placeholder URL `https://kiberqalqon-cloud.vercel.app`. Free-tier
  sizing (Vercel Hobby + Supabase Free) estimated to last years for one phone.

---

## 3. Telegram bot commands

**Cloud webhook bot** (`api/telegram/webhook.ts`) — text slash-commands, no inline buttons:
`/start`, `/help`, `/stats` (today), `/last [N]` (def 5, cap 1–20), `/threats` (top 10 by
severity + seen_count), `/devices` (last 10), `/id` (echo chat_id). Whitelist via
`isAdmin(chatId)` against `ADMIN_CHAT_IDS`; unauthorized chats get a **silent 200** (so an
attacker can't burn the bot's Telegram quota).

**In-app command panel** (`CommandRouter.kt`, served by the phone) — **inline-button driven**
(typing on a phone is awkward). Text commands: `/start` / `/panel` / `/menu` open the panel.
Fixed callbacks: `stats`, `scan_now`, `history`, `danger_list`, `clear_hist`, `diag`,
`version`, `last_inst`, `logcat`, `toggle_apk`, `panel`. Per-APK callbacks: `del:<token>`,
`rescan:<token>`, `info:<token>` (token = first 12 hex of SHA-256(path), 7-day TTL via
`ThreatActions.kt`). Whitelist enforced in `TelegramUpdate.parse(obj, ownChat)`.

---

## 4. Python polling bot (Gen-2)

`telegram_bot/bot.py` — handlers `/start`, `/help` (Uzbek welcome) and a `Document.ALL`
handler `on_apk` that accepts a forwarded `.apk` (≤20 MB), saves to `samples/`, runs
`apk_analyzer.py` in a subprocess (120s timeout), parses output, replies with a verdict.
Still usable as an independent intake tool; **not** part of the app runtime or Gen-3.

---

## 5. Flask server (Gen-1)

`ApkGuard/server/app.py` — Flask + Werkzeug. `POST /upload` (multipart field `apk`, ≤100 MB),
`GET /` (HTML list), `GET /download/<filename>`. Auth: `KIBERQALQON_API_KEY` via `X-Api-Key` /
`Authorization: Bearer` / `?token=` (random token generated + logged if unset). Binds
`127.0.0.1:5000` by default. `test_server/test_apk_server.py` is unrelated — it *serves* test
malware APKs to an emulator (`http://10.0.2.2:8000`) for QA. **Gen-1 legacy, superseded by
cloud; only active if a user sets a server URL.**

---

## 6. Android ↔ backend contract

There are **three independent Android networking paths**, all currently pointing at Telegram
or the local Flask server — **none point at the cloud yet**:

1. **Telemetry → personal Telegram** (`TelemetryReporter.kt`). Reads
   `tg_bot_token`/`tg_chat_id`/`tg_enabled` from `kiberqalqon_telemetry`. ~35–40 event
   categories as **POST** JSON to `https://api.telegram.org/bot<token>/sendMessage` with
   `{chat_id, text, disable_web_page_preview}`. Switched from GET → POST because long emoji
   text overflowed URL limits and exposed the token. 1.1s throttle.
2. **Community sharing → dev's Telegram** (`CommunityReportClient.kt`). Strict opt-in (4
   preconditions incl. `Config.hasCommunityShareConsent`). Uses `BuildConfig.DEV_TG_BOT_TOKEN`/
   `DEV_TG_CHAT_ID` (from `local.properties`, empty by default → off). Text report (SHA-256,
   package, verdict, reason, device/Android — **no PII, no installed-app list**) + APK via
   `sendDocument` (≤50 MB). Uses GET querystring; crash reports via `sendBlocking` (spawns a
   thread so it survives a dying process). Inline keyboard Delete/Rescan/Info.
3. **Dangerous APK file → Flask** (`ServerUpload.kt`, called by `GuardWorker`). Multipart
   `POST <serverUrl>/upload`, ≤50 MB, **HTTPS-only** (rejects non-HTTPS; URL via `HttpUrl`).
   Only when `Config.isUploadEnabled` AND a non-empty server URL is set.

Command reception (`TelegramCommandPoller.kt`): a WorkManager OneTimeWork chain long-polls
`getUpdates` (25s) with `allowed_updates=["message","callback_query"]`, persisting the offset;
each update goes to `CommandRouter`. `NetworkInfo.kt` supplies local/external IP (via
`api.ipify.org`) + connection type for diagnostics — personal bot only.

**Intended cloud contract** (Android side unimplemented): JSON POST to `/api/scan/upload` with
`x-device-secret`, plus `/api/device/register` on first run.

---

## 7. File inventory

**Cloud (`ApkGuard/cloud/`):** `api/scan/upload.ts` (core ingest + alert), `api/device/register.ts`,
`api/stats.ts`, `api/scans.ts`, `api/threats.ts`, `api/devices.ts`, `api/telegram/webhook.ts`,
`lib/{supabase,telegram,auth,format}.ts`, `supabase/schema.sql`, `scripts/{set-webhook,ping}.mjs`,
`public/index.html`, `package.json`, `vercel.json`, `tsconfig.json`, `.env.example`, `README.md`.
**Polling bot (`telegram_bot/`):** `bot.py`, `requirements.txt` (ptb 21.6), `README.md`.
**Flask (`ApkGuard/server/`):** `app.py`, `requirements.txt`. **Test server
(`ApkGuard/test_server/`):** `test_apk_server.py`. Launcher: `ApkGuard/ZAPUSK_SERVERA.bat`.
**Android networking:** `TelegramBot.kt`, `CommandRouter.kt`, `TelegramCommandPoller.kt`,
`TelemetryReporter.kt`, `ThreatActions.kt`, `CommunityReportClient.kt`, `ServerUpload.kt`,
`NetworkInfo.kt`, `GuardWorker.kt`, `Config.kt`.

---

## 8. Secrets & config

| Secret | Where set | Notes |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | Vercel env / `.env.local` | service_role bypasses RLS — never expose to client |
| `TELEGRAM_BOT_TOKEN` | Vercel env (cloud); Android SharedPrefs `tg_bot_token` (telemetry); `local.properties` `dev.tg.bot.token` (community) | **Never hardcode in the distributed APK** |
| `TELEGRAM_WEBHOOK_SECRET` | Vercel env | validates inbound webhook |
| `DEVICE_SHARED_SECRET` | Vercel env | device→cloud auth (`x-device-secret`); also dashboard login |
| `ADMIN_CHAT_IDS` | Vercel env | bot whitelist (negative group id) |
| `KIBERQALQON_API_KEY` | Flask env | random-generated if unset |
| `DEV_TG_BOT_TOKEN`, `DEV_TG_CHAT_ID` | `local.properties` → `BuildConfig` | empty by default; forks ship the feature disabled |

Never commit/hardcode: the Supabase service key, any Telegram bot token, the device shared
secret. Gitignored: `node_modules/`, `.vercel/`, `.env`, `.env.local`. Standing policy: the
Telegram bot stays personal — token never baked into the shipped APK, chat_id whitelisted,
shared telemetry opt-in only.

**For the skill: when adding Android-side cloud networking,** the upload target is
`POST /api/scan/upload` with header `x-device-secret`; threats auto-notify `ADMIN_CHAT_IDS`
(no separate notify call needed). Body shape is the table in §2.
