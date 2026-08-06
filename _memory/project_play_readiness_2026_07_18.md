---
name: project_play_readiness_2026_07_18
description: "Google Play submission audit — 7 blockers, needs a dedicated 'play' product flavor; dossier committed under play/"
metadata: 
  node_type: memory
  type: project
  originSessionId: 989e8632-573f-4514-aec6-3351c70225d0
---

2026-07-18: multi-agent Play-readiness audit (9-agent Workflow). Deliverables committed under `play/` (commit 0f9fff6, pushed): `PLAY_SUBMISSION.md` (full dossier), `privacy_policy.html` (hostable, uz+ru), `store_listing_uz/ru.txt`, `data_safety_content_rating.md`. Public contact = Telegram @zimdevuz only (see [[feedback_public_contact]]).

**Verdict: NOT submittable as-is.** 7 ranked blockers:
1. Emulator self-kill — `App.kt:98-105` killProcess+exitProcess when `SecurityGuard.runAllChecks()` trips; `SecurityGuard.kt:84-94` list has `isEmulator()` (line 92) fatal → Play review/Pre-Launch-Report run on emulators → auto-reject.
2. **Signature check boot-loops on ALL Play installs** — Play App Signing re-signs the AAB with Google's key; only sideload cert `1cb3f378…` whitelisted (`SecurityGuard.kt:44-52`, `build.gradle.kts:127`) → `isSignatureInvalid()` true on-device → kill every launch. Must make sig non-fatal until Google's Play cert is whitelisted post-first-upload.
3. Self-update installs own APK (`SelfUpdate.kt:109-163`) — violates Device&Network Abuse (no out-of-Play code delivery). Must disable in Play build.
4. Accessibility auto-cancel + `isAccessibilityTool="true"` misdeclaration (`InstallShieldService`, accessibility_service_config.xml:16). Strip from Play build.
5. Notification-listener reads all notifications (`PhishingNotificationService`). Remove from Play build.
6. MANAGE_EXTERNAL_STORAGE (All-files) — needs Console declaration + demo video; drop redundant READ_MEDIA_* (manifest:23-25).
7. Missing 1024×500 feature graphic + hosted privacy-policy URL. [feature graphic + screenshots now DONE — see below]

**ASSETS DONE** (branch feat/play-flavor, commits d57a87e + e6d3802): `play/feature_graphic.png` (1024×500) + `play/screenshot_1..4.png` (1080×1920, 9:16, RGB) — generated with Pillow (dark-navy brand gradient + blue/green glows + Onest type + real UG-shield logo/app screens), all on Desktop too. Screenshots framed from clean `.demo_video/caps/` (dash/scan2/prot_status/scan_safe; skipped old-brand `xavfli` + ANR-dialog `autoscan1`). Icon 512 = playstore_512.png (blue "M"; full UG shield = design_v4_extracted/logo_v4.png). Play asset package now COMPLETE; only owner-account steps remain (host privacy, upload AAB, declarations, submit, post-upload cert whitelist).

**UPDATE — `play` flavor IMPLEMENTED + verified** (branch `feat/play-flavor`, commit 20c4949, pushed; live self-update branch feat/anti-re-hardening untouched). Added `dist` flavor dim (direct=default | play). play BuildConfig HARD_KILL/SELF_UPDATE/INSTALL_SHIELD/NOTIF_LISTENER=false. Edits: App.kt (kill gated by HARD_KILL), SelfUpdate.kt (checkAndNotify/downloadVerifyInstall no-op), Config.kt (install-shield/phishing gated), accessibility_service_config.xml (dropped isAccessibilityTool), NEW src/play/AndroidManifest.xml (tools:node=remove InstallShieldService + PhishingNotificationService + READ_MEDIA_*), ci.yml (tasks → direct variant). VERIFIED locally: `bundlePlayReleasefast` BUILD SUCCESSFUL 8m46s; merged play manifest = 0 InstallShield/Phishing <service>, 0 READ_MEDIA, BIND_ACCESSIBILITY/NOTIFICATION gone, MANAGE_EXTERNAL_STORAGE/ProtectionService/QUERY_ALL_PACKAGES retained. Play AAB on Desktop `UzGuard-PLAY-8.7-87.aab` (9.1MB). Build cmd: `./gradlew bundlePlayReleasefast` (sideload now `assembleDirectReleasefast`). NOT device-tested. STILL owner-only: Play Console acct ($25) + ~9 declarations (in play/PLAY_SUBMISSION.md §3) + 1024×500 feature graphic + host play/privacy_policy.html + submit to internal track first; after 1st upload, whitelist Google's Play-signing cert in SecurityGuard.kt+build.gradle before re-enabling sig check.

ORIGINAL PLAN (now done): **a dedicated `play` product flavor** (dist dimension: play/direct) gating INSTALL_SHIELD/SELF_UPDATE/NOTIF_LISTENER/HARD_KILL off for Play; build `bundlePlayRelease`. **CAUTION: adding a flavor dimension RENAMES every Gradle variant** (assembleReleasefast → assembleDirectReleasefast etc.) → breaks the live CI + self-update build commands; deliberate device-tested work, NOT a blind change — DEFERRED. Also needs owner's Play Console account ($25) + ~9 declarations; only owner can submit. The AAB built this session (`UzGuard-8.7-87.aab` on Desktop, releasefast, all features, hard-kill ON) is the CURRENT-features build — fine for internal-testing/sideload, NOT the final Play artifact. See [[project_anti_re_hardening]] (self-kill is deliberate).
