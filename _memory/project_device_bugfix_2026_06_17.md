---
name: project_device_bugfix_2026_06_17
description: "Device-test bug round (heat/battery/popup/delete) — 4 fixes found via 5-agent workflow, pushed ce2c005; CI now uploads debug APK artifact."
metadata: 
  node_type: memory
  type: project
  originSessionId: b4a6f1ed-c5b7-494b-912a-56cd637d1f9c
---

2026-06-17 the user device-tested and reported (garbled RU voice): phone heats/lags, battery
drains, the auto-scan popup window doesn't appear, and detected virus files don't get deleted.
A 5-agent investigation workflow root-caused each against current source. Fixes (committed
+pushed **ce2c005** on branch `feat/anti-re-hardening`; CI artifact step **00cf132**):

- **Heat + battery — `ProtectionService.startFastScanLoop`:** the fast poll ran the expensive
  `ApkScanner.findApkFiles` (MediaStore + recursive walk) every 45s screen-on / 180s idle
  **unconditionally**. Added a cheap `quickDirSignature()` (lastModified of ~9 download dirs);
  the full walk now runs only when a watched dir's mtime changed, or every `FORCE_FULL_FIND_MS`
  (5 min) as backup. Idle interval 180s→**600s**. Detection unchanged (inotify + 30-min
  GuardWorker still cover real-time).
- **Battery — `TelegramCommandPoller`:** `NORMAL_RESCHEDULE_SEC` 1L→**8L** (~138→~33 net
  wakeups/hr). Only affects opt-in remote-control users (default OFF).
- **Files not deleted — `ScanResultActivity.deleteApk()` (THE bug):** used bare
  `File(apkPath).delete()` which silently returns false on Android 11+ scoped storage (virus
  stayed). Rewired to the full `FileDeleter` ladder (mirrors AutoScanActivity): MANAGE_EXT_STORAGE
  → MediaStore consent launcher → SandboxedByOwner guidance, + write-perm launcher (API≤28) +
  onResume auto-retry after grant. SelfGuard preserved.
- **Popup window (Android 14+/A56) — already fixed in HEAD:** `ProtectionStatusActivity.showWindowPermWarning()`
  "Enable" button calls `startWizard()` (walks overlay+FSI grants). The working tree had regressed
  it to `renderRows()` (OneDrive stale-copy); restoring matched HEAD so no diff.

**NOT yet device-tested.** Verified only by static review — local build impossible (RAM, see
[[reference_build_env_gotchas]]); CI compiles + runs unit tests. **Deferred (risky, needs a build
+sample test): the deeper heat fix — each APK is opened/decompressed ~8-9× per scan (SHA-256, ZIP-enc,
inline ZipFile, then 5 analyzers each re-open). Opening once would cut it, but touches the detection
engine (false-SAFE risk) so I did NOT do it blind.** Also deferred: O(N²) prefs churn in finalizeResult,
MIUI overlay-window fallback (needs Redmi verification).

**Follow-up (commit 4b2c6ce):** the "Telegram `/Android/data` file can't be deleted" worry —
reframed and improved. Reality: (1) most Telegram downloads are in PUBLIC `/storage/emulated/0/Telegram/`
→ deletable with All-Files; (2) `/Android/data/<owner>/` is a hard OS wall (no app, even with
MANAGE_EXTERNAL_STORAGE, can read/delete it; SAF picker also blocks it on 11+); BUT such a file is
**inert until installed**, and the install is gated both ways — `ShareReceiverActivity` (copies to our
cache + scans BEFORE install) + `PackageInstallReceiver` (scans installed pkg → DANGER → uninstall
notif). So it's a cleanup-UX issue, not a security hole. Changed `SandboxedByOwner` in AutoScanActivity
+ ScanResultActivity from a dead-end "OK" to a one-tap **"Open <owner>"** (getLaunchIntentForPackage,
fallback app-settings) + reassuring message (strings sandboxed_inert_msg/sandboxed_open_owner/
sandboxed_dialog_title). NOT YET DONE (offered): nudge to set UzGuard as default APK handler so EVERY
apk routes through the pre-install scan.

**Scan-heat refactor STAGE A (commit 2df6c71, pushed; LOCAL build-verified):** the deep scan-time
heat cause = each APK was parsed by `getPackageArchiveInfo` (framework reads the whole APK) **4×
separately** (cert L719, perms L751, label L1007, ManifestAnalyzer). Collapsed to ONE union-flag fetch
reused everywhere. Safe-by-construction: union flags = SUPERSET of every consumer (false-SAFE trap is a
LESSER-flag PackageInfo → null components — avoided), and each call site is guarded
`if (sharedArchiveInfo != null) reuse else original-path` so any fetch failure = byte-identical old
behavior. New: `CertUtil.fingerprintSha256(info: PackageInfo?)`, `ManifestAnalyzer.MANIFEST_FLAGS` (val)
+ `analyze(info, path)` overload (old `analyze(pm, path)` kept as wrapper). Raw-byte steps
(ZipEncryptionDetector RandomAccessFile, apkFileSha256) deliberately NOT shared (they read bytes ZipFile
hides — the Ajina.Banker anti-evasion detection). **Verified locally: `./gradlew testDebugUnitTest` =
BUILD SUCCESSFUL, all unit tests green** (env: JAVA_HOME=/c/Java/jdk-17.0.19+10, taskkill java, OneDrive
up, dangerouslyDisableSandbox — works, ~5 min). DEFERRED (lower value + riskier, needs device + malware-
sample verification): sharing the ZipFile handle / decompressed DEX bytes across inline+Dex/Dropper/Native
(getPackageArchiveInfo is the heavy repeat; ZipFile-handle sharing wouldn't avoid the dex re-inflation).
NOT device-tested. The 7-agent map/design is in the workflow output (run wf_8e0d32eb-ee2).

GOTCHA discovered: HEAD (cfd8fb9) was **mid-rebrand inconsistent** (some files com.uzguard, some
com.kiberqalqon, namespace com.kiberqalqon); the **working tree is the finished consistent rebrand**
(namespace com.uzguard, all files com.uzguard, applicationId stays com.kiberqalqon). So ce2c005 had
to commit ALL tracked `ApkGuard/` changes (312 files = full rebrand + my fixes) — the app only builds
in that consistent state. See [[project_rebrand_uzguard]]. CI now uploads `uzguard-debug-apk`
artifact — see [[reference_build_env_gotchas]].
