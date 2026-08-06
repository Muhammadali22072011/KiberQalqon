---
name: project-owner-mode-settings
description: "Settings rows (server URL, Telegram, admin panel) hidden behind owner mode — 7 taps on footer version; build-verified + committed 2026-06-11 (c5f8628)"
metadata: 
  node_type: memory
  type: project
  originSessionId: b1f2bf74-10c3-4b98-be6b-63df839bfe76
---

2026-06-11: User decided ordinary users must NOT see three Settings rows — server URL, Telegram telemetry, admin panel (owner-internal tools). Implemented: rows + dividers GONE by default; secret toggle = 7 consecutive taps on the version footer (tvSettingsFooter, >2.5s pause resets count) flips `Config.isOwnerUiEnabled` (`owner_ui_v1` pref) and shows/hides them with a toast (set_owner_mode_on/off in values, values-uz, values-ru).

Status: build-verified (assembleDebug green 2026-06-11) + committed in `c5f8628` (rode in with the [[project-anor-redesign-2026-06-10]] wave) + pushed to feat/anti-re-hardening. Behaviour itself not yet checked on a real device.
