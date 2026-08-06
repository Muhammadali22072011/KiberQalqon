---
name: project_panel_anor_wave_2026_06_12
description: "Cloud panel anor-brand redesign + audit log page + threat-domains manager + update card; committed 1ecf63e, deployed+verified live 2026-06-12"
metadata: 
  node_type: memory
  type: project
  originSessionId: 064ab739-53a9-420e-8c19-623e6c868cbe
---

2026-06-12 (user: "пора обновить облако/сайт — дизайн, функции, убрать лишнее"): cloud panel wave.

**Design re-theme (src/styles.css):** neon-teal dark → **anor brand warm-dark** matching app v4:
bg #0f0a0d family, --primary #e0506b / --primary-2 #c2143d, saffron secondary glow rgba(240,162,60),
**Onest font self-hosted** in public/fonts/*.ttf (copied from app res/font; Google Fonts BLOCKED by
strict CSP font-src 'self' — keep self-hosting). Teal #25e0b0 kept ONLY as semantic safe color
(`--ok`/`--ok-dim` tokens): verdict colors, SEV_COLOR low, kq-dot-safe, note.ok, duel "our scanner",
health gauge green. Chrome literals swapped via sed; .btn/.gate-tab.active text now #fff.

**New panel functions:**
- `/app/audit` page (src/pages/Audit.tsx) — owner-only nav item (`ownerOnly` flag in Layout NAV
  filter), reads /api/stats?audit=1, ACTION_UZ labels incl. domain_add/domain_del.
- **Domain blacklist manager** in Threats page (DomainsPanel): GET /api/threats?domains=1 (panel
  read), POST /api/threats {action:add_domain|delete_domain} — OWNER-only (checkAdminSecret),
  normalizeDomain validation, bank/gov allowlist guard (isNeverFeedDomain), source:'owner',
  audit-logged. Added domain reaches all phones via signed feed (LinkScanner + VPN filter).
- **App-update card** in Profile (UpdateCard): GET /api/config?app=1 (panel-only) shows UPDATE_*
  env status or "sozlanmagan" hint.

**Removed:** _legacy/index.html (56KB dead pre-Vite panel) — user authorized junk removal.

**Verified:** tsc clean, vite build OK, local preview screenshot (anor theme + Onest render,
no console errors), prod deployed (kiberqalqon-cloud-ll7nqz1q6), live smoke: panel 200, font 200,
domains GET/config?app=1/audit → 401 unauth, threats POST → 403 "faqat egasi". Committed+pushed
**1ecf63e** (17 files, +403/−1055).

Preview note: .claude/launch.json has "cloud-panel" config = python http.server on cloud/dist
(static; API calls fail gracefully — fine for visual checks; rebuild dist first).

**Second pass same day (user: "дизайн как прям в приложении"):** panel flipped from warm-DARK to
the app's actual **LIGHT warm-cream v4 theme** (commit ffd6fa3, deployed). Tokens taken verbatim
from app res/values/colors.xml kq_*: bg #F6F3EE, surface #FFFDFA/#F4EFE7, ink #20160F/#685C52/
#9A8D82, hairlines #E9E2D6/#D9CFBF, anor #C52A3E (+soft #F8E0E3, deep #9E1F30), statuses safe
#1A9E54/#E1F4E7/#0C6135, warn #DF8A18/#FBECD2/#84500C, danger #E0432F/#FBE0DB/#911C10, feruz
#1F9489, zafaron #C5871F. format.ts VERDICT_BG/FG/DOT + SEV_COLOR now app palette; riskColor
hsl lightness 55%→40%. White-alpha hover/hairline literals → ink-alpha rgba(32,22,15,.0X); dark
glass (topbar/fs-feed/car-nav/modals) → cream glass; carousel text stays WHITE over photo shade
(explicit #fff). Leaflet bg #EFE9E0. Verified via preview_inspect computed styles (screenshot
tool was timing out — snapshot+inspect used instead).
