---
name: project-feature-wave-2026-06-11
description: "2026-06-11 5-feature wave (link/QR check, weekly report, fake-bank audit, permission x-ray) — built green, 103 tests, committed+pushed 77a9b45. Needs cloud migration 14 + redeploy for the domain feed."
metadata: 
  node_type: memory
  type: project
  originSessionId: 53458987-6497-4d5b-b257-a3d76ef4b3ba
---

2026-06-11: shipped 5 new features (user: "добавь полностью"), built via a 24-agent multi-workflow
(8-agent recon → 6 foundation + 5 feature agents → 8-reviewer adversarial verify → fix). **Committed +
pushed `77a9b45`** on branch feat/anti-re-hardening. assembleDebug green; **103 unit tests** pass
(LinkScannerTest 26, BankAppAuditTest 13, PermissionXrayTest 7 new + existing 57); cloud api/lib+SPA tsc clean.
versionCode 80→81, versionName 8.0→8.1. New deps: ZXing core 3.5.3 + CameraX **1.3.4** (NOT 1.4.x — needs
compileSdk 35). 35 new + 12 modified files. Adversarial pass confirmed+fixed 13 defects (mostly
false-positive hardening + never-false-SAFE), dismissed 5 false alarms.

Features (each = engine object + v4 Activity + uz/ru strings file `strings_kq4_<screen>.xml`):
1. **Havola tekshiruvi** — `LinkScanner.kt` (pure, JVM-tested) + `MaliciousDomains.kt` + `LinkCheckActivity.kt`
   + `ShareUrlReceiverActivity.kt` (exported text/plain SEND target). Offline URL verdict: bank typosquat
   (Levenshtein, **corroborated** to avoid FP), punycode/homoglyph, IP-literal, '@', suspicious TLD,
   non-HTTPS, shorteners, financial keywords, curated C2 list + cloud domain feed. Blank/unparsable/error
   → SUSPICIOUS (never SAFE; SAFE only when zero flags).
2. **QR tekshiruvi** — `QrScanActivity.kt`, CameraX+ZXing offline decode → routes URLs into LinkScanner;
   re-checks CAMERA perm onResume after Settings round-trip.
3. **Haftalik hisobot** — `WeeklyReportWorker.kt` (clone of DailyReportWorker, 24h+7-day-guard,
   `kiberqalqon_weekly_report`) + `NotificationHelper.showWeeklyReportNotification` (id 7004). Settings toggle
   `Config.isWeeklyReportEnabled` (default on). Data = Statistics.weekCounts + ScanHistory 7d + Quarantine.size.
4. **Soxta-bank auditi** — `BankAppAudit.kt` + `BankGuardActivity.kt` + `KnownBanks.kt` (reconciled UZ bank
   list across AppReputation/IconImpersonation/FilenameHeuristic). Flags fake banking apps among INSTALLED
   apps by brand+icon(aHash)+cert+install-source. **isFake requires evidence beyond name resemblance**;
   excludes own pkg (anor vs anorbank!); a real Play-signed bank under its real pkg is never flagged; scan
   failure → warn card, not green SAFE.
5. **Ruxsat rentgeni** — `PermissionXray.kt` + `PermissionXrayActivity.kt`. Ranks installed apps by
   PermissionCombos score, **bridging** accessibility/device-admin/notification-listener services (GET_SERVICES
   serviceInfo.permission) so the strongest combos fire; ENG-03 device-admin exclusion applied.

Entry points: Dashboard gained Havola/QR tiles + a clickable bank-guard guard-cell; Settings gained
rowBankGuard + rowPermXray chevrons + rowWeeklyReport toggle.

Cloud: `api/threats.ts` now also feeds an **owner-curated** domain blacklist (separate version field `dv`,
fail-soft if table missing, owner-only source scope so a single device can't auto-feed). `CloudBlacklist.kt`
parses `domains:[{d,f}]` → `ThreatDb.mergeCloudDomains`. **NEW DEPLOY ITEM: run `cloud/supabase/14_threat_domains.sql`
in the Supabase SQL Editor + redeploy Vercel** for the cloud domain feed to go live (curated domains added via
panel/SQL; the offline curated C2 list works without it). This is on top of the still-pending audit-2 deploy
(migrations 11/12/13, DEVICE_TOKEN_SECRET).

**LinkScanner HARDENING (same day, committed+pushed `85fef8c`)** — red-team-driven (5 agents, 77-URL corpus
+ skeptic verify, then a 2nd fresh red-team). Closed false-negatives without regressing zero-false-positives:
confusable/leet fold (cl1ck/payme0/hum0/kapita1bank → HARD DANGER), mixed-script homoglyph, brand-in-subdomain
on foreign registrable, eTLD-aware registrable (click.com.uz FP fixed), decimal/hex/octal IP, new flags
APK_DELIVERY (anti-Ajina: link delivers the .apk) + PHISHING_PATH/SOCIAL_IMPERSONATION/OPEN_REDIRECT/SCAM_LURE/
BENIGN_IDN. 2nd red-team found+fixed 2 residual evasions: the "edit-budget cliff" (brand padded with ≥2
invisible/format/combining or U+217D Number-Letter glyphs → was SAFE; fix: confusableFold drops zero-width/Cf/Mn,
homoglyphKind escalates exotic-non-ASCII within fold-distance-2) and alternate Unicode dots (U+3002/FF0E/FF61/2024
→ normalized to '.'). LinkScannerTest 103→108, all green; assembleDebug+tests SUCCESSFUL. Engine stays
pure/offline/never-throw/never-false-SAFE. NOTE there is ONE curated SOFT_ALLOWLIST {osaka,asana,mobile,...} —
unavoidable: those words are edit-1 neighbours of bank tokens and can only be separated semantically.

⚠️ PARALLEL-SESSION WIP IN TREE (2026-06-11, NOT mine, left UNCOMMITTED on top of 85fef8c — do NOT sweep into an
unrelated commit): VPN C2-filter activation (Config.KEY_VPN_FILTER + VpnFilterService + Settings toggle +
strings_kq4_set_extra), cloud admin audit-log (cloud/lib/audit.ts + supabase/15_admin_audit_log.sql +
api/admin/login.ts + package.json), Quarantine encryption (Quarantine.kt + QuarCryptoTest.kt), ApkScanner.kt
tweak, and a new `.github/` CI workflow. Another Claude session is working these — commit them under their own
feature, not mine.

NOT yet checked on a real device (built+adversarially-reviewed only). Recon + spec scratch in
`.claude/tmp_recon/` (FEATURE_SPEC.md = the pinned-names spec). Related: [[project-audit-2026-06-10]]
(deploy backlog), [[project-anor-redesign-2026-06-10]] (v4 design system these screens follow),
[[project-competitor-apk-qoriqchi]] (link/QR check = a moat a VirusTotal-wrapper + Telegram bots can't match).
