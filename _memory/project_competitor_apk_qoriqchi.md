---
name: project-competitor-apk-qoriqchi
description: "Real Google Play competitor to KiberQalqon — \"Apk Qo'riqchi\" (com.baxtiyorov.security); facts + how it compares."
metadata: 
  node_type: memory
  type: project
  originSessionId: 2b4ceb87-41ec-4f49-a293-52d5dfd21ae0
---

**Apk Qo'riqchi** (`com.baxtiyorov.security`, Google Play) is a REAL direct competitor to
KiberQalqon — an Uzbek APK security scanner. Unlike SamCyber102 (no data — see
[[project-kiberqalqon-pitch]]), this one has real, citable data, so it's a usable benchmark on
the pitch "competitors" slide.

Facts (researched 2026-06-03):
- Developer **Baxtiyorov Dev** — solo Uzbek dev (likely Islom Baxtiyorov,
  islombaxtiyorov827@gmail.com, privacy on a Google Sites page), **only 1 app published**.
- Free, Tools category, ~7.8 MB, Android 7.0+, v1.2.8 (upd 2026-06-02).
- **Rating 4.6★ (confirmed** via dev-profile page). Installs **50k–100k — sources conflict,
  NOT confirmed**; rating count / ads / IAP **unconfirmed** — don't cite these as fact.
- Markets "No data collection / all local."

What it does (from listing + permissions, NOT from its code): installed-apps + install-source
overview; "dangerous permissions" list; scans Downloads/messenger folders for APKs; scan+remove
APKs; an **AccessibilityService "Installation Blocker"** that auto-taps "Cancel" on the system
install screen (off by default). Permissions: MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES,
accessibility, foreground-service. → It's essentially a **permission-auditor + install-canceller**,
not a deep static-analysis engine.

Positioning vs KiberQalqon: KiberQalqon wins hard on tech depth (~13 analyzers, malware-family
signatures, ZIP-encryption evasion, icon-impersonation, DEX/dropper analysis, quarantine,
self-defense, Telegram panel, cloud threat-map — all built on real Uzbek-trojan RE). Competitor's
only real edges = **it's already on Google Play with traction** and a slick modern UX. So the gap to
close is **distribution (publish to Play)** + **UX**, NOT features.

## RE of their actual APK 1.2.8 (decompiled 2026-06-03, jadx → `_competitor/jadx_out`; APK SHA-256 c9d34cb8…326290)
**Their malware verdict is 100% VirusTotal.** Confirmed in code: detection backbone = VirusTotal v3
API (`NetworkModule.java:81` base `https://www.virustotal.com/api/v3/`); they SHA-256 the APK
(`p5/m.java`), `GET /files/{hash}`, and **if VT doesn't know the hash they POST /files = upload the
WHOLE APK file** to VirusTotal (US 3rd party). VT API keys hidden in native lib `native-lib`,
rotated via `VirusTotalNative.getToken(index)/getTokenCount()` to dodge free-tier limits (4/min,
500/day; interceptor handles 429 "Kunlik limit tugadi"). NO own backend (LoginRequest/Response =
dead code; only URL in the APK is virustotal.com). NO local engine — grep-proven absence of: hash
blacklist, DEX parsing, ZIP/encryption inspection, icon perceptual-hash, entropy, native-.so scan.
They DO have shallow local triage: a permission-COUNT(12)×install-source risk matrix
(`InstallDetectorReceiver.a()` → SAFE/LOW/MED/HIGH), a video/wedding filename-lure regex + a textual
typosquat/gibberish-package detector (`p5/m.java`), a 35-store trusted-installer allowlist, and the
headline **accessibility install-blocker** (`InstallBlockAccessibilityService` auto-clicks Cancel on
the system installer, Telegram-APK-targeted, heavy keep-alive workers). Stack is MODERN: Compose +
Hilt + Room + Retrofit/Moshi + WorkManager, wrapped in PairIP (Play license/integrity). Brand =
**"SurxonCyber" / by Baxtiyorov Islom (Surxondaryo)** — note: different region from KiberQalqon's
Navoiy pilot.

**Three proven weaknesses to hammer in the pitch:** (1) **no offline** — no internet = no verdict;
(2) **VirusTotal ToS violation + fragility** — public API banned in commercial apps; rotated embedded
keys can all be banned → core dies; (3) **privacy contradiction** — uploads users' full APKs to
VirusTotal while marketing "No data collection / all local" → a Google Play Data-safety
misrepresentation. Plus: VT hash-lookup is **blind to a fresh repack** (zero-day), which KiberQalqon's
heuristics catch locally. Pitch line: **"they rent a foreign brain (VirusTotal); we built our own,
trained on the exact local trojans — offline, instant, catches the never-seen-before sample."**
Worth borrowing: their aggressive accessibility auto-Cancel for Telegram APKs + onboarding/keep-alive
UX; consider VT as an OPTIONAL secondary check in KiberQalqon, not the brain.
