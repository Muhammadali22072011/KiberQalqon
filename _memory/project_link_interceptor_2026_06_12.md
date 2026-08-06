---
name: project_link_interceptor_2026_06_12
description: "Havola qalqoni — automatic link interceptor + block page (logo instead of malicious site); built green 2026-06-12, not committed/device-tested"
metadata: 
  node_type: memory
  type: project
  originSessionId: 064ab739-53a9-420e-8c19-623e6c868cbe
---

2026-06-12: Added **Havola qalqoni** — automatic link interceptor so the user doesn't paste
links by hand. Wires the existing offline brain [[project_feature_wave_2026_06_11]] (LinkScanner)
to a system-level link handler + a full-screen block page.

**New files:**
- `LinkGuardActivity.kt` — translucent, exported, plain `Activity` (NOT AppCompat — translucent
  crash, same reason as ShareUrlReceiverActivity). intent-filter http/https VIEW + BROWSABLE.
  Runs `LinkScanner.analyze`; SAFE → forward silently; DANGER/SUSPICIOUS → launch block page.
  Feature OFF → pure pass-through. Uses framework AlertDialog for browser picker.
- `LinkBlockActivity.kt` + `res/layout/activity_link_block.xml` — full-screen dark block page:
  kq_logo_anor + badge (kq4_circle_danger tinted danger/warn) + "Bu xavfli/shubhali sayt" +
  host + reasons (inc_kq_perm_row) + buttons. **By verdict** (user's choice): DANGER = hard
  block, only "Yopish"; SUSPICIOUS = "Yopish" + "Baribir ochaman" (forwards).
- `LinkForwarder.kt` — discovers browsers (excludes self via QUERY_ALL_PACKAGES), resolves a
  silent target (preferred → system default → single), opens URL with explicit setPackage so it
  never loops back to us. Stores choice in Config.

**Config:** `isLinkGuardEnabled` (default **true**), `getPreferredBrowser`/`setPreferredBrowser`.
**Settings:** new `rowLinkGuard` toggle (ic4_link) + setup dialog → opens
`MANAGE_DEFAULT_APPS_SETTINGS` (fallback app details). Strings in strings_kq4_link.xml +
strings_kq4_set_extra.xml. Manifest: both activities registered.

**Auto-surface ("сразу включается"):** user asked it to just turn on. Android forbids silently
making yourself the default link handler (security) — always needs one user tap in a system
dialog. Best achievable: added a row to `ProtectionStatusActivity` (the first-run setup checklist
shown Splash→Consent→ProtectionStatus) — "Havola qalqoni" shows ✓/✗ via
`LinkForwarder.isDefaultLinkHandler()` with a one-tap "Yoqish" → `openDefaultApps()`. Non-critical
(doesn't gate onboarding, since Telegram in-app browser bypasses anyway). Feature flag already
default-on, so the row appears on first launch automatically.

**Hard Android limitation (told user):** only catches links routed through the Android intent
system. Requires user to set KiberQalqon as default link/browser handler. **Telegram's in-app
browser bypasses it** unless the user switches Telegram to "open links in external browser." The
VpnFilterService (DNS sinkhole) is the complementary silent always-block path that DOES cover
in-app browsers + trojan C2 traffic.

**VPN in checklist too (same session):** user wanted ALL protection to come up at once. Added
"Internet himoyasi" row to ProtectionStatusActivity (ic4_wifi, non-critical): state =
`isVpnFilterEnabled && prepareIntent()==null`; fix = one-tap `enableVpn()` → VpnService.prepare
consent via new `vpnLauncher` → setVpnFilterEnabled(true)+start. After that single consent,
App.onCreate:151 auto-restarts the VPN every process start (try/catch fail-soft for background
starts). No overheating risk: VPN is DNS-only (/32 route), LinkGuard is event-driven,
ProtectionService already adaptive-poll.

**Status:** `assembleDebug` BUILD SUCCESSFUL (green) 2026-06-12, three times (base feature,
+linkguard checklist row, +VPN checklist row). NOT committed, NOT pushed, NOT device-tested.

**UPDATE 2026-07-09 — false "brauzer topilmadi" fix (committed 008e5c0, CI-green, DEVICE-TESTED).**
User reported: safe site sometimes says "Qurilmada brauzer topilmadi" even though Chrome is
installed. Root cause: `LinkGuardActivity.forwardOrPick()` (the SAFE path) showed the
`kq4_link_no_browser` toast whenever `browserOptions()` (= `queryIntentActivities`) returned EMPTY
— which it can on Android 11+ pkg-visibility / WebView-only / when UzGuard itself is the default
handler, even with Chrome present. The sibling `LinkBlockActivity.openAnyway()` already fell back
to `LinkForwarder.openSystemChooser()` (system resolver ALWAYS sees browsers); the safe path did
not. Fix: mirror the sibling — empty list → `openSystemChooser`, single browser → direct-open with
chooser fallback, toast only if the chooser itself fails. ("always opens Chrome" = correct silent
behavior via saved/default target — not a bug.) Device-verified on A56 (R5CY32375DH): fired safe
link at the activity, Chrome came to foreground, no toast, no crash, no loop. Empty-list branch
couldn't be force-reproduced (device has Chrome+Samsung) but it's a strict-safe mirror. Install
needed uninstall-first (CI debug key ≠ local) — see [[reference_build_env_gotchas]].
