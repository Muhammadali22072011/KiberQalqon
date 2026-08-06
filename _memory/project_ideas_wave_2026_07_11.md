---
name: ""
metadata: 
  node_type: memory
  originSessionId: 2fc939bd-0497-442e-8111-c2a1750d4ae4
---

2026-07-11: user asked to "add everything честно очень нужно". Ran a 30-agent Workflow (inventory→5 lenses→adversarial verify) → 17 KEPT features (not-already-built, feasible, honest value). Implemented ALL end-to-end via 3 parallel subagents (cloud panel, Android engine, Android new-UI) + my own cloud-API + shared-file integration.

**Committed 1059936 on feat/anti-re-hardening; pushed (HEAD 99bd407 on origin 2026-07-13, CI GREEN all 3 jobs). NOT device-tested.** Cloud `tsc` (api/lib + src) GREEN locally; Android compile gate = CI (no local build — RAM). **Migration 17 (`supabase/17_feature_wave_2026_07_11.sql`) RUN + VERIFIED in Supabase SQL Editor 2026-07-13** — self-check all 7 true (devices cols, threat_rules, known_good, rule_hits, v_rule_stats, v_fleet_health, news.group_id).

Shipped (all 12 Vercel fns preserved — everything is `?param` branch of existing handlers):
- **YARA-lite rule packs** — `threat_rules` + client `RuleStore`/`RuleEngine`, served in signed `threats?feed=1` (rv/gv guards); managers add/delete/mute; muted→advisory. React to new Ajina variant in ~30min w/o APK release.
- **Scam paste-checker** — `ScamTextAnalyzer`/`ScamMessageActivity`, routed from `ShareUrlReceiverActivity` no-URL dead-end.
- **Protection battery** — `ProtectionState.collect`→register `protections` jsonb; `v_fleet_health`; panel strip + per-device bar; **watchdog** Telegram alert on critical protection OFF.
- **Zip anomalies** (advisory) + **dropper XOR/Base64 second-stage probe** + inner-hash IOC.
- **known_good** downgrade-only (feed `good`) — same-day FP fix w/o release.
- **Manifest-diff** `CapabilitySnapshot` on PACKAGE_REPLACED (incl. Play) + rescan catch-up.
- **rule_hits telemetry** (upload parses `rule:<id>`) + `v_rule_stats` FP panel.
- **PIN lock** (`PinStore`/`PinLockActivity`) gates disabling protection; **Checkup wizard**; **Family guard** (`devices?family=1&code=`); **Limits screen**.
- Panel: **outbreak "Epidemiya"** button (block+news+fan-out rescan via `scans?hash=`), **per-group news** (`news.group_id`, `?g=`), **admin message** (device_commands 'message'), **lost/compromised flag**, **stats?weekly**.
- Cleanup: repurposed dead `scans.ts`→`?hash`; fixed `MapPage.tsx` NUL-byte sentinel. Did NOT delete dead `password.ts`/`demo.html` (user rule: no deletes without permission).

**CI: GREEN** after fix 5f1b6f2 (first push 1059936 was red: one `Val cannot be reassigned` in ScamMessageActivity.runAnalysis — `text:String` param shadowed `TextView.text` inside `apply{}`; renamed param→msg). Whole wave now compiles+builds+tests on CI.

**Anti-bug gates added (commit 99bd407)** so such compile bugs surface pre-push, not after a 2.5min CI: (1) new CI `quick` job = `:app:compileDebugKotlin` only (no dex/R8/NDK) → ~1min fast-fail, parallel; (2) detekt via CLI, ADVISORY (continue-on-error, report artifact) — make blocking after a curated baseline; (3) `.githooks/pre-push` blocks push on cloud `tsc` + best-effort local `:app:compileDebugKotlin` (`KQ_SKIP_ANDROID=1` to skip when RAM-bound; enable via `git config core.hooksPath .githooks`). Root cause of the class = no local build (assembleDebug OOMs), CI was sole slow gate. See [[reference-build-env-gotchas]].

**RELEASE 8.6/86 ROLLOUT LIVE 2026-07-13** — this wave is now ON PHONES via self-update: built
`assembleRelease` locally (SDK moved to D:/AndroidSdk, see [[project-build-toolchain]]), cert
1cb3f378 verified, hosted as kiberqalqon-cloud.vercel.app/kq-update-86.apk (sha 0e5cf800…),
env UPDATE_*=86 + CONFIG_VERSION=6, prod deployed (auto-promoted this time). E2E verified: live
config v=6/update→86, hosted APK sha bit-exact. 8.6 = everything: 17-feature wave + 51 bugfixes +
FP round-2 + self-update re-check. Desktop copy UzGuard-8.6-release.apk. NB versionCode 86 bump in
app/build.gradle.kts is UNCOMMITTED. Fun fact: old kq-update-85.apk sha 08e8dbef… was the hash
UzGuard self-flagged as sms_stealer (phone scanned its own downloaded update; round-2 fix covers it).

Package is `com.uzguard` (rebrand) not kiberqalqon. See [[project-bughunt-2026-07-11]], [[project-feature-wave-2026-07-11]].
