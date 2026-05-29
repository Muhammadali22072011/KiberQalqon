---
name: kiberqalqon
description: >-
  Complete project knowledge for KiberQalqon (formerly ApkGuard) — Muhammadali's
  personal Android APK antivirus written in Kotlin (package com.kiberqalqon) — together
  with the bundled Android-malware forensics case study, all living in
  C:\Users\User\OneDrive\Desktop\KiberQalqon. ALWAYS use this skill when the user
  mentions KiberQalqon, ApkGuard, the APK antivirus / scanner / "skaner", any analyzer
  (ApkScanner, DexPatternAnalyzer, DropperDetector, IconImpersonationDetector,
  ZipEncryptionDetector, PermissionCombos, ManifestAnalyzer, NativeLibAnalyzer,
  ObfuscatedSignatures, MaliciousHashes/Certs/Packages, TrustedSignatures, FilenameHeuristic),
  the background workers/receivers, quarantine or self-defense (SecurityGuard/SelfGuard),
  the Telegram bot command panel or telemetry, the Vercel+Supabase cloud backend, building
  or signing the APK (gradlew assembleDebug), or the malware analysis (the analysis/ Python
  scripts, the RASMLAR / VIDEO / toydanfotolar / TAKLIFNOMA samples, Ajina.Banker, RoundRift,
  the fake-ZIP-encryption evasion, IOCs / decryption keys). Also use whenever editing any
  .kt file under com.kiberqalqon, the cloud/ TypeScript, the telegram_bot/ Python, or the
  analysis/ scripts. This is a personal defensive-security project for authorized analysis.
metadata:
  type: project
---

# KiberQalqon — Project Knowledge

Personal Android security project by **Muhammadali**. One workspace
(`C:\Users\User\OneDrive\Desktop\KiberQalqon`) holds two tightly-linked halves:

1. **KiberQalqon antivirus** — a Kotlin Android app (`com.kiberqalqon`, ~89 `.kt` files,
   versionName 7.9) that finds, judges, quarantines and deletes malicious APKs. It now has
   **two backends**: a personal Telegram bot (remote command panel + per-event telemetry) and
   a **live Vercel+Supabase cloud** (central monitoring map + admin panel). The phone reports
   anonymous, opt-in structured telemetry to the cloud (`CloudTelemetry.kt`) on top of the
   Telegram path.
2. **A malware forensics case study** — the user reverse-engineered real Uzbek banking
   trojans / droppers (disguised as photos/videos, spread over Telegram). That RE work is
   the **empirical basis** for every detector in the antivirus. The detectors hard-code the
   exact hashes, packages, certs, keys and evasion tricks discovered here.

Treat the two halves as one story: **the malware in `analysis/` is what KiberQalqon is built
to catch.** When touching a detector, the matching threat is documented in the case study.

---

## Repository map (top level)

```
KiberQalqon/                   # workspace root (was "APK Virus Analysis")
├── ApkGuard/                  # the antivirus app (folder still named ApkGuard; package = com.kiberqalqon)
│   ├── app/src/main/java/com/kiberqalqon/   # all ~89 Kotlin files
│   ├── app/src/test/java/com/kiberqalqon/   # JUnit4 unit tests (pure functions only)
│   ├── app/build.gradle.kts                 # build config, signing, BuildConfig fields
│   ├── cloud/                 # LIVE cloud backend: Vite+React SPA + Vercel functions + Supabase + Telegram webhook (TS)
│   ├── server/                # Gen-1 backend: local Flask upload sink (legacy)
│   ├── test_server/           # serves test APKs to an emulator for QA
│   ├── gradlew / gradlew.bat  # build from HERE
│   └── *.md                   # design/UX/dev docs (mostly RU/UZ)
├── analysis/                  # malware forensics: 01_*.py … 36_*.py, outputs, unpacked/, HISOBOT*.md
├── telegram_bot/              # Gen-2 backend: python-telegram-bot sample-intake bot (auxiliary)
├── jadx_tool/ + jdk/          # bundled decompilation tooling (jadx + a JDK)
├── apktool.bat + apktool_*.jar
└── *.md                       # root-level virus reports (RU / UZ / EN)
```

---

## The two halves — where to read more

| You're working on… | Read |
|---|---|
| The Android app: scanning engine, analyzers, workers, services, UI, quarantine, self-defense, persistence, build | `references/antivirus-app.md` |
| The Telegram bot (command panel + telemetry), the cloud (Vercel/Supabase) backend, the Flask/python bots, the Android↔backend contract, secrets/env | `references/backends.md` |
| The malware itself: the analyzed samples, the attack chain, the decryption scripts, IOCs/keys, and how each finding maps to a detector | `references/malware-case-study.md` |

Read the relevant reference file fully before making non-trivial changes — the detail there
prevents you from re-deriving things the user already worked out (e.g. the two-level
XOR+Base64 key scheme, the ZIP general-purpose-bit evasion, the verdict thresholds).

---

## Build & run

```powershell
cd "C:\Users\User\OneDrive\Desktop\KiberQalqon\ApkGuard"
.\gradlew.bat assembleDebug
# output: app\build\outputs\apk\debug\kiberqalqon-<epoch-millis>-debug.apk
```

Gotchas that bite every time:
- **Workspace root is `KiberQalqon/`, but the app module folder is still `ApkGuard/`** (package
  `com.kiberqalqon`). The old workspace path `C:\Users\Muhammadali\Desktop\APK Virus Analysis`
  is stale — some in-repo docs (cloud README, HOW_TO_BUILD.md) still reference it. Build from
  `ApkGuard/`.
- **APK filename carries `System.currentTimeMillis()`** on purpose: Windows Defender locks a
  freshly-built APK for ~15–30 min, so each build gets a unique name instead of overwriting.
- **Debug installs side-by-side** with release (`applicationIdSuffix=.debug`, `-DEBUG` suffix).
- **Release signing** reads a gitignored `keystore.properties` (alias `kiberqalqon`, v2+v3+v4).
  No keystore → unsigned build (doesn't fail). Self-defense (`SecurityGuard`) only runs in
  release, so debug behaves differently — test security checks on a real signed build.
- minSdk 24, target/compile 34, Java 17. Only third-party runtime dep is **OkHttp 4.12.0**.
- Building needs an Android SDK (`local.properties` → `sdk.dir`); on a fresh machine the
  easiest path is opening the project in Android Studio once to install SDK 34 / build-tools.

---

## Backend at a glance

Two backends are LIVE today; two older generations are dormant:
- **LIVE — Telegram command panel:** the phone itself long-polls `getUpdates`
  (`TelegramBot.kt` + `TelegramCommandPoller.kt` + `CommandRouter.kt`) and sends per-event
  telemetry to the user's *personal* bot. The phone *is* the bot server.
- **LIVE — Gen-3 cloud** (`cloud/`, deployed at **`https://kiberqalqon-cloud.vercel.app`**):
  Vite+React SPA dashboard + Vercel serverless functions + Supabase + Telegram webhook. **Now
  wired into Android** — `CloudTelemetry.kt` POSTs structured JSON to `/api/device/register`
  and `/api/scan/upload` (header `x-device-secret`), so the panel's monitoring map and stats
  fill with real device data. The old "not deployed / Android client missing" note is obsolete.
- **Dormant — Gen-1 Flask** (`server/app.py`): local LAN upload sink, off by default (empty
  `DEFAULT_SERVER_URL`). **Gen-2 python bot** (`telegram_bot/bot.py`): standalone sample-intake
  bot, not part of the app runtime. Details + the cloud access model in `references/backends.md`.

---

## Core conventions — do not violate

These are load-bearing project rules (several come from the user's standing preferences):

- **Uzbek only for everything a human sees.** All user-facing UI strings, notifications and
  Telegram messages are Uzbek. Verdicts/categories/statuses are translated (`verdictUz()`,
  `labelOf()`); never surface a raw enum name. Code comments may be RU/UZ.
- **Never return a false SAFE.** Unreadable files and scan errors resolve to **SUSPICIOUS**,
  never SAFE — a wrongly-SAFE verdict is the worst possible bug in an antivirus. Preserve
  this whenever you touch `ApkScanner`.
- **The Telegram bot stays personal.** Never hardcode a bot token into the distributed APK.
  The personal-telemetry token lives in `SharedPreferences` (user enters it); the optional
  dev/community token comes from a gitignored `local.properties` via `BuildConfig`. There are
  **two distinct bots**: personal (full admin panel + telemetry) and dev-community (opt-in,
  hash + file only). `chat_id` is whitelisted; shared telemetry is opt-in only.
- **Cloud panel access model is owner + one admin — no roles.** The panel has exactly two kinds
  of user: the **owner** (master key + optional 2FA, full rights) and a **single restricted admin**
  (login+password, view+export+post-news only). A roles/hierarchy system was tried and
  deliberately removed — **do not reintroduce roles, `rolecode`, or per-permission tiers.** Details
  in `references/backends.md` §3.
- **Just fix, don't ask.** When the user reports bugs, fix them all in one pass — don't
  enumerate them and ask which to fix first.

---

## Finding things fast

- Scan orchestrator: `app/src/main/java/com/kiberqalqon/ApkScanner.kt` → `scan()`.
- App startup orchestration: `App.kt` → `onCreate`.
- All detectors are individual top-level files named after what they detect
  (e.g. `DropperDetector.kt`, `DexPatternAnalyzer.kt`, `ZipEncryptionDetector.kt`).
- Verdict thresholds & sensitivity: bottom of `ApkScanner.kt` + `Config.kt`.
- Telegram: `TelegramBot.kt`, `CommandRouter.kt`, `TelemetryReporter.kt`, `TelegramCommandPoller.kt`.
- Persistence: everything is `SharedPreferences` keyed `kiberqalqon_*` (see `Config.kt`).
- Malware decryption methodology + recovered keys: `analysis/HISOBOT_FINAL.md` and
  `analysis/27_inline_decrypt_all.py`.

The reference files include full per-file inventories. Memory cited file:line numbers are
point-in-time — verify against current code before asserting them as fact.

---

## Scope & safety

This is an **authorized, personal defensive-security project**: the owner analyzes real
malware that targets Uzbek users in order to build protection against it. Work on detection,
analysis, reverse-engineering of the bundled samples, and hardening is in scope. Do not turn
any of this into something offensive (e.g. weaponizing the recovered C2 protocol, building a
dropper). When in doubt, keep the framing defensive.
