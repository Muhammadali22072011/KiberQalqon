---
name: project_installed_virus_wave_2026_07_18
description: "2026-07-18 wave — Play-Market apps forced SAFE, on-demand installed-apps virus scan, live threat panel, app-icon in virus notifications"
metadata: 
  node_type: memory
  type: project
  originSessionId: 989e8632-573f-4514-aec6-3351c70225d0
---

2026-07-18 wave on `feat/anti-re-hardening` (4 commits, CI GREEN, NOT built/device-tested). User (RU voice) wanted: Play-Market apps always shown "xavfsiz" + the scan to actively find ALREADY-INSTALLED viruses (not just APK files) + show the found app's icon.

**What shipped (all reviewed adversarially ×2, no false-SAFE hole):**
- **2855a41** — Dashboard installed-apps list: apps from a trusted store (`ApkScanner.isFromTrustedStore` → Play/Galaxy/AppGallery/Mi/RuStore/Amazon) are forced `verdict="SAFE"` and NOT scanned. Dropped the `verdict==null` guard so stale DANGER from the old FP flood clears too. `knownBad` (MaliciousPackages) still overrides → DANGER.
- **ae94e12** — Dashboard: `foundThreatOnOpen` flag → `populateThreatRows()` re-run after the on-open inline scan, so an installed virus shows in the "So'nggi tahdidlar" panel immediately (was only on next reopen).
- **8afc6be** — NEW `ApkScanner.scanInstalledForThreats(context, limit=40)` (+ `InstalledThreat` data class): scans installed apps' own base.apk, skips system (keeps updated-system), skips self, skips trusted-store. Wired into `MainActivity.startAutoProtection()` (the Skaner scan) — surfaces each DANGER via `NotificationHelper.showInstalledDangerNotification` (has uninstall action) + Toast. Guarded by `installedScanRunning` (in-flight) + `installed_threat_notified` prefs set (siren only re-blasts for NEW threats).
- **b6a510d** — Virus notifications now set the found app's real icon as largeIcon: `installedAppIcon` (getApplicationIcon) for installed, `apkFileIcon` (getPackageArchiveInfo+loadIcon) for APK files. `drawableToBitmap` handles vector/adaptive via Canvas; null-guarded.

**CRITICAL DESIGN DECISION (do not undo):** The "Play → safe" was deliberately NOT put in the engine (`ApkScanner.scan`). An early-return SAFE for trusted-store was tried and REVERTED because it sits ABOVE the TIER-1 hard-IoC signals (`obfuscatedSignature`/hidden-dropper/encrypted-payload) → would false-SAFE a store-delivered supply-chain trojan. The engine's existing `trustedInstalledApp -> SAFE` in `decideVerdict` already sits BELOW TIER-1 (correct). A **sideload** installed app has `trustedInstalledApp==false` → full detection applies (not falsely SAFE). See [[project_false_positive_flood_2026_07_09]].

Build reality: CI is the compile gate (push with `KQ_SKIP_ANDROID=1`, JDK C:\Java\jdk-17.0.19+10, SDK D:/AndroidSdk). See [[project_build_toolchain]], [[reference_build_env_gotchas]].

**RELEASE 8.7/87 BUILT+SIGNED 2026-07-18** (commit c745828, version bump pushed). `assembleReleasefast` (no R8, --no-daemon --max-workers=2) SUCCEEDED locally in 4m33s — RAM held (releasefast is light; the "local build impossible" note applies to full R8 `release`). APK on Desktop as `kiberqalqon-v87.apk`, sha256 `e95bad74f9e072bc7ce12dfb801b4fc3c6a5bbf59b28daa9116f3f4af8c049a6`, signer cert = live release cert `1cb3f378...` (self-update will accept). **ROLLOUT BLOCKED ON VERCEL AUTH (2026-07-18).** APK staged for Vercel-hosting (like 86): `cloud/public/kq-update-87.apk` (sha e95bad74…, .vercelignore deploys public/*.apk → served at https://kiberqalqon-cloud.vercel.app/kq-update-87.apk). Ready-to-run script written: `cloud/deploy_87.sh` (sets 4 prod env + `vercel --prod` + supports `VERCEL_TOKEN`). BUT this session has NO Vercel auth: `vercel whoami`=no credentials, no VERCEL_TOKEN, no auth.json on disk, Vercel MCP unauthed. `/api/config` verified still serving OLD v=6/versionCode=86 (checked via x-device-secret from local.properties `cloud.device.secret` + base `cloud.base.url`; decode signed envelope = base64url payload before the `.`). **Owner must: `vercel login` (or export VERCEL_TOKEN) once, then `bash cloud/deploy_87.sh`.** Then re-verify /api/config = v7/vc87/sha e95bad74. NOT device-tested. Note: 86 hosted APK on Vercel itself (not Supabase) despite publish_update.sh mentioning Supabase — Vercel-hosting is simpler/self-contained.
