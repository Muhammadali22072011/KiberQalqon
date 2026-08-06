---
name: project-anti-re-hardening
description: "Anti-reverse-engineering hardening shipped for KiberQalqon (secrets, native .so, per-device cloud auth, signed RemoteConfig) — what's done and the pending cloud deploy steps"
metadata: 
  node_type: memory
  type: project
  originSessionId: a6810506-02e1-4e0a-83a5-05d1381b1ae8
---

On 2026-06-03 a 4-stream anti-RE hardening was implemented so a downloaded APK doesn't reveal
how the detection engine works and an attacker can't abuse the cloud backend. Goal came from the
user: "сделай чтобы хакеры не понимали движок и не зашли в наш проект". All code BUILDS + unit
tests pass + cloud TS typechecks; an adversarial review found only 7 LOW findings, all fixed.

**Stream A — secrets hidden:** the 3 network secrets are no longer plaintext `buildConfigField`s.
`app/build.gradle.kts` has a `shieldEnc()` (byte-exact with `Shield.kt` / `scripts/shield_encode.py`,
verified) that emits `CLOUD_DEVICE_SECRET_ENC` / `DEV_TG_BOT_TOKEN_ENC` / `DEV_TG_CHAT_ID_ENC` /
`CONFIG_SIGNING_SECRET_ENC`. New `Secrets.kt` decodes them at runtime; all consumers
(CloudTelemetry, NewsClient, CommunityReportClient, Diagnostics/ReportProblem) route through it.
Verified: real secret `6ad31084…` absent from the release APK, decodes correctly at runtime.

**Stream B — native layer:** `libkqguard.so` (sig check + TracerPid anti-debug) via JNI
RegisterNatives; `NativeBridge.kt` choke-point with Kotlin fallback. Wired into SecurityGuard
(new `"native"` check + native sig gate, authoritative when loaded). See [[project-build-toolchain]]
for the NDK-on-D: setup. NO secrets in native — only the public `KQ_EXPECTED_SIG` hash.

**Stream C — backend per-device auth (cloud):** `cloud/lib/devauth.ts` issues a per-device token
at register (`HMAC(device_token, DEVICE_TOKEN_SECRET)`) and verifies writes signed with it
(HMAC over `label\nts\nnonce\nsha256(body)` + ±5min freshness + `device_nonces` replay table).
**Dual-accept**: old APKs using bare `x-device-secret` still work (transition). Client signing in
`CloudTelemetry.addSignedHeaders`. New files: `lib/devauth.ts`, `lib/rawbody.ts`, migration
`supabase/10_device_auth.sql`. Edited `api/scan/upload.ts` + `api/device/[id].ts` (bodyParser off).

**Stream D — engine secrecy:** stronger R8 (JNI keeps), manifest `usesCleartextTraffic=false` +
**cert pinning** (`res/xml/network_security_config.xml`) — pins are **Google Trust Services**
(WR1 + GTS Root R1, verified from the live `*.vercel.app` chain; + ISRG X1 backup; expiration
2027-06-01 fail-safe). NOTE: the design-workflow's Let's-Encrypt pins were WRONG and would have
bricked telemetry — always re-verify the live chain before pinning. `FLAG_SECURE` on
ScanResultActivity. Signed `RemoteConfig.kt` delivers verdict thresholds from `cloud/api/config.ts`
(HMAC `CONFIG_SIGNING_SECRET`); clamp = min(remote, baked) so remote can ONLY tighten detection,
every failure → baked defaults (golden rule). Baked == current ApkScanner numbers, so behavior
unchanged until tuned server-side.

## DONE — cloud deployed & verified LIVE (2026-06-03)
The cloud is deployed to `https://kiberqalqon-cloud.vercel.app` and the migration ran. Verified
live via curl: `/api/config` returns a signed envelope whose HMAC matches the APK's baked
`CONFIG_SIGNING_SECRET` (phone will accept remote config); a pure signed register (no
x-device-secret) returns `device_auth_token` matching the client computation (so bodyParser:false
+ raw-body works on Vercel AND `device_nonces` table exists); replaying the same nonce → 401
(replay protection active); old `x-device-secret` path still 200 (backward compat).

Vercel env set (project `kiberqalqon-cloud`, account **muhammadali22072011**, prod): `DEVICE_TOKEN_SECRET`
+ `CONFIG_SIGNING_SECRET` (latter == APK's `config.signing.secret`). `DEVICE_SHARED_SECRET` kept
for transition (don't delete until all field devices upgrade). Hobby is at **12/12 functions** —
no room for new `api/*.ts` endpoints (fold into existing routes via query param).

CAVEAT: a test device `kqtest_signed_000000000001` (name "KQ signed test (ochirib tashlang)")
was created on the live map during verification — delete it from the panel (Devices) so it
doesn't show during a pitch demo. Deploy command: `ApkGuard/cloud/deploy-prod.bat` (or
`npx vercel@latest deploy --prod --yes` from `cloud/` — must be logged in; CLI 54.x auth is NOT
readable by the older local v37, use `vercel@latest`). Vercel deploy needs the user's browser
login; there is NO token stored in the repo. See [[reference-vercel-deploy]],
[[reference-cloud-supabase-ops]].

The Android APK is built/signed/verified (secret hidden, native .so ×4 ABI, sig matches) and
committed on branch `feat/anti-re-hardening` (commit d0b150b). The whole branch incl. later
waves was pushed to GitHub 2026-06-10 (head 3694c69).

## 2026-07-09 — final hardening pass + SIGNED RELEASE build (goal: "protect fully, all layers, 1 release APK")
Full recon confirmed the 2026-06-03 hardening is real + comprehensive + wired (SecurityGuard.runAllChecks
→ killProcess+exitProcess in App.onCreate; NativeBridge loads first). Manifest already had
allowBackup=false / extractNativeLibs=false / usesCleartextTraffic=false / dataExtractionRules.
KEY POINT: all SecurityGuard checks are SKIPPED when BuildConfig.DEBUG → protection is LIVE only in a
**release** build. CI (ci.yml) builds only assembleDebug (unsigned, no R8) = unprotected; and a CI
release would lack local.properties secrets (baked at build time). So the real protected APK MUST be
built LOCALLY. Three genuine fixes made this pass:
  1. **R8 full-mode ON** — gradle.properties `android.enableR8.fullMode` false→true (was a documented
     TODO release-blocker). Audited every reflection site first (Class.forName/getField/getDeclaredMethod):
     all safe (framework classes R8 ignores, or kept classes). Residual risk = on-device reflection
     smoke-test (ViewBinding screens / OkHttp cloud call / Shizuku delete) — NOT yet device-verified.
  2. **proguard package-name bug fixed** — rules kept `com.kiberqalqon.*` but namespace is `com.uzguard.*`
     (rename leftover) → App/NativeBridge/databinding keeps were DEAD no-ops (worked only via AGP
     manifest auto-keep + native-methods catch-all). Fixed to com.uzguard.* + added
     `-keep ...BuildConfig` (Config.kt getField) + generic Application keep.
  3. **keystore.properties was WRONG** (would silently build UNSIGNED): storeFile pointed to a
     non-existent `Desktop\UzGuard\` path; pass/alias were `UzGuard2026`/`uzguard`. CORRECT signing key
     (verified, SHA-256 = 1CB3F378…983 = KQ_EXPECTED_SIG, so no self-kill):
       storeFile = C:\Users\User\OneDrive\Desktop\KiberQalqon\ApkGuard\release.keystore
       storePassword = keyPassword = **KiberQalqon2026** ; keyAlias = **kiberqalqon**
     (this is the ONE irreplaceable signing key — created 2026-05-29, valid to 2053; keystore.properties
     now fixed & gitignored). Temporarily lowered gradle heap 3072→2560 to fit ~1.4 GB free RAM for the
     local R8 build (revert to 3072 after — CI/other machines).

**SIGNED RELEASE APK built + verified 2026-07-09** (`app/build/outputs/apk/release/kiberqalqon-<ts>-release.apk`,
6.03 MB; copied to `Desktop\UzGuard-8.4-release-signed.apk`). BUILD SUCCESSFUL 12m18s, full-mode R8 clean,
lintVitalRelease passed. apksigner: v2+v3 true, cert SHA-256=1cb3f378…983 (= KQ_EXPECTED_SIG → no self-kill).
In-APK: class/method names obfuscated (SecurityGuard/isFridaPresent/isSignatureInvalid = 0 in dex), NativeBridge/
nSigInvalid kept (JNI), libkqguard.so ×4 ABI stripped. pkg com.kiberqalqon v84/8.4.
**Full-mode R8 runtime-safety = adversarially audited (4-surface workflow, 5 agents): 0 confirmed breaks.**
KEY FACT proven: enum `.name()`/`valueOf()` persistence (Verdict SAFE/SUSPICIOUS/DANGER round-tripped through
prefs + compared to literal "DANGER") is SAFE under full-mode R8 — R8 renames the enum FIELD identifier (SAFE→a)
but NOT the constant-pool name string baked in `<clinit>` (`new Verdict("SAFE",0)`), so `.name()` still returns
"SAFE". No reflective-JSON lib (no Gson/Moshi). NOT device-tested (phone disconnected) — final gate = on-device
smoke test (ViewBinding screens, OkHttp cloud call, Shizuku delete). Hardening changes (proguard pkg fix + keeps,
fullMode=true) UNCOMMITTED as of build.
