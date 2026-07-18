All audit claims verified against the actual files (namespace `com.uzguard`, applicationId `com.kiberqalqon`; SecurityGuard checks list at lines 84–94 with emulator at 92; App.kt kill at 98–105; single sideload cert in both SecurityGuard.kt:44–52 and build.gradle.kts:127; `isAccessibilityTool="true"` at accessibility_service_config.xml:16; install-shield default-ON at Config.kt:231; no product flavors in build.gradle.kts). Here is the dossier.

---

# UzGuard — Google Play Submission Dossier

**App:** UzGuard · **Package (applicationId):** `com.kiberqalqon` · **Namespace:** `com.uzguard` · **versionName 8.7 / versionCode 87** · minSdk 24 · targetSdk 34 · Free · No ads · No IAP
**Repo:** `C:/Users/User/OneDrive/Desktop/KiberQalqon` · **App module:** `ApkGuard/`

---

## 1. TL;DR — Is this submittable as-is?

**No. Do not upload the current `release` build.** It will (a) kill its own process on the reviewer's emulator and (b) boot-loop on every real Play install. On top of that it ships an accessibility auto-clicker and an out-of-Play self-updater that are direct policy rejections. All are fixable, but every item below marked **BLOCKER** must be resolved before the first upload.

**Blockers, ranked (each with its one-line fix):**

1. **Emulator self-kill (SecurityGuard).** `App.kt:98–105` calls `killProcess()`+`exitProcess(10)` when `runAllChecks()` fails; the check list (`SecurityGuard.kt:84–94`) treats `isEmulator()` as fatal. Google's review + Pre-Launch Report run on emulators → app closes on launch → auto-reject. **Fix:** remove `emulator`/`root`/`frida`/`xposed`/`native`/`debug`/`installer` from the fatal `checks` list; keep only tamper/signature (and gate those per #2).
2. **Signature check boot-loops on ALL Play installs.** Play App Signing re-signs the AAB with Google's key; only the sideload cert `1CB3F378…` is whitelisted (`SecurityGuard.kt:44–52`, `build.gradle.kts:127`), so `isSignatureInvalid()` returns true on-device → kill on every launch. **Fix:** for the first upload make `signature` non-fatal too; after upload, copy the Play "App signing key certificate" SHA-256 into both files, then optionally re-enable.
3. **Self-update installs its own APK.** `SelfUpdate.downloadVerifyInstall()` (`SelfUpdate.kt:109–163`) downloads `kq-update-<code>.apk` and hands it to the system installer. Violates Device & Network Abuse (no out-of-Play code delivery). **Fix:** feature-flag the whole self-update path OFF in the Play build; ship updates through Play only.
4. **Accessibility auto-cancel + `isAccessibilityTool="true"` misdeclaration.** `InstallShieldService` reads the system installer window and presses Cancel/BACK (`InstallShieldService.kt`); `accessibility_service_config.xml:16` falsely claims accessibility-tool status. Highest-scrutiny accessibility pattern. **Fix (recommended):** strip the service from the Play build entirely (flavor split) and remove the `isAccessibilityTool` flag unconditionally.
5. **Notification-listener that reads/cancels all notifications.** `PhishingNotificationService` (BIND_NOTIFICATION_LISTENER_SERVICE) inspects every notification and cancels "phishing-like" ones. Very high rejection risk stacked with the above. **Fix (recommended):** remove from the Play build for the first submission.
6. **MANAGE_EXTERNAL_STORAGE (All files access).** `manifest:16`. Eligible for antivirus, but **requires the Console declaration + demo video**, and the redundant `READ_MEDIA_IMAGES/VIDEO/AUDIO` (`manifest:23–25`) weaken the narrative. **Fix:** submit the All-files declaration; delete the three READ_MEDIA_* lines.
7. **Missing store assets / privacy policy.** No **1024×500 feature graphic** (required) and **no public privacy-policy URL** (required given location + telemetry). Both block submission independent of code.

**Bottom line:** ship a dedicated **`play`** product flavor (no accessibility service, no notification listener, no self-update, no emulator/signature self-kill), fill ~9 Console declarations, host the privacy policy, and create one feature graphic. Then it is submittable — first to a closed/internal track.

---

## 2. Required CODE CHANGES before submission

> The `release` buildType is reused for direct/sideload self-update distribution, so a buildType split **cannot** separate Play from sideload. Introduce a **flavor dimension** and build the Play artifact as `playRelease`. Everything below hangs off that.

### 2.0 Add a `dist` flavor dimension (enables all the gates below)

**`ApkGuard/app/build.gradle.kts`** — inside `android { … }` (near `buildTypes`), add:

```kotlin
flavorDimensions += "dist"
productFlavors {
    create("play") {
        dimension = "dist"
        buildConfigField("boolean", "INSTALL_SHIELD", "false")
        buildConfigField("boolean", "SELF_UPDATE",    "false")
        buildConfigField("boolean", "NOTIF_LISTENER", "false")
        buildConfigField("boolean", "HARD_KILL",      "false")
    }
    create("direct") {
        dimension = "dist"
        isDefault = true
        buildConfigField("boolean", "INSTALL_SHIELD", "true")
        buildConfigField("boolean", "SELF_UPDATE",    "true")
        buildConfigField("boolean", "NOTIF_LISTENER", "true")
        buildConfigField("boolean", "HARD_KILL",      "true")
    }
}
```
Ensure `buildFeatures { buildConfig = true }` is set. Build the store artifact with `:app:bundlePlayRelease`.

### 2.1 BLOCKER — Stop the environment self-kill (emulator/root/frida/etc.)

**`ApkGuard/app/src/main/java/com/kiberqalqon/SecurityGuard.kt:84–94`** — replace the 9-entry list so only repackaging checks can be fatal (env checks become dead/advisory):

```kotlin
val checks = listOf<Pair<String, () -> Boolean>>(
    "tamper"    to { isTampered(ctx) }
    // "signature" is added back only AFTER the Google cert is whitelisted — see 2.2
)
```

**`ApkGuard/app/src/main/java/com/kiberqalqon/App.kt:98–105`** — belt-and-suspenders: never hard-kill in the Play flavor:

```kotlin
val result = SecurityGuard.runAllChecks(this)
if (BuildConfig.HARD_KILL && !result.passed) {
    Log.e("UzGuard", "Security check failed: ${result.reason}. Exiting.")
    try { NotificationHelper.showSecurityBlockNotification(this, result.reason) } catch (_: Throwable) {}
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(10)
}
```
With `HARD_KILL=false` in `play`, the reviewer's emulator (and any real device) never self-terminates. The direct flavor keeps its anti-RE behavior.

### 2.2 BLOCKER — Fix the Play-App-Signing boot-loop

`SecurityGuard.kt:44–52` and `build.gradle.kts:127` currently hold only the sideload cert `1CB3F378…`. Google re-signs Play builds.
- **First upload:** leave `signature` OUT of the `checks` list (done in 2.1). Do **not** re-enable it until the Google cert is known.
- **After the AAB exists in Console** → Play Console → **App integrity → App signing** → copy the **"App signing key certificate" SHA-256** (strip colons, uppercase), then:
  - `SecurityGuard.kt:50` → add the second cert: `add(Shield.dec("<encrypted-google-sha256>"))`
  - `build.gradle.kts:127` → comma-append: `-DKQ_EXPECTED_SIG=1CB3F378…,<GOOGLE_SHA256>`
  - Only then may you re-add `"signature" to { isSignatureInvalid(ctx) }` to the `checks` list. Until both files contain the Google cert, keep it off.

### 2.3 BLOCKER — Disable self-update in the Play build

**`SelfUpdate.kt` / `SelfUpdateActivity.kt`** — early-return the entry points when `!BuildConfig.SELF_UPDATE`:

```kotlin
fun checkAndNotify(context: Context) {
    if (!BuildConfig.SELF_UPDATE) return
    // …existing logic…
}
// and in downloadVerifyInstall(...): if (!BuildConfig.SELF_UPDATE) return
```
Also do not serve an `update` block in the `/api/config` payload to Play installs. (Keep the code path fully alive only in the `direct` flavor.) `REQUEST_INSTALL_PACKAGES` (`manifest:29`) stays only for the user-initiated "scan this APK, then let me install it" flow (ShareReceiver) — never for programmatic install.

### 2.4 BLOCKER — Remove the AccessibilityService from the Play build

Create **`ApkGuard/app/src/play/AndroidManifest.xml`**:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
    <application>
        <service android:name=".InstallShieldService" tools:node="remove" />
    </application>
</manifest>
```
Gate the UI so the row/wizard step vanish — **`Config.kt:231–232`**:

```kotlin
fun isInstallShieldEnabled(context: Context): Boolean =
    BuildConfig.INSTALL_SHIELD && prefs(context).getBoolean(KEY_INSTALL_SHIELD, true)
```
And **remove `android:isAccessibilityTool="true"` from `ApkGuard/app/src/main/res/xml/accessibility_service_config.xml:16` unconditionally** (the misdeclaration is a policy problem even in the direct build). Result: the `play` variant declares **no** accessibility service, so no Console accessibility declaration is needed. (`AccessibilityWatcher.kt` is a WorkManager worker that only *reads* `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — it is **not** an AccessibilityService, holds no `BIND_ACCESSIBILITY_SERVICE`, and is policy-compliant. Leave it untouched; make sure any Console answer distinguishes the two.)

### 2.5 BLOCKER/HIGH — Remove the NotificationListenerService from the Play build

Add to **`ApkGuard/app/src/play/AndroidManifest.xml`**:

```xml
<service android:name=".PhishingNotificationService" tools:node="remove" />
```
Also remove the `BIND_NOTIFICATION_LISTENER_SERVICE`-related permission/registration reaching the play manifest, and gate any UI entry behind `BuildConfig.NOTIF_LISTENER`. (If you later want it back, it needs its own notification-access declaration + Data Safety entry + in-app consent — out of scope for the first submission.)

### 2.6 HIGH — Delete the redundant media permissions

**`ApkGuard/app/src/main/AndroidManifest.xml:23–25`** — delete all three:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```
An APK scanner does not need photo/video/audio content; `MANAGE_EXTERNAL_STORAGE` already covers file reads on API 30+. Removing them avoids the Photo/Video Permissions declaration and tightens the All-files-access case. (Keep the `maxSdkVersion`-capped `READ_/WRITE_EXTERNAL_STORAGE` at `manifest:10–12` as-is — they are correctly scoped.)

### 2.7 MEDIUM — Prefer battery-settings intent over the direct dialog

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`manifest:60`) is a flagged permission. Where you launch `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, prefer routing to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` instead (lower rejection risk). Keep the permission — an always-on AV has a plausible claim — but be ready to justify it.

### 2.8 LOW — Keep `TestVirusGenerator` out of the Play variant

`TestVirusGenerator.kt` writes ZIPs with APK/DEX/ELF magic bytes and a live C2 string (`elrxzx.com`) at runtime, reachable from `DiagnosticsActivity`'s long-pressed "Test scanner." Google Play Protect static/dynamic scanning can false-positive. Gate the button/class behind `!BuildConfig.… (dev)` or exclude it from the `play` source set. Be ready to appeal a false GPP flag by explaining the AV signature DB.

**Housekeeping:** strings still say 7.6/7.8 in some `strings.xml` (stale vs the 8.7 build) — the listing deliberately omits a version number; just don't surface a wrong number anywhere user-facing.

---

## 3. Play Console DECLARATIONS to fill

All live under **App content → Sensitive app permissions/APIs**, **App content → Data safety**, and the per-API declaration forms. Paste-ready justifications:

| Permission / API | Console form | Paste-ready justification |
|---|---|---|
| **MANAGE_EXTERNAL_STORAGE** (`manifest:16`) | All files access declaration (+ demo video) | "UzGuard is an offline on-device antivirus. It must enumerate and read APK files across shared storage (Download, Telegram, file-manager folders) to scan them for banking-trojan malware before installation, and delete confirmed-malicious APKs. Scoped storage / MediaStore expose only media collections and cannot reliably reach the arbitrary Download/Telegram paths where malicious APKs land, so broad file access is essential to the core scanning feature." Attach a screen recording of the scan+delete flow. |
| **QUERY_ALL_PACKAGES** (`manifest:68`) | Permissions declaration → "Apps that must discover any/all installed apps" → **Device security / anti-malware** | "UzGuard is an on-device antivirus that must inspect every installed package — including malware that hides its launcher icon (a primary Ajina/dropper persistence trick) — to detect malicious packages, run daily re-scans, and evaluate dangerous permission combinations. The `<queries>` LAUNCHER filter returns only apps with a launcher icon, so it cannot see icon-hidden threats; full package visibility is core to detection." |
| **REQUEST_INSTALL_PACKAGES** (`manifest:29`) | Sensitive permission (manual review) | "Used only to hand a user-selected APK to the system PackageInstaller after our scan, so the user can complete an install they initiated. The app never installs code silently and never updates itself outside Google Play." (Self-update path is disabled in the Play build.) |
| **BIND_VPN_SERVICE** (`manifest:491–498`) | VpnService declaration | "The optional, user-enabled VPN is a purely local DNS sinkhole that runs entirely on-device to block known malware command-and-control domains. It does not route traffic to any remote server, does not inspect or collect user traffic, and fails open (disables itself) if upstream DNS is unreachable so connectivity is never lost." Default OFF; disclose in-app before `VpnService.prepare()`. |
| **USE_FULL_SCREEN_INTENT** (`manifest:55`) | Full-screen intent declaration | "Full-screen intent is used only to surface a critical, time-sensitive malware-detected warning (a banking trojan about to be installed) so the user can cancel installation immediately, including when the screen is locked." Treat as enhancement only — fall back to a high-priority heads-up notification if the grant is refused. |
| **FOREGROUND_SERVICE_SPECIAL_USE** (`manifest:51`) | Foreground service declaration → `specialUse` justification | "ProtectionService provides continuous, always-on antivirus protection: it watches for newly installed/downloaded APKs and performs real-time malware scanning. None of the standard foreground service types (dataSync, mediaPlayback, location, etc.) describe on-device security monitoring, so specialUse is the only accurate type per Android 14 guidance." Fallback if pushed back: re-type to a standard type. |
| **ACCESS_FINE / COARSE_LOCATION** (`manifest:46–47`) | No separate form (no background location) → **Data safety** + in-app Prominent Disclosure | Prominent-disclosure dialog **before** the runtime prompt naming the data, purpose, and that it is uploaded. Data safety: Location → collected + (declare) shared, purpose "App functionality / Security," optional, user-disableable, foreground only. Consider dropping FINE and keeping only COARSE for a city-level heatmap to reduce scrutiny. |
| **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS** (`manifest:60`) | Flagged permission (be ready to justify) | "Requested only so the always-on real-time protection foreground service and periodic scan are not killed by aggressive OEM battery managers (Xiaomi/Huawei/Samsung), which would silently disable protection." Prefer the settings-screen intent (see 2.7). |
| **SYSTEM_ALERT_WINDOW** (`manifest:54`) | No form; reviewed for necessity | "SYSTEM_ALERT_WINDOW is used solely to display a malware/phishing warning above other apps so the user is not tricked into completing a dangerous install." Keep usage minimal and dismissible. |
| **CAMERA** (`manifest:38`) | No form | Just-in-time request for QR/link scanning; QR decoded on-device, images not collected/sent. |
| **RECEIVE_BOOT_COMPLETED**, **POST_NOTIFICATIONS** | No form | Standard; ensure graceful degradation if notifications are denied. |
| **Shizuku** (`manifest:20`, provider `manifest:466–472`) | No Google form | "Shizuku is an optional user-installed helper; when present it lets the user grant shell-level file access so UzGuard can delete malware quarantined in other apps' sandboxes. It is never required and the app works fully without it." Confirm `ShizukuProvider` export/permission config isn't broader than the shizuku-api requirement. |

> **Not needed for the Play build** (removed by the flavor split): the **AccessibilityService** declaration and the **Notification access** declaration. If the owner ever overrides and ships InstallShieldService on Play, it additionally requires removing `isAccessibilityTool`, a runtime prominent-disclosure+affirmative-consent screen, a demo video, and the Console AccessibilityService-API-usage declaration — with elevated rejection risk. Recommended verdict: **do not.**

---

## 4. Store listing

Both locales below are within Play limits (title ≤30, short ≤80, full ≤4000). Version number intentionally omitted. Minor note for the owner: the Uzbek title uses "Skaner" and the Russian uses "антивирус APK" — either is fine on Play; align them if you prefer one framing. Do **not** re-introduce any "national cyber-defense / Republic of Uzbekistan" wording (government-affiliation / impersonation risk) — "created in Uzbekistan / O'zbekiston uchun" is the safe framing.

### 4a. Uzbek (primary)

**Title (20/30):** `UzGuard — APK Skaner`

**Short description (65/80):** `Zararli APK'larni oflayn aniqlang. Telegram troyanlaridan himoya.`

**Full description (~2870/4000):**

> UzGuard — O'zbekiston foydalanuvchilari uchun yaratilgan telefon xavfsizligi yordamchisi. U APK fayllarni siz o'rnatishdan OLDIN tekshiradi va zararli dasturlarni aniqlashga yordam beradi.
>
> Bugungi kunda firibgarlar zararli ilovalarni ko'pincha Telegram, brauzer yoki havolalar orqali "video", "taklifnoma" yoki "yangilanish" niqobida tarqatishadi. UzGuard aynan shunday hujumlardan himoyalanishga qaratilgan.
>
> QANDAY ISHLAYDI
> • Oflayn, telefonning o'zida tekshirish. APK tahlili qurilmada bajariladi — faylni tekshirish uchun uni hech qayerga yuklash shart emas va doimiy internet talab qilinmaydi.
> • Telegram, "Yuklab olishlar" va boshqa papkalardagi yangi APK fayllarni avtomatik kuzatadi.
> • Xavfli fayl topilsa, aniq va sodda ogohlantirish beradi — o'rnatishni to'xtatib turishga yordam beradi.
>
> ASOSIY IMKONIYATLAR
> • APK skaneri — fayl ichidagi shubhali ruxsatlar, xatti-harakat belgilari va yashirin qismlarni tahlil qiladi.
> • Telegram bank troyanlariga e'tibor — Ajina.Banker, RoundRift kabi Markaziy Osiyoga qaratilgan zararli oilalar belgilarini aniqlashga mo'ljallangan.
> • O'rnatilgan ilovalar tekshiruvi — telefondagi ilovalar orasidan shubhalilarini topishga yordam beradi.
> • Havola va QR tekshiruvi — Telegram yoki SMS'dan kelgan havolani ochishdan oldin baholaydi.
> • Soxta bank ilovalari auditi — haqiqiy bank brendiga taqlid qiluvchi ilovalarni ajratib beradi.
> • Ruxsatlar rentgeni — qaysi ilova SMS, qo'ng'iroq yoki joylashuvga kirishini sodda tilda ko'rsatadi.
> • Karantin — shubhali fayllarni ajratib qo'yish va keyin o'chirish yoki tiklash.
> • C2 domen filtri (ixtiyoriy, standart holatda o'chiq) — ma'lum zararli boshqaruv domenlariga ulanishni cheklashga urinadi.
>
> SODDA TIL
> Natijalar shifrlangan texnik atamalarda emas, tushunarli o'zbek tilida beriladi. Nima xavfli va nima qilish kerakligini oddiy so'zlar bilan aytamiz.
>
> MAXFIYLIK BIRINCHI O'RINDA
> • Google akkaunti yoki ro'yxatdan o'tish talab qilinmaydi.
> • Asosiy tekshiruv qurilmangizda, oflayn bajariladi.
> • Ixtiyoriy va anonim telemetriya — "Jamoatchilik xavfsizligi" opsiyasi faqat siz yoqsangiz ishlaydi va faqat texnik belgilar (fayl xesh va paket nomi) yuboriladi.
> • Joylashuv (GPS) faqat ixtiyoriy tahdid xaritasi uchun va faqat ruxsat bersangiz o'qiladi. Rad etsangiz ham ilova to'liq ishlayveradi.
> • Shaxsiy fayllaringiz, kontakt yoki xabarlaringiz sotilmaydi.
>
> TILLAR: O'zbekcha (asosiy) · Русский
>
> HALOL MA'LUMOT
> UzGuard xavfsizlikni oshirishga yordam beruvchi vosita bo'lib, hech bir dastur 100% himoyani kafolatlay olmaydi. Noma'lum manbalardan ilova o'rnatmang va shubhali havolalarni ochmang. Ilova ichida "Himoya chegaralari" bo'limida nimalarni qila olmasligimizni ham ochiq yozganmiz.
>
> Telefoningizni zararli APK'lardan himoyalashni bugundan boshlang.

### 4b. Russian

**Title:** `UzGuard — антивирус APK`

**Short description:** `Проверяет APK офлайн и ловит банковские трояны из Telegram и ссылок.`

**Full description:**

> Оффлайн-антивирус для Android, созданный в Узбекистане. UzGuard проверяет APK-файлы прямо на телефоне — ещё до установки приложения — и специально настроен против банковских троянов Центральной Азии (Ajina.Banker, RoundRift и похожих семейств), которые чаще всего рассылают через Telegram под видом «фото», «видео» или «обновления».
>
> ГЛАВНОЕ
> • Проверка APK офлайн. Движок анализа работает на устройстве — файл не нужно никуда загружать, чтобы получить вердикт.
> • Защита в реальном времени. Каждый новый APK в Telegram, Загрузках и других папках проверяется автоматически; при опасной находке вы сразу видите предупреждение.
> • Понятный результат. Вердикт на русском и узбекском простыми словами: опасно, подозрительно или безопасно — без технического жаргона.
>
> ЧТО УМЕЕТ
> • Ручная и полная проверка всех APK на телефоне.
> • Проверка уже установленных приложений, включая те, что прячут свой значок.
> • Проверка ссылок и QR-кодов из Telegram и SMS перед переходом.
> • «Это сообщение — мошенничество?» — офлайн-проверка текста на признаки обмана.
> • Поиск поддельных банковских приложений, копирующих известные банки.
> • «Рентген разрешений» — показывает приложения с опасными комбинациями доступа.
> • Карантин: подозрительный файл можно изолировать, а потом восстановить или удалить.
> • Оценка защиты (0–100) с понятными шагами, как её повысить.
> • Быстрая плитка в шторке и виджет на рабочем столе.
>
> ДОПОЛНИТЕЛЬНО, ПО ВАШЕМУ ВЫБОРУ (по умолчанию выключено)
> • Фильтр опасных доменов через локальный VPN. Работает как «защитный DNS»; если фильтр даёт сбой — интернет не блокируется.
> • Карта угроз по стране: устройство отправляет лишь приблизительное местоположение и только пока вы пользуетесь приложением.
>
> КОНФИДЕНЦИАЛЬНОСТЬ
> • Проверка файлов идёт офлайн, на вашем устройстве.
> • Анонимная статистика об угрозах отправляется только с вашего согласия и содержит лишь хеш и имя пакета — без ваших личных файлов, контактов и сообщений.
> • Согласие можно отозвать в настройках в любой момент.
> • Дополнительные функции (VPN-фильтр, геолокация) включаются вручную и полностью необязательны.
>
> ЧЕСТНО О ГРАНИЦАХ
> Ни один антивирус не заменяет осторожность. UzGuard не может удалить файл из закрытой папки другого приложения — это ограничение Android; в таком случае он подскажет, как убрать файл вручную. И помните: настоящие приложения и обновления не приходят как APK в мессенджерах.
>
> ПОЧЕМУ НУЖНЫ РАЗРЕШЕНИЯ
> • Доступ к файлам — чтобы находить и проверять APK.
> • Просмотр списка приложений — чтобы находить вредоносные приложения, спрятавшие значок.
> • Уведомления и показ поверх окон — чтобы вовремя предупредить об угрозе.
> • Установка приложений — чтобы после проверки безопасный APK можно было поставить, а опасный — заблокировать.
> Остальные разрешения (камера для QR, геолокация для карты, VPN) запрашиваются только при включении соответствующей функции.
>
> Приложение бесплатное. Интерфейс на узбекском и русском.

> **Note on the RU copy:** it mentions "Живой щит установки (спец. возможности)" in the source draft. Since the accessibility service is removed from the Play build (§2.4), **delete that bullet** before pasting — the listing must not advertise a feature the Play binary doesn't contain. (Removed above.) If an English default-listing language is required by your Console setup, prepare an EN variant too.

---

## 5. Assets checklist

| Asset | Status | Spec | Action |
|---|---|---|---|
| **Hi-res icon 512×512** | ✅ Present | 32-bit PNG ≤1024 KB | Use `playstore_512.png` (repo root, 512×512, ~96 KB). It has an alpha/transparent bg — Play masks it on a colored tile, so optionally flatten onto anor `#C2143D` first. Not strictly required. |
| **Feature graphic 1024×500** | ❌ **MISSING (BLOCKER)** | PNG/JPEG, **no alpha**, ≤15 MB | Must create. Composite `pitch/assets/logo.png` + "UZGUARD" wordmark on the anor brand background; `pitch/index.html` hero is a ready visual reference. Submission is blocked without it. |
| **Phone screenshots (2–8)** | ✅ Present & reusable | 320–3840 px/side, long side ≤2× short side | Upload 4–8 of `pitch/assets/screen-*.png` (1360×2240, ratio 1.647 — compliant). Good set: dash-light, dash-dark, alert-scan, alert-result, apk-list, result-banker, result-dropper, settings, onboarding, stats. |
| Root-level `kq_screen*.png`, `s3–s5.png` | ⚠️ **Do NOT submit** | — | All 1080×2400 (ratio 2.22 > 2×) → Play rejects; s3/s5 are near-blank placeholders. Ignore them (or crop/pad to ≤1080×2160 first). |
| `pitch/assets/screen-cloud-panel.png` | — | — | Landscape web-panel shot, not a phone screenshot. Optionally use as a 7"/10" tablet screenshot only. |
| **Privacy policy URL** | ❌ **MISSING (BLOCKER)** | Public HTTPS URL | Host the provided bilingual HTML (see §7). |
| **Store listing text** | ✅ Provided | See §4 | Paste uz + ru (and EN if required). |

---

## 6. Data Safety + Content Rating

### 6a. Data Safety form

**Top-level answers:**
- **Collects/shares user data?** → **Yes** (only when optional features are enabled — still answer Yes).
- **All data encrypted in transit?** → **Yes** (`usesCleartextTraffic="false"` + network-security config + certificate pinning; HTTPS-only in `CloudTelemetry.baseUrl()`).
- **Users can request deletion?** → **Yes** (in-app "Revoke consent" + "Clear history"; a working deletion-request email/URL must be entered in Console; uninstall wipes local data).

**"Collected" vs "Shared":** Vercel, Supabase, and the developer's Telegram channel act as processors/your own infrastructure — Google excludes processors from "Shared," so items are **Collected: Yes / Shared: No** by default. Be ready to switch the threat-sample items to **Shared: Yes** if a reviewer treats the Telegram channel as third-party. **The per-install device identifier is a random UUID** — no IMEI/serial/MAC/phone number/Advertising ID/Android ID is read or sent.

| Data type (Google category) | Collected | Shared | Optional | Purposes |
|---|---|---|---|---|
| **Precise location** | Yes | No* | Optional | App functionality; Fraud prevention, security | 
| **Approximate location** | Yes | No* | Optional | App functionality; Fraud prevention, security (server derives city from IP for the map) |
| **Name** | Yes | No | Optional | App functionality (only if user joins a group via `GroupJoinActivity`) |
| **Phone number** | Yes | No | Optional | App functionality (user-typed on group-join; not read from SIM) |
| **Device or other IDs** | Yes | No | Optional | Analytics; App functionality; Fraud prevention (random per-install UUID, not a hardware/ad ID) |
| **App activity → Other actions** | Yes | No | Optional | Fraud prevention, security; Analytics (scan events: package, label, verdict, score, reasons, dangerous perms, duration) |
| **Files and docs** | Yes | No | Optional | Fraud prevention, security; App functionality (the suspected-malware APK ≤50 MB + SHA-256, only for DANGER/SUSPICIOUS when sharing ON — never safe files, never personal docs) |
| **Crash logs** | Yes | No | Optional | Analytics (stacktrace + model + version → developer Telegram, gated on consent) |
| **Diagnostics** | Yes | No | Optional | Analytics; App functionality (model, Android/app version, protection counters) |

\* Foreground only, **no ACCESS_BACKGROUND_LOCATION**; sent only if location permission granted AND "Community safety" opt-in is ON (default OFF).

**Mark NOT collected (explicit code exclusions — state if asked):** installed-apps list (never sent), contacts/SMS/messages/call logs (scam-message checker is 100% on-device — text never leaves the device), photos/videos/audio content (media perms removed per §2.6). All cloud telemetry is gated behind BOTH the mandatory ToS consent AND the default-OFF "Community safety" opt-in, so a default install sends nothing to the cloud.

### 6b. Content Rating (IARC questionnaire)

- Category: **Utility / Productivity / Tools** (reference-style questionnaire).
- Violence, sexual content, profanity, controlled substances, gambling, user-generated content sharing: **No** to all.
- The app references malware/security but contains no violent, sexual, or otherwise mature content. The bundled malicious-hash/cert/domain DBs and `TestVirusGenerator` are internal detection data, not user-facing content.
- Does it share the user's location with other users? The threat-map aggregates anonymized/coarse data — answer per the questionnaire's exact wording; this affects Data Safety, not the age rating.
- **Expected result: Everyone / PEGI 3 / rated for all ages.**

---

## 7. Privacy policy (separate HTML file to host)

A complete bilingual (Uzbek + Russian) privacy-policy page is provided as **standalone HTML** (theme-aware, anor-branded, with a language toggle). Deliver it as its own `.html` file — it is **not** inlined here.

Before publishing you must:
1. Replace the `[DATE]` placeholder in the header ("Kuchga kiradi / Дата вступления") with the effective date.
2. Add a real contact/deletion-request email (matching the Data Safety "request deletion" answer).
3. Confirm it covers: on-device scanning; anonymous opt-in device telemetry (random UUID, package/hash) to Vercel/Supabase; optional foreground GPS for the threat map; camera (QR, on-device); QUERY_ALL_PACKAGES; that VPN/notification-scan data never leaves the device; and that self-update is disabled in the Play build.

**Recommended hosting (simplest path):** add a `/privacy` page to the existing Vercel panel — a new file under `ApkGuard/cloud/src/pages` (e.g. `Privacy.tsx`, or serve the static HTML) on `uzguard-cloud.vercel.app` — then paste that URL into **Play Console → App content → Privacy policy** and link it from `ConsentActivity`. The policy text and Data Safety form (§6a) must match exactly; mismatch is a common rejection cause.

---

## 8. Step-by-step submission checklist

**A. Prerequisites**
1. Google Play Developer account created ($25 one-time), identity + D-U-N-S/address verification complete (new accounts face extra verification and a closed-testing-first requirement).
2. Host the privacy policy (§7); note the public URL.
3. Create the 1024×500 feature graphic (§5).

**B. Code / build (do §2 first)**
4. Add the `dist` flavor dimension; apply all §2 gates (emulator kill off, self-update off, no accessibility service, no notification listener, media perms removed, `isAccessibilityTool` removed).
5. Build the AAB: `:app:bundlePlayRelease` (signed with your upload key). Do **not** ship `release`/`releasefast` sideload builds to Play.
6. Smoke-test the `playRelease` build **on an emulator** — confirm it launches and does **not** self-terminate (this is the exact environment the reviewer uses).

**C. Console setup**
7. Create the app (default language = Uzbek or your primary; add Russian). Enroll in **Play App Signing** (mandatory).
8. Store listing: paste title/short/full for uz + ru (§4); upload icon, feature graphic, 4–8 screenshots (§5).
9. **App content** section — complete every declaration:
   - Privacy policy URL.
   - Data safety (§6a) — must mirror the privacy policy.
   - Content rating questionnaire (§6b).
   - Target audience, ads (declare **no ads**), news, government apps (declare **not** a government app), financial features (none).
   - Sensitive permissions: All files access (+ demo video), QUERY_ALL_PACKAGES, VpnService, Full-screen intent, Foreground-service `specialUse` (§3).
10. Set pricing = Free; select countries (Uzbekistan + as desired).

**D. Testing track first (do not go straight to production)**
11. Upload the AAB to **Internal testing** (or Closed testing — required for new personal accounts before production). Add tester emails.
12. After upload, grab the **Google app-signing certificate SHA-256** → apply §2.2 step 2 → rebuild only if/when you re-enable the signature check.
13. Review the **Pre-Launch Report** (runs on real Google devices/emulators): confirm no startup crash, no unexpected background-activity/overlay warnings, no self-termination. Fix anything flagged.
14. Test the release on the internal track on a real device and an emulator.

**E. Promote to production**
15. Once internal testing is clean and all declarations are green, create a **Production** release, roll out (consider a staged 20% rollout), and submit for review.
16. Keep the `direct` flavor (with self-update + install-shield + anti-RE) for out-of-Play/sideload distribution — never upload it to Play.

**Reality check:** even after all fixes, the **All files access + VpnService + QUERY_ALL_PACKAGES** trio guarantees manual review and possible back-and-forth. The demo video and precise, honest justifications (§3) are what get antivirus apps through. Budget for at least one review round-trip.