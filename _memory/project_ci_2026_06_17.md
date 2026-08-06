---
name: project_ci_2026_06_17
description: "CI status 2026-06-17: green@27abc8e, b2b18e4 broke compile; UzGuard↔Uzcard audit bug fixed; branch churned by parallel session"
metadata: 
  node_type: memory
  type: project
  originSessionId: 959dd559-d659-448b-b154-489ed2b7e56f
---

2026-06-17, branch `feat/anti-re-hardening`. CI = `.github/workflows/ci.yml`: two jobs —
**android** (`./gradlew testDebugUnitTest` then `assembleDebug`) + **cloud** (`npm run typecheck`).
Cloud job has been green throughout; the android job is the one going red.

**How to check CI without `gh`** (gh NOT installed here): get token via
`printf "protocol=https\nhost=github.com\n\n" | git credential fill | grep ^password= | cut -d= -f2-`,
then curl `api.github.com/repos/Muhammadali22072011/KiberQalqon/actions/...` (runs / jobs / job logs).
`head_sha` filter needs the FULL 40-char sha. Job-log URL: `/actions/jobs/<id>/logs`.

**Timeline:** green @ `27abc8e` → **`b2b18e4` (perf commit) broke `compileDebugKotlin`** module-wide
(cascade of "Unresolved reference"/"overload ambiguity"/"when not exhaustive"/"Variable expected" —
all CASCADE from the compiler failing to build its symbol table; the genuine root was a single bad
call). Later parallel commits fixed the compile. Then the **only** red test was
`BankAppAuditTest.ownApp_*` — a REAL bug: rebrand made own name "uzguard" collide with bank brand
"uzcard" (`levenshtein=2 = MAX_DISTANCE`), so the fake-bank audit flagged the OWN app. Fixed in
`BankAppAudit.looksLikeBank` (own-app guard covering namespace `com.uzguard` AND applicationId
`com.kiberqalqon` — see [[reference_package_ids]]); commits `ed6d223` (wrong-pkg first try) + `0dd9c0e`.

**Key caveat:** the branch is being **actively committed to by a parallel session** (commits land
every few minutes, several break compile, e.g. `4b2c6ce` → AutoScanActivity Intent). Combined with
OneDrive disk-vs-committed-blob desync ([[reference_build_env_gotchas]]), CI is a moving target —
can't be stabilized while parallel commits land. My news feature (`c08056c`) is committed & fine;
its redness was purely inherited from the branch, not the feature.

**RESOLVED — CI GREEN again @ `2bf2a29`** (the near-real-time news commit). The parallel session
fixed its own breakages (incl. the AutoScanActivity `Intent` error), my Uzcard fix held, and the
real-time-news change passed. So the whole branch — news feature (c08056c) + real-time delivery
(2bf2a29) + bank-audit fix (ed6d223/0dd9c0e) — is green and pushed.
