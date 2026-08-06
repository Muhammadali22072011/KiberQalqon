---
name: project-audit-2026-06-10
description: "2026-06-10 second full audit of KiberQalqon (bugs + UX) — report + top findings; ~50 fixes applied, committed+pushed 2026-06-11. Still NEEDS DEPLOY (migrations 11/12/13 + Vercel redeploy)."
metadata: 
  node_type: memory
  type: project
  originSessionId: ec1858b2-8afd-4499-8f13-01a202c39fc1
---

2026-06-10: ran a second multi-agent audit (bugs + UX-неудобства) after the 2026-06-07
remediation. Report saved at repo root **`AUDIT_KIBERQALQON_2026-06-10.md`**. ~96 confirmed
findings, all verified by adversarial skeptics; **nothing fixed yet** — this was analysis only,
the user asked to "выявить" (identify), not fix. Prior remediation [[project_audit_remediation_2026_06_07]]
is mostly in tree & committed (branch feat/anti-re-hardening).

**Most dangerous (CRIT-01, verified by hand):** `cloud/lib/devauth.ts` + `lib/session.ts` reuse
ONE HMAC key (tokenKey() = DEVICE_TOKEN_SECRET||SESSION_SECRET||ADMIN_SECRET; session key() =
SESSION_SECRET||ADMIN_SECRET) with NO domain separation, both = base64url(HMAC-SHA256(str,key)).
`/api/device/register` returns `device_auth_token = issueDeviceToken(attacker_device_token)` over
the legacy x-device-secret path (APK-extractable). Setting device_token = base64url('{"exp":big}')
makes the server hand back exactly session.sign(payloadB64) → forge an OWNER session = full panel
takeover. Fix: domain-prefix both HMACs + require a distinct DEVICE_TOKEN_SECRET. Also verify in
Vercel that DEVICE_TOKEN_SECRET is set AND different.

**Other top findings:** ProtectionService never restarts on Android 12+ after OEM kill (even on
app open) + background toggle calls neither start()/stop() (stop() has 0 callers) → real-time
protection silently dead; screen-unlock re-pops virus popup/full-screen alarm for old SUSPICIOUS
files every unlock (BG-03); full_sweep markDatabaseUpdated every 15min wipes ScanCache → undoes the
overheating fix (BG-04); Quarantine promises 7-day restore but restore() has 0 callers + no UI
(UX-01); consent forces community-share checkbox (UX-02); HiddenThreats/Telegram-settings only
reachable via secret splash long-press (UX-03); QUERY_ALL_PACKAGES missing → hidden-threat scanner
blind on Android 11+ (ENG-01); DeviceAdmin alone → hard DANGER via circular combo (ENG-03);
Play App Signing → boot-loop self-kill (SD-01) + false-DANGER on legit gov/bank updates (ENG-04);
login rate-limit bypass via X-Forwarded-For + non-atomic fail_count (CL-01); devices list/Excel
capped at 50 (CL-02); demo.html dead on prod CSP (CL-03); Supabase views bypass RLS, anon keeps
SELECT (CL-05); python bot: no sample quota → trivially bricked (PY-01), entry-count zip-bomb (PY-02);
.gitignore misses _competitor/ + _samples/ (decompiled competitor + malware one `git add -A` away).

Recommended fix order is in the report's last section. Remediation gaps still open from 06-07:
CLOUD-02, CC-02 (cert pin), FORENSIC-02 (hash A89122D1), FORENSIC-03 (analysis abs-paths).

**STATUS 2026-06-11: ALL of the remediation below is now COMMITTED + PUSHED** (bulk in `3694c69`; the
last 7-line migration-13 guard for `scan_duration_ms` in `d686bcc`) on branch feat/anti-re-hardening.
Re-verified before push: assembleDebug green, cloud api/lib+SPA tsc clean.

**STATUS 2026-06-11 (later, independent 10-agent verification + follow-up fixes 359d2f2):**
- Cloud redeploy is **DONE** — prod probed live: v4 "Anor Qalqon" title (from c5f8628), generic
  auth errors everywhere, /api/scan-perf 404 (merged into stats). Deploy happened right after the
  push (likely Vercel Git auto-deploy) — so the "migrations before redeploy" ordering was already
  violated; harmless because all new code paths are fail-soft, but **run migrations 11+12+13 in the
  Supabase SQL Editor ASAP** (all idempotent). Detect: `select to_regclass('public.auth_attempts');`
  and check Vercel logs for "[threats] corroborated_threats RPC failed".
- Top-6 verified in tree: CRIT-01 ✔ (closed exactly per audit, also live in prod), BG-01 ✔, BG-02 ✔,
  BG-03 ✔, UX-01 ✔ (QuarantineActivity), UX-05 ✔ (installedPkg uninstall). SD-01 was the only one NOT
  fixed → **now fixed in `359d2f2`**: signature gate accepts a SET of certs (SecurityGuard.kt Shield
  list + kqguard.c comma-separated KQ_EXPECTED_SIG parser). Before Play publishing still need the
  actual Play Console "App signing key certificate" SHA-256 appended in build.gradle.kts + SecurityGuard.kt.
- Still owner-manual: set/verify DEVICE_TOKEN_SECRET distinct in Vercel env; delete test device
  kqtest_signed_000000000001 from the panel.

**2026-06-10 REMEDIATION (same session) — ~50 fixes applied & build-verified, NOW COMMITTED (see STATUS above):**
- Cloud (verified: `tsc` api/lib + SPA clean, `vite build` ok): CRIT-01 HMAC domain-separation
  (session.ts/devauth.ts `kq-session-v1\n`/`kq-devtok-v1\n` prefixes), CL-01 trusted IP (`x-real-ip`/last
  XFF hop in ratelimit.ts+geo.ts) + atomic `record_auth_failure` RPC (new migration **13_audit_2026_06_10.sql**),
  CL-02 devices limit 50→2000 +count, CL-03 demo.html CSP block in vercel.json, CL-04 feed/threats error
  states, CL-05 view security_invoker+revoke (migr 13), CL-06 v_stats_today Asia/Tashkent (migr 13), CL-07
  generic DB errors across ~12 endpoints, CLOUD-02 `DISABLE_LEGACY_DEVICE_SECRET` flag, config.ts v monotonic
  (`CONFIG_VERSION` env), rawbody 512KB cap, usePoll hidden-tab pause, session-expiry Login message, news badge
  route-sync, exportExcel total-fail throw + uz enums, Landing retry, Overview health null-on-no-data, News
  https image validation + delete/pin owner-only, uzRegions distance threshold, public stats viloyat grouping.
  **NEEDS: run migration 13 in Supabase SQL editor + redeploy.**
- Android (verified: compileDebugKotlin + 65 unit tests pass + assembleDebug ok, all -Pkq.skipNative=true):
  BG-01 MY_PACKAGE_REPLACED receiver + onResume ProtectionService.start, BG-02 toggle start/stop + refresh/
  observer gates, BG-03 unlock-scan persistent `unlock_warned` dedup + suppressAlerts param, BG-04 removed
  full_sweep markDatabaseUpdated, BG-05 seed 10s budget, BG-06 FullPhoneScan depth 5→8 + visit-cap, BG-08
  startForeground→stopSelf, NotificationAccessWatcher APPEND_OR_REPLACE, kill-detect uptime guard, ScreenUnlock
  USER_PRESENT-only +10min throttle, ENG-01 QUERY_ALL_PACKAGES, ENG-02 DexPatternAnalyzer no size-skip, ENG-03
  deviceAdmin independent-combo, ENG-04 CertUtil.installedSigningFingerprints (rotation history), ENG-05
  DropperDetector all-res magic, SD-02 Uzbek notif before kill, SD-03/04 boot-props out of root path, UX-01 new
  QuarantineActivity (restore/purge from Karantin tile), UX-02 consent not forced, UX-03 settings nav to
  HiddenThreats/Telegram/ProtectionStatus, UX-04 portrait lock, UX-05 installedPkg uninstall fix, UX-06 real
  quarantine count, UX-08 manual scan ungated +30s timeout, UX-12 reset owner/offset on token/chat change,
  TG-01 poller network constraint, build: .gitignore (_competitor/_samples/build_log/screenshots), proguard
  OkHttp keep removed, multi-dot apk pathPattern, R8 fullMode comment.
- Python (py_compile ok): PY-01 sample quota+rotation+no-double-copy, PY-02 zip entry-count/depth limits,
  PY-03 escape_markdown + per-user rate-limit + error handler.

**POST-AUDIT first-run/permission UX (same session, compiles, uncommitted):**
- "Protected" signals now fire ONLY when protection is real: new `ProtectionActivator.activateIfReady(ctx)`
  gates on `Config.isBackgroundEnabled && VersionCompat.hasFileScanAccess` before starting ProtectionService
  + showing welcome ONCE (`Config.isWelcomeShown`/`KEY_WELCOME_SHOWN`). Removed premature welcome from
  App.onCreate firstRun + replaced unconditional ProtectionService.start. Called from App.onCreate +
  Splash/Dashboard/Main onResume + ProtectionStatus.proceed. First run before permission no longer lies
  "himoyalangan"; onboarding screens ask for permission, "protected" appears after grant.
- Overlay (SYSTEM_ALERT_WINDOW) made OPTIONAL not critical: `allCriticalPermissionsGranted()` no longer
  requires `hasOverlayPermission` (some OEMs lock overlay → users were stuck forever on ProtectionStatus).
  Fallback: full-screen-intent notification shows warnings without overlay. Critical now = file access +
  notifications + background only.

**SPLASH/CONSENT REDESIGN + PRIVACY v5 (same day, later session, compiles via assembleDebug):**
big-motion splash (rotating star ornament kq_star_ornament, orbit dot, scan beam, overshoot shield +
float, letterSpacing-settle wordmark, exit zoom-fade; stable IDs kept), consent screen cascade entrance;
UX-14 splash part FIXED (single-dialog guard + Chiqish button on Android 11+ storage dialog);
PRIVACY_POLICY + TERMS in ConsentActivity.kt fully rewritten to match code: community share = optional
(was "MAJBURIY" text contradicting UX-02 fix), new §3 technical traffic (blacklist/config/news downloads,
no personal data), new §8 transfer security (pinning, signed feeds, UUID); typos fixed;
**CURRENT_CONSENT_VERSION bumped 4→5** (existing users re-consent); consent_community_chk strings in
3 locales: "*" removed → "(ixtiyoriy)/(необязательно)"; terms_last_update → 2026-06-10.

**DEFERRED (not done, lower priority):** UX-07 settings string labels, UX-09 full RU localization (large),
UX-10/11/13 misc telegram/UX (UX-14 splash-dialog part done, rest of UX-14 list open),
prior CC-02/FORENSIC-02/03 (SD-01 dual-accept DONE 2026-06-11 in 359d2f2 — see STATUS above),
various low (NativeLibAnalyzer SAFE_LIB, IconImpersonation threshold, ScanCache content-hash, ZIP64 LFH, session
revocation, keystore-in-OneDrive=user action, 76 legacy .txt=needs user OK to move per their no-delete rule).
