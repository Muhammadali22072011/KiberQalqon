---
name: project_feature_wave_2026_07_11
description: "2026-07-11 \"add all\" wave — 5 new Android protections + panel offline flag, committed 306b27e (not pushed/device-tested)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 91c46e5b-20f2-43f4-a640-d947d83cfdb5
---

User asked "ДОБАВЬ ТАМ ВВСЕ ТАМ" (add all) to the 8 small-feature ideas I proposed. Result on
feat/anti-re-hardening, commit **306b27e** (NOT pushed, NOT device-tested; local
compileDebugKotlin OK + 3 new JUnit classes pass + cloud tsc clean).

**Net-new (5):**
- `RemoteAccessDetector.kt` — flags installed AnyDesk/TeamViewer/RustDesk/AirDroid/... (the UZ
  "bank xodimi asks victim to install AnyDesk" scam vector). Warn-only, NEVER DANGER (legit apps).
  Hooked into `InstalledAppsRescanWorker` (dedup via `remote_access_seen` StringSet, notify +
  REMOTE_ACCESS telemetry only on newly-appeared). `matchPackages()` pure + unit-tested.
- `WifiGuard.kt` — open/passwordless Wi-Fi MITM warning. `ProtectionService` NetworkCallback
  (`startWifiWatch`), per-SSID dedup in `uzguard_wifi`. API31+ WifiInfo.currentSecurityType;
  legacy fallback = WifiManager scanResults match. `isOpenCapabilities()` pure + unit-tested.
- `SecurityScore.kt` (0–100) + `SecurityScoreActivity` (reuses the previously-UNWIRED `KqScoreView`).
  Posture: screen-lock(25)/background(20)/no-danger(20)/no-remote(15)/vpn(10)/no-adb(10). Fixable
  issue cards, recomputes onResume. `evaluate()` pure + unit-tested. dangerCount read from
  `uzguard_rescan` verdict_* prefs.
- `SideloadAuditActivity` — code-built (XML-less) list of non-Play apps + installer source;
  remote-access apps pinned on top. Uses `ApkScanner.isFromTrustedStore`.
- `PhishingNotificationService` enhanced — extracts URLs from notif text, runs `LinkScanner`,
  warns on DANGER (`showPhishingLinkNotification` → LinkCheckActivity). Closes the known Telegram
  in-app-browser LinkGuard bypass. Dedup in `uzguard_notif_links`. Still also cancels classic phish.

- `AlarmSiren.kt` (commit **12fc84b**, follow-up "разбуди громким сигналом") — loud wake-up siren
  on DANGER at night. Plays default alarm on STREAM_ALARM at MAX vol (loud even in silent/vibrate
  ringer) + strong looping vibration, auto-stop 60s. GATED: only when `isLoudAlarmEnabled` (default
  on) AND sleeping (`!isInteractive || keyguardLocked`) — never blasts while phone in active use.
  Temporarily maxes STREAM_ALARM, saves prev in `uzguard_siren` prefs, restores on stop;
  `App.onCreate` → `AlarmSiren.recover()` fixes process-death case. Fired from DANGER notif paths
  (showQuarantined/showFoundApk[DANGER]/showInstalledDanger); stopped in AutoScan/ScanResult onCreate.
  Config toggle + Settings row "Uyg'otuvchi signal".

- App-update notify (commit **d63e57c**, "уведомление что установленные приложения обновились") —
  `PackageInstallReceiver` SAFE branch now fires `NotificationHelper.showAppUpdatedNotification`
  (CH_NEWS, PRIORITY_LOW, tap→app info) "Ilova yangilandi — tekshirildi ✅". ONLY sideload updates
  (Play/trusted-store updates stay skipped — no scan, no notif — to avoid the known flood/heat).
  Config toggle `isAppUpdateNotifyEnabled` (default on) + Settings row.

**PERIODIC SELF-UPDATE FIX (commit f0d6142) + 8.5 REBUILD:** root cause of "обновление не пришло" —
`RemoteConfig.refresh`+`SelfUpdate.checkAndNotify` ran ONLY in App.onCreate; foreground service keeps
process alive for days → no cold start → update notif NEVER arrived without force-stop. Fix:
`RemoteConfig.refreshIfStale()` (6h attempt-throttle, `rc_last_refresh_attempt` in uzguard_remote_config)
+ call from GuardWorker non-realtime path (realtime scan path stays offline). 8.5 was REBUILT with this
fix and re-hosted (sha 08e8dbef…c3b7, CONFIG_VERSION=5); Desktop APK replaced too. On-phone recipe for
8.4 devices TODAY: force-stop UzGuard → reopen → notif in ~30s. Second deploy auto-promoted (rollback
pin cleared by first manual promote).

**8.5 SELF-UPDATE ROLLOUT LIVE (2026-07-11):** kq-update-85.apk hosted at
`https://kiberqalqon-cloud.vercel.app/kq-update-85.apk` (sha `14b23652…d1cf`, byte-verified);
prod envs UPDATE_VERSION_CODE=85 / UPDATE_APK_URL / UPDATE_APK_SHA256 + CONFIG_VERSION 3→4;
deployed + **manually promoted** (alias was pinned by the 07-09 rollback — see
[[reference_vercel_deploy]] gotcha). Live config verified: v=4, update block → 85. Old
kq-update-82.apk kept. Release-signed 8.4 phones will get "Yangi versiya chiqdi" notif →
in-app download → SHA+cert verify → installer. Debug installs can't take it (cert mismatch, known).
This deploy also took the panel offline-flag (Devices.tsx) live.

**RELEASE 8.5 (versionCode 85):** signed release APK built locally (assembleRelease, R8, 14min) —
`app/build/outputs/apk/release/kiberqalqon-1783760722295-release.apk` (6.07 MB), copied to
`C:\Users\User\OneDrive\Desktop\UzGuard-8.5-release.apk`. Cert SHA-256 =
`1cb3f378189d6ef38985b3ae234d859e750029ab353246fa496349a8ff14d983` = SAME as prior release key →
installs as UPDATE over 8.4 (no uninstall). Release self-kills on emulator (SecurityGuard
anti-emulator) — DEVICE-ONLY test. Local release build DID work this time (OneDrive up + taskkill
java recipe). versionCode 84→85 / versionName 8.4→8.5 in commit d63e57c.

**Pre-existing (2, no change):** QS tile (`ScanTileService`), home widget (`KqWidgetProvider`).

**Cloud (1):** `Devices.tsx` — client-side "aloqasiz (3+ kun)" offline flag + filter option, from
existing `last_seen` (no server/DB change; matches [[feedback_client_side_display_fix]]).

Config toggles added: `isWifiGuardEnabled`/`isRemoteAccessAlertEnabled` (both default true) +
Settings rows (2 chevron screens + 2 toggles) in `activity_settings_new.xml` + strings in
`values/strings.xml` and `strings_kq4_set_extra.xml` (Uzbek base only; RU falls back). New files use
`package com.uzguard` (rebrand). See [[project_rebrand_uzguard]], [[reference_build_env_gotchas]].

OWNER TODO: push → CI, then device-test (esp. WifiGuard open-network detection on real OEM Wi-Fi,
and SecurityScore ADB/keyguard reads).
