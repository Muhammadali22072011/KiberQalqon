---
name: project_rebrand_uzguard
description: The app was rebranded KiberQalqon → UzGuard; package/cloud/prefs use uzguard but folder paths still say kiberqalqon.
metadata: 
  node_type: memory
  type: project
  originSessionId: 01a63f3d-b8aa-46f7-bf60-8495df6da504
---

The Android app was rebranded from **KiberQalqon → UzGuard**. As of 2026-06-16 the code uses the new name but the filesystem layout kept the old names — this mismatch is confusing, so verify before asserting the old "kiberqalqon" identifiers.

- Kotlin package: `com.uzguard` (NOT `com.kiberqalqon`). All `.kt` files declare `package com.uzguard`; ViewBinding is `com.uzguard.databinding.*`.
- But the source dir is STILL `ApkGuard/app/src/main/java/com/kiberqalqon/`, the module folder is STILL `ApkGuard/`, and the workspace is STILL `KiberQalqon/`. Path ≠ package.
- SharedPreferences files renamed to `uzguard_*` (e.g. `uzguard_stats`, `uzguard_rescan`, `uzguard_prefs`).
- Cloud base URL: local.properties `cloud.base.url` was set to `https://uzguard-cloud.vercel.app` during rebrand — **but that domain is DEAD (HTTP 404 DEPLOYMENT_NOT_FOUND, alias never created)**. The LIVE prod is still **`https://kiberqalqon-cloud.vercel.app`** (Vercel project `kiberqalqon-cloud`, see [[reference_vercel_deploy]]). This mismatch meant every cloud call (telemetry/feed/join) silently 404'd for uzguard-cloud builds. FIXED 2026-07-09: repointed `cloud.base.url` → kiberqalqon-cloud + rebuilt release APK (verified URL swapped in dex; device-secret matches live server). If prod is ever re-aliased to uzguard-cloud, repoint back.
- User-facing strings say "UzGuard".

The [[kiberqalqon]] skill and several older memories still say `com.kiberqalqon` / `kiberqalqon_*` prefs / `kiberqalqon-cloud.vercel.app` — those are stale on the name; the architecture they describe is still accurate.

**Git state (2026-06-17):** the rebrand was a LARGE *uncommitted working-tree* change — git HEAD up to cfd8fb9 was mid-rebrand and INCONSISTENT (some files `com.uzguard`, some `com.kiberqalqon`, `app/build.gradle.kts` namespace still `com.kiberqalqon`). The full consistent rebrand lived only in the working tree. Committing my device-bug fixes therefore required committing ALL 312 tracked `ApkGuard/` changes together (commit **ce2c005**, branch `feat/anti-re-hardening`) — the app only builds in the consistent state (`namespace = com.uzguard`, `applicationId = com.kiberqalqon` kept for self-update cert compatibility). Non-`ApkGuard/` rebrand bits (docs/cloud/pitch) + untracked logos + `.demo_video/` remain uncommitted. See [[project_device_bugfix_2026_06_17]].
