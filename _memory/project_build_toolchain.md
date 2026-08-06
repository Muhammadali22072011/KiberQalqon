---
name: project-build-toolchain
description: "Where JDK 17 and the Android SDK are installed on this machine, and how to build the KiberQalqon APK from ApkGuard/"
metadata: 
  node_type: memory
  type: project
  originSessionId: 80bb80cf-f684-4104-91bb-443cf2dd4477
---

The KiberQalqon build toolchain was installed by Claude on 2026-05-29 on this machine
(user akobirturgunov5566), since Java/SDK/Android Studio were all absent.

- **JDK 17**: `C:\Java\jdk-17.0.19+10` (Temurin). Set `JAVA_HOME` to this before building.
- **Android SDK — MOVED (discovered 2026-07-13): `C:\Android` NO LONGER EXISTS** (deleted, likely
  in a C:-disk purge). SDK now lives at **`D:\AndroidSdk`** (platform android-34, build-tools 34.0.0,
  platform-tools, cmdline-tools, licenses, emulator, system-images, avd). AGP auto-downloaded the
  missing platforms/build-tools on first build since `licenses/` was present.
- `ApkGuard/local.properties` updated 2026-07-13 to `sdk.dir=D:/AndroidSdk` (gitignored).
  Symptom of the old path: "SDK location not found" at 27s into configure — NB `gradlew | tail`
  can swallow this and still exit 0; log to a file to see failures.
- **NDK junction (2026-07-13):** `D:\AndroidSdk\ndk\26.1.10909125` is a junction →
  `D:\Android\ndk\26.1.10909125` (created `cmd //c mklink /J`; the old C:\Android junction died
  with the folder).

Build command (from `ApkGuard/`, package is `com.kiberqalqon`):
```powershell
$env:JAVA_HOME = "C:\Java\jdk-17.0.19+10"
cd "C:\Users\User\OneDrive\Desktop\KiberQalqon\ApkGuard"
.\gradlew.bat assembleDebug
```
Output: `app\build\outputs\apk\debug\kiberqalqon-<epoch-millis>-debug.apk` (~8 MB).

**Why:** the project's HOW_TO_BUILD.md references an old machine
(`C:\Users\Muhammadali\Desktop\APK Virus Analysis`) where Android Studio was preinstalled;
none of that exists here. Tools were deliberately put OUTSIDE OneDrive (the repo lives in
`C:\Users\User\OneDrive\Desktop\KiberQalqon`) so OneDrive doesn't sync gigabytes to the cloud.

**How to apply:** when asked to build/rebuild the APK, set `JAVA_HOME` to the JDK path above
and run gradlew from `ApkGuard/` — no need to re-investigate or reinstall. First build took
~10 min (Gradle 8.4 + deps download); later builds are ~1 min. See [[kiberqalqon]] skill for
project structure.

## Release signing (set up 2026-05-29)

The original `release.keystore` (hash `34C1D12C…`) was lost. A NEW keystore was generated:
- `ApkGuard/release.keystore`, alias `kiberqalqon`, RSA 2048, validity 10000 days.
- Password is in `ApkGuard/keystore.properties` (gitignored; absolute `storeFile` path to dodge
  the relative-path inconsistency in `app/build.gradle.kts`). New cert SHA-256:
  `1CB3F378189D6EF38985B3AE234D859E750029AB353246FA496349A8FF14D983`.
- `SecurityGuard.kt` → `EXPECTED_RELEASE_SIGNATURE_SHA256` was updated to that hash, so the
  release self-defense signature gate passes with the new key. If the keystore is ever
  regenerated, this constant MUST be re-synced or the release self-destructs on launch.

Build release: `.\gradlew.bat assembleRelease`. **Add `-x lintVitalRelease
-x lintVitalAnalyzeRelease -x lintVitalReportRelease` if the network is flaky** — release lint
downloads `lint-gradle` from dl.google.com and fails offline (a permanent fix is
`android { lint { checkReleaseBuilds = false } }`, not yet applied). Release output:
`app/build/outputs/apk/release/kiberqalqon-<epoch>-release.apk` (~3.7 MB, R8-minified, v2+v3+v4).

**Release self-destructs on emulators/LDPlayer by design** (`SecurityGuard.isEmulator()` +
likely root checks → `App.kt` kills the process). Test the release on a REAL phone; use the
debug build (all checks skipped) for emulator testing.

## NDK / native layer + DISK constraints (set up 2026-06-03)

The app now has a **native lib `libkqguard.so`** (`app/src/main/cpp/kqguard.c` + `CMakeLists.txt`,
`externalNativeBuild` + `ndkVersion = "26.1.10909125"` in `app/build.gradle.kts`). It does the
signature check + anti-debug in native (harder to patch than Kotlin); `NativeBridge.kt` has a
Kotlin fallback so the app still builds/runs without the `.so`.

- **NDK 26.1.10909125 + CMake 3.22.1** are installed, but **on D:** at `D:\Android\ndk\26.1.10909125`,
  **junctioned** into the SDK at `C:\Android\ndk\26.1.10909125` (so `ndkVersion` resolves it).
  Create the junction with PowerShell (NOT `cmd /c` from git-bash — MSYS mangles `/c`):
  `New-Item -ItemType Junction -Path 'C:\Android\ndk\26.1.10909125' -Target 'D:\Android\ndk\26.1.10909125'`.
- **WHY D:** `C:` is essentially full (~0.8–1 GB free; 97 GB disk at 100%). `sdkmanager` NDK install
  FAILS on C: ("No space left" + Windows long-path errors during toolchain unzip). `D:` has ~200 GB.
  git-bash `tar` can't read the NDK zip (GNU tar); use Windows `bsdtar` (`/c/Windows/System32/tar.exe`)
  or download `android-ndk-r26b-windows.zip` from dl.google.com and bsdtar-extract it.
- **Build with Gradle home on D:** to avoid filling C:
  `export GRADLE_USER_HOME=/d/gradle-home` (git-bash) before `./gradlew.bat`. Free C: first by
  deleting `app/build`, `.gradle`, `.cxx` (all regenerable). Release build with native+R8 ≈ 8–15 min.
- **EXPECTED_RELEASE_SIGNATURE_SHA256** in `SecurityGuard.kt` is now **Shield-encoded** (decodes to
  the same `1CB3F378…D983`) and ALSO injected into native as `-DKQ_EXPECTED_SIG=…` (build.gradle.kts
  `externalNativeBuild.cmake.arguments`). If the keystore changes, update BOTH the Shield literal
  (re-run `scripts/shield_encode.py`) AND the `-DKQ_EXPECTED_SIG` arg. Verified the release APK's
  apksigner cert == that hash, so no self-kill.

See [[project-anti-re-hardening]] for the full anti-reverse-engineering work shipped this session.

## RAM constraint — local build often IMPOSSIBLE while Claude Code is open (2026-06-17)

This is an **8 GB machine**. Claude Code itself uses **~2 GB** (multiple `claude` processes), plus
Defender + OS, leaving frequently only **~0.5–1.5 GB free**. A Gradle/Android build wants ~3 GB
(`gradle.properties` sets `-Xmx3072m`). Result: the build JVM **OOM-crashes at startup** —
`hs_err_pid*.log` says *"insufficient memory ... Failed to commit metaspace"* — even for the tiny
64 MB Gradle *wrapper* JVM. Lowering `-Xmx` doesn't help because RAM can't be sustained for the
minutes a build needs (it dips to <500 MB mid-build). Closing other apps is insufficient since
Claude Code is the dominant consumer and can't be closed mid-session.

**Symptoms seen:** builds die silently at "Configure project :app" (5–6 log lines, EXIT 1); or
"Gradle build daemon has been stopped: stop command received" mid-`compileDebugKotlin`; or
`Couldn't delete R.jar` / `mergeDebugResources` MD5-of-missing-file (corrupted intermediates from
JVM-killed prior builds). Stray `java.exe` daemons from failed runs pile up (847 MB each) and make
it worse — kill them: `powershell Stop-Process -Name java,javaw -Force`.

**How to apply — don't thrash on local builds here.** To verify code compiles + tests pass, push to
the branch and let **GitHub Actions CI** (`.github/workflows/ci.yml`, triggers on `feat/**`/`fix/**`/
`main`) build `assembleDebug` + run `testDebugUnitTest` on a clean cloud runner. To get an installable
APK on the phone, build in **Android Studio with Claude Code CLOSED** (it manages its own JVM heap).
A from-shell `gradlew` build only works right after killing all java AND when >~2.5 GB is genuinely
free (rare while Claude is running). See [[project-perf-wave-2026-06-17]].

**WORKING low-memory recipe (proven 2026-06-17, succeeded at only ~2.1 GB free):** the build
completes from-shell if you (1) **stop OneDrive** `Stop-Process -Name OneDrive -Force` — it syncs the
in-repo `app/build/` and locks `R.jar`/resource intermediates → "Couldn't delete"/silent daemon death;
(2) **kill stray java** + `rm -rf ~/.gradle/daemon` (repeated `--stop` corrupts the registry → new daemon
exits 127 at startup; also delete a locked `app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar`);
(3) **free RAM** (close Chrome/Roblox/etc); (4) build single-JVM, lowest footprint:
`./gradlew :app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`
(in-process Kotlin = no separate compiler JVM, the key lever; ~5 min). **Restart OneDrive afterwards**
(`Start-Process "$env:LOCALAPPDATA\Microsoft\OneDrive\OneDrive.exe" /background`). Symptom that you're
close: each retry caches more tasks and dies later (compile→dex is the peak-RAM moment); plain retries
DON'T converge because a JVM-killed task isn't marked up-to-date and re-runs — fix the RAM, don't retry.

**2026-07-13 datapoint:** `./gradlew assembleRelease -x lint --no-daemon` SUCCEEDED from git-bash in
11m34s with Claude Code open and OneDrive running — RAM headroom varies session to session; try the
plain build first before assuming impossible.
