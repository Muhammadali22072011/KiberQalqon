---
name: project_sandbox_delete_ux_2026_07_09
description: "Sandbox-virus deletion UX finalized — honest \"blocked/safe\" + simple Telegram-clear path + optional Shizuku; UzGuard 8.4 shipped"
metadata: 
  node_type: memory
  type: project
  originSessionId: 734a40eb-849c-4a3e-9b90-a15c31c93493
---

2026-07-09 session (RU voice, garbled): user pushed on the "Telegram virus won't delete / app lies it deleted" problem and on heat. Resolution of the long-running [[project_telegram_sandbox_limit_2026_06_18]] + [[project_delete_false_success_2026_06_18]] threads.

**Hard technical truth (confirmed, not worked around):** a file in another app's `/Android/data/<pkg>/` sandbox CANNOT be deleted by any normal app on Android 11+ — MANAGE_EXTERNAL_STORAGE explicitly excludes it, SAF OPEN_DOCUMENT_TREE picker blocks that folder since Android 13, only root or Shizuku (shell uid) reach it. This is Android law for ALL AVs, not a UzGuard bug. `/Android/media/<pkg>/` IS deletable with All-Files-Access (FileDeleter.sandboxOwner only matches `/Android/data/`, returns null for media). FileDeleter.kt already handles all this correctly (SandboxedByOwner result; sandbox check ABOVE !exists() so no false "deleted").

**What shipped this session (feat/anti-re-hardening, all CI GREEN, NOT device-tested):**
- `07c1211` (vc83/8.3): honest sandbox strings — title "🛡 Bloklandi: bu virusni o'rnatib bo'lmaydi", msg leads with "✅ Siz xavfsizsiz" (can't install, UzGuard blocks install) then explains Android limit. is_sandboxed_owner same.
- `9a46eaf` (vc84/8.4): **Path A = simplest zero-download delete.** For sandbox files the primary button is now "«Telegram»da o'chirish" (was "ochish"); on tap shows `sandboxed_open_owner_hint` Toast ("virusli XABARNI bosib turing → O'chirish — fayl ham o'chadi") then opens owner app. Deleting the Telegram MESSAGE removes the downloaded file — works on any phone, no download, no reboot hassle. Shizuku demoted to neutral button labeled "ilg'or foydalanuvchilar uchun" (optional/advanced). Edited: ScanResultActivity.openOwnerApp + AutoScanActivity.openOwnerApp (uses fully-qualified android.widget.Toast there) + strings + gradle bump.
- Earlier same session `db6bfb4` (Shizuku real-delete + heat, from parallel session) already CI-SUCCESS.

**Decision — Shizuku is NOT the answer for mass users:** bundling Shizuku APK removes only the "download" step, NOT the per-reboot wireless-debugging activation (impossible to automate without root/Device-Owner). So Path A (Telegram message/cache clear) is THE simple path; Shizuku stays optional for power users. Did NOT bundle Shizuku.

**Device state:** earlier this session the A56's 2 real viruses were deleted. UzGuard **8.4 installed on A56 (R5CY32375DH) and launch-verified clean** (pid alive, no FATAL, ShizukuProvider loads).

**IMPORTANT keystore finding (corrects earlier belief):** CI does NOT use a stable debug keystore — GitHub-runner generates a FRESH `~/.android/debug.keystore` per run, so EVERY CI APK has a different debug signature. Result: 8.2→8.3→8.4 each failed `adb install -r` with INSTALL_FAILED_UPDATE_INCOMPATIBLE and required `adb uninstall com.kiberqalqon.debug` first → **wipes UzGuard app settings each update** (user must redo first-run: perms, owner mode). Proposed permanent fix (not yet done): commit a fixed `debug.keystore` to repo + point debug signingConfig at it (debug keys aren't secret, pw "android"; can't sign Play) so all CI debug builds share one signature and `-r` works without wiping. Offered to user; awaiting go.

**MIUI verification (2026-07-09):** UzGuard 8.4 also installed + full-protection-verified on **Redmi Note 10 (M2101K7AG, cd643537, MIUI 14 / V140, Android 12)**. MIUI gotchas hit: (1) `adb install` → INSTALL_FAILED_USER_RESTRICTED — MIUI "Install via USB" toggle needs SIM + Mi-account; WORKAROUND = `adb push` APK to /sdcard/Download (use `export MSYS_NO_PATHCONV=1` or Git-Bash mangles the remote path) + user taps to install from file manager. (2) MIUI blocks even `adb shell pm grant POST_NOTIFICATIONS` (SecurityException, no GRANT_RUNTIME_PERMISSIONS) — but on Android 12 notifications post fine without the runtime perm anyway. `adb dumpsys deviceidle whitelist +pkg` and `appops set … MANAGE_EXTERNAL_STORAGE allow` DO work via shell. After user did in-app setup + MIUI autostart/popup toggles, dumpsys confirmed ALL live: ProtectionService (foreground, SYSTEM_ALLOW_LISTED), InstallShieldService (accessibility enabled), VpnFilterService, 3+ WorkManager jobs, and a completed scan (uzguard_scan/apk_scan_full notifications posted). Full protection works on MIUI.

Heat fixes from prior sessions also rode along (MEDIA_DIR_BLACKLIST in ApkScanner, quickDirSignature narrowing) — see [[project_overheat_diagnosis_2026_06_18]]. Branch still feat/anti-re-hardening, not merged to main.
