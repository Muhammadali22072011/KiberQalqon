<div align="center">

# 🛡️ KiberQalqon

**O'zbek foydalanuvchilarini bank troyanlaridan himoya qiluvchi Android antivirus**
*An on-device Android APK antivirus specialized against Central-Asian banking trojans*

![Platform](https://img.shields.io/badge/platform-Android%208%2B%20(API%2024)-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-50.8%25-7f52ff)
![Version](https://img.shields.io/badge/version-8.0%20(80)-f0506e)
![Backend](https://img.shields.io/badge/cloud-Vercel%20%2B%20Supabase-000000)

</div>

---

## What is this?

**KiberQalqon** ("Cyber Shield") is a personal defensive-security project with two tightly-linked halves:

1. **The antivirus** — a native Android app (`com.kiberqalqon`, Kotlin) that **finds, judges, quarantines and deletes malicious APKs** entirely on-device. It is purpose-built to catch the banking trojans and droppers that spread across Uzbekistan over Telegram, disguised as photos, videos and invitations.

2. **The malware case study** — real Uzbek banking trojans (`Ajina.Banker`, `RoundRift`, fake-`TAKLIFNOMA` droppers) were reverse-engineered, and **that analysis is the empirical basis for every detector** in the app. The scanner hard-codes the exact hashes, certificates, packages and evasion tricks recovered from real samples.

> The malware in [`analysis/`](analysis/) is exactly what the antivirus is built to stop.

---

## ✨ Highlights

- **On-device, no cloud required to scan** — the verdict engine runs locally; OkHttp is the only third-party runtime dependency.
- **Never a false "safe"** — unreadable files and scan errors resolve to *suspicious*, never *safe*. A wrongly-safe verdict is treated as the worst possible bug.
- **Beats the ZIP-encryption evasion** — directly parses ZIP local-file **and** central-directory headers for the general-purpose encryption bit that Android installs but `ZipFile` refuses to read (the classic `Ajina.Banker` / `TAKLIFNOMA` trick).
- **24/7 real-time protection** — a foreground service watches Downloads / Telegram / WhatsApp / Bluetooth folders and scans new APKs within ~1 second, even off the lock screen.
- **Phishing-icon detection** — perceptual aHash compares an app's icon against real bank/messenger icons (Click, Payme, Uzcard, Telegram…) to catch impersonation.
- **Self-defense & quarantine** — tamper/root/Frida/emulator checks (release builds), and malicious APKs are moved to an internal, non-installable quarantine with a 7-day restore window.
- **Two opt-in backends** — a personal Telegram command panel and a live Vercel + Supabase cloud (monitoring map, stats, threat feed).
- **Uzbek-first UX** — every user-facing string, notification and alert is in Uzbek.

---

## 🔬 Detection engine

`ApkScanner.scan()` runs a layered pipeline that short-circuits on hard signals and otherwise accumulates a risk score. Each analyzer is isolated so a single failure can never abort the scan.

| Layer | Analyzer | Catches |
|---|---|---|
| **Hard signals** (instant verdict) | `MaliciousHashes` · `MaliciousCerts` · `MaliciousPackages` · `ThreatDb` | Known sample/cert/package blacklists |
| | `ZipEncryptionDetector` | AV-evasion via ZIP encryption flag |
| | `IconImpersonationDetector` | Phishing icons of protected brands |
| | `DropperDetector` | Hidden APK/DEX/ELF + high-entropy payloads |
| **Scoring** | `ManifestAnalyzer` | Accessibility / device-admin / exported components |
| | `PermissionCombos` | Dangerous **combinations** (OTP-grabber, overlay banker, botnet…) |
| | `DexPatternAnalyzer` | DexClassLoader, reflection, SMS APIs, packers |
| | `ObfuscatedSignatures` | XOR+Base64-hidden C2 / IOC markers |
| | `NativeLibAnalyzer` | Suspicious `.so` imports + entropy |
| | `FilenameHeuristic` | Lure names, double extensions, typosquat, homoglyph |
| **False-positive guard** | `AppReputation` | Verified vendor (package **+** matching cert) suppresses only *soft* signals — hard signals still fire |

---

## 🏗️ Architecture

```
                          ┌──────────────────────────┐
   APK arrives  ───────▶  │   ProtectionService      │  24/7 foreground watcher
 (Telegram/WhatsApp)      │   + MultiPathFileObserver │  + 1s fast-scan loop
                          └────────────┬─────────────┘
                                       ▼
                          ┌──────────────────────────┐
                          │      ApkScanner.scan()    │  layered analyzers → verdict
                          └────────────┬─────────────┘
                  SAFE / SUSPICIOUS / DANGER
                                       ▼
        ┌──────────────┬──────────────┴───────────────┬──────────────┐
        ▼              ▼                               ▼              ▼
   Quarantine    AutoScanActivity                Telegram        ☁️ Cloud
   (.quar, 7d)   (live result UI)               (personal bot)   (Vercel + Supabase)
                                                                  map · stats · feed
```

### Backends (both opt-in, off by default in forks)
- **Telegram command panel** — the phone long-polls the Bot API directly (`TelegramBot` + `CommandRouter`); the device *is* the bot server. The owner's `chat_id` **and** numeric user-id are whitelisted.
- **Cloud** ([`ApkGuard/cloud/`](ApkGuard/cloud/)) — a Vite + React SPA on Vercel serverless functions + Supabase, deployed at `kiberqalqon-cloud.vercel.app`. The app POSTs anonymous, structured telemetry (`CloudTelemetry`) so the central panel's map and stats fill with real device data. Access model is **owner** (master key + optional TOTP) + **one restricted admin** (view / export / news) — no roles.

---

## 🚀 Build & run

> **Toolchain:** JDK 17, Android SDK 34, Kotlin. Build from the `ApkGuard/` module folder.

```powershell
cd ApkGuard
.\gradlew.bat assembleDebug
# output: app\build\outputs\apk\debug\kiberqalqon-<epoch-millis>-debug.apk
```

- `minSdk 24`, `target/compile 34`, ViewBinding + BuildConfig on.
- The APK filename carries `System.currentTimeMillis()` on purpose (Windows Defender locks a freshly-built APK for a while — each build gets a unique name).
- **Release signing** reads a gitignored `keystore.properties`; without it the build is unsigned (and self-defense, which is release-only, is skipped).
- Cloud / community / device secrets come from a gitignored `local.properties` → `BuildConfig`. Empty by default, so forks build with those features silently disabled.

### Run the unit tests
```powershell
.\gradlew.bat testDebugUnitTest    # pure-function JUnit4 tests (no Robolectric)
```

---

## 🧱 Tech stack

| | |
|---|---|
| **App** | Kotlin · AndroidX · WorkManager · OkHttp 4.12 · ViewBinding |
| **Cloud** | TypeScript · Vite + React 18 · Vercel serverless · Supabase (Postgres + Storage) · Leaflet |
| **Tooling** | python-telegram-bot · `jadx` · `apktool` (bundled for analysis) |

---

## 📁 Repository layout

```
KiberQalqon/
├── ApkGuard/              # the antivirus app (package com.kiberqalqon)
│   ├── app/src/main/...   # ~89 Kotlin files: scanner, analyzers, workers, UI
│   ├── cloud/             # Vercel + Supabase backend (TS) + React SPA panel
│   └── gradlew(.bat)      # build from here
├── analysis/              # malware forensics: decryption scripts, IOCs, reports
├── telegram_bot/          # auxiliary Python sample-intake bot
└── *.md / *.txt           # virus analysis reports (RU / UZ / EN)
```

---

## ⚖️ Scope & ethics

This is an **authorized, personal defensive-security project**. The owner analyzes real malware that targets Uzbek users **in order to build protection against it**. The repository contains detection logic, reverse-engineering notes, and recovered IOCs for defensive purposes only — it is not, and must not become, an offensive toolkit.

---

<div align="center">

Built by **Muhammadali** · Navoiy, O'zbekiston 🇺🇿

</div>
