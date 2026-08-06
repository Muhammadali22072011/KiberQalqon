---
name: project-audit-remediation-2026-06-07
description: 2026-06-07 full multi-agent audit of KiberQalqon + applied fixes (all P0/P1 + most P2); what still needs deploying.
metadata: 
  node_type: memory
  type: project
  originSessionId: fcb65cbe-e6d7-4ed7-bd63-65945fe89c82
---

2026-06-07: ran a 20-agent audit of the whole project (report saved at repo root
`AUDIT_KIBERQALQON_2026-06-07.md`), then fixed everything in one pass on branch
`feat/anti-re-hardening` (committed & pushed 2026-06-10, commit 3694c69). Verified: Android `testDebugUnitTest` BUILD
SUCCESSFUL (incl. 2 new test files), cloud `tsc` api/lib + SPA both clean, Python `py_compile`
+ a functional GP-bit test pass.

**Fixed (all P0+P1, most P2):**
- FORENSIC-01 (critical false-SAFE): `apk_analyzer.py` now clears ZIP GP-bit-0 fake-encryption and
  emits a hard threat reason on evasive/empty/broken containers; `telegram_bot/bot.py` never returns
  `safe` without a DEX present.
- SCAN-01: `CloudBlacklist.refresh` calls `Config.markDatabaseUpdated` only when `ThreatDb.mergeCloud`
  actually adds entries (mergeCloud now returns Boolean) → invalidates ScanCache without thrashing it.
- CLOUD-01 feed poisoning: new RPC `corroborated_threats` (migration **11**) requires ≥2 DISTINCT
  devices; `threats.ts` uses it + a never-block package allowlist; client `CloudBlacklist` excludes self.
- TG-01/02: `TelegramBot.passesOwnerGate` pins owner from first `/`-command in the whitelisted chat
  (group too); removed the permanent "no owner → allow everyone" hole.
- DET-02: `ApkScanner` promotes hidden ELF / payload-located `.so` to hard DANGER (over verifiedTrusted).
- VpnFilterService: deactivated in manifest (commented out), DNS forwarder made non-blocking
  (thread pool + per-query socket + txid check) with a fail-open kill-switch.
- CLOUD-03: `/api/admin/login` DB-backed rate-limit (migration **12** + `lib/ratelimit.ts`) + placeholder-secret refusal.
- CSP tightened (`vercel.json`), DET-03 ZIP data-descriptor scan-forward + new `ZipEncryptionDetectorTest`,
  extracted pure `decideVerdict` + `ApkScannerVerdictTest`, orphaned `periodic_apk_check` cancelled,
  CLOUD-02 legacy-write logging, CC-03 cached-feed re-verify, GuardWorker popup-gate + battery + dedup,
  SECGUARD-01 FRIDA active-portscan removed, SCAN-02 oversized-DEX scanned+penalised, InitialScan defer fix,
  plus many small P2s (App.kt Dispatchers.IO, Splash exported=false, build packaging/testOptions/native gate,
  UUID regex, webhook generic error, mdEscape, quarantine token 96-bit, news-image https+cap, WebView scheme).

**STILL TO DEPLOY (not automatic):** run `cloud/supabase/11_feed_corroboration.sql` and
`12_auth_rate_limit.sql` (+ `13_audit_2026_06_10.sql`) in the Supabase SQL editor. Until 11 runs,
the feed degrades to a `seen_count>=2` fallback (safe). The Vercel redeploy half is DONE —
verified live 2026-06-11 (prod serves the newest cloud code; see [[project-audit-2026-06-10]]).

**Deliberately deferred:** Shield→asymmetric CONFIG_SIGNING_SECRET (large crypto redesign),
FORENSIC-02 (one extra hash, needs full value + Shield encode; sample already caught), analysis/
absolute-path cleanup (internal, JSON outputs are source of truth). See [[project_feature_wave_2026_06_05]]
and [[project_anti_re_hardening]].
