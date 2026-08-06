---
name: project-competitor-landscape
description: "Full competitor map for KiberQalqon (Uzbek/CA APK-antivirus & anti-Telegram-dropper market) — who exists, ranked by threat."
metadata: 
  node_type: memory
  type: project
  originSessionId: 2b4ceb87-41ec-4f49-a293-52d5dfd21ae0
---

Competitive landscape for KiberQalqon (researched 2026-06-03). The on-device Uzbek
APK-antivirus niche is thinly contested by APPS, but competition has shifted to **Telegram
bots**. Use this for the pitch "competitors" slide. See [[project-competitor-apk-qoriqchi]] for
the deep RE of the main app rival, and [[project-kiberqalqon-pitch]].

**Highest-threat new players (the ones to know):**
- **@Cyberqalqonbot** — GOVERNMENT Telegram bot (a state cybersecurity center; exact agency
  MVD vs SGB unverified — check before quoting). **Name nearly identical to KiberQalqon** →
  biggest BRAND risk. Forward-a-file → verdict; 6,300+ checked. Expect the meeting question
  "isn't this the government's Cyberqalqon?".
- **ZIRH BOT** (@ZIRH_BOT, private co **Cyber Himoya MCHJ**, cyberhimoya.uz) — AI bot, ~29.5k
  channel subs, claims 485k+ files analyzed / "78.8% of Telegram APKs malicious" (their claim),
  v2 shipped. Strongest private rival by traction.
- **Cyber Shield** (@cybershielduz_bot) — government bot, auto-deletes malicious APKs from
  Telegram groups; launched 14 Nov 2025.
- **UZB CYBER KALKAN** (`uz.abducation.antivirus`, dev "abducation") — near name-clone
  (Kalkan=Qalqon=shield), on-device APK scanner, SHA-256, offline, ~15-min rescan. But brand-new
  & tiny (v1.0.8, ~16 installs on Softonic) — not a threat yet, but the name confuses.
- **CBU Regulation No. 3759** (in force 22 Apr 2026) — mandates EVERY Uzbek bank/payment app to
  embed an on-device antivirus/antifraud SDK. Structural threat (commoditizes on-device detection)
  AND opportunity (KiberQalqon could license its engine to banks — B2B path).

**Lower threat:** Apk Qo'riqchi (100k+, but engine=VirusTotal — see its memory); SamCyber102
(no real data); UZCERT/CSEC/Xavfsizlink.uz (advisory/web, shape narrative); Group-IB Fraud SDK
(B2B). **Regional:** KZ Nomad Guard (in eGov), TSARKA, KG Tunduk, C-Prot/CHOMAR (Turkey),
Kaspersky Who Calls. **Global (indirect):** Kaspersky (REMOVED from Play 2024), Dr.Web, Avast/AVG,
Bitdefender, ESET, Norton/Avira/McAfee/Defender, Google Play Protect — generic, no Uzbek UI, weak
on local droppers.

**Pass-2 deep-dig net-new (2026-06-03):**
- **CSEC government on-device app** — state Cyber Security Center shipped a standalone Android app:
  scans installed apps every 15 min, OFFLINE, flags dangerous permissions, ~3000 devices. The
  CLOSEST functional twin to KiberQalqon's core loop, and it's free/government. BUT it's
  permission-scanning only (shallow, like Apk Qo'riqchi) — no deep engine/install-blocker/quarantine.
  Counter-message: depth (ZIP-evasion, icon-impersonation, dropper, Ajina/RoundRift sigs) + blocker
  + quarantine + cloud map. (App name/Play listing unconfirmed — verify.)
- **Bank in-app antivirus LIVE since 22 Apr 2026** (decree PQ-153 / CBU): 20+ banks (Uzum, Click,
  Kapitalbank, Asaka, Milliy…) embed anti-RAT (app self-disables on remote-control/screen-share),
  root/VPN/Tor detection, session kill. Protects the banking SESSION, not the whole phone → KiberQalqon
  guards the device before malware reaches the bank app; also a B2B opportunity (be the mandated SDK).
- **Pitch bracket peers** (President Tech Award "Cybersecurity"): ALATOR (1st, web WAF/DDoS), PCP (2nd),
  SectorSIEM, InsiderGPT — all different layer (web/enterprise), none do mobile on-device AV →
  KiberQalqon is the only consumer mobile antivirus in that bracket.
- Low-overlap: telecom anti-spoofing (Beeline/Mobiuz/Uzbektelecom), VERA AI Antispam
  (com.enaza.antispam, caller-ID), Xavfsiz kibermakon (uni/police group bot), CyberFeed.uz, CYBER-BRO
  LLC (B2B pentest, markets itself as "haqiqiy kiberqalqon"), Octosec, ArkStone, HackZone.
- **Confirmed gaps = arguments:** NO private Uzbek startup in KiberQalqon's exact niche; NO named local
  antifraud-SDK vendor for banks (B2B slot open); SamCyber102 is real, package `uz.cyber102.cyber102`.

**KiberQalqon's defensible moat vs all:** the ONLY true on-device, OFFLINE antivirus with
install-blocker + quarantine + a deep local engine (13 analyzers) specialized in named local
trojans (Ajina.Banker, RoundRift) and dropper heuristics — vs bots that only act when a file is
forwarded or inside a Telegram group, and vs global AV with no Uzbek tuning. Three meeting
landmines to prep: (1) the @Cyberqalqonbot name clash, (2) "why pay when gov bots are free",
(3) the CBU bank-SDK mandate (flip to a B2B opportunity).
