# Аудит фич KiberQalqon/UzGuard — 2026-08-13

Полный проход по всем 150 Kotlin-файлам (`ApkGuard/app/src/main/java/com/kiberqalqon/`).
Метод: 12 доменных агентов (чтение кода + grep call-sites + манифест + layout XML) + адверсариальный
верификатор всех claims «мёртвое/удаляемое». 13 агентов, 435 tool-вызовов, ~1.94M токенов.
Ветка на момент аудита: `feat/play-flavor`.

## Итог

| Статус | Кол-во |
|---|---|
| Работает | 137 |
| Работает частично (дефект или дремлет) | 12 |
| Мёртвый код (0 ссылок) | 2 |
| **Всего фич/модулей** | **151** |

**Ядро защиты целиком живое.** Все детекторы вызываются из `ApkScanner.scan()`, все воркеры
планируются, все сервисы зарегистрированы. Правило «никогда не false-SAFE» соблюдено:
нечитаемый файл → SUSPICIOUS (`ApkScanner.kt:748-757`), любой Throwable → SUSPICIOUS без
кэширования (`ApkScanner.kt:1587-1605`). ML — advisory, verdict не трогает. UserWhitelist
понижает только SUSPICIOUS, DANGER — никогда (`ApkScanner.kt:1561`).

---

## 1. Удалить можно (верифицировано полным grep: код + res/ + манифест + gradle + тесты)

| Файл | Что это | Доказательство |
|---|---|---|
| `RadarScanView.kt` | Анимация радара до редизайна | 0 ссылок; InitialScan использует KqRingView (`activity_initial_scan.xml:64`). **НЕ трогать** drawable `ic_radar_scan` — он живой в `item_onboarding.xml:24-30` |
| `SpeedometerView.kt` | Старое хиро-кольцо дашборда | 0 живых ссылок; только упоминания в комментариях `DashboardNewActivity.kt:798`, `KqScoreView.kt:17` |
| `ServerUpload.kt` + ветки в GuardWorker | Gen-1 Flask-загрузка APK | Дремлет: `DEFAULT_SERVER_URL=""` (`build.gradle.kts:95`), URL задаётся только в owner-mode строке настроек. Вытеснен CloudTelemetry + Telegram sendDocument. При удалении зачистить `GuardWorker.kt:94-95, 282-289, 344-351` и plumbing `srv_url`. ⚠️ Нюанс: `isUploadEnabled` default **TRUE** (`Config.kt:123`) — пустой URL единственный предохранитель |

Мелкий мёртвый код (удалять по желанию, вреда нет):
- `ApkScanner.matchesSignature` (`ApkScanner.kt:417`) — только тест использует.
- `PinStore.clear()` — 0 вызовов → **фичи «отключить PIN» не существует** (см. §2.8).
- `Config.incrementCommunityReportCount` (`Config.kt:365`) — счётчик никогда не растёт, никто не читает.
- `ConsentActivity` ветка `REQ_LOCATION` (`:136`) — запрос локации переехал в ProtectionStatusActivity.
- `AutoScanActivity.resultShown` — write-only переменная.
- `InstallApproval.isApproved()` — per-package `ok:<pkg>` записи пишутся, но никогда не читаются.
- `SecurityGuard`: параметр `strict` не используется; `TRUSTED_INSTALLERS` — мёртвые данные (isUntrustedInstaller всегда false, log-only).

---

## 2. Реальные баги (работает не так, как задумано)

### 2.1 PhishingNotificationService — фича молча мертва у всех пользователей 🔴
Notification access для СВОЕГО листенера **никогда не запрашивается**: ни онбординг, ни
настройки, ни CheckupWizard, ни дашборд не открывают `ACTION_NOTIFICATION_LISTENER_SETTINGS`
для само-выдачи. Единственные два вызова этого экрана (`NotificationHelper.kt:600`,
`HiddenThreatsActivity.kt:175`) — для ОТЗЫВА чужих листенеров. Тумблер «phishing» в настройках
просто флипает pref. Итог: листенер не биндится → весь анти-фишинг уведомлений инертен,
если пользователь сам не найдёт системную настройку.
Бонус-баги: мёртвое ключевое слово `о'tkazma` (`:90`, первая буква — кириллическая `о`,
проверено побайтово); pref `uzguard_notif_links` растёт бесконечно.

### 2.2 WifiGuard — вероятный no-op на Android 12+ 🔴
`ProtectionService.kt:129` создаёт `NetworkCallback()` **без** `FLAG_INCLUDE_LOCATION_INFO` →
на API 31+ SSID в `transportInfo` всегда `<unknown ssid>` → `WifiGuard.kt:75 cleanSsid()`
возвращает null → предупреждение об открытой сети не срабатывает никогда. Фикс — одна строка:
`NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` под `SDK_INT>=31`.

### 2.3 SecurityScore — «-20 навсегда» за удалённое приложение 🔴
`SecurityScore.kt:122-126` считает `dangerousAppCount` по ключам `verdict_*==DANGER` в prefs
`uzguard_rescan`, но ключи **не чистятся при удалении приложения** (`PackageInstallReceiver.kt:72-81`
на PACKAGE_REMOVED шлёт только телеметрию). Пользователь удаляет заражённое приложение —
штраф и карточка «Xavfli ilova o'rnatilgan» висят вечно, «добей до 100» невыполнимо.
Фикс: purge `verdict_<pkg>` в обработчике PACKAGE_REMOVED или фильтр по установленным.

### 2.4 `trigger_scan` extra — шлют трое, не читает никто 🟠
`ScanTileService.kt:34`, `CheckupWizardActivity.kt:222`, `SecurityScoreActivity.kt:164` кладут
`putExtra("trigger_scan", true)` — **MainActivity не читает ни одного extra** (grep: нет
getBooleanExtra). QS-тайл и кнопки «Tuzatish» открывают экран, но скан не автостартует;
работает только случайно через auto-scan первого resume холодного старта.

### 2.5 AccessibilityWatcher алертит на самого себя 🟠
Цикл алертов (`AccessibilityWatcher.kt:49-52`) не исключает `ctx.packageName` (в отличие от
`NotificationAccessWatcher.kt:44`). Включаешь собственный InstallShieldService (к чему активно
ведёт `InstallProtectionGuide.kt:142`) → следующий прогон watcher'а поднимает полноэкранный
алерт «банкер-угроза» против UzGuard с кнопкой «Удалить» + телеметрия.

### 2.6 AutoScanActivity — регресс уже исправленного delete-бага 🟠
`isScratchCopy()` (`AutoScanActivity.kt:1316`) считает scratch-копией ЛЮБОЙ путь с `/cache/` —
ровно то, что чинили в `ScanResultActivity.kt:680-686`. Реально удалённый файл из кэш-папки
Telegram получает ложное «копия удалена, оригинал остался» вместо карточки успеха.

### 2.7 MainActivity доступна до согласия 🟠
QS-тайл и виджет обходят гейты consent/onboarding → MainActivity может сканировать
до принятия ToS (локально, но для Play-комплаенса лучше загейтить).

### 2.8 PIN-замок — три дыры 🟠
1) MODE_SET не спрашивает старый PIN — кто держит разблокированный телефон, молча меняет PIN.
2) PIN-ом загейчен только тумблер фоновой защиты; VPN, авто-удаление, link guard, сирена
   отключаются без PIN (`SettingsActivity.kt:496-537`) — скрипт мошенника «отключи антивирус»
   заблокирован лишь частично.
3) Нет rate-limit на 4-значный PIN (10k комбинаций, попытки не ограничены).
Плюс `PinStore.clear()` мёртв — отключить PIN вообще нельзя.

### 2.9 HeartbeatWorker — no-op у обычных пользователей 🟡
`CloudTelemetry.pollCommands` стоит ПОСЛЕ early-return `if (!TelemetryReporter.isConfigured)`
(`HeartbeatWorker.kt:40-50`) → на устройствах без личного бота (все обычные юзеры) воркер —
полный no-op, «6-часовой фоновый канал команд» не работает. Смягчено: `NewsNotifier.kt:62`
подцепляет pollCommands к циклам GuardWorker, команды доезжают.

### 2.10 OemAutostartGuide — половина модуля мертва 🟡
`openAutostartSettings()`, `openSystemBatterySettings()`, `instructions()`, `wasShown/markShown/
isDismissed` — 0 вызовов. Нотификация «включи автостарт» есть, а экран автостарта MIUI/EMUI/
ColorOS (ради которого модуль писался, `:140-226`) не открывает ничего. Либо подключить к
ProtectionStatusActivity, либо выпилить мёртвую половину.

### 2.11 Мёртвые сигнатуры/ключевые слова 🟡
- `DexPatternAnalyzer.kt:142` — needle `setMethod(ZipEntry.DEFLATED)` не может встретиться в
  DEX string pool (это compile-time выражение) → его метка в `evasionLabels` (`ApkScanner.kt:1257`)
  никогда не даёт evasionCount.
- `ScamTextAnalyzer.kt:44` — `"sud "` с хвостовым пробелом: hasWord требует не-букву ПОСЛЕ
  пробела → «sud majlisi» не матчится (кириллица «суд» работает).
- `LinkScanner.kt:885` — `.apk` substring-catch-all флажит DANGER любую ссылку с «.apk» в
  path/query (в т.ч. слаги статей); `:112` BANK_OK_TLDS={uz,com,ru} → `payme.ru`, зареганный
  атакующим, получает подавление слабых флагов (окно evasion).
- `InstallShieldService`: в списке инсталлеров опечатка `p.android.packageinstaller` (безвредна).

### 2.12 SelfUpdate не пишет InstallApproval 🟡
Док в шапке InstallApproval обещает approve() перед установкой обновления — grep: SelfUpdate
его не вызывает. Edge-case: если own-label не читается в течение 5 мин после DANGER-вердикта,
install shield может заблокировать собственное обновление.

---

## 3. Работает частично ПО ДИЗАЙНУ (не баги — ограничения платформы/задумки)

| Фича | Ограничение |
|---|---|
| InstallShieldService | Мёртв в play-флейворе (BuildConfig.INSTALL_SHIELD=false + вырезан из манифеста — так и задумано для Play). Требует ручного включения accessibility (Android 13+ restricted settings). Гонка с быстрым тапом «Установить» — бэкстоп PackageInstallReceiver |
| Link interceptor (Havola qalqoni) | Работает только если юзер назначил UzGuard дефолтным обработчиком ссылок; in-app браузеры (Telegram) обходят — прикрывает VPN-фильтр |
| IconImpersonationDetector | База — только реально установленные бренды: без Telegram/Click/Payme на телефоне не детектит ничего. Фейк «иконка+имя пакета» ловится AppReputation.SIGNATURE_MISMATCH — net gap нет |
| SystemStateReceiver | SIM-детект по MCC+MNC: смена SIM того же оператора невидима (нет READ_PHONE_STATE). Только телеметрия |
| VPN C2-фильтр | DoT/DoH/QUIC/TCP-DNS не перехватывает; банки исключены намеренно; сервис не foreground → low-memory kill до рестарта |
| BankAppAudit | Запускается только вручную из BankGuardActivity — не участвует в авто-скане; установленный фейк-банк проактивного алерта от ЭТОГО движка не получает (ловят общие сканеры) |
| MultiPathFileObserver | Подпапки, созданные после startWatching, не наблюдаются до рестарта сервиса (компенсируют poll + GuardWorker) |
| InstalledAppsRescanWorker | Бюджет 50 пакетов без курсора ротации: у юзера с >50 sideload-приложений хвост может не сканироваться никогда |
| TestVirusGenerator | Debug-диагностика, но гонит синтетику через ПОЛНЫЙ пайплайн: портит статистику, пишет 6 фейковых DANGER в историю, шлёт телеметрию, и ScanCache.clear() перед каждым прогоном |

---

## 4. Ядро — не трогать (полный список живой защиты)

Скан-движок: ApkScanner, RuleEngine/RuleStore (правила реально приходят из signed cloud-фида,
`CloudBlacklist.kt:66-67` + cold-start `App.kt:131`), ThreatDb, FullPhoneScan, HiddenThreatScanner,
все 12 детекторов, MaliciousHashes/Certs/Packages/Domains, FilenameHeuristic, AppReputation.
Реалтайм: ProtectionService, оба FileObserver, PackageInstallReceiver, GuardWorker, BootReceiver,
ScreenUnlockReceiver, InstalledAppsRescanWorker, оба Watcher'а, RemoteAccessDetector.
Действия: Quarantine (AES-CTR), FileDeleter-лестница, ShizukuDeleter, VPN-фильтр, LinkScanner.
Самозащита: SecurityGuard, SelfGuard, Shield, NativeBridge, Secrets.
Облако: CloudBlacklist/RemoteConfig (подписи проверяются), CloudTelemetry, SelfUpdate (подпись+церт).
Всё перечисленное — wired, вызывается, вердиктная логика корректна.

---

## 5. Рекомендации по приоритету

1. **Фикс 2.1** — открыть notification-access для своего листенера (онбординг/ProtectionStatus/CheckupWizard шаг). Сейчас анти-фишинг уведомлений — мёртвый груз у 100% юзеров.
2. **Фикс 2.2** — одна строка `FLAG_INCLUDE_LOCATION_INFO`; Wi-Fi guard оживает на Android 12+.
3. **Фикс 2.3** — чистка `verdict_<pkg>` на PACKAGE_REMOVED; SecurityScore перестаёт врать.
4. **Фикс 2.4** — прочитать `trigger_scan` в MainActivity.onNewIntent/onCreate; чинит сразу три фичи.
5. **Фикс 2.5** — исключить свой пакет в AccessibilityWatcher (одна строка по образцу NotificationAccessWatcher).
6. **Удаление** — RadarScanView.kt, SpeedometerView.kt (безопасно, верифицировано); ServerUpload — решить: выпилить или оставить как owner-инструмент.
7. PIN-дыры (2.8) — до Play-релиза желательно: старый PIN при смене + backoff + PIN-гейт на все защитные тумблеры.

Полные материалы прогона: журнал воркфлоу
`~\.claude\projects\...\subagents\workflows\wf_4990d511-282\journal.jsonl`.

---

# ДОПОЛНЕНИЕ: исправления и чистка (2026-08-13, та же сессия)

Итог: **45 файлов, +716/−1302 строк, НЕ закоммичено** (ветка feat/play-flavor).
Ревью-воркфлоу (4 линзы + адверсариальная верификация, 8 агентов): 2 major-находки
в самих правках — обе исправлены; 2 major опровергнуты; 11 minor — 7 исправлено.

## Починено (из §2 аудита)
- 2.1 Анти-фишинг: notification-access теперь запрашивается (Settings-тумблер → диалог
  → ACTION_NOTIFICATION_LISTENER_SETTINGS; + строка и шаг мастера в ProtectionStatusActivity,
  только direct-флейвор). Мёртвое слово `о'tkazma` (кириллическая о) заменено, апострофы
  нормализуются, преф `uzguard_notif_links` ограничен 500 записями.
- 2.2 Wi-Fi guard: `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` на API 31+
  (ProtectionService) + generic-fallback «открытая Wi-Fi» при нечитаемом SSID
  (24ч dedup, локализованное имя из ресурса).
- 2.3 SecurityScore: чистка `verdict_<pkg>` на PACKAGE_REMOVED + фильтр по установленным.
- 2.4 «Тупик»: MainActivity читает `trigger_scan` (onCreate+onNewIntent+onResume форс-скан,
  переживает rotation через saved state) — QS-тайл и «Tuzatish» SecurityScore реально сканируют.
- 2.5 AccessibilityWatcher: skip собственного пакета — само-алерт «банкер» устранён.
- 2.6 AutoScanActivity: `/cache/`-эвристика isScratchCopy убрана (только свой cacheDir);
  мёртвая переменная resultShown удалена.
- 2.7 MainActivity: consent-gate (QS-тайл/виджет до согласия → Splash).
- 2.8 PIN: смена требует старый PIN; backoff 5 ошибок → 30s×2 (cap 8 мин); PIN-гейт
  на ВСЕ защитные тумблеры (фон, авто-удаление, фишинг, VPN, link guard, сирена);
  «Отключить PIN» добавлено (PinStore.clear теперь вызывается).
- 2.9 HeartbeatWorker: pollCommands ПЕРЕД isConfigured-гейтом.
- 2.10 OEM: kill-уведомление → EXTRA_OPEN_AUTOSTART → диалог с инструкцией +
  openAutostartSettings (мёртвая половина ожила; wasShown/isDismissed удалены).
- 2.11 Мёртвые сигнатуры: DEX-needle `setMethod(ZipEntry.DEFLATED)` удалён (+ sync
  evasionLabels в ApkScanner); `"sud "` → `"sud"`; LinkScanner `.apk` — границы слова
  (статьи больше не DANGER) + добавлен `.apks` (split-APK).
- 2.12 SelfUpdate вызывает InstallApproval.approve перед установкой.
- Опечатка `p.android.packageinstaller` убрана (InstallShieldService + a11y config XML).
- InstalledAppsRescanWorker: ротационный курсор (хвост >50 sideload-приложений теперь
  сканируется; курсор пишется ПОСЛЕ партии — kill не пропускает пакеты).
- ConsentActivity: мёртвая ветка REQ_LOCATION удалена. Док-комментарии Quarantine
  (fromPackage-обещание) и CommunityReportClient (APK-файл шлётся по consent v4) исправлены.

## Чистка настроек («toza dastur»)
Удалено полностью: **ServerUpload** (файл + ветки GuardWorker + plumbing srv_url/upload
в Config/Settings/Diagnostics/ReportProblem + DEFAULT_SERVER_URL из gradle),
**CheckupWizardActivity** (дубль SecurityScore; файл+манифест+строка), **RadarScanView**,
**SpeedometerView**, дубликат строки «Yordam».
Тумблеры убраны, фичи всегда-ВКЛ (Config-геттеры hardcode true, сеттеры удалены):
авто-обновление, недельный отчёт, новости-пуши, Wi-Fi guard, remote-access алерт,
«ilova yangilandi». Осталось тумблеров: фон, авто-удаление, анти-фишинг, VPN,
link guard, сирена (все — под PIN на выключение).

## Находки ревью моих же правок (исправлены)
- VPN: отказ в системном VPN-диалоге дёргал PIN-запрос и оставлял тумблер ложно-ON —
  revert теперь под ready=false.
- Play-флейвор: тумблер анти-фишинга был «зомби» (всегда OFF) — строка скрывается
  при !BuildConfig.NOTIF_LISTENER (+id divPhishing).
- Автостарт-диалог не повторяется при rotation (removeExtra + savedInstanceState-гейт).
- Хардкод-узбекский в новых диалогах заменён ресурсами (uz+ru): phishing-access,
  PIN-меню, generic Wi-Fi имя.

## Принятые残 minor (не чинил, задокументировано)
- PinStore backoff на wall-clock (перевод часов сбрасывает лок) — сопоставимо с
  reboot-обходом, усложнение не окупается.
- InstallApproval.approve при self-update штампует глобальный KEY_LAST_OK (90s окно
  подавления label-null эвристики щита) — тот же паттерн, что в AutoScanActivity.
- PinLockActivity остаётся хардкод-узбекским (весь файл такой до этой волны).

## Отложено (чипы созданы)
1. Баг «нет программы для открытия ссылки» (LinkForwarder/выбор браузера).
2. Удаление Oila qalqoni (экран; механизм групп остаётся).
3. Тест на реальном устройстве (владелец подключит телефон).

⚠️ Сборка локально невозможна (RAM/OneDrive) — проверка компиляции только через CI
после коммита. Код-ревью пройдено, но `assembleDebug` не запускался.
