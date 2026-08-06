---
name: reference_package_ids
description: App namespace (com.uzguard) ≠ applicationId (com.kiberqalqon) — they differ; own-app checks must cover both
metadata: 
  node_type: memory
  type: reference
  originSessionId: 959dd559-d659-448b-b154-489ed2b7e56f
---

In `ApkGuard/app/build.gradle.kts`: `namespace = "com.uzguard"` (line ~82) but
`applicationId = "com.kiberqalqon"` (line ~90), with `applicationIdSuffix = ".debug"` for debug.

So they are **NOT the same**:
- **namespace `com.uzguard`** = the Kotlin source package, the `R` class, and `BuildConfig` location.
  `BuildConfig.APPLICATION_ID` is the *applicationId*, NOT this.
- **applicationId `com.kiberqalqon`** (debug: `com.kiberqalqon.debug`) = the INSTALLED package name
  (`context.packageName`), what the device/Play sees, kept for Play continuity through the rebrand.

Consequence: any "is this our own app?" check must cover BOTH ids. The fake-bank audit
([[project_news_push_2026_06_17]] CI saga, [[project_ci_2026_06_17]]) bit on this — `BankAppAudit.looksLikeBank`'s
own-app guard had to exclude `"com.uzguard"` (namespace, used by the unit test) AND
`BuildConfig.APPLICATION_ID.removeSuffix(".debug")` (= `com.kiberqalqon`, the real installed pkg).
`BuildConfig.APPLICATION_ID` alone = `com.kiberqalqon`, which does NOT match the source package.
