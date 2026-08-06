---
name: project_autoscan_window_fix_2026_06_16
description: "Why the auto-scan popup (AutoScanActivity) didn't appear on Android 14+/MIUI but worked on old Samsung J4 — root causes + the fix."
metadata: 
  node_type: memory
  type: project
  originSessionId: d8642ac8-e2c6-4b06-a615-9b050ed86da0
---

2026-06-16: Fixed the reported bug "avto-okno ne vyhodit" — the full-screen `AutoScanActivity`
popup that should auto-appear when an APK arrives worked on **Samsung J4 (Android 8/9, API 26-28)**
but NOT on **Samsung A56 (Android 14/15, API 34+)** nor **Xiaomi Redmi (MIUI/HyperOS)**.

**Root cause (verified, multi-agent):** the window is launched from background
(ImprovedApkFileObserver / ProtectionService poll / GuardWorker) and on new devices BOTH paths
were broken in ways that don't exist on API ≤28:
1. **Android 10+ BAL** — background `startActivity` needs the `SYSTEM_ALERT_WINDOW` (overlay)
   exemption; on a fresh install overlay isn't granted → direct launch silently blocked. (J4 = API
   ≤28 has no BAL → direct launch just works. That's the whole asymmetry.)
2. **Android 14 `USE_FULL_SCREEN_INTENT`** (the A56 primary) — since API 34 this is no longer
   auto-granted to non-calling/non-alarm apps. The notification fallback called
   `setFullScreenIntent(pi, true)` unconditionally → OS silently demotes it to a ~60s heads-up,
   the Activity never auto-launches. App never checked `NotificationManager.canUseFullScreenIntent()`.
3. **MIUI/HyperOS** (the Redmi primary) — `canDrawOverlays()==true` is NOT enough; MIUI needs the
   separate, off-by-default "Display pop-up windows while running in background" toggle. The code
   for it existed in `OemAutostartGuide` but was never wired into the setup gate.
4. **`POST_NOTIFICATIONS` never requested at runtime** — Splash requests only storage; the gate's
   notif row only deep-linked to Settings, never fired the runtime dialog → on Android 13+ even the
   degraded heads-up was a silent no-op.

**Fix (build-verified `assembleDebug` OK; NOT device-tested):**
- `VersionCompat.canUseFullScreenIntent(ctx)` (true on <API34; else NotificationManager API on
  `applicationContext`).
- `NotificationHelper` — all 5 `setFullScreenIntent(..., true)` → `..., canUseFullScreenIntent(context)`
  (kept PRIORITY_MAX/CATEGORY_ALARM so it's still a loud heads-up when FSI ungranted).
- `ProtectionStatusActivity` — new FSI row (API34+, → `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) +
  MIUI "fonda popup oynasi" row (wired the existing `OemAutostartGuide.openOemAppPermissions`) +
  notif row now fires a real runtime `POST_NOTIFICATIONS` request (mirrors the location pattern).
  **Gate strictness (per owner, 2026-06-16):** owner wanted permissions MANDATORY ("без них не
  защищает"). Implemented safely: hard-block (`allCriticalPermissionsGranted`, no escape) stays
  files+notif+background (always grantable; also used by Splash routing → must NOT add ungrantable
  perms or it loops). Overlay+FSI = `allWindowPermsGranted()` — Continue button looks locked until
  granted and shows a WARNING dialog ("avto-oyna o'chirilgan" → Yoqish / Baribir davom etish) so
  the rare ROM that can't grant them isn't bricked. Battery + MIUI-popup stay non-blocking (Samsung
  reports battery false even when Unrestricted → would lock out A56; MIUI popup toggle is NOT
  machine-readable). Did NOT do pure hard-block on everything (would brick Samsung/Xiaomi onboarding).
- `ImprovedApkFileObserver` — added screen interactive+unlocked gate before direct launch (locked →
  notification), aligning it with GuardWorker/ProtectionService (MIUI-safe).
- Localized all hardcoded-Uzbek notification strings in `NotificationHelper` (quar / secblock /
  a11y-threat / notif-access-threat) → `kq4_notif_*` keys in `values/` + `values-ru/`
  strings_kq4_quarantine.xml (was the spawned side-task task_d2d2edee, done same session).

On branch `feat/anti-re-hardening`; **NOT committed/pushed** (user didn't ask). Relates to
[[project_perf_brand_2026_06_09]] (just-in-time perms) and [[project_audit_2026_06_10]].
