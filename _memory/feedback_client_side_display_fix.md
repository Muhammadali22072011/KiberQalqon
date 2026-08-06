---
name: client-side-display-fix
description: "For KiberQalqon display bugs where the panel already holds the source data (e.g. GPS coords), fix on the client side so EXISTING records correct on reload — don't rely on a server-only fix."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 7e581de2-12a0-477e-a697-6a1b13fa4b90
---

When a value shown on the cloud panel is wrong but the underlying raw data (coordinates, timestamps, etc.) the panel already receives is correct, fix the derivation **on the panel (client) side**, not only server-side.

**Why:** This session the map showed an IP-derived city name ("Tashkent") even though the device dot was correctly at Navoiy (the lat/lng were right). A server-only fix (`cloud/lib/geo.ts` deriving city from GPS) did NOT visibly change existing device rows — those rows were already stored with the stale "Tashkent" name and only refresh when the phone re-sends (12h register throttle / next scan). The user kept seeing "Tashkent" after the first deploy and got frustrated ("серавно так", "ну исправь"). The thing that actually fixed it instantly was adding the same nearest-city lookup to the SPA (`src/lib/uzRegions.ts` `nearestCity()`, used in `MapPage`, `Devices`, `exportExcel`) so the panel names the city from the stored coordinates on every load — all existing devices corrected on a hard refresh, no phone action.

**How to apply:** Do BOTH — fix the server so newly-ingested data is stored correctly, AND fix the client so already-stored rows display correctly without waiting for re-ingestion. Reserve server-only fixes for cases where the stored raw data itself is wrong and must be rewritten. Note the user expects fixes to produce an *immediate visible* result; a fix that "works but needs the device to re-send first" reads as not-fixed. See [[vercel-deploy]] (each prod deploy needs an explicit "да, деплой"; auto-mode blocks otherwise).
