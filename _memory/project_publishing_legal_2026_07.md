---
name: project_publishing_legal_2026_07
description: "UzGuard publishing/legal track — author is a 15yo minor, dad at Navoiy cyber-center (MVD) publishes, IP registration filed, contract drafted, name kept UzGuard"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2866c718-799b-4606-91aa-d41123b43745
  modified: 2026-08-06T19:54:05.182Z
---

**Owner is a MINOR** — Izatillayev Muhammadali Jasur o'g'li (turned 15 on ~2026-07-22, JSHSHIR 5220711…, Navoiy). Dad = **Usmanov Jasur Izatilloyevich** (JSHSHIR 3240887…), works at the **Navoiy region cyber-security unit under MVD** — is the legal representative (qonuniy vakil) AND the intended publisher via the center's org account. Google Play needs 18+, so son can't hold his own account until 18.

**Plan (agreed):** cyber-center (org) publishes UzGuard → later, at son's 18, transfer to his personal account via Google **App Transfer** (official, free, ~2 days, keeps installs/reviews/rating). Org account may skip D-U-N-S as a government body.

**IP registration (авторское право на ПО) — FILED, payment unresolved:** at im.adliya.uz via dad's OneID. **Talabnoma ID 308101**, author + rightsholder = the SON (creative IP ownership is allowed even for MVD staff; kept son as owner to avoid conflict-of-interest). Created 28.05.2026 (git first commit — proves authorship, which is automatic regardless of registration). Program: UzGuard (avvalgi: KiberQalqon/Anor Qalqon), Kotlin, Android API24+. Fee = 1 BHM = **412 000 sum**. ⚠️ **TWO invoices appeared** (33525936786810 "topshirish" + 58695265605127 "davlat boji"), both 412k — unclear if both required (824k risk) or duplicate; TOLD USER not to pay blindly, ask online chat / check payment status. my.gov.uz (service 1067) shows 0.5 BHM = **206 000** discount but ONLY via unified portal (im.adliya.uz redirect kept giving 412k). Cert issues auto after payment + ≤10-day exam. Deposit PDF: `Desktop/UzGuard-deponent-manba-kod.pdf` (ApkScanner.kt listing + official title page). **Org will pay the fee** (per contract).

**Contract (shartnoma) — DRAFTED, not signed:** `Desktop/UzGuard-SHARTNOMA.docx` (+ .md), uz+ru, generated via `scratchpad/mkdocx.py` (python-docx). Author=son (rep=dad, minor), publisher=cyber-center. Key clauses to keep: **2.4** (not a service/xizmat asari → org can't claim IP), **5** (org must App-Transfer to son at 18, free, 30 days), **3.3** (org pays registration + Play costs). Cert № field = "in process, talabnoma 308101". Needs org's юротдел review + fill placeholders.

**NAME = keep "UzGuard"** (user decision, overrode advice). CLASH: "Uzguard AV" = Kaspersky + Universoft IT product (Jan 2024) → trademark unavailable + Play brand-complaint risk. Mitigation: package `com.kiberqalqon` unchanged → rename is cheap (listing/icon only) if Google complains. Backup clean names (clash-checked via workflow): **Nigohbon**, **Qorgon** (both fully clean); English candidates mostly taken.

**Play tech prep = DONE** on branch `feat/play-flavor` (pushed, last 35b4c67). See [[project_play_readiness_2026_07_18]]: play product flavor (strips accessibility/notif-listener/self-update/hard-kill), AAB `Desktop/UzGuard-PLAY-8.7-87.aab`, all assets + docs in `play/` (feature graphic, 4 screenshots, listings, privacy, data-safety, PLAY_SUBMISSION.md, SUBMISSION_STEPS.md, all_files_demo.mp4 + voiced). Contact = @zimdevuz only [[feedback_public_contact]].

**NEXT when resuming:** (1) online chat re: 2 invoices → org pays correct one; (2) sign shartnoma; (3) org opens Play Console + submits via internal track using play/ package. User works async — everything saved to Desktop + git + memory.
