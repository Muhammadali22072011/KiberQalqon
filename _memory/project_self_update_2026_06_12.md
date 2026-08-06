---
name: project_self_update_2026_06_12
description: "Self-update via signed RemoteConfig \"update\" block — 3-layer verify (HMAC config, SHA-256, own signing cert); built green 2026-06-12, cloud side = owner step"
metadata: 
  node_type: memory
  type: project
  originSessionId: 064ab739-53a9-420e-8c19-623e6c868cbe
---

2026-06-12 (same session as [[project_link_interceptor_2026_06_12]] /
[[project_user_whitelist_2026_06_12]]): added **app self-update** (sideload, no Play yet).

**Flow:** App.onCreate background → RemoteConfig.refresh → `SelfUpdate.checkAndNotify` — if
signed config has `update:{versionCode,apkUrl,apkSha256}` with versionCode > installed →
ONE notification per version → tap → `SelfUpdateActivity` (translucent, plain Activity,
framework dialog) → confirm → background download → verify → system installer.

**3-layer security:** (1) metadata rides the HMAC-signed rollback-guarded RemoteConfig;
(2) downloaded file SHA-256 must equal signed value; (3) downloaded APK signing cert must
equal installed app's own cert (CertUtil) — even a stolen config key can't push foreign APKs.
Any mismatch → file deleted, no install prompt. Fail-soft: no update fields → nothing happens.

**Files:** SelfUpdate.kt (notify + downloadVerifyInstall), SelfUpdateActivity.kt,
RemoteConfig.apply stores rc_up_vc/rc_up_url/rc_up_sha + RemoteConfig.updateInfo(),
App.kt hook after CloudBlacklist.refresh, manifest registration, strings kq4_update_*.
Download target: cacheDir/shared/ (existing FileProvider cache-path).

**Cloud side DONE (same day):** cloud/api/config.ts now emits the `update` block from env vars
UPDATE_VERSION_CODE / UPDATE_APK_URL / UPDATE_APK_SHA256 (all three valid → block included;
sha must match /^[0-9a-f]{64}$/, url https). tsc clean. Release helper:
ApkGuard/scripts/publish_update.sh (parses versionCode from build.gradle.kts, sha256sums APK,
prints exact vercel env commands). APK host = **Supabase Storage public bucket "updates"**
(GitHub Releases unusable — repo Muhammadali22072011/KiberQalqon is PRIVATE, 404 unauthenticated).
Owner steps documented in RUCHNYE_SHAGI_VLADELCA.md §7.

**OWNER STEPS remaining per release:** build signed release → run script → upload APK to
Supabase Storage → set 3 UPDATE_* envs + CONFIG_VERSION+1 → vercel --prod. Public "updates" bucket CREATED 2026-06-12 (one-time step done). Remaining one-time: none.


**Status:** COMMITTED+PUSHED 2026-06-12 as a85dfc0 (feat/anti-re-hardening, 22 files, all five
waves of the day in one commit) and cloud DEPLOYED to production same day (deployment
kiberqalqon-cloud-eb1yvtyc0, Ready; /api/config live → 401 without secret = healthy; panel 200).
Still NOT device-tested. Update feature stays dormant until owner sets UPDATE_* envs +
creates Supabase "updates" bucket (RUCHNYE_SHAGI_VLADELCA.md §7).
