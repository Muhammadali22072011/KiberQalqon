---
name: project_device_groups_2026_07_09
description: "Device GROUPS feature — owner segments the fleet into colored groups; users join in-app by code/QR + name/surname/phone. Built + committed 6f6c7cd, needs manual Supabase migration."
metadata: 
  node_type: memory
  type: project
  originSessionId: 67d2901d-8ae9-4257-bf6e-f2e55b8a8d01
---

Device **groups** (rang-yorliq segmentation) shipped 2026-07-09 (commit 6f6c7cd on `feat/anti-re-hardening`, pushed). Owner segments the whole fleet into named+colored groups; phones join in-app.

**Flow:** owner → panel «Guruhlar» → create group (name + color) → server auto-generates `join_code` (7 chars A-Z2-9, no 0/O/1/I) + self-drawn QR → share/print. User → UzGuard dashboard «Guruhga qo'shilish» card → GroupJoinActivity → enter code OR scan QR → **Ism/Familiya/Telefon** form (all required, phone plain TEXT, no SMS) → joined, colored badge. Owner sees fleet split by group (color dot + column + filter on Devices) and exports an Excel «Guruhlar» roster.

**Decisions (user-confirmed):** groups = colored LABELS (owner sees ALL — no tenants/roles, fits «owner + 1 admin» model); 1 device = 1 group; code auto-gen; phone plain text. PII (name/phone) is explicit user input, shown ONLY to owner — form says so; distinct from CloudTelemetry which never sends phone.

**Backend (NO new Vercel function — Hobby 12-limit already maxed):**
- `16_groups.sql`: `device_groups(name,color,join_code)` + `devices.group_id` + `member_first/last/phone`; `v_devices_with_counts` & new `v_group_members` (drop+create → must re-apply `security_invoker=on`+revoke per CL-05).
- `api/devices.ts`: branched — `?groups=1` list, POST `create_group`/`delete_group` (owner-only `checkAdminSecret`, audited), `?members=1` roster.
- `api/device/[id].ts`: new `join` branch (device HMAC via `verifyDeviceWrite`, upserts group_id + member_* by code; wrong code → 200 `{ok:false,error:'code'}`).

**Panel:** new `Groups.tsx` page (+route/menu); `Devices.tsx` group column+filter+member in detail; `exportExcel.ts` «Guruhlar» sheet. QR is a **vendored pure-TS Nayuki port** `src/lib/qr.ts` (byte-mode, NO npm dep — CSP blocks external, and a dep would break local `tsc`); structurally verified (v3, finder/timing/format-info correct). Panel `tsc` + `vite build` GREEN.

**App:** `GroupJoinActivity` + layout; `QrScanActivity` got a return-code mode (`EXTRA_RETURN_JOIN_CODE`/`RESULT_JOIN_CODE`) that extracts code from `uzguard://join?code=X` or bare code; `CloudTelemetry.joinGroup()` (signed, does NOT require community-share consent, works without prior register via upsert) + `savedGroup()`; dashboard group card; `uzguard://join` deep link in manifest (external camera). UZ+RU strings. Android built by CI (local build impossible — RAM, see [[project_build_toolchain]]).

**Status 2026-07-09 (device-checked):** signed release APK built locally (`assembleRelease` R8, cert 1cb3f378, versionCode 84) → on Desktop. GroupJoinActivity UI CONFIRMED working on phone (screenshot: form renders, code/name/phone entered). Backend all LIVE on **kiberqalqon-cloud.vercel.app** (NOT uzguard-cloud — see below): join API deployed, migration 16 RUN (owner created group code D4RW4RA in panel → `error:code` probe proves table exists), device-secret matches server (curl-verified). 

**GOTCHA (cost a debug cycle):** first release APK 404'd every cloud call → `cloud.base.url` in local.properties pointed to DEAD `uzguard-cloud.vercel.app`; live prod is `kiberqalqon-cloud.vercel.app`. FIXED local.properties + rebuilt (dex-verified URL swapped). See [[project_rebrand_uzguard]]. Owner just needs to reinstall the corrected APK + join with a real group code. Feature parallels [[project_feature_wave_2026_06_11]] (QR check reused).
