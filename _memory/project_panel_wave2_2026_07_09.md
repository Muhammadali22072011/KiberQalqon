---
name: project_panel_wave2_2026_07_09
description: "Cloud panel feature wave 2 (2026-07-09) — 11 features across panel+API+SQL+Kotlin, committed 2c696ab, CI green"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0864a9a0-cad2-4517-8886-bb78d18a618f
---

Cloud panel (ApkGuard/cloud, uzguard-cloud.vercel.app) 2-to'lqin — committed+pushed **2c696ab** on feat/anti-re-hardening 2026-07-09, **CI GREEN both jobs** (cloud tsc + Android unit+APK). Verified: local `tsc` (api+src) clean + `vite build` green; Kotlin verified via CI (local build impossible — RAM, see [[project_build_toolchain]]). **NOT device-tested.** Migration 16_panel_wave2: run 2026-07-09, re-verified 2026-07-13 via information_schema self-check (threats.family/review_status, device_commands, alert_state, corroborated_threats fn, both indexes — all true).

Hard constraint honored: **12/12 Vercel Hobby functions = at limit** → NO new api/*.ts files; every new backend capability is a `?param` branch in an existing handler (like `stats?perf=1`).

Wave 1 (earlier same turn, folded into same session): #1 Overview 14-day trend chart (`stats?series=1`, SVG hand-drawn, no chart lib), #12 health strip, #2 device drilldown verdict breakdown, #4 search+filter (Devices/Threats), #7 bulk IOC import, #8 version distribution card.

Wave 2 (this commit):
- **#5 sample review queue** — threats.ts POST `set_review` (owner) → `review_status` pending/confirmed/dismissed; dismissed EXCLUDED from signed feed. Panel `ReviewQueue` on Threats (owner).
- **#6 campaign/family tags** — threats.ts POST `set_family`; feed `f` = family||category; family tag+search in Threats.
- **#9 DANGER spike alert** — upload.ts counts danger scans in window (env SPIKE_WINDOW_MIN/MIN/THROTTLE_MIN), throttled via `alert_state` table, Telegram to admins (per-threat alert already existed).
- **#3 remote command channel** — `device_commands` table + `/api/device/poll` (device auth via x-device-secret+x-device-token, **at-most-once**: poll marks done→no infinite rescan loop) + enqueue POST (owner). Kotlin `CloudTelemetry.pollCommands` called from App.onCreate (immediate on app-open) + HeartbeatWorker (~6h passive). **NO new frequent loop** (avoid overheat regression per [[project_overheat_diagnosis_2026_06_18]]). Only type = `rescan` → GuardWorker unique work. Latency: app-open immediate, else ≤6h.
- **#10 map heatmap** — MapPage `Circle` translucent weighted blobs toggle, no new dep.

**DONE 2026-07-09 (LIVE):** owner ran migration 16 in Supabase (Success). Fixed a migration bug first: `corroborated_threats` return type changed 5→6 cols → needs `drop function` before create-or-replace (committed f5f00d4). Then deployed prod via `npx vercel@latest deploy --prod` (dpl_Aknwg…, aliased kiberqalqon-cloud.vercel.app). Verified live: `/`=200, `/api/stats?public=1` ok (15 dev/92 scan/58 blocked, Navoiy pilot 8dev/4danger), `/api/threats?feed=1` no-secret=401 (not 500 → RPC+threats.ts deploy clean). Authed features (trend/review/family/commands/heatmap) not owner-token-verified — owner checks in UI.

**KEY DEPLOY FACTS (learned this session):** prod domain is **kiberqalqon-cloud.vercel.app** (NOT uzguard-cloud). Vercel project prj_DyJXftrFClChtCwyq3ypbztDIGfK / team_lHzRLyOVfskCHM8AG66VxwR7, account muhammadali22072011 (logged in on this machine). **git push does NOT auto-deploy** — prod deploy is MANUAL `npx vercel@latest deploy --prod` (or ApkGuard/cloud/deploy-prod.bat); all deploys show gitDirty:1 = local-CLI from working tree. Prod had drifted ~3 weeks behind branch before this deploy. `vercel deploy --prod` ships the WORKING TREE (uncommitted changes included).

**Branded group QR (2026-07-09):** replaced plain b/w QR in Groups.tsx QrModal with a branded one (lib/qr.ts is a self-contained Nayuki port, no external lib) — ECC 'H', rounded dark modules (#17100F on white — white bg kept for scan reliability), colored finder "eyes" + centre shield-check logo tinted from `darken(group.color,0.82)`, dark brand modal (was `var(--surface,#fff)` → white box clashing) via new `.qr-modal` CSS. Build green, deployed prod (73cx4dvqn). Groups.tsx + the `.qr-modal` styles.css block are UNCOMMITTED (parallel session owns Groups.tsx) — they should commit together. NOT phone-scan-tested (can't verify scannability without a camera; safeguards = ECC H + white bg + high-contrast modules + logo ≤5% area).

**Vercel deploy warning:** `npx vercel deploy --prod` warns "Node.js 20.x is deprecated — deployments on/after 2026-10-01 will fail" — package.json has `"engines":{"node":"20.x"}`, project setting is 24.x. Bump package.json engines to 24.x before Oct 2026 or prod deploys break.

NB the prod deploy also carried a **parallel session's UNCOMMITTED groups/join feature** (device_groups table, v_group_members view, /api/devices ?groups/?members + POST create/delete_group, GroupJoinActivity). It compiles + is dormant: core Devices list uses v_devices_with_counts select('*') (unaffected); group branches 500 on device_groups/v_group_members (tables don't exist in live DB yet) but isolated (don't break core pages). Their groups SQL migration is NOT yet run.

NB: a **parallel session** landed a "groups/join" feature (device_commands sibling) into the SAME files (api/devices.ts groups POST, device/[id].ts handleJoin, api.ts GroupRow/GroupMember, Devices.tsx group filter) — co-exists cleanly with this wave; their devices.ts was NOT part of 2c696ab (left uncommitted for them).
