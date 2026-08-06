---
name: access-model-simplification
description: "Access/login model for the KiberQalqon cloud panel + Android — deliberately simplified from a 3-tier role hierarchy to owner + one admin account. Don't re-introduce roles."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3a686267-d172-4cb4-8b0a-ff265f766768
---

On 2026-05-29 the user tore out the entire role/permission hierarchy (egasi → rahbar →
xodim, role creation, per-permission components, hourly secret code, multi-operator) and
replaced it with a deliberately minimal model:

- **Owner (you/dev)** — logs in with master `ADMIN_SECRET` (+ optional TOTP). FULL access:
  view, change, export, post news, everything.
- **One restricted admin** — logs in with a single login+password (env `ADMIN_LOGIN` /
  `ADMIN_PASSWORD`). Can ONLY view + export + post/manage news. Cannot change/delete data.

**Why:** the user found the 3-tier role system (which had just been built) too complex and
wanted radical simplicity — "only one admin, me; others just look." They communicate in
garbled voice-to-text Russian, so intent took several rounds to pin down; the final spec was
"remove everything, I'm full-access as the developer, one admin login+password for
view+export+news." See [[user-chat-language]].

**How to apply:** Do NOT re-introduce roles, managers (rahbar), operators (xodim),
permission/component picking, or the hourly code — the user explicitly removed them. Cloud
token kinds are now: owner (no `kind`) and `kind:'admin'` (restricted), both in
`x-admin-secret`. Read endpoints use `canRead`; news write uses `canManageNews`; destructive
owner-only endpoints use `checkAdminSecret`. The Android app is now antivirus-only — the whole
secret-access panel (SecretAccess*, RoleAccessClient, RolePanels, OperatorDataClient,
OwnerNews/CodeClient, BiometricGate) was deleted; `NewsClient` stays (shows announcements on
the home screen). `supabase/03_roles.sql` and `05_hierarchy.sql` are now obsolete. See
[[project-build-toolchain]] and the [[kiberqalqon]] skill.
