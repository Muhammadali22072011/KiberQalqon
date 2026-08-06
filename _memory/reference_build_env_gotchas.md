---
name: reference_build_env_gotchas
description: Why gradle builds randomly fail on this machine (OneDrive placeholders + low RAM) and how to get a green build
metadata: 
  node_type: memory
  type: reference
  originSessionId: 959dd559-d659-448b-b154-489ed2b7e56f
---

This machine's gradle builds fail in confusing, NON-code ways. Two root causes, both
environmental (see [[project_build_toolchain]] for JDK/SDK paths):

1. **OneDrive placeholders.** The project lives under `...\OneDrive\Desktop\KiberQalqon`, so
   gradle's `app/build` intermediates are inside OneDrive. When **OneDrive.exe is NOT running**,
   freshly generated files (`R.jar`, `BuildConfig.java`) become unreadable cloud placeholders →
   Kotlin front-end dies with `NoSuchFileException: ...R.jar` / `Unresolved reference: R`
   (looks like a code error in a random file like AutoScanActivity/AdminPanelActivity, but it
   ISN'T). **FIX: make sure OneDrive.exe is running** (`%LOCALAPPDATA%\Microsoft\OneDrive\OneDrive.exe /background`)
   so it hydrates files on read. With it running, builds go green. (A Defender path-exclusion
   on the build folder alone did NOT fix it; redirecting buildDir out of OneDrive via init
   script broke AGP's merged_res paths — don't.)

2. **Low RAM.** `gradle.properties` sets `-Xmx3072m`, but the machine often has <1.5 GB free
   (disk C: also tight ~5–7 GB). The daemon JVM gets OS-killed mid-compile → `EXIT=127` or
   "Gradle build daemon has been stopped: stop command received" (NOT an external stop — it's
   the OS killing it). Zombie daemons from prior attempts pile up ("N busy" in `--gradlew --status`)
   and hog RAM. **FIX: `taskkill //F //IM java.exe` to reclaim RAM + clear zombies, then build.**
   If still killed, temporarily lower heap (1024m worked) — but prefer just freeing RAM.

**Do NOT** run `gradlew --stop` repeatedly — it leaves stale stop signals; instead
`taskkill //F //IM java.exe` and optionally `rm -rf ~/.gradle/daemon` to clear the registry.

**Reliable recipe:** OneDrive running → `taskkill //F //IM java.exe` → (optional `rm -rf
/c/Users/User/.gradle/daemon`) → build. To verify code changes cheaply without the heavy
4-ABI CMake native build, run `./gradlew.bat compileDebugKotlin` (sandbox-disabled Bash so the
daemon survives). All Bash here needs `dangerouslyDisableSandbox: true` or the daemon is torn down.

**FULL local `assembleDebug` DID succeed 2026-07-09** (contradicts "impossible") — the winning combo was `--no-daemon` + a bounded heap so the JVM can't grow into OOM-kill territory: with OneDrive up + zero stray java procs, `JAVA_HOME=/c/Java/jdk-17.0.19+10 ./gradlew assembleDebug --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=512m"` finished **BUILD SUCCESSFUL in 10m36s** (full 4-ABI native included), output `app/build/outputs/apk/debug/kiberqalqon-<ts>-debug.apk` (~11.6MB, timestamped name, not `app-debug.apk`). Run it via `run_in_background: true` (10min+ wall). So local build is viable when RAM is free — CI is the fallback, not the only option.

**When local build is hopeless (RAM), use CI to get an installable APK.** `.github/workflows/ci.yml`
runs on push to `main`/`feat/**`/`fix/**`: JUnit tests + `assembleDebug` + (as of 2026-06-17,
commit 00cf132) uploads the debug APK as artifact **`uzguard-debug-apk`**. Download path for the
user: GitHub repo → Actions → latest run → bottom "Artifacts" → `uzguard-debug-apk` → unzip →
install the `.apk` on the phone. Debug build skips SecurityGuard (anti-emulator/tamper) but perf/
delete/popup behavior is identical, so it's fine for device-testing those. Note OneDrive can serve
**stale working-tree copies** of files (a Read showed an old version of ProtectionStatusActivity that
git considered unchanged) — re-verify against `git show HEAD:<path>` if a diff looks wrong.

**CI-debug-key ≠ local-debug-key (2026-07-09).** The CI `uzguard-debug-apk` is signed with CI's
own auto-generated debug keystore, which does NOT match the local machine's `~/.android/debug.keystore`.
Installing it over an existing locally-built `com.kiberqalqon.debug` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. **Fix: `adb uninstall
com.kiberqalqon.debug` first, then `adb install -r -d <apk>`** — this WIPES the debug app's
SharedPreferences/history (Telegram token, linkguard toggle, scan history), so confirm with the
user before doing it. (Two consecutive CI builds share the same CI key, so CI→CI updates in place;
only local→CI or CI→local needs the uninstall.)

**Driving the link path on-device without setting default handler:** fire the intent straight at
the (exported) activity — `adb shell am start -a android.intent.action.VIEW -c
android.intent.category.BROWSABLE -d "https://example.com" -n
com.kiberqalqon.debug/com.uzguard.LinkGuardActivity`. Note applicationId `com.kiberqalqon.debug`
but the class namespace is `com.uzguard` (see [[reference_package_ids]]). Verify with `dumpsys
activity activities | grep ResumedActivity` (Chrome should be on top) and `logcat -d | grep -iE
"FATAL|Toast|Displayed com.android.chrome"`. On this A56, browsers = Chrome (default) + Samsung
Internet, so `browserOptions()` is non-empty and the empty-list fallback branch can't be forced
naturally.
