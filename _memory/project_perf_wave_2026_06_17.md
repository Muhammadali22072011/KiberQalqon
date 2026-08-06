---
name: project-perf-wave-2026-06-17
description: "Overheating/lag fixes for the UzGuard/KiberQalqon Android app — 7 perf fixes from an 11-agent audit, committed b2b18e4 + pushed, CI-verified (not yet device-tested)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 7b99e2c9-2981-45c1-8122-f305b30edb53
---

User reported phones overheating + lagging again (the [[project-perf-brand-2026-06-09]] adaptive-poll
fix wasn't enough). Ran an 11-agent perf audit + adversarial verification: it REJECTED most scary
theoretical findings (scan-engine cost, screen-unlock scans, ScanCache — all already mitigated) and
confirmed **10 real hotspots, all medium/low (no critical/high)** — i.e. overheating = the SUM of
several medium factors, not one bug.

**7 fixes shipped (commit `b2b18e4`, pushed to `feat/anti-re-hardening` 2026-06-17). Detection
strength UNCHANGED — verifiers checked: no false-SAFE, hard-signals + real-time watch intact.**
- `MainActivity.startAutoProtection` (the #1 heat cause): "scan all" did `apks.forEach { scope.launch(Dispatchers.IO){ scan() } }` — unbounded, Dispatchers.IO's 64-thread pool ran dozens of heavy SHA-256/ZIP/DEX scans at once, pinning every core. Now bounded by `Semaphore((cores/2).coerceIn(2,4))` via `withPermit`.
- `ProtectionService`: backup fast-scan poll `POLL_INTERVAL_ACTIVE_MS` 12s→45s, idle 90s→180s (real-time is inotify+GuardWorker; poll is only backup).
- `GuardWorker.schedulePeriodic`: periodic full sweep 15→30 min.
- `TelegramCommandPoller`: `getUpdates` returns emptyList() on BOTH success-no-updates and failure → distinguished by elapsed time (real long-poll blocks ~25s; fast-empty = TG blocked/429/401 → failure). Added exponential backoff (5s→…→300s) instead of the old fixed 1s reschedule. Fixes the UZ "Telegram blocked → retry every 1s → constant heat" case. Fail count in prefs `uzguard_tg_poll`.
- `ObfuscatedSignatures.hash`: thread-local reused `MessageDigest` + hex lookup instead of `getInstance()`+`"%02X".format()` per token (tens of thousands per DEX). Output BYTE-IDENTICAL (ShieldTest/ObfuscatedSignaturesTest invariants hold).
- `App.onCreate` + `ApkScanner.scan`: ThreatDb (~730 KB / ~9700 lines) moved off the MAIN thread to `appScope.launch(Dispatchers.IO)`; readiness barrier `ThreatDb.init(context)` at top of `scan()` (idempotent+synchronized → scan thread waits during load window, never false-SAFE). Fixes cold-start jank.
- `MainActivity` news ticker: 16ms (60fps) postDelayed loop → 32ms (30fps) `TICKER_FRAME_MS`, step doubled so scroll speed is unchanged — halves UI-thread work.

**Verification:** local build was IMPOSSIBLE (8 GB machine, Claude Code eats ~2 GB → JVM OOM-crash;
see [[project-build-toolchain]] RAM section). All runs that reached compilation showed **0 Kotlin
`e:` errors**. Pushed so **GitHub Actions CI** (ci.yml) builds+tests on a clean runner — that is the
verification. **NOT yet device-tested.** To get the APK on a phone: build in Android Studio with
Claude Code closed.
