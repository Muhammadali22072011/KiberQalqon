---
name: project_telegram_sandbox_limit_2026_06_18
description: "Why UzGuard can't auto-catch a Telegram-downloaded virus (no popup, no delete) — Android sandbox, confirmed live on the A56."
metadata: 
  node_type: memory
  type: project
  originSessionId: 721c7984-7050-4223-afec-c8c8a891d29c
---

2026-06-18 live device diagnosis (Samsung Galaxy A56 SM-A566E, **Android 16**, debug pkg `com.kiberqalqon.debug`, serial R5CY32375DH). User reported: downloaded a virus via Telegram → **no AutoScan popup, not deleted**. ADB-confirmed root cause:

- Telegram saves downloaded APKs to its **private sandbox** `/sdcard/Android/data/org.telegram.messenger/files/Telegram/Telegram Files/` (e.g. the `toydanfotolar(9.jpg).apk` Ajina.Banker sample). Photos go to public `/Android/media/.../Telegram Images` but **documents/APKs go to /Android/data**.
- On Android 11+, **no app** (even with MANAGE_EXTERNAL_STORAGE) can read/watch/delete another app's `/Android/data/`. **Proven**: `run-as com.kiberqalqon.debug ls /sdcard/Android/data/org.telegram.messenger` → "No such file or directory"; `adb shell` (shell uid) sees it fine. So FileObserver never fires → no popup; nothing to delete. NOT a bug, NOT a permissions issue — OS limit that hits every AV (Kaspersky/Dr.Web included).
- It was NOT a permissions/service problem: ProtectionService **alive & foreground**, SYSTEM_ALERT_WINDOW allow, MANAGE_EXTERNAL_STORAGE allow, USE_FULL_SCREEN_INTENT allow, standby bucket 5 (EXEMPTED) + in deviceidle whitelist. User was right that "all permissions given."
- Also: copying the APK into public `/sdcard/Download` did **not** fire the real-time popup either — FileObserver/inotify on emulated FUSE storage is unreliable on Android 16. Real-time folder-watching is fragile; don't rely on it.
- Two Telegram apps installed: `org.telegram.messenger` + `org.telegram.plus` (Plus Messenger fork — its media path is NOT in MultiPathFileObserver's watch list anyway, but moot given the sandbox limit).

**Correct protection model (what actually works, all already in the app):** a file at rest = harmless; danger = INSTALL. So defend at: (1) install-time — user picks UzGuard from "Open with" → ShareReceiverActivity copies to cache + scans (verified: AutoScanActivity surface got created via `am start -n .../ShareReceiverActivity -a VIEW -d file://...`); (2) post-install — PackageInstallReceiver (registered at runtime in App.onCreate); (3) periodic/on-demand installed-app scan. See [[project_autoscan_window_fix_2026_06_16]], [[project_device_bugfix_2026_06_17]].

**Proposed next step (user dismissed the choice 2026-06-18, awaiting instruction):** make UzGuard the **default APK handler** so every install routes through it first (industry standard; turns unreliable folder-watch into reliable install-time intercept). NEVER install the real `toydanfotolar`/Ajina.Banker sample on this phone — it has real bank apps (payme, agrobank, etc.); demo post-install detection only via the built-in [[TestVirusGenerator]]. AutoScanActivity is correctly exported=false (adb can't launch it directly — SecurityException, by design). adb gotchas: filenames with `(` break the device sh — quote the whole remote cmd; set `MSYS_NO_PATHCONV=1` so Git Bash doesn't mangle /sdcard paths.
