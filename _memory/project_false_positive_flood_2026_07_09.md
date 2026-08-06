---
name: project_false_positive_flood_2026_07_09
description: Mass false-DANGER flood — every installed app (Chrome/Gmail/Play/Facebook/Excel...) flagged XAVFLI on A56; root causes + fix on feat/anti-re-hardening
metadata: 
  node_type: memory
  type: project
  originSessionId: 0d58c713-44ab-4d2b-84f6-3b7942a2c51c
---

2026-07-09: panel/Telegram flooded with "🔴 XAVFLI topildi" for ~40 of the owner's OWN
installed apps (Chrome, Gmail, Google Play, Facebook, Excel, Bixby, Samsung authfw, Android
Auto, a dialer, DJ apps...) + a bogus "🚨 Tahdid to'lqini" spike alert.

**Flood source:** `DashboardNewActivity` scans up to 40 *installed* apps via
`ApkScanner.scan(sourceDir)` on each open; every scan → `finalizeResult` → `CloudTelemetry.uploadScan`
→ cloud `api/scan/upload.ts` relays each danger to Telegram. The cloud just relays; the wrong
verdicts are on-device.

Committed 9709276 (feat/anti-re-hardening, 5 files). testDebugUnitTest + assembleRelease GREEN;
release APK (versionCode 84/8.4, cert 1cb3f378) on Desktop as UzGuard-8.4-release-fpfix.apk.
NOT pushed (CI not yet run), NOT device-tested (release self-kills on emulator).

**Three real FP bugs (all fixed):**
1. Generic Android-API strings were treated as malware IoCs. `ObfuscatedSignatures.matchDecrypted`
   substring-matches `WindowManager.LayoutParams`(overlay.windowmgr), `TYPE_APPLICATION_OVERLAY`,
   `com.topjohnwu.magisk`(anti.magisk — legit root-detection), `android.net.VpnService`, `jetski`
   (6-char coincidental hit on binary DEX read as Latin-1). Any one → `obfuscatedSignature=true`
   → DANGER, overriding even a Google-cert-VERIFIED app. FIX: added `ObfuscatedSignatures.SOFT_FAMILIES`
   + `isHardFamily()`; `obfuscatedSignature = signaturesFound.any { isHardFamily(it) }` — only real
   IoCs (C2 domain/key/bot hashes + `/commends` bot-endpoint) trigger DANGER; soft markers stay in details.
2. Medium signals (`strongCombo`, `evasionCount>=2`, `deviceAdminWithCombo`) fired ABOVE the
   `verifiedTrusted`/`trustedInstalledApp` rescue in `decideVerdict` — but super-apps have strong
   permission combos, banking/security apps do root+anti-debug, MDM declares device-admin. FIX:
   moved those 3 to TIER-2 (below the trust shield). TIER-1 hard signals (icon/hiddenApk/hiddenElf-
   droppedSo/encPayload/obfuscatedSig-IoC) still override trust (verdict tests lock these).
   Sideloaded malware is never VERIFIED/trusted → TIER-2 still fires → no detection loss.
3. Cloud got spammed with the owner's own installed inventory. FIX: `CloudTelemetry.uploadScan`
   now early-returns for installed-app self-scans (`isInstalledAppSelfScan`: scanned path == an
   installed pkg's sourceDir). Real threat paths (downloaded APK files, real-time, shared files)
   are not sourceDir → still upload. Installed malware still Telegram-alerted by InstalledAppsRescanWorker.
4. Bonus: `uzguard-setup.apk` flagged as `uzcard` typosquat (Levenshtein-2). FIX: `FilenameHeuristic.OWN_BRANDS`
   whitelist skips our own brand tokens.

Tests: added TIER-2-suppressed-for-trusted / still-DANGER-for-untrusted cases to `ApkScannerVerdictTest`.
Files: `ObfuscatedSignatures.kt`, `ApkScanner.kt` (obfuscatedSignature + decideVerdict + comments),
`CloudTelemetry.kt`, `FilenameHeuristic.kt`, `ApkScannerVerdictTest.kt`.

**OWNER follow-up — DONE 2026-07-13:** bogus rows PURGED in SQL Editor. Two-step (preview SELECT
→ owner-eyeballed ~80 rows → DELETE in one tx): `threats` + matching `scans` (verdict
suspicious/danger) removed by legit-package-prefix whitelist (google/samsung/microsoft/facebook/
whatsapp/banks/gov uz.*) + own-app self-flags (com.kiberqalqon sms_stealer ×2) + empty-file-hash
row (uzguard-setup.apk, e3b0c442…). 3 owner-'confirmed' FPs (Uptodown/Midasbuy/拼多多) deleted too
with consent. Real Ajina samples (toydanfotolar/RASMLAR/SexRolik/Sud qarori, random pkgs) untouched.
Review queue 62 → real-only.
See [[project_panel_wave2_2026_07_09]] (spike-alert) and [[project_audit_2026_06_10]] (AppReputation FP history).

**ROUND 2 — 2026-07-11 (commit 6ce6c9c, pushed feat/anti-re-hardening):** owner pasted NEW field
Telegram logs still showing the flood (incl. UzGuard flagging ITSELF com.kiberqalqon with
banker.overlay_inject). 5-agent Opus trace workflow (FN-verified) found the mass soft-marker flood
= OLD-BUILD artifact (round-1 SOFT_FAMILIES already closes it on HEAD; device just needs the 8.5
build), BUT two LIVE HEAD defects remained: (1) `banker.overlay_inject`/`banker.sms_exfil`/
`banker.admin_panel` are HARD token-hash families whose IoC is a GENERIC REST path (/api/inject,
/api/upload_sms, /admin/banks) → obfuscatedSignature → TIER-1 above the shield → flags legit REST
apps + UzGuard itself (own DEX carries those paths via LinkScanner.MALWARE_PATHS). FIX: added
`ObfuscatedSignatures.GENERIC_BANKER_PATHS`; obfuscatedSignature now needs a banker corroborator
(strongCombo/dropped-so/randomPkg/hidden APK|DEX|ELF) for those 3 — all malware-unique C2/key/bot
IoCs stay standalone-HARD (Ajina/RoundRift still fire alone). (2) `finalizeResult` fired Telegram
report/reportThreat/sendDocument + CommunityReportClient for EVERY scan → DashboardNewActivity
scan-all-installed = 50-msg alphabetical flood + APK uploads; CloudTelemetry.uploadScan already had
the installed-self guard but Telegram/community didn't. FIX: compute `installedSelfScan` once
(getPackageArchiveInfo + existing isInstalledSelfScan helper) and skip Telegram+community for
installed self-scans (sideload paths still alert; InstalledAppsRescanWorker still catches installed
malware on transition). Only 2 files: ApkScanner.kt + ObfuscatedSignatures.kt. NOT built locally
(RAM) → CI is the gate. Workflow crashed 2/3 fix agents mid-Edit (API connection closed) → reverted
their partial SelfGuard/LinkScanner edits, applied both primary fixes by hand. SelfGuard cross-build
own-cert-pin hardening = deferred optional (Fix A already stops the self-flag).
