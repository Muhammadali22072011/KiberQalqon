---
name: project_vpn_bank_fix_2026_07_09
description: VPN broke many banks — real cause = bank anti-fraud VpnService detection; fixed by excluding bank apps from the VPN (addDisallowedApplication)
metadata: 
  node_type: memory
  type: project
  originSessionId: be26e84f-7ddb-4403-be12-25dbb909dca5
---

2026-07-09: user reported "ko'p banklar VPN (UzGuard C2 filter) yoqilganda ochilmaydi, brauzer ishlaydi". Fixed `ApkGuard/.../VpnFilterService.kt`.

**Root cause (most likely, per 4-agent adversarial review):** bank anti-fraud SDKs detect ANY active `VpnService` (`hasTransport(TRANSPORT_VPN)` / `tun0`) and refuse to open — regardless of what the DNS filter does. Matches the exact symptom (banks fail, browsing works). My first theory (hardcoded 8.8.8.8 vs split-horizon/geo bank DNS) was **rejected** by the review: `protect()`ed socket already egresses the real UZ IP + EDNS Client Subnet, so 8.8.8.8 gave geo-correct answers anyway.

**Fix shipped:**
- Bank/fintech/payment packages (`KnownBanks.ALL`) excluded via `builder.addDisallowedApplication(pkg)` (per-package try/catch — throws NameNotFoundException if not installed). Disallowed apps "use networking as if VPN wasn't running" → no TRANSPORT_VPN → not detected. C2 filter still covers all other apps.
- **Reverted** my own risky change: dynamic upstream DNS via ConnectivityManager NetworkCallback had a multi-network race (could store carrier-only DNS while socket egresses wifi → self-inflicted bank breakage). Back to static `UPSTREAM_DNS = [8.8.8.8,1.1.1.1,8.8.4.4]` + sequential retry.
- Narrowed allowlist to UZ bank `.uz` domains only; removed broad cloud zones (amazonaws/cloudfront/firebaseio/…) that were C2 blind spots.
- Added `setMtu(4096)` to match 4096 recv buffer.

**Follow-up (same day, commit 2ea2c7a pushed):** completeness check of `KnownBanks.ALL` (the VPN exclusion source) via a 7-agent Play-Store verification workflow found the list was BADLY stale — ~13 of 23 packages were guessed `uz.<name>.mobile` patterns that 404 on Play (so those banks were NEVER excluded), and ~20 major banks were missing entirely (NBU/Milliy `com.tune.milliy`, Ipoteka `com.bss.ipotekabank.retail.lite`, Xalq/Xazna `uz.tune.xazna`, Aloqabank/Zoomrad, Turonbank, Mikrokreditbank, Trustbank/Trastpay, Asia Alliance, OFB, Ziraat, Tenge, Octobank, Universal, Poytaxt, Alif, Zood, Iman, Beepul, Paylov, SQB/Joyda `com.uzpsb.olam`). Same staleness existed in `AppReputation.TRUSTED_EXACT` and `IconImpersonationDetector.PROTECTED_PACKAGES`. Fixed ADDITIVELY (kept old rows for legacy installs + to keep unit tests green; appended verified current packages) across all 3 files. Key corrections: Click `uz.click.evo`→`air.com.ssdsoftwaresolutions.clickuz`, Uzcard→`uz.uzcardpay.android`, Paynet→`uz.paynet.app`, TBC→`ge.space.app.uzbekistan`, Hamkorbank→`com.hamkorbank.mobile`, InfinBank→`uz.xsoft.myinfin`, Anorbank→`uz.anormobile.retail`, Asaka→`uz.asakabank.myasaka`, Mobiuz→`uz.mobiuz.mobiservice`, Smart Bank→`uz.smartbank` (now "Openbank UZ"), QQB→BRB `com.qqb.quant`. Note the Kapitalbank/Apelsin/Uzum merger: `uz.kapitalbank.android` is now the Uzum Bank app; legacy Kapitalbank retail = `uz.kapitalbank.kbonline`. VPN now excludes ~59 packages. Safe because TRUSTED_EXACT is signature-gated and BankAppAudit needs corroboration beyond label — a wrong pkg = lost coverage, not harm.

**Known residual limits (documented, not fixed):** raw `tun0` interface enumeration still visible to disallowed apps; DNS-over-TCP (TC=1 fallback) and DoT/DoH/QUIC not handled (filter bypass, not failure). Opt-in, default OFF; fail-open kill-switch unchanged.

**Status:** committed+pushed on `feat/anti-re-hardening` — but bundled into a PARALLEL SESSION's commit `db6bfb4` ("feat(delete): Shizuku …") which swept my working-tree file in (branch churn again — see [[project_ci_2026_06_17]]). Origin blob verified to contain all fix markers. CI GREEN (verified via GH API @db6bfb4: Cloud TS success + Android unit tests+debug build success). NOT device-tested. Local build still impossible (RAM — [[project_build_toolchain]], [[reference_build_env_gotchas]]). Related: [[project_security_wave_2026_06_11]] (original VPN C2 filter), [[project_link_interceptor_2026_06_12]].
