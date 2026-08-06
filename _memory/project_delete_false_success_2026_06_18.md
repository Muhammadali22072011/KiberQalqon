---
name: project_delete_false_success_2026_06_18
description: "Confirmed on-device delete false-success bug — app said \"o'chirildi\" but Telegram-shared virus survived; fixed (FileDeleter + share-copy honesty)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0a2a5617-efd2-4374-85e8-02aca0d7fa2f
---

2026-06-18, device-test on **Samsung Galaxy A56 (SM-A566E), Android 16 / SDK 36**, debug pkg `com.kiberqalqon.debug`, over adb (R5CY32375DH). User report (RU voice): "virus won't delete — app says deleted but it stays."

**Root cause (PROVEN with adb ground truth):** Telegram-delivered malware lands in Telegram's sandbox `/sdcard/Android/data/org.telegram.messenger/files/Telegram/Telegram Files/`. When the user shares it into the app, `ShareReceiverActivity.copyToCache()` copies it to `cacheDir/shared/<name>.apk` and scans the COPY. On "delete", `FileDeleter` deletes the cache copy → app shows "o'chirildi / Telefoningiz xavfsiz" → **but the original in the Telegram sandbox is untouched** (Android blocks `/Android/data/<pkg>/` for everyone, even with MANAGE_EXTERNAL_STORAGE; SAF picker also blocks that subtree on 13+ → genuinely undeletable programmatically). logcat showed `FileDeleter: Direct delete OK: .../cache/shared/toydanfotolar(9.jpg).apk` while `find` confirmed `.../org.telegram.messenger/.../toydanfotolar(9.jpg).apk` survived. This is the worst antivirus bug class (false success) and hits THE primary attack vector. Note: normal on-disk files (e.g. /sdcard/Download) DO delete fine once all-files-access is granted (verified).

**Second bug found+fixed:** `FileDeleter.delete()` had `if (!file.exists()) return Deleted` as the FIRST check, ABOVE the sandbox check — so a foreign-`/Android/data/` path we can't even stat returned a false `Deleted`. Reordered: SelfGuard → sandboxOwner → then `!file.exists()`.

**Fix (written, NOT yet built/committed/device-verified):**
- `FileDeleter.kt` — reordered checks (sandbox before exists shortcut).
- `ShareReceiverActivity.kt` — passes `apk_is_copy=true` + `apk_origin_uri`.
- `AutoScanActivity.kt` + `ScanResultActivity.kt` — `isScratchCopy()` + best-effort `tryDeleteOrigin()` (DocumentsContract/ContentResolver — works for file-manager MediaStore shares, fails for Telegram read-only grant); when copy deleted but original survives → honest msg `autoscan_copy_deleted_original_remains` (added to values/values-uz/values-ru) instead of "safe". ScanResultActivity.intent() got optional isCopy/originUri params.

**Still open:** local build impossible (RAM, see [[project_build_toolchain]] / [[reference_build_env_gotchas]]) → build via CI artifact `uzguard-debug-apk` then `adb install`, re-test share-from-Telegram → delete. The actual surviving test viruses (`toydanfotolar(9.jpg).apk`, padded-name variant, several `kiberqalqon-*-debug.apk`) are still on the phone in the Telegram sandbox. Related: [[project_device_bugfix_2026_06_17]] (the earlier File.delete()→FileDeleter ladder fix).
