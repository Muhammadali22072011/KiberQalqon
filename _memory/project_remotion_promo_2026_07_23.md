---
name: project_remotion_promo_2026_07_23
description: Remotion (React-code) promo video pipeline for UzGuard — separate from the emulator+ffmpeg demo pipeline
metadata: 
  node_type: memory
  type: project
  originSessionId: 5074f477-1729-4801-b7c2-ca78b6fe9a74
  modified: 2026-07-23T09:34:26.993Z
---

Code-based promo video via **Remotion** (React → mp4 through headless Chrome). Built 2026-07-23 on user request ("создай видео нашего проекта", wanted Remotion animated, "just to show").

**REBUILT via `remotion-dev/skills` method** (user asked "сделай видео со скиллом" — installed `npx skills add remotion-dev/skills` → gives remotion-create/markup/interactivity/render skills into repo `.claude/skills/remotion-*` AND `.agents/skills/remotion-*` w/ symlinks). Method = `create-video@latest --blank --no-tailwind` scaffold + Interactive markup best-practices: `Interactive.Div name="..."` (editable in Studio Visual Mode, edits write back to code), `interpolate()`+`Easing.bezier` over `spring()`, `scale`/`translate`/`rotate` CSS shorthands (not `transform`), named `<Sequence>`.

**Output:** `.demo_video/UZGUARD_promo_36s.mp4` (1080×1920 vertical, 36s, 1080 frames @30fps, ~6 MB).
**Source (reproducible):** `.demo_video/remotion_src/` = full create-video scaffold — `src/Video.tsx` (all 7 scenes + palette `C` + durations `D`), `src/Composition.tsx` (registers id `UzGuard`), `Root.tsx`, `index.ts`, `remotion.config.ts`, `public/screens/*` (from `pitch/assets/`), README. remotion 4.0.496. node_modules NOT committed (.gitignore).

**7 scenes:** intro logo → problem (fake RASMLAR(18).apk) → 9-layer scan ring 0→92% → XAVFLI detect (banker screen) → feature grid → cloud-panel scale → outro @zimdevuz. All Uzbek text. Brand: teal #2fe3d0 + anor #e23d4d on near-black, Segoe UI fallback (Onest not bundled).

**Re-render:** `cd .demo_video/remotion_src && npm install && npx remotion render UzGuard out/uzguard.mp4`. `npm run dev` = Remotion Studio (Visual Mode editor). NO `render` npm script in scaffold — use `npx remotion render`. Remotion auto-downloads its own headless-shell (~100MB) first run — needs net.

**Gotchas:** built in scratchpad (fast disk) to keep node_modules OUT of OneDrive sync, then copied source+mp4 into repo. ffmpeg NOT on PATH (use `npx remotion still <comp> --frame=N` for frame previews). Node 24 / npm 11. Skill install added many files to repo tree (`.claude/skills/`, `.agents/skills/`) — candidate for .gitignore.

Different from [[project_demo_video_2026_06_12]] (that = real emulator screen-record + ffmpeg + uz TTS). This one = pure animation, no device, no voiceover.
