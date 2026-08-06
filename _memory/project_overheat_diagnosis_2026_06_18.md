---
name: project_overheat_diagnosis_2026_06_18
description: Device-measured root cause of UzGuard phone overheating (A56) + the two code fixes
metadata: 
  node_type: memory
  type: project
  originSessionId: 7f1de76b-affb-4a5a-8952-87a96fc2ff14
---

2026-06-18: overheat finally diagnosed on the REAL device (Samsung A56 / SM-A566E, over adb USB). The installed build was the LATEST (8.2-DEBUG, vc82, all prior perf-wave fixes present) — so it was NOT a stale build. App was pinned at **~290-360% CPU (≈3.4 cores), 32+ min CPU time, 2.9-3.3GB RSS, AP=54.8°C, thermal status 4 (throttling)**, with constant NativeAlloc GC (ZIP-inflate native churn) and 2 ANR dumps from 06-16.

**Diagnosis method (reuse for future heat):** `top -b/-H` (3 hot `DefaultDispatcher-worker` threads — note kotlinx `Dispatchers.IO` runs on the SAME "DefaultDispatcher-worker" pool). perf_event is DISABLED kernel-wide on this Samsung → `simpleperf` ALL events fail; `debuggerd -j` needs root; SIGQUIT trace goes to root-only /data/anr. WHAT WORKED: **`adb shell am profile start --sampling 1000 <pkg> /data/local/tmp/x.trace` → stop → pull** (ART built-in, no root, debug build). Read it with `sed -n '/^\*methods/,/^\*end/p'` (v3 trace = text sections). Gotcha: Git-Bash mangles `/data/...` on `adb pull` → prefix `export MSYS_NO_PATHCONV=1`. adb at `/c/Android/platform-tools/adb.exe`.

**Root cause (two measured hot paths, both fixed — detection unchanged):**
1. `ObfuscatedSignatures.matchTokenHashes` SHA-256'd EVERY token of every DEX (tens of thousands, no dedup, double-lowercase). FIX: dedup unique tokens (HashSet `seen`) + single lowercase via private `hashLower`.
2. `ScanCache.get/put` re-parsed the ENTIRE 500-entry JSON from prefs on EVERY scan call — and scans re-fire on every onResume/navigation even when fully cached → "keeps heating after everything is scanned". FIX: in-memory `LinkedHashMap<pathHash,Entry>` loaded once (`ensureLoaded`); get()=O(1), disk write only on put().

Secondary (NOT the cause): VPN filter (`VpnFilterService`) was user-enabled and running but its `runLoop` blocks on `input.read()` (not spinning); was not a top CPU thread. Cache-stamp churn ruled out (`markDatabaseUpdated` only on real cloud-feed change; GuardWorker deliberately skips it — BG-04).

Fixes = edits to ObfuscatedSignatures.kt + ScanCache.kt. COMMITTED+PUSHED 2026-06-18 as **3e39a98** on feat/anti-re-hardening; CI GREEN, `uzguard-debug-apk` artifact built (local build impossible — RAM, see [[project_build_toolchain]]). **DEVICE-VERIFIED on A56 same day**: installed CI APK via `adb install -r`, launched, let it scan → app CPU **~290% → 41% peak then 0% (settled)**, RSS **~3GB → 195MB**, thermal **status 4/54.8°C → status 2/46°C**. Fix confirmed working.

**UPDATE 2026-07-09 — THIRD hot path:** `ObfuscatedSignatures.matchDecrypted` still did `text.contains(sig, ignoreCase=true)` per sig → `Character.to*Case` over the 8MB DEX text ×10 sigs (regionMatches+to*Case hot in am-profile). Also matchTokenHashes' Regex.findAll was replaced by a hand-written char-class tokenizer (ICU MatcherNative.setInput was copying the whole DEX into a native buffer per entry, ×260 entries — ~70% of scan profile; that tokenizer change landed earlier in db6bfb4). matchDecrypted FIX (lower text once + `loweredSignatures()` cache → plain indexOf; ASCII sigs, detection unchanged) COMMITTED 2026-07-09 as **4914e20** on feat/anti-re-hardening — NOT yet pushed, NOT device-verified. **Local build SUCCEEDED this time** (contradicts "local build impossible"): recipe = OneDrive.exe running + zero stray java procs + `JAVA_HOME=/c/Java/jdk-17.0.19+10 ./gradlew assembleDebug --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=512m"` → BUILD SUCCESSFUL in 10m36s, APK `app/build/outputs/apk/debug/kiberqalqon-*-debug.apk` (~11.6MB). `--no-daemon` + bounded heap is what avoided the OS OOM-kill. (adb gotcha that cost time: stale adb server held device as not-visible; Windows `Get-PnpDevice` showed "ADB Interface" present → `Stop-Process -Name adb` + restart → device appeared as `unauthorized` → user must UNLOCK phone + tap Allow.) Builds on [[project_perf_wave_2026_06_17]] and [[project_device_bugfix_2026_06_17]]. STILL on branch feat/anti-re-hardening — not merged to main.
