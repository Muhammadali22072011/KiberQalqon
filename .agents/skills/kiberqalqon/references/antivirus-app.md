# KiberQalqon Android App — Architecture Reference

Package `com.kiberqalqon` · versionName 7.8 / versionCode 78 · Kotlin · ~78 `.kt` files.
A personal APK antivirus specialized against Central-Asian banking trojans
(Ajina.Banker, RoundRift, Uzbek droppers). Source root:
`ApkGuard/app/src/main/java/com/kiberqalqon/`. The Telegram bot is the remote
command/telemetry backend. All user-facing strings are Uzbek; comments mix RU/UZ.

> file:line citations are point-in-time — verify before relying on exact numbers.

## Contents
1. App lifecycle & entry points
2. Scanning engine (`ApkScanner.scan()`) and every analyzer
3. Background protection (workers / receivers / services / file observers)
4. Telegram integration
5. Self-defense & quarantine
6. Persistence & config
7. UI surface
8. Full file inventory
9. Build config
10. Conventions, gotchas & known issues

---

## 1. App lifecycle & entry points

**`App.kt`** (`onCreate`) orchestrates startup; every step is wrapped in try/catch so one
failure never kills the app:
1. `CrashHandler.install()` — first line; routes later uncaught exceptions to a file + Telegram.
2. `Config.ensureFirstRunDefaults()` — bakes all protection toggles ON on first launch; welcome notification.
3. `TelemetryReporter.report(APP_START)`.
4. "Service killed" detector — compares `last_start` in `kiberqalqon_lifecycle`; gap ≥6h on an OEM-restricted device → kill notification; ≥12h → `SERVICE_KILLED` telemetry.
5. `SecurityGuard.runAllChecks()` — **release only**; kills the process (`Process.killProcess` + `exitProcess(10)`) if tamper/root/Frida/emulator is detected, before protection classes are observable.
6. `ThemeHelper.applyTheme()` + `LocaleHelper.apply()`.
7. `CertUtil.selfFingerprintSha256()` → `TrustedSignatures.registerSelf()` (own APK always SAFE).
8. `ProtectionService.start()` if background protection enabled.
9. Schedules `GuardWorker` (15-min periodic, `KEEP`, work name `kiberqalqon_scan`), starts `TelegramCommandPoller` if listen opted-in, schedules Heartbeat / DailyReport / InstalledAppsRescan / AccessibilityWatcher.
10. Dynamically registers 3 receivers (manifest broadcasts don't fire on API 26+): `PackageInstallReceiver` (PACKAGE_ADDED/REPLACED/REMOVED), `SystemStateReceiver` (SIM/airplane), `ScreenUnlockReceiver` (USER_PRESENT/SCREEN_ON).

**`SplashActivity`** (launcher, exported) — logo animation → permission gauntlet
(`checkPermissions()`): storage → overlay → OEM overlay → battery optimization →
notifications → OEM autostart guide. Long-press the version text opens `DiagnosticsActivity`
(hidden backdoor). Routing (`goToMainActivity()`): no consent→`ConsentActivity`; first
run→`OnboardingActivity`; no initial scan→`InitialScanActivity`; else→`DashboardNewActivity`.

**`MainActivity`** — the "Skaner" tab; lists found APKs (`ApkAdapter`), tap-to-scan with 5s
timeout, hosts `MultiPathFileObserver`, schedules `PeriodicCheckWorker` (separate 15-min work
`periodic_apk_check`).

**`DashboardNewActivity`** — main home post-onboarding; speedometer protection level, recent
threat rows (`ScanHistory`), full installed-apps list with verdict dots + install-source
badges (Play/Sideload), UZ/RU toggle.

---

## 2. Scanning engine — `ApkScanner.scan()`

Pipeline short-circuits on "hard" signals; each analyzer is in try/catch defaulting to empty
findings so a single analyzer crash can't abort the scan.

**Early exits (instant verdict):**
- **SelfGuard check** → own APK = SAFE skip.
- **ScanCache** → cached result on (path + mtime + size) hit (no re-telemetry).
- File unreadable → **SUSPICIOUS** (never false-SAFE).
- **`MaliciousHashes`** — SHA-256 of APK bytes vs blacklist (6 hardcoded families:
  Ajina.Banker ×3, RoundRift, Uzbek-dropper.vudgi / .taklifnoma) → instant DANGER.
- **`ZipEncryptionDetector`** — raw byte scan of ZIP **local-file-headers AND central-directory
  headers** for GP-flag bit-0 (encryption). Android installs these but Java `ZipFile` can't
  read them → all other analyzers get neutered. Classic Ajina.Banker / TAKLIFNOMA evasion.
  Any encrypted entry → DANGER. (Checks both header tables because some samples only set the
  flag in the central directory.)
- **`MaliciousCerts`** — SHA-256 of signing cert (`CertUtil`) vs known dropper keys → DANGER.
- **`MaliciousPackages`** — package name vs blacklist (catches repacks with a new hash) → DANGER.
- **`TrustedSignatures`** — whitelist by (package + cert sha256) → SAFE. Currently only the
  self-fingerprint is populated; other ENTRIES are commented-out placeholders.

**Scoring analyzers (accumulate `totalScore`):**
- **`ObfuscatedSignatures`** — scans DEX (up to 8 MB) + other entries (256 files × 1 MB) for
  IoCs hidden as SHA-256 token-hashes (first 16 hex) + XOR+Base64 strings (key `0x5A`), so
  `strings` reveals nothing. Holds C2 domains (elrxzx.com, ydbllnjd.com, ilovekkksfm.com),
  AES keys, anti-Frida/Magisk/VPN markers, overlay-infra markers.
- **`ManifestAnalyzer`** — `getPackageArchiveInfo` flags + raw AXML scan for
  SMS_RECEIVED / BOOT_COMPLETED / DEVICE_ADMIN_ENABLED. Red flag = 30 pts, orange = 10. Flags
  accessibility services, device admin, debuggable, exported-without-permission (≥5),
  cleartext traffic, allowBackup.
- **`PermissionCombos`** — scores *combinations*, not counts. 14 combos incl. OTP-grabber
  SMS+Accessibility (90), full banker overlay+a11y+net (100), SMS-stealer (70), overlay
  phisher (70), persistent botnet (70), ransomware / dropper / credential-stealer /
  stealth-spy / call-hijacker.
- **`DexPatternAnalyzer`** — byte-substring scan of `classes*.dex` (4 MB sample) for ~40
  patterns: DexClassLoader / InMemoryDexClassLoader, Runtime.exec, getDeviceId/IMSI,
  sendTextMessage, TYPE_APPLICATION_OVERLAY, AccessibilityEvent.getText, MediaProjection,
  frida-server, magisk, `api.telegram.org/bot`, packers (Bangcle/SecNeo/Qihoo/Tencent/Ijiami
  = 40 each). >5 DEX files = +15.
- **`DropperDetector`** — magic-byte check for hidden APK(PK)/DEX/ELF in
  assets/raw/META-INF; `.so` outside `lib/{abi}/`; high-entropy (≥7.5 Shannon) encrypted
  payloads in assets ≥100 KB. Hidden APK/DEX = 40 pts each, encrypted payload = 60.
- **`IconImpersonationDetector`** — 8×8 grayscale aHash; Hamming distance <10 vs installed
  legit apps (13 PROTECTED_PACKAGES: Telegram/Click/Payme/Uzcard/Apelsin/Mobiuz/Beeline …) →
  instant DANGER (phishing icon under a wrong package).
- **`NativeLibAnalyzer`** — `.so` suspicious imports (dlopen/execve/ptrace/mprotect) +
  entropy >7.4 → DANGER.
- **`FilenameHeuristic`** — 8 layers: name templates (`RASMLAR (NN)`, `VIDEO.DD.MM.YYYY`,
  `VID_`), double extension (`.mp4.apk` = 50), lure keywords (MTS/kupon/yangilanish/crack/
  porno/soliq), brand impersonation (hard DANGER), typosquat (Levenshtein ≤2), homoglyph
  Cyrillic+Latin (hard DANGER), label-vs-filename mismatch, Unicode invisible chars (Hangul
  filler), bigram rarity for random package segments.

**Verdict model (bottom of `ApkScanner.kt`):** sensitivity from `Config` (low/medium/high)
sets thresholds — DANGER ≥ {90, 65, 45}, SUSPICIOUS ≥ {45, 30, 20}; dangerous-permission
count threshold {4, 3, 2}. **Hard DANGER overrides score:** icon match, hidden APK/DEX,
encrypted payload + extra signal, device-admin + comboScore ≥30, any obfuscated signature,
suspicious native lib, comboScore ≥90, **≥2 evasion techniques** (anti-Frida/Magisk/debug/VPN).
1 evasion technique → SUSPICIOUS. Random package name + 2 perms → DANGER. Critical scan error
→ **SUSPICIOUS** (never SAFE). On result: updates Statistics, ScanHistory, ScanCache, widget,
ProtectionService; fires telemetry + community report.

---

## 3. Background protection

- **`GuardWorker`** — 15-min periodic (+ one-shot on screen-unlock & Telegram "scan now").
  Scans up to 10 found APKs. DANGER + auto-delete → `Quarantine` (works screen-locked); else
  `AutoScanActivity` popup or notification fallback.
- **`PeriodicCheckWorker`** — separate 15-min worker from `MainActivity` (`periodic_apk_check`);
  `FullPhoneScan` recursive, tracks `checked_paths` to scan only new files.
- **`HeartbeatWorker`** — every 6h (15-min initial delay). Battery/storage/uptime; diff-state
  alerts for unknown-sources toggle, battery optimization, revoked critical permissions.
- **`DailyReportWorker`** — ~21:00 daily; 24h summary from ScanHistory.
- **`InstalledAppsRescanWorker`** — daily (2h initial delay); rescans up to 50 user apps with
  a fresh blacklist; alerts only when a verdict newly flips to DANGER (idempotent via
  `kiberqalqon_rescan`).
- **`AccessibilityWatcher`** — every 4h; reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`,
  alerts on a new non-system service (banking-trojan vector). Whitelists Google/Samsung a11y.
- **`ProtectionService`** — foreground service (type `specialUse` on API 34+), `START_STICKY`,
  IMPORTANCE_LOW ongoing notification "KIBER QALQON faol"; status reflects worst 24h verdict.
- **`PackageInstallReceiver`** — scans newly installed/replaced packages; DANGER → popup +
  uninstall notification; warns if KiberQalqon itself is removed.
- **`BootReceiver`** — manifest-registered (priority 999); on BOOT_COMPLETED reschedules all
  workers + restarts ProtectionService + telemetry.
- **`ScreenUnlockReceiver`** / **`SystemStateReceiver`** — one-shot scan on unlock; SIM-change
  & airplane-mode telemetry (anti-theft).
- **File observers:** `ImprovedApkFileObserver` (active — debounce 700 ms + waitUntilStable),
  `MultiPathFileObserver` (manages observers across Downloads/Telegram/WhatsApp/Bluetooth +
  Android/media paths, dedups by canonical path), `ApkFileObserver` (legacy/unused).
- **`ScanTileService`** — Quick-Settings tile → MainActivity. **`KqWidgetProvider`** — home
  widget (refreshed via `refreshAll()` after scans), colored status dot + last-scan time.
- **`PhishingNotificationService`** — NotificationListener that hides phishing notifications.

---

## 4. Telegram integration

- **`TelegramBot.kt`** — thin Bot API wrapper (OkHttp). Reads token/chat_id from
  `kiberqalqon_telemetry` on every call. sendMessage / editMessageText (Markdown + plaintext
  fallback), answerCallbackQuery, sendDocument (50 MB cap), getUpdates (long-poll 25s,
  persists offset). `TelegramUpdate.parse(obj, ownChat)` enforces the **chat_id whitelist** —
  only the owner's chat is processed. Models: `InlineButton` / `InlineKeyboard`.
- **`CommandRouter.kt`** — `/start`,`/panel`,`/menu` open the inline-button panel; everything
  else is button taps. Fixed callbacks: Statistika, Skan boshla, Tarix, Xavfli ro'yxat,
  Oxirgi o'rnatish, Diagnostika, Logcat, Versiya, toggle APK upload, clear history. Action
  callbacks `del:` / `rescan:` / `info:` resolve through `ThreatActions`.
- **`TelegramCommandPoller.kt`** — self-rescheduling OneTimeWork chain (`tg_command_poller`,
  REPLACE, 1s buffer); stops if listen disabled. PeriodicWork's 15-min floor is too slow for
  commands, hence the chained one-shots.
- **`TelemetryReporter.kt`** — fires ~40 event categories (`Cat.*`) to the user's **personal**
  bot. Throttle 1.1s. Uzbek labels via `labelOf()`, verdicts via `verdictUz()`. POSTs JSON
  (token in the HTTPS path, not the query string — changed from GET because long emoji text
  overflowed URL limits and leaked the token).
- **`ThreatActions.kt`** — registry mapping a 12-hex token (sha256(path) prefix, fits
  Telegram's 64-byte callback_data limit) → APK entry, 7-day TTL, in `kiberqalqon_threat_actions`.
- **`CommunityReportClient.kt`** — **separate** opt-in path to the dev's bot
  (`BuildConfig.DEV_TG_BOT_TOKEN` / `DEV_TG_CHAT_ID` from gitignored `local.properties`).
  Strict gates: user consent + community-share consent + non-empty config + non-SAFE verdict.
  Sends hash/package/verdict/reason/device + APK file (no PII, no installed-app list).
  `reportCrash` bypasses the consent gate in DEBUG and sends synchronously (process is dying).
- **`TelemetrySettingsActivity.kt`** — code-built UI (no XML): token/chat_id fields, toggles,
  test message, **auto-detect chat_id** (calls getUpdates, finds the group), setup guide.
  **`NetworkInfo.kt`** — local/external IP + connection type for the panel (personal bot only;
  "like TeamViewer for your own phone").

Two distinct bots: **personal** (TelemetryReporter + full admin panel) vs **dev community**
(CommunityReportClient, hash + file only). Tokens are never hardcoded in the shipped APK.

---

## 5. Self-defense & quarantine

- **`SecurityGuard.kt`** — release-only (skipped in DEBUG): tamper (package mismatch +
  `Class.forName` core class), signature SHA-256 (`EXPECTED_RELEASE_SIGNATURE_SHA256`
  constant), root (su binaries, root apps, Magisk files + `/proc/mounts` tmpfs over /system +
  `/data/adb` + boot props like `ro.boot.flash.locked`), debugger, Frida (ports 27042–27050,
  `/proc/self/maps` markers, thread names gum-js/frida/pool-frida, `/proc/self/net/tcp`),
  Xposed (classpath + installed apps), emulator (Build fingerprint/model/product + QEMU files).
- **`SelfGuard.kt`** — prevents self-deletion: checks package (`com.kiberqalqon[.debug]`),
  `/data/app/` path, sourceDir match, cached signature match, filename fallback. Used in
  scan / findApk / FileDeleter / Quarantine.
- **`Quarantine.kt`** — moves an APK to `filesDir/quarantine/<token>.quar` (+ `.json` meta),
  7-day TTL, restore/purge. `.quar` can't be installed; the dir is MODE_PRIVATE.
  Reports `QUARANTINE_RESTORE` to Telegram on user restore.
- **`FileDeleter.kt`** — version-aware deletion: full-storage direct delete → MediaStore
  consent dialog (API 29 RecoverableSecurityException, API 30 createDeleteRequest) →
  NeedsManageStorage → SandboxedByOwner (`/Android/data/<pkg>/` undeletable). `sandboxOwner()`
  is unit-tested. SelfGuard-protected.

---

## 6. Persistence & config

`Config.kt` (object `Config` + nested `Statistics`) — `SharedPreferences` `kiberqalqon_prefs`.
Keys include: `background_on`(def true), `upload_on`, `phishing_on`, `lang`(uz),
`auto_delete_mode`(delete), `sensitivity_level`(medium), `sound_enabled`, `vibration_enabled`,
`auto_update_enabled`, `first_run`, `initial_scan_done`, `dark_theme`(system),
`accent_variant`(pomegranate), `user_consent_v1` (int vs `CURRENT_CONSENT_VERSION=3`),
`community_share_v1`, `defaults_baked_v1`.

Other prefs files: `kiberqalqon_stats` (total_scanned / **total_blocked** / total_safe +
day_0..6 — `total_blocked` is the **canonical** key), `kiberqalqon_history` (ScanHistory JSON,
200-entry LRU), `kiberqalqon_scan_cache` (500-entry), `kiberqalqon_telemetry`,
`kiberqalqon_rescan`, `kiberqalqon_a11y`, `kiberqalqon_state_diff`, `kiberqalqon_lifecycle`,
`kiberqalqon_sysstate`, `kiberqalqon_checked`, `kiberqalqon_quarantine`,
`kiberqalqon_threat_actions`.

---

## 7. UI surface

Activities: **SplashActivity** (launcher + permissions), **ConsentActivity** (mandatory
ToS+Privacy, 3 checkboxes incl. mandatory community-share; full legal text in-source for
resilience), **OnboardingActivity** (3 ViewPager2 slides), **InitialScanActivity** (one-time
full scan with RadarScanView + per-threat delete rows + bulk delete + VoiceVerdict),
**DashboardNewActivity** (home), **MainActivity** (scanner tab), **AutoScanActivity**
(full-screen overlay popup, `showWhenLocked`, dedup window, auto-delete 2.5s countdown,
exported=false), **ScanResultActivity** (verdict banner, evasion/IOC rows, delete),
**ScanHistoryActivity** (7-day chart, family bars, sample rows, C2 rows), **SettingsActivity**
(reactive toggles, theme/accent/lang, consent section, revoke), **DiagnosticsActivity**
(code-built; copy/share diagnostics, test-virus button), **TelemetrySettingsActivity**
(code-built), **ShareReceiverActivity** (exported translucent; copies a shared APK to cacheDir
→ AutoScanActivity).

Custom views: **`RadarScanView`** (sweeping radar + threat/safe pings), **`SpeedometerView`**
(270° protection gauge), **`StatsGraphView`** (7-day bar chart — legacy, likely unused by new
screens). **`KqBottomNav`** — shared bottom nav (Home/Scan/Stats/Settings).

---

## 8. Full file inventory

**Entry/lifecycle:** `App.kt`, `SplashActivity.kt`, `MainActivity.kt`,
`DashboardNewActivity.kt`, `CrashHandler.kt`.
**Scanner core:** `ApkScanner.kt`, `ScanCache.kt`, `ScanHistory.kt`, `CertUtil.kt`,
`ApkItem.kt`, `FullPhoneScan.kt`.
**Analyzers:** `MaliciousHashes.kt`, `MaliciousCerts.kt`, `MaliciousPackages.kt`,
`TrustedSignatures.kt`, `ObfuscatedSignatures.kt`, `ManifestAnalyzer.kt`,
`PermissionCombos.kt`, `DexPatternAnalyzer.kt`, `DropperDetector.kt`,
`IconImpersonationDetector.kt`, `NativeLibAnalyzer.kt`, `ZipEncryptionDetector.kt`,
`FilenameHeuristic.kt`.
**Background:** `GuardWorker.kt`, `PeriodicCheckWorker.kt`, `HeartbeatWorker.kt`,
`DailyReportWorker.kt`, `InstalledAppsRescanWorker.kt`, `AccessibilityWatcher.kt`,
`ProtectionService.kt`, `PackageInstallReceiver.kt`, `BootReceiver.kt`,
`ScreenUnlockReceiver.kt`, `SystemStateReceiver.kt`, `ImprovedApkFileObserver.kt`,
`MultiPathFileObserver.kt`, `ApkFileObserver.kt` (legacy), `ScanTileService.kt`,
`KqWidgetProvider.kt`, `PhishingNotificationService.kt`.
**Telegram:** `TelegramBot.kt`, `CommandRouter.kt`, `TelegramCommandPoller.kt`,
`TelemetryReporter.kt`, `ThreatActions.kt`, `CommunityReportClient.kt`,
`TelemetrySettingsActivity.kt`, `NetworkInfo.kt`.
**Self-defense/quarantine:** `SecurityGuard.kt`, `SelfGuard.kt`, `Quarantine.kt`, `FileDeleter.kt`.
**Config:** `Config.kt` (incl. `Statistics`).
**UI activities:** `OnboardingActivity.kt`, `ConsentActivity.kt`, `InitialScanActivity.kt`,
`ScanResultActivity.kt`, `ScanHistoryActivity.kt`, `SettingsActivity.kt`,
`DiagnosticsActivity.kt`, `AutoScanActivity.kt`, `ShareReceiverActivity.kt`.
**Views/UI helpers:** `RadarScanView.kt`, `SpeedometerView.kt`, `StatsGraphView.kt`,
`KqBottomNav.kt`, `ApkAdapter.kt`, `AnimationHelper.kt`.
**Util:** `ThemeHelper.kt`, `LocaleHelper.kt`, `VersionCompat.kt`, `NotificationHelper.kt`
(4 channels for sound/vibrate combos), `VoiceVerdict.kt` (TTS uz/ru), `OemAutostartGuide.kt`
(Xiaomi/Huawei/etc. autostart+overlay intents), `ServerUpload.kt` (HTTPS-only APK upload to
the optional self-hosted server), `TestVirusGenerator.kt` (6 synthetic non-malicious test APKs).
**Tests (`src/test/`):** `ApkScannerTest.kt` (matchesSignature word-boundary, inferSource),
`FileDeleterTest.kt` (sandboxOwner), `MaliciousCertsTest.kt`. Plain JUnit4, no Robolectric —
only pure functions are tested.

---

## 9. Build config (`app/build.gradle.kts`)

- minSdk 24, target/compile 34, Java 17. ViewBinding + BuildConfig on.
- ABIs: armeabi-v7a, arm64-v8a, x86, x86_64. `extractNativeLibs=false`.
- **Output APK name:** `kiberqalqon-${System.currentTimeMillis()}` (unique per build — Windows
  AV locks fresh APKs ~15–30 min).
- **Signing:** release reads gitignored `keystore.properties` (storeFile/storePassword/
  keyAlias=kiberqalqon/keyPassword); v2+v3+v4. Applied only if the keystore exists (else
  unsigned, doesn't fail).
- **BuildConfig fields:** `DEFAULT_SERVER_URL` (empty), `DEV_TG_BOT_TOKEN` / `DEV_TG_CHAT_ID`
  (from gitignored `local.properties`; empty → community sharing is a no-op).
- **release:** R8 minify + shrinkResources, not debuggable. **debug:** `applicationIdSuffix=.debug`,
  `versionNameSuffix=-DEBUG`, no minify.
- Deps: AndroidX (core/appcompat/material/constraintlayout/cardview/recyclerview/
  coordinatorlayout/viewpager2), WorkManager 2.9.0, **OkHttp 4.12.0** (only third-party),
  JUnit 4.13.2.
- Build: `.\gradlew.bat assembleDebug` from `ApkGuard/`.

---

## 10. Conventions, gotchas & known issues

- **Uzbek-only user/Telegram strings**; raw enum names are never shown. `strings.xml` has
  values / values-uz / values-ru.
- **Never false-SAFE:** unreadable files and scan errors → SUSPICIOUS, not SAFE.
- **Debug vs release:** SecurityGuard skipped in DEBUG; `reportCrash` bypasses consent + sends
  synchronously in DEBUG; debug installs side-by-side via `.debug` suffix.
- **Code-built UIs** (DiagnosticsActivity, TelemetrySettingsActivity) deliberately avoid XML
  so they render even if resources fail to load.
- **ZIP-encryption asymmetry** is the headline defense — checks both local AND central
  directory headers (some samples only flag the central directory).
- **OEM aggression** is heavily mitigated: foreground service + battery-optimization prompts +
  OEM autostart/overlay deep-links + screen-unlock one-shot scans + kill detection (Xiaomi/
  Huawei kill WorkManager).
- `ServerUpload` / `DEFAULT_SERVER_URL` is a vestigial self-hosted path; the live backend is
  Telegram. The `cloud/` effort (Vercel+Supabase+webhook) is the intended replacement but is
  outside the app module and not yet wired in — see `backends.md`.

**Known issues worth fixing if you're in the area (not urgent):**
- There are two parallel 15-min scan systems (`GuardWorker` `kiberqalqon_scan` from `App` +
  `PeriodicCheckWorker` `periodic_apk_check` from `MainActivity`) plus BootReceiver's
  `guard_work_boot` — some redundancy.
- `DiagnosticsActivity` reads stale stats keys (`scanned_count`/`blocked_count`) that no
  longer exist (canonical is `total_blocked` in `kiberqalqon_stats`), so it can show 0.
- `StatsGraphView` and `ApkFileObserver` look dead (superseded by newer screens/observers).
