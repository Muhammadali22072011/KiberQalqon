---
name: project_news_push_2026_06_17
description: "News → system push notification with image (polling, no FCM); NewsNotifier + CH_NEWS channel + Settings toggle"
metadata: 
  node_type: memory
  type: project
  originSessionId: 959dd559-d659-448b-b154-489ed2b7e56f
---

2026-06-17: panel "Yangiliklar" posts now also arrive on every phone as a **system
notification with the image** (user ask: "новости должны приходить всем как уведомление с
картинками"). Previously news was in-app only ([[project_news_section_2026_06_12]] —
dashboard carousel + NewsActivity).

**Approach = polling, NOT FCM/Firebase** — deliberately, to keep the offline/no-Google
stance ([[project_competitor_landscape]]). No server change needed; phones already poll
`/api/news` ([[project_news_section_2026_06_12]]). Owner attaches image_url in panel → it
shows as the notification's big picture.

New `NewsNotifier.kt` (`checkAndNotify(ctx)`, IO-thread): fetch via `NewsStore.refresh ?:
loadCached`; **first run seeds all current ids as seen with NO notify** (no install-spam);
then notifies only ids not in seen-set, sorted newest-first, **max 3/run**, skipping items
older than 7 days; 30-min attempt-throttle; seen-set capped 500 (current feed ids kept first
so they never drop → no re-notify). Wired into `GuardWorker` non-realtime block (next to
`CloudBlacklist.refreshIfStale`, runs ~15 min regardless of background toggle) + `App.onCreate`
IO block. `NotificationHelper.showNewsNotification(ctx, item, bmp)`: dedicated `CH_NEWS`
channel (IMPORTANCE_DEFAULT, "Yangiliklar"), BigPictureStyle if image else BigTextStyle,
critical→HIGH else DEFAULT, tap→NewsActivity, id from item.id hash. Image loaded by
`NewsImages.load` on IO and passed in (helper stays network-free). `Config.isNewsNotificationEnabled`
default true + Settings toggle `rowNewsNotif` (ic4_bell). Strings: `strings_kq4_news_notif.xml`
(uz+ru) + news rows in `strings_kq4_set_extra.xml`.

**Git: NOW FULLY COMMITTED.** Call sites (`App.kt`+`GuardWorker.kt`) were in `b2b18e4`; the
rest (NewsNotifier.kt, Config, NotificationHelper, SettingsActivity, layout, news strings,
uz+ru) committed 2026-06-17 as **`c08056c`** on `feat/anti-re-hardening` — NOT yet pushed.
b2b18e4 + c08056c together are self-consistent. (The prior session's uncommitted copies had
been lost off disk — OneDrive churn / clean — so this session faithfully re-created the
identical design from scratch and committed it.)

**Build verification this session = BLOCKED by environment, NOT by code.** Resources
(`processDebugResources`) passed → strings/layout valid; Kotlin emitted ZERO `e:/error:`
diagnostics. But every `assembleDebug` died on the documented OneDrive-placeholder I/O bug
([[reference_build_env_gotchas]]: R.jar / BuildConfig.java `NoSuchFile`) + low RAM (~1.5 GB
free vs `-Xmx3072m` → OS-killed daemon; had to drop heap to 1024m, then reverted). Also
hit: my own `gradlew --stop` poisoned the daemon registry (later daemons self-stop —
"stop command received"); fix = kill java + wipe `~/.gradle/daemon` + DON'T run `--stop`.
The prior session DID get `compileDebugKotlin BUILD SUCCESSFUL` on this identical design.
So: code statically sound + previously compile-verified, but NO fresh clean build this
session and NOT device-tested. Recipe to actually build: OneDrive.exe RUNNING (hydrates
placeholders) + taskkill java + lower heap if RAM tight.

**UPDATE (later same session) — NEAR-REAL-TIME delivery added + CI GREEN.** User: "news don't
arrive in real time." Chose (AskUserQuestion) "faster, no Google (~2–5 min)" over FCM (FCM
rejected — keeps offline/no-Google moat). Hooked `NewsNotifier.checkAndNotify` into the always-on
foreground `ProtectionService` fast-scan loop (fire-and-forget `serviceScope.launch`, before the
isBackgroundEnabled gate, so it never stalls scanning). Lowered throttles: `NewsNotifier`
`CHECK_INTERVAL_MS` 30→3 min; `NewsStore` `MIN_REFRESH_INTERVAL_MS` 10→2 min (must be < notifier
interval so refresh actually fetches). Result: service alive + screen on → new panel post arrives
in ~3 min; screen off → ~10 min (idle loop). App-open + GuardWorker checks retained as fallback.
Committed `2bf2a29` → **CI GREEN** ([[project_ci_2026_06_17]]) — confirms the whole feature
(c08056c + 2bf2a29) compiles + passes. Still NOT device-tested.

**UPDATE 2 — SCREEN-OFF / Doze delivery fixed.** Device test (RU voice): "screen off → no
notification, no sound." Root cause: foreground service keeps the PROCESS alive but NOT the CPU —
when the device sleeps, the poll-loop `delay()` freezes and network is cut, so the check never
runs while screen off. Polling can't beat that. Fix (still no FCM): new `NewsAlarmReceiver` +
`NewsNotifier.scheduleNext/cancel` using `AlarmManagerCompat.setAndAllowWhileIdle` (RTC_WAKEUP,
~12 min) — fires even in Doze (OS floors allow-while-idle ~9 min), wakes the device, runs
`checkAndNotify` in a `goAsync` thread, posts the notification (CH_NEWS DEFAULT → sound; wakeup →
sound is heard), then reschedules itself (self-perpetuating chain). Inexact alarm → NO
SCHEDULE_EXACT_ALARM permission needed. Chain started from App.onCreate + BootReceiver; Settings
toggle arms/cancels it. Survives process-kill (alarm lives in system AlarmManager); reboot clears
it → BootReceiver re-arms. Manifest: `<receiver android:name=".NewsAlarmReceiver" exported=false>`.
Committed `822d998` → **CI GREEN**. Screen-off latency ~12 min (Doze floor without FCM). Still NOT
device-tested — owner should verify: screen off, post panel news, wait ≤~12 min, expect
notification + sound.

**UPDATE 3 — idle-overheat tuning (this news feature WAS the idle-heat culprit).** Device test
(RU voice): "phone heats up when nobody is using it." 4-agent code recon confirmed: when screen
OFF the scanner barely runs (ProtectionService idle poll 10 min + mtime-gated, `delay()` freezes
in Doze; no wakelocks anywhere) — the **dominant idle drain/heat was THIS news path**: the
`NewsAlarmReceiver` chain woke the device from Doze every ~12 min 24/7 (RTC_WAKEUP + network),
AND the ProtectionService poll *also* re-ran `checkAndNotify` every loop (redundant). User chose
(AskUserQuestion) "Balance" over max-cool/measure-first. Two edits on `feat/anti-re-hardening`:
(1) ProtectionService poll now calls `NewsNotifier.checkAndNotify` **only when screen is
interactive** (screen-off relies solely on the alarm); (2) `NewsNotifier.ALARM_INTERVAL_MS`
**12 min → 40 min** (idle wakeups ~3× fewer). Tradeoff accepted: screen-off news latency now
≤~40 min (was ~12); screen-on stays ~3 min via the poll. **Committed `f8a517f` + pushed
`feat/anti-re-hardening` → CI GREEN** (both CI runs success @f8a517f — compiles + APK built;
verified via gh API, branch-filter not head_sha-filter which flaked). Committed blob
re-verified on disk (40L*60 + `if (interactive)`) — no OneDrive desync. NOT device-measured.
Local build still impossible (RAM, see [[reference_build_env_gotchas]]). HONEST CAVEAT: news-alarm is the top *code-level* suspect for the physical
heat but unproven without a device measurement (`dumpsys batterystats` / Battery Historian) —
if idle heat persists after this, measure before more code changes. Other idle factors are
small (foreground service resident process; VPN + Telegram poller both default-OFF).
