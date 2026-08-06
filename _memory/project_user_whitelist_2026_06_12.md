---
name: project_user_whitelist_2026_06_12
description: "UserWhitelist (ishonchli ro'yxat) — user exclusions, crypto-bound, SUSPICIOUS-only; built green + tests pass 2026-06-12, not committed"
metadata: 
  node_type: memory
  type: project
  originSessionId: 064ab739-53a9-420e-8c19-623e6c868cbe
---

2026-06-12 (same session as [[project_link_interceptor_2026_06_12]]): added **user whitelist**
("Ishonchli ro'yxat") — user-managed exclusions for false positives.

**Technique (security design, explain if user asks):**
- Entries crypto-bound, never name-only: file = APK SHA-256; app = (package + signing-cert
  SHA-256) pair (same rationale as TrustedSignatures — package alone is a backdoor).
- **SUSPICIOUS-only**: hook in ApkScanner.scan() right before final ScanResult build downgrades
  SUSPICIOUS→SAFE when whitelisted. DANGER is NEVER overridden; hard IOCs (MaliciousHashes/
  Certs/Packages, ZIP-encryption) early-return before the hook. Golden rule preserved.
- Whitelist change → ScanCache.clear() (else old cached SUSPICIOUS persists until mtime change).
- Storage: prefs `kiberqalqon_user_whitelist`, JSON, max 200 entries.

**Files:** new `UserWhitelist.kt`; hook in `ApkScanner.kt` (~line 1255, finalVerdict/finalReason);
`AutoScanActivity.kt` — new `tvTrustHint` link (layout activity_auto_scan.xml) shown ONLY in
showSuspiciousResult → confirm dialog → background thread computes sha+cert+pkg → addFile+addApp;
reset to GONE in presentResult. `SettingsActivity.showTrustListDialog()` — chevron row
`rowTrustList` (ic4_check_circle) lists entries (📦 app / 📄 file), tap → confirm remove.
Strings in strings_kq4_set_extra.xml (kq4_trust_*, kq4_trustlist_*).

**Installed-virus detection (user asked "можем ли обнаруживать установленные?"):** already
existed — InstalledAppsRescanWorker (daily, 50 pkgs/run, alerts on verdict flip to DANGER),
HiddenThreatsActivity, PackageInstallReceiver, BankAppAudit, PermissionXray. Whitelist hook
inside scan() covers all these callers automatically.

**Status:** assembleDebug + testDebugUnitTest both BUILD SUCCESSFUL 2026-06-12. NOT committed,
NOT pushed, NOT device-tested (same pending state as the link-interceptor work).
