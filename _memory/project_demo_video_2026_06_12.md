---
name: project-demo-video-2026-06-12
description: Automated demo video pipeline (emulator + ffmpeg motion montage with Uzbek TTS) — final MP4 on Desktop; how to rebuild
metadata: 
  node_type: memory
  type: project
  originSessionId: 99a7103c-15d7-4df6-af9a-0706ce83fb62
---

2026-06-12: built fully automated demo video of Anor Qalqon catching Rasmlar_9.apk (dad asked for a phone video; user wanted motion design + voiceover + story, rejected the plain v1 cut as "laggy/cheap").

**Final (v3):** `Desktop/ANOR_QALQON_demo.mp4` (56s, 1080×1920@30, 12.8MB; same file in `.demo_video/ANOR_QALQON_demo_v3.mp4`). Story: kinetic hook (word-slam "TELEFONINGIZGA / VIRUS KELSA-CHI?" + rgbashift glitch + shake) → Telegram scene in phone mockup (typing dots → file bubble slides in + ding → punch-zoom into bubble) → problem card → hero ("ANOR QALQON o'rnatilgan": card slam + REAL footage of dock icon tap → red splash → dashboard, rec_e.webm) → detection (jump-cut over the white-flash bug, riser→boom verdict, alarm, auto-delete + success) → result (blocked list) → 4-beat features section with screenshots + slide-down text strips (INTERNETSIZ ISHLAYDI / HAVOLA va QR / SOXTA BANK / BEPUL!), voice lists afzalliklar → outro with CTA "Hoziroq o'rnating!". Beat 110bpm enters after hook (stream_loop), 8 booms, 2 whooshes, riser, alarm, 2 success chimes — all numpy-synthesized.

**Pipeline (all in `.demo_video/`, rerunnable):** gen_cards/gen_tg/gen_assets2/3/4.ps1 (GDI+ PNGs, Onest fonts from app res; phone_frame.png = alpha bezel for 600×1334 screen), gen_voice3.py (edge-tts `uz-UZ-SardorNeural`, per-line rate/pitch prosody), gen_sfx.py + gen_music.py, record_host.sh, build_v3a.sh + final concat list in pieces3/ + 26-input amix command (in transcript; voice chain = highpass 80 → presence EQ 3.2k → acompressor → vol 1.55). Useful stills: cards2/scr_link.png = Havolani tekshirish red verdict for `anorbank-bonus.top` (typed via adb input text); cards2/scr_dash.png, scr_bank.png from rec_d3.

**Hard-won gotchas:**
- AVD `kq_demo` (android-34 google_apis x86_64; image now installed in C:\Android). Emulator GUI crashes → run `-no-window -gpu angle_indirect`; swiftshader = ANR hell.
- **Guest screenrecord is broken** on this AVD (exit 235, 0-byte) → record HOST-side: `adb emu screenrecord start --time-limit N <host.webm>` (VP9 1080×2400@24).
- git-bash mangles `/sdcard/...` → `MSYS_NO_PATHCONV=1`.
- appops grants (MANAGE_EXTERNAL_STORAGE) reset on reinstall — re-grant after every `adb install`.
- AutoScanActivity popup has ~1.3s WHITE flash (4.9–7.2s after push) before dark scan UI renders — cut it in editing (jump-cut home→scan). Verdict ~8s, auto-delete ~13s, auto-close ~17s after push+3s offset. Could be a real UX bug worth fixing ([[project-audit-2026-06-10]]).
- App flow on emulator: language → terms (2 required checkboxes; do NOT tick the optional cloud-sharing one — keeps junk devices out of the live panel) → onboarding skip → initial scan (~3min) → ProtectionStatusActivity checklist → dashboard.
- winget absent on this machine; ffmpeg = gyan.dev static zip; edge-tts rate must be `+0%` not `0%`.
