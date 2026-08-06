---
name: project-feature-wave-2026-06-05
description: "Feature wave added 2026-06-05 on branch feat/anti-re-hardening: scanner merge, cloud-updatable blacklist, TrustedSignatures pin, ML scaffold, opt-in VPN, FCM handoff — what's live vs needs activation"
metadata: 
  node_type: memory
  type: project
  originSessionId: e2847c67-086f-4041-aefd-b624cf4c3154
---

On 2026-06-05 (branch `feat/anti-re-hardening`) a 9-item feature wave was
implemented. **Verified only by `compileDebugKotlin` + `compileDebugUnitTestKotlin`
(BUILD SUCCESSFUL); full `assembleDebug` / device run NOT done.** Committed & pushed
2026-06-10 (commit 3694c69, together with audit fixes + perf/brand wave).

**Done & compiling:**
1. **Scanner consolidation.** `GuardWorker` is now the ONLY periodic worker — 3 modes via
   `inputData`: explicit paths (real-time) / `full_sweep=true` (periodic full-phone + `checked_paths`
   dedup, replaces old PeriodicCheckWorker) / quick top-10 (one-shots). `PeriodicCheckWorker.kt`
   DELETED. `App.scheduleGuardWork` + `BootReceiver` call `GuardWorker.schedulePeriodic()`
   (UPDATE policy, work name `kiberqalqon_scan`). `MainActivity.startPeriodicCheck()` removed.
2. **Dead code removed:** `ApkFileObserver.kt` (legacy), `StatsGraphView.kt` + its node in
   `activity_dashboard_new.xml`. (DiagnosticsActivity was ALREADY reading canonical stats keys —
   no "shows 0" bug; nothing to fix.)
3. **Cloud-updatable blacklist (#8).** New `CloudBlacklist.kt` fetches an HMAC-signed feed from
   `GET /api/threats?feed=1` (header `x-device-secret`), verifies with `Secrets.configSigningSecret()`
   (SAME key as RemoteConfig), caches, merges into `ThreatDb`. `ThreatDb` maps are now
   `ConcurrentHashMap`, gained `packages`/`packageFamily()`/`mergeCloud()`. `MaliciousPackages`
   falls back to `ThreatDb.packageFamily()`. Cloud `api/threats.ts` now branches: device →
   signed minimal feed (hashes+packages, severity `high`/`critical`, no sample URLs), admin →
   full view. **Activation: deploy cloud (`vercel --prod`) + `threats` table needs high/critical
   rows. Else no-op — assets blacklist still works. Certs NOT in feed (no cert column) — stay in
   `assets/malicious_certs.txt`.**
4. **TrustedSignatures (#7).** `captureInstalledTrusted()` pins certs of `AppReputation.TRUSTED_EXACT`
   apps that are installed **from Google Play only** (`com.android.vending`) — no fabricated
   fingerprints. `AppReputation.exactTrustedPackages()` added. Called in `App.onCreate` background.
   Static `ENTRIES` still empty (fill via `scripts/extract_cert_fingerprint.py` if wanted).
5. **ML zero-day (#12).** `MlRiskModel.kt` — standalone logistic scorer over detector features,
   NO deps, **advisory-only (NOT wired into verdict)**. Unit test `MlRiskModelTest.kt`. TFLite
   swap-in documented in-file.
6. **VPN C2 filter (#9).** `VpnFilterService.kt` — **EXPERIMENTAL, opt-in, default OFF, NOT
   auto-started, UNTESTED on device.** DNS-sinkhole: routes ONLY DNS (so other traffic untouched),
   NXDOMAINs C2 domains (elrxzx.com, ydbllnjd.com, ilovekkksfm.com, dashapp-v2.org), forwards rest
   to 8.8.8.8. Manifest `<service>` with BIND_VPN_SERVICE added. **No UI toggle yet** — wire
   `VpnFilterService.prepareIntent()/start()` from Settings to use it.
7. **FCM (#10).** NOT implemented (needs user's Firebase project + `google-services.json`).
   Ready-to-apply patch in **`ApkGuard/FCM_SETUP.md`**.
8. **Realtime map (#11).** Already realtime via polling (`usePoll('/api/geo', 12000)`, feed 5s) —
   no change. True WebSocket would be Supabase Realtime (anon key + RLS) — not done.

9. **Real-time a11y kill-switch.** `AccessibilityWatcher.checkNow()` (one-shot OneTimeWork) added,
   called from `PackageInstallReceiver` (added/replaced) + `ScreenUnlockReceiver` → accessibility
   abuse caught in real time, not every 4h. Now ALSO fires a local heads-up notification
   `NotificationHelper.showAccessibilityThreatNotification()` (MAX priority, full-screen, actions:
   open Accessibility settings / uninstall) — previously only a Telegram alert. Compiles green.
   **Crowdsource threat loop CONFIRMED already working** (no change): `api/scan/upload.ts` upserts
   DANGER as severity `high` → `greatest_severity()` only upgrades → `GET /api/threats?feed=1`
   serves high/critical → `CloudBlacklist` blocks on other devices. Today's feed closed the loop.
10. **OTP-theft watcher.** New `NotificationAccessWatcher.kt` mirrors AccessibilityWatcher but for
   `enabled_notification_listeners`: a new non-system NotificationListener app → Telegram alert +
   local heads-up (`NotificationHelper.showNotificationAccessThreatNotification`). Catches OTP theft
   via notification access (bankers read bank/Telegram push codes without RECEIVE_SMS). Scheduled in
   App.onCreate + BootReceiver (4h) + real-time `checkNow()` from PackageInstallReceiver +
   ScreenUnlockReceiver (same wiring as the a11y kill-switch). Compiles green.
11. **Hidden / undeletable installed-threat scanner.** `HiddenThreatScanner.kt` enumerates
   non-system installed apps and flags: hidden launcher icon (+dangerous perms), active device-admin
   (blocks uninstall), enabled Accessibility, notification access, or blacklisted package. New
   `HiddenThreatsActivity.kt` (code-built UI, entry button in DiagnosticsActivity, registered in
   manifest) lists them with a step-by-step removal guide (disarm device-admin → a11y → uninstall;
   Safe Mode; copyable ADB `pm uninstall --user 0`). Detect-and-guide only (no root → can't
   auto-remove other apps). Compiles green.
_(Karakalpak language was added then REVERTED at the user's request — not a native translator;
shipping guessed security strings was unwise. App stays UZ/RU.)_

**Build-invocation gotcha (this machine):** to run gradle from the Bash tool, use a `.bat`
(`set JAVA_HOME=C:\Java\jdk-17.0.19+10` → `cd /d <ApkGuard>` → `call .\gradlew.bat <tasks> > out.txt 2>&1`)
invoked via **`cmd.exe //c "<path>.bat"`** (double-slash). PowerShell `*>` redirect BUFFERS (log
looks frozen) and bare `cmd /c` is MSYS-mangled (`/c` → path) — both caused false "build failed"
readings. See [[project-build-toolchain]], [[project-anti-re-hardening]].
