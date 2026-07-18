I have everything I need from the actual code. Here is the compliance package, grounded in the real implementation.

---

# UzGuard (com.kiberqalqon) — Google Play Submission Answers

Version 8.7 (versionCode 87) · minSdk 24 · targetSdk 34 · Free · No ads · No IAP

**How the code actually behaves (this drives every answer below):**
- The app is offline-first. All scanning is on-device. Nothing leaves the phone unless the user turns on an optional feature.
- Two independent consents (`Config.kt`): (1) ToS+Privacy accept = mandatory; (2) "Jamoatchilik xavfsizligi / Community safety" opt-in = **default OFF** (`community_share_v1` defaults to `false`). Cloud telemetry (`CloudTelemetry.enabled()`) requires BOTH to be true, so by default the cloud path sends nothing.
- The device identifier is a **randomly generated UUID** created on-device (`CloudTelemetry.deviceToken`). No IMEI, serial, MAC, phone number, Advertising ID, or Android ID is ever read or sent — the code and Privacy Policy both state this explicitly.
- GPS is sent **only if** the user granted location permission AND community sharing is ON. No background location (`DeviceLocation` uses last-known/one-shot only, foreground).
- Name + phone are collected **only if** the user actively joins a group (`GroupJoinActivity` → `/api/device/join`).
- Malicious/suspicious APK **files** (up to 50 MB) are uploaded only when community sharing is ON and verdict is DANGER/SUSPICIOUS. Safe APK files are never uploaded.
- Cloud backend = Vercel + Supabase (your processors). A developer-operated Telegram bot receives threat reports/crash logs when community sharing is ON. A **separate** personal-Telegram feature (default OFF, user enters their own bot token) sends events to the *user's own* bot — the developer never receives that.
- Transport: `usesCleartextTraffic="false"`, network security config + certificate pinning, HTTPS-only enforced in `CloudTelemetry.baseUrl()`. Everything is encrypted in transit.

---

## (a) DATA SAFETY FORM

### Top-level answers

- **Does your app collect or share any of the required user data types?** → **Yes** (only when the user enables optional features; still answer Yes).
- **Is all of the user data collected by your app encrypted in transit?** → **Yes** (HTTPS-only + certificate pinning; cleartext disabled in manifest).
- **Do you provide a way for users to request that their data is deleted?** → **Yes.** Provide a deletion-request method (a contact email — the app routes users via Settings → "Yordam"/Help, plus in-app "Revoke consent" and "Clear history"; uninstall wipes all local data). You must enter a working deletion-request URL/email in the console.

### "Collected" vs "Shared" note
Vercel, Supabase, and the developer's Telegram channel act as **service providers / your own infrastructure**, which Google's definition **excludes from "Shared."** So every item below is **Collected: Yes / Shared: No**. (If a reviewer disagrees about Telegram, the fallback honest answer is Shared: Yes for the threat-sample items — but Processor treatment is defensible.)

### Data type table (enter each as "Collected", the listed purposes, Optional, Encrypted in transit = Yes, Deletable = Yes)

| Data type (Google category) | Collected | Shared | Optional? | Purposes to tick | Notes from code |
|---|---|---|---|---|---|
| **Precise location** (Location) | Yes | No | **Optional** (user-controlled) | App functionality; Fraud prevention, security & compliance | GPS lat/lng only if location permission granted AND community sharing ON; foreground only, no background tracking. `DeviceLocation`/`CloudTelemetry.putGeo`. |
| **Approximate location** (Location) | Yes | No | Optional | App functionality; Fraud prevention, security & compliance | Server derives city-level from IP for the threat map when GPS absent. Only relevant when community sharing ON. Declare to be safe (IP→coarse location). |
| **Name** (Personal info) | Yes | No | Optional | App functionality | First+last name entered by user in `GroupJoinActivity` only when joining a group. Shown to that group's owner. |
| **Phone number** (Personal info) | Yes | No | Optional | App functionality | Entered by user in group-join flow only. Not read from SIM — user types it. |
| **User IDs / Device or other IDs** | Yes | No | Optional | Analytics; App functionality; Fraud prevention, security | Random per-install UUID (not a hardware ID / not Advertising ID). Sent only when community sharing ON. Declare under "Device or other IDs" and clarify in your justification it is app-generated. |
| **App activity → Other actions** (App activity) | Yes | No | Optional | Fraud prevention, security & compliance; Analytics | Scan events: package name, app label, verdict, risk score, reasons, dangerous permissions, scan duration. Sent per scan (incl. safe) only when community sharing ON. |
| **Files and docs** | Yes | No | Optional | Fraud prevention, security & compliance; App functionality | The suspected-malware **APK file itself** (≤50 MB) + its SHA-256 hash, uploaded only for DANGER/SUSPICIOUS verdicts when community sharing ON. Not the user's personal documents. |
| **Crash logs** (App info & performance) | Yes | No | Optional | Analytics (crash fixing) | Stacktrace + device model + Android/app version → developer Telegram, gated on community consent in release builds. |
| **Diagnostics** (App info & performance) | Yes | No | Optional | Analytics; App functionality | Device model, Android version, app version, protection-state counters accompany cloud/telegram reports. |

### Data types you should mark NOT collected (the code explicitly excludes them — say so if asked)
- **Installed apps list** — never sent (explicit exclusion in `CloudTelemetry`/Privacy Policy).
- **Contacts, SMS, messages, call logs** — not accessed/sent. (The scam-message checker is 100% on-device; text is never transmitted.)
- **Photos/Videos/Audio files** — not collected/uploaded (media permissions are used only to *scan* disguised APKs locally).
- **Financial info, Health, Web history, Emails, Passwords/credentials, Advertising ID, IMEI/serial/MAC/phone-from-SIM** — none.

### Edge case to note in your records (not a store-form field)
The **personal Telegram telemetry** feature (default OFF; user supplies their own bot token via a hidden Diagnostics screen) sends app events + device model to the *user's own* Telegram bot. Because it goes to an endpoint the user controls and the developer never receives it, it is treated as user-directed transfer. Keep the Privacy Policy language (section 5) that discloses it.

---

## (b) CONTENT RATING (IARC) QUESTIONNAIRE

App category: **Utility / Tool** (not a game). Recommended answers:

| Question area | Answer |
|---|---|
| Is this a game? | **No** — reference/utility app |
| Violence (realistic/cartoon/fantasy) | **None** |
| Blood / gore | **None** |
| Sexual content or nudity | **None** |
| Profanity or crude humor | **None** |
| Controlled substances (alcohol, tobacco, drugs) | **None** |
| Gambling (real or simulated) | **None** |
| Scary / horror content | **None** |
| Discrimination / hate | **None** |
| Does the app let users interact or communicate with each other? | **No** — no user-to-user chat/social features |
| Does the app let users share user-generated content? | **No** (threat reports go to the operator's backend, not published to other users) |
| Does the app share the user's current physical location with other users? | **No** for user-to-user. (Opt-in location goes to the operator's threat map / group owner, not broadcast to other end-users. If your IARC flow phrases it as "with the operator," answer per exact wording and rely on the Data Safety disclosure.) |
| Does the app allow purchase of digital goods / contain in-app purchases? | **No** |
| Does the app contain ads? | **No** |
| Miscellaneous — does the app collect/share personal data or location? | **Yes** (disclosed fully in Data Safety + Privacy Policy) |

**Expected result:** Everyone / PEGI 3 / ESRB Everyone / rated for ages 3+ on content. (Content rating reflects content, not the 13+ eligibility you set in your ToS — that is handled by Target Audience below.)

---

## (c) TARGET AUDIENCE & CONTENT

- **Target age groups:** Select **18 and over** only.
  - Rationale grounded in the app: it requests high-risk permissions (MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES, REQUEST_INSTALL_PACKAGES, Accessibility, VPN, SYSTEM_ALERT_WINDOW), collects optional location + phone number, and your own ToS sets a 13-year minimum. Choosing adults-only keeps the app **out of the Designed for Families / Google Play Families program** and its stricter obligations. (13+ is technically consistent with your ToS, but 18+ is the clean, defensible choice given the sensitive surface.)
- **Does your app appeal to children / is it designed for children?** → **No.** Store listing, icon, screenshots, and content are a security utility for adults; nothing is child-directed.
- **Designed for Families / "Teacher Approved":** → **Do NOT opt in.**
- **Ads present:** **No.**

---

## Additional Play Console items your submission WILL also require (grounded in the manifest — flagging so you are not blindsided)

These are separate from the three forms above but are mandatory for this exact manifest:

1. **Permissions Declaration form** — you must justify and/or record video for each of:
   - `MANAGE_EXTERNAL_STORAGE` (All files access) — Play restricts this heavily; you need the "core functionality = anti-malware file scanning/removal" justification, and Play may still reject or require the "malware/security" use-case exception.
   - `QUERY_ALL_PACKAGES` — allowed for antivirus apps; declare "device security / malware scanning."
   - `REQUEST_INSTALL_PACKAGES` — declare; note the **self-update feature installs its own APK** — this can draw scrutiny; you may need to gate/disable in-app self-install for the Play build.
   - Accessibility (`BIND_ACCESSIBILITY_SERVICE`, `InstallShieldService`) — Play's Accessibility policy requires a prominent disclosure and a clear justification; "auto-cancel installer dialogs" is an unusual use that reviewers often flag.
   - `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE` (you already set a good specialUse subtype string), VPN service.
2. **Prominent Disclosure & Consent** — your `ConsentActivity` already implements explicit opt-in; make sure the location + personal-data disclosure appears before collection (it does).

## Review-risk callout (must fix before you upload the Play build)

`SecurityGuard.kt` (release-only, `isEmulator` ~line 519) **kills the app process on emulator/root/Frida/Xposed detection**. **Google Play reviews run on emulators.** The app will self-terminate during review → near-certain rejection for "crashes / doesn't function." Before submitting, disable the emulator branch of the self-kill for the Play (release) build (keep root/Frida/Xposed if you like, but the emulator check has to go, or be no-op'd behind a build flag). This is the single most likely cause of a failed review and is not addressed by any of the forms above.

Key source files: `ApkGuard/app/src/main/java/com/kiberqalqon/CloudTelemetry.kt`, `DeviceLocation.kt`, `ConsentActivity.kt`, `Config.kt` (consent defaults), `GroupJoinActivity.kt`, `TelemetryReporter.kt`, `CommunityReportClient.kt`, `NetworkInfo.kt`, `AndroidManifest.xml`, and `SecurityGuard.kt` (emulator self-kill).