---
name: project_perf_brand_2026_06_09
description: "2026-06-09 — overheating fix (adaptive poll, uncommitted) + brand/design direction (anor color, Anor-Qalqon logo, just-in-time perms)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b1660e0-d242-41e3-a8e2-38d8d53163cf
---

2026-06-09 session on "почему программа неудобна".

**Overheating fix (compiles; committed & pushed 2026-06-10, commit 3694c69):**
`ProtectionService.startFastScanLoop()` polled `findApkFiles()` (MediaStore query + recursive FS
walk) every **1s 24/7** → phone overheated. Changed to adaptive interval:
`POLL_INTERVAL_ACTIVE_MS=12s` (screen on) / `POLL_INTERVAL_IDLE_MS=90s` (screen off).
`compileDebugKotlin` = BUILD SUCCESSFUL. NOT tested on device.
Secondary heat sources left untouched: MultiPathFileObserver watches whole storage root recursively;
two parallel 15-min workers (GuardWorker + PeriodicCheckWorker); Telegram long-poll.

**Brand/design direction (decided):** drop the teal→green AV-cliché gradient. Firmennyy cvet =
**anor (pomegranate) `#C2143D`** + graphite `#1A1A1F` + safe-green only for status. Logo concept
**"Anor-Qalqon"** (shield with pomegranate seeds) is the chosen direction. Artifacts created:
`ApkGuard/brand/kiberqalqon_logo.svg`, `ApkGuard/brand/mockup.html` (2 screens). Planned redesign:
3 simple screens + **just-in-time permissions checklist** to replace the 7-step splash gauntlet.

**Marketing wedge:** "100% offline + privacy" — competitor Apk Qo'riqchi is just VirusTotal (uploads
files), can't claim this. Name-clash risk with gov bot **@Cyberqalqonbot** — must differentiate brand.

**Name FINAL (2026-06-12):** father (Dadajon) pushed for "Kiber Qalqon", user refused; the
@Cyberqalqonbot name-clash argument won — father approved **«Anor Qalqon»** stays. Backup names
from brainstorm if ever needed: Himo, Sher, Jigar, Qoplon, Safeme. Journal:
`Журнал/2026-06-12-выбор-имени-anor-qalqon.md`.

Full write-up in Obsidian: `Журнал/2026-06-09-peregrev-marketing-dizayn.md`. Related:
[[project_competitor_apk_qoriqchi]] [[feedback_pitch_presentation]] [[project_audit_remediation_2026_06_07]].
