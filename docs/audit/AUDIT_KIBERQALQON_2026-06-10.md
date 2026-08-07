# Аудит KiberQalqon — баги и UX-неудобства (2026-06-10)

Второй полный аудит после ремедиации 2026-06-07. Запущен по запросу владельца:
«выяви баги и неудобства». Метод: 12 поисковых агентов по зонам (движок, фон/службы,
UI/UX, Telegram, cloud API, cloud SPA, сборка, самозащита, devauth/SQL, python-бот,
прочие анализаторы) + проверка ремедиации; каждая находка medium+ проходила состязательную
проверку 1–2 скептиками (critical/high — двумя независимыми линзами). Дубли прошлого аудита
исключены. Подтверждено ~96 находок; ниже — приоритизированная выжимка.

Самая опасная находка (**CRIT-01**) проверена вручную по исходному коду.

---

## Краткое резюме

Ремедиация 2026-06-07 в основном на месте и закоммичена (проверены десятки фиксов — см.
раздел «Статус ремедиации»). Но новый проход вскрыл слой проблем, который прошлый аудит почти
не трогал: **жизненный цикл фоновой защиты и UX**. Сильнейшие находки:

- **CRIT-01 (cloud):** новая per-device auth-схема (сердце anti-RE-бэкенда) переиспользует
  один HMAC-ключ для device-token и для session-токена панели **без доменного разделения**.
  `/api/device/register` превращается в оракул подписи — реверсер APK (ровно тот противник,
  против которого схема и создана) получает **полный owner-доступ к панели**. Это строго хуже,
  чем мир до внедрения схемы.
- **Фоновая защита незаметно умирает** и **не управляется тумблером**: на Android 12+ после
  OEM-килла `ProtectionService` больше не поднимается даже при открытии приложения; тумблер
  «выключить» ничего не выключает, «включить» — не запускает. Главное обещание «24/7 himoya»
  ломается тихо.
- **Антивирус «орёт» при каждой разблокировке** на старый файл, **карантин обещает откат,
  которого нет**, **флагманские экраны недостижимы**, **согласие на обмен данными
  принудительно** — это причины удаления приложения.
- **Play-дистрибуция (заявленная цель) сегодня невозможна**: Play App Signing вызовет и
  boot-loop самозащиты, и false-DANGER на легитимных обновлениях гос/банк-приложений.
- **Анти-брутфорс панели обходится** (подмена X-Forwarded-For + неатомарный счётчик),
  **список устройств и Excel-экспорт молча обрезаны до 50**, **demo.html мёртв на проде**.

Полный список ниже сгруппирован по серьёзности; раздел **UX-неудобства** вынесен отдельно
по прямому запросу.

---

## КРИТИЧЕСКАЯ

### CRIT-01 — Переиспользование HMAC-ключа → `/api/device/register` как оракул owner-сессии (полный захват панели)
- **Файлы:** `cloud/lib/devauth.ts:28-31,45-49`; `cloud/lib/session.ts:12-13,20-22,83-87`; `cloud/api/device/[id].ts:68-85,141-142`
- **Проверено вручную по коду.**
- **Суть:** `issueDeviceToken(t) = base64url(HMAC-SHA256(t, tokenKey()))`, где
  `tokenKey() = DEVICE_TOKEN_SECRET || SESSION_SECRET || ADMIN_SECRET`. Подпись сессии
  `sign(p) = base64url(HMAC-SHA256(p, key()))`, где `key() = SESSION_SECRET || ADMIN_SECRET`.
  Обе функции — HMAC-SHA256 над **одной строкой**, base64url, **без доменного префикса**. Когда
  `DEVICE_TOKEN_SECRET` не задан (так в `.env.example`, и код это явно поддерживает как
  валидный фолбэк), ключи **совпадают**.
- **Эксплойт:** `handleRegister` возвращает в теле ответа
  `device_auth_token = issueDeviceToken(b.device_token)` для **полностью управляемого
  атакующим** `device_token`, причём register доступен по legacy `x-device-secret`
  (`DEVICE_SHARED_SECRET` — извлекаем из APK по модели угроз проекта). Проверка совпадения
  заголовка и тела (`[id].ts:82-85`) срабатывает только если прислан заголовок `x-device-token` —
  на legacy-пути его не шлют. Атакующий ставит `device_token = base64url('{"exp":9999999999}')`
  (24 символа, проходит порог ≥16), сервер возвращает `HMAC(payloadB64, ADMIN_SECRET)` =
  **ровно** `sign(payloadB64)`. Токен `<payloadB64>.<device_auth_token>` → `readSession` валиден,
  `kind` отсутствует → `verifySession=true` = **ВЛАДЕЛЕЦ** (удаление устройств, дамп всех
  сканов, полный контроль).
- **Почему важно:** anti-RE-схема, призванная нейтрализовать кражу секрета из APK, сама
  становится оракулом подписи owner-токена. Дефолтная/примерная конфигурация эксплуатируема.
- **Как чинить:** доменно разделить HMAC так, чтобы вывод `issueDeviceToken` **никогда** не
  совпадал с подписью сессии при любом ключе: `HMAC(key, "kq-devtok-v1\n"+deviceToken)` и
  `HMAC(key, "kq-session-v1\n"+payloadB64)`. Дополнительно — жёстко требовать **заданного и
  отличного** `DEVICE_TOKEN_SECRET` (не падать в `SESSION_SECRET/ADMIN_SECRET`), убрать пустой
  `SESSION_SECRET` из `.env.example`. Проверить, что в проде `DEVICE_TOKEN_SECRET` задан и
  отличается (это снижает остроту, но код-уязвимость остаётся).

---

## ВЫСОКИЕ — жизненный цикл фоновой защиты (главный продукт)

### BG-01 — Android 12+: после убийства процесса `ProtectionService` не поднимается никогда, даже при открытии приложения
- **Файлы:** `ProtectionService.kt:287-298`; `App.kt:143-149`
- `start()` зовётся только из `App.onCreate` и `BootReceiver`. OEM убил процесс → WorkManager
  через ≤15 мин поднимает процесс **в фоне** → `startForegroundService` из фона на Android 12+
  бросает `ForegroundServiceStartNotAllowedException`, исключение глотается. Процесс живёт как
  cached, поэтому при открытии приложения `App.onCreate` уже **не** перевыполняется — сервис,
  FileObserver и быстрый поллинг мертвы до перезагрузки. Real-time перехват APK тихо отключён.
- **Чинить:** звать `ProtectionService.start(this)` из `onResume` главных активити (старт из
  foreground разрешён всегда) + manifest-receiver на `MY_PACKAGE_REPLACED`.

### BG-02 — Тумблер фоновой защиты не останавливает и не запускает сервис; `refresh()` воскрешает «faol»
- **Файлы:** `MainActivity.kt:315-322`; `SettingsActivity.kt:152-156`; `ProtectionService.kt:300-317`; `ApkScanner.kt:537-542`; `ImprovedApkFileObserver.kt:101-131`
- `ProtectionService.stop()` **не вызывается нигде** (grep = 0). Выключил защиту → foreground-
  сервис и постоянное «KIBER QALQON faol» остаются, FileObserver не проверяет тумблер и
  по-прежнему открывает попапы и шлёт телеметрию. Включил → сервис не стартует. А `refresh()`
  (зовётся после **каждого** скана) постит «faol» безусловно — у выключившего пользователя
  значок воскресает после любого ручного скана.
- **Чинить:** в обработчике тумблера `checked ? start() : stop()`; в `onApkReady` и `refresh()`
  первой строкой гейт `if (!Config.isBackgroundEnabled) return`.

### BG-03 — Попап «вирус!»/полноэкранный «будильник» при КАЖДОМ включении экрана на старый файл
- **Файлы:** `ScreenUnlockReceiver.kt:26-47`; `GuardWorker.kt:121-139,165,275-300`; `AutoScanActivity.kt:200-211`; `NotificationHelper.kt:128-159`
- `ScreenUnlockReceiver` на каждый `SCREEN_ON`/`USER_PRESENT` ставит quick-scan (режим 3) с
  `suspiciousAsPopup=true` по умолчанию. Памяти «уже предупреждали» в режиме 3 нет (она только
  в full_sweep). Пока в топ-10 по mtime есть хоть один SUSPICIOUS файл (а по инварианту №1 туда
  попадает любой нечитаемый/битый APK) — окно `AutoScanActivity` открывается при каждой
  разблокировке (дедуп всего 20 с). На залоченном экране — full-screen-intent + звук
  (`CATEGORY_ALARM`, без `setOnlyAlertOnce`), а `AutoScanActivity` (`showWhenLocked+turnScreenOn`)
  сам включает экран → риск ночного цикла тревог. **Это поведение, за которое антивирусы сносят.**
- **Чинить:** в режиме 3 `suspiciousAsPopup=recentlyDownloaded` + персистентный набор
  «показанных» `path|mtime|size`; реагировать только на `USER_PRESENT`; мин. интервал 10–15 мин.

### BG-04 — Full sweep каждые 15 мин сбрасывает ВЕСЬ ScanCache (бамп штампа без реального обновления БД)
- **Файлы:** `GuardWorker.kt:113-115`; `ScanCache.kt:58-59,72`; `Config.kt:139-141`
- Периодический full_sweep при auto-update (дефолт `true`) безусловно зовёт
  `markDatabaseUpdated()` каждые 15 мин. `ScanCache` штамп = `VERSION_CODE:lastDatabaseUpdate`,
  при несовпадении кэш отвергается → кэш живёт максимум 15 минут, после каждого sweep — повторный
  полный SHA-256 + ZIP/DEX-анализ. **Прямо обнуляет свежий анти-перегревный коммит:** телефон
  продолжает греться на повторных хэшированиях у всех с дефолтными настройками. Правильный бамп
  уже есть в `CloudBlacklist` (только при реальном merge) — вызов в GuardWorker лишний.
- **Чинить:** удалить блок `markDatabaseUpdated` из full_sweep.

### BG-05 — Seed быстрого цикла усекается бюджетом 800 мс → старые APK всплывают как «новые» с попапом
- **Файлы:** `ProtectionService.kt:144-171,284`
- Первый проход сидирует `seenPaths` через `findApkFiles(timeBudget=800ms)`. На холодном старте
  I/O медленный, 800 мс не хватает — самые старые APK (конец MediaStore DESC) в seed не попадают,
  на следующем полле объявляются «новыми» → полноэкранная `AutoScanActivity` для файла, лежащего
  месяцами. Обратная сторона: всё, что пришло между стартом сервиса и первым поллом, сидируется
  как «виденное» и быстрым циклом не показывается (только 15-мин sweep).
- **Чинить:** сидировать щедрым бюджетом (10 с), не ставить `seeded=true` пока листинг не
  завершён без срабатывания бюджета; персистить `seenPaths`.

### BG-06 — FullPhoneScan: MAX_DEPTH=5 не достаёт до WhatsApp Documents, MAX_FILES=100 молча обрезает «полный» скан
- **Файлы:** `FullPhoneScan.kt:13-14,49-57`
- Страховочный 15-мин sweep: (1) `MAX_DEPTH=5` не доходит до
  `Android/media/com.whatsapp/.../WhatsApp Documents` (глубина 6); (2) `MAX_FILES=100` — лимит
  **найденных** APK: на телефоне со 100+ APK хвост никогда не сканируется. Когда остальные слои
  мертвы (BG-01), этот остаётся единственным — и он дырявый, тихо.
- **Чинить:** поднять глубину до 7–8, лимитировать **посещённые** файлы/время, не количество
  найденных; `Log.w` при срабатывании лимита.

### BG-07 — Скан по разблокировке слеп к backdated-APK; адаптивный цикл после разблокировки спит до 90 с
- **Файлы:** `GuardWorker.kt:120-139`; `ProtectionService.kt:146-150`
- Режим 3 берёт топ-10 по mtime, но проект сам задокументировал, что Telegram/WhatsApp ставят
  файлу mtime из прошлого — свежескачанный backdated-вирус в топ-10 не попадает. А интервал цикла
  выбирается **до** delay по состоянию экрана: телефон спал → 90-с сон, пользователь разблокировал
  и качает APK → реакция до ~90 с. Окно до попапа: до 90 с (поллинг) или 15 мин (sweep), пока
  пользователь активен.
- **Чинить:** «новизна» по персистентному набору `path|mtime|size`, а не топ-10 mtime; будить
  цикл по `SCREEN_ON`/`USER_PRESENT`.

### BG-08 — Молчаливый системный краш «did not then call startForeground»
- **Файлы:** `ProtectionService.kt:60-86`
- `onStartCommand` ловит `Throwable` от `startForeground` и «продолжает как обычный сервис» — но
  контракт `startForegroundService`→`startForeground` остаётся невыполненным, система всё равно
  бросит `RemoteServiceException`/ANR. На агрессивных OEM это циклический краш (START_STICKY
  перезапустит и повторит).
- **Чинить:** в catch `stopSelf()` + `START_NOT_STICKY`, повторная попытка через WorkManager/onResume.

---

## ВЫСОКИЕ — движок и обнаружение

### ENG-01 — Нет `QUERY_ALL_PACKAGES`: сканер «скрытых угроз» слеп к иконко-скрытой малвари на Android 11+
- **Файлы:** `AndroidManifest.xml:56-61`; `HiddenThreatScanner.kt:14,61,76`; `InstalledAppsRescanWorker.kt:39`; `CommandRouter.kt:405`
- В манифесте только `<queries>` MAIN/LAUNCHER, разрешения нет (и не было). На Android 11+
  `getInstalledApplications` возвращает только приложения с launcher-иконкой — то есть малварь,
  спрятавшая иконку (главный трюк персистентности), **не попадает в перечисление**. Флагманский
  «Yashirin tahdidlar» покажет «чисто», хотя сканер физически не способен увидеть угрозу
  (квази-false-clean, дух инварианта №1). Комментарий `HiddenThreatScanner.kt:14` «у нас есть
  QUERY_ALL_PACKAGES» — ложь.
- **Чинить:** добавить `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>`
  (для AV это разрешённый Play-кейс, при sideload ограничений нет); скрестить с
  `Settings.Secure` enabled_accessibility/notification_listeners; исправить комментарий.

### ENG-02 — `DexPatternAnalyzer` пропускает classes.dex > 30 МБ → поведенческий тир обходится паддингом
- **Файлы:** `DexPatternAnalyzer.kt:42,152`; `ApkScanner.kt:802,851-855`
- `if (entry.size > MAX_DEX_SIZE=30MB) continue` — DEX > 30 МБ не сканируется на anti-Frida/
  anti-Magisk/TracerPid/SMS/overlay/packer/C2-паттерны. Малварь паддит DEX до ~31 МБ → `dexFindings.score=0`,
  `evasionCount=0`, исчезает правило `evasionCount>=2 → DANGER`. Отдельный путь от фикса SCAN-02
  (тот чинил только inline-цикл `ObfuscatedSignatures`). Окно 30–32 МБ не получает ни паттернов,
  ни штрафа `oversizedDexPenalty` (срабатывает при >32 МБ).
- **Чинить:** читать первые `SAMPLE_SIZE` байт **любого** DEX независимо от размера; согласовать
  порог штрафа; логировать усечение.

### ENG-03 — DeviceAdmin сам по себе (без реального combo) даёт hard-DANGER → false-DANGER на легит MDM/банк
- **Файлы:** `ApkScanner.kt:899-900,1098,1270,1274`; `PermissionCombos.kt:60-63`; `ManifestAnalyzer.kt:101-104`
- `deviceAdminWithCombo = declaresDeviceAdmin && comboScore>=30` стоит выше `verifiedTrusted`. Но
  при `declaresDeviceAdmin` в perms добавляется `BIND_DEVICE_ADMIN`, а combo «Ransomware»
  = `setOf(BIND_DEVICE_ADMIN)` (score 45) → **всегда** совпадает. Логика циклична: «combo»
  вытекает из самого объявления DeviceAdmin. Любой APK с `DeviceAdminReceiver` (Find My Device,
  Knox/MDM, банк с remote-wipe) → hard DANGER, даже VERIFIED.
- **Чинить:** исключить Ransomware-combo из `deviceAdminWithCombo` (считать combo без
  `BIND_DEVICE_ADMIN`), либо опустить `deviceAdminWithCombo` ниже `verifiedTrusted`.

### ENG-04 — Play App Signing: ротация ключа → false-DANGER на легит обновлениях гос/банк-приложений
- **Файлы:** `AppReputation.kt:184-195`; `CertUtil.kt:29-38,80-86`; `ApkScanner.kt:754-780`
- `evaluate()` требует **точного** равенства имзо скан-APK и установленной копии; обе берут
  `apkContentsSigners.firstOrNull()` и игнорируют `signingCertificateHistory` (grep = 0). При
  ротации ключа Play (или переводе приложения на Play App Signing) старая версия подписана A,
  новый APK — B. Скан нового APK из Downloads (не sourceDir): `selfInstalled=false` → спасение
  `SIGNATURE_MISMATCH→VERIFIED` не работает → hard DANGER «Soxta imzo» — на тех самых Kapitalbank/
  Click/Payme/Soliq/MyGov, про которые в репо был отдельный коммит.
- **Чинить:** читать `signingCertificateHistory`, считать совпадением «текущая ИЛИ любая из
  истории»; если историю не прочитать — `UNVERIFIED` (heuristics), не DANGER.

### ENG-05 — `DropperDetector` слеп ко всему `res/`, кроме `res/raw/` (скрытый APK/DEX/ELF в res/drawable)
- **Файлы:** `DropperDetector.kt:65,208-212`
- `if (name.startsWith("res/") && !looksLikeRawWithPayload(name)) continue`, а
  `looksLikeRawWithPayload` = true только для `res/raw/`. Payload в `res/drawable/icon.png`
  отбрасывается до проверки magic — не попадает в `hiddenApks/hiddenDex/hiddenElf`, которые
  суть hard-DANGER. raw-APK можно собрать с любым именем entry, минуя aapt.
- **Чинить:** проверять magic у **всех** `res/`-entry, как для `assets/`.

---

## ВЫСОКИЕ — самозащита и Play-дистрибуция

### SD-01 — Play App Signing → boot-loop: приложение из Play само себя убивает на чужом cert
- **Файлы:** `SecurityGuard.kt:140-172`; `cpp/kqguard.c:59-64`; `build.gradle.kts:123`; `App.kt:96-102`
- `EXPECTED_RELEASE_SIGNATURE_SHA256` (Shield) и нативный `KQ_EXPECTED_SIG` оба захардкожены под
  отпечаток sideload-ключа. При Play App Signing Google пере-подписывает APK → на устройстве
  другой cert → `isSignatureInvalid=true` → `App.onCreate` делает `killProcess+exitProcess(10)`
  **до показа UI** → каждый запуск из Play мгновенно крашится. Native-копию нельзя поменять без
  пересборки .so.
- **Чинить:** перед публикацией внести SHA-256 из Play Console; сделать оба отпечатка (Kotlin +
  native) **множеством** допустимых хэшей (dual-accept, массив в `kqguard.c`). До этого Play App
  Signing не включать. *(Сейчас приложения в Play нет — баг латентный, но блокирует стратегическую цель.)*

### SD-02 — Молчаливый security-kill без узбекского объяснения на root/кастомных прошивках
- **Файлы:** `App.kt:96-107`; `SecurityGuard.kt:74-106`
- Любое срабатывание (root, эмулятор, mismatch, Frida, debug) → `Log.e + killProcess` без единого
  слова пользователю. На узбекском рынке масса Xiaomi/Redmi/Tecno с разлоченным загрузчиком/
  инженерными прошивками — легитимный владелец получает «приложение исчезает при запуске», неотличимо
  от поломки. Нарушает инвариант №2 (узбекский UX) на критическом пути.
- **Чинить:** перед kill показать узбекское объяснение (Notification/Toast); различать жёсткие
  случаи (mismatch подписи = перепаковка → kill) и мягкие (среда → деградация + предупреждение).

### SD-03 — `ro.debuggable=1` / `veritymode=logging` / разлоченный загрузчик → false-root и kill на легит прошивках
- **Файлы:** `SecurityGuard.kt:257-284`
- `checkBootProps()` считает root'ом разлоченный загрузчик, `verifiedbootstate=orange` (штатное
  для любого кастом-ROM), `ro.debuggable=1`. Любого такого пользователя приложение молча убивает,
  без признаков реального root.
- **Чинить:** эти boot-props — слабые индикаторы; не давать им в одиночку приводить к kill,
  требовать прямого маркера (su/Magisk/tmpfs-mount).

### SD-04 — `runAllChecks` синхронно в main-потоке `onCreate`: 5× `ProcessBuilder(getprop)` + чтение /proc → ANR-риск на старте
- **Файлы:** `SecurityGuard.kt:257-298,205-245,380-433`; `App.kt:97`
- Каждый `readSystemProp` форкает `/system/bin/getprop` + `waitFor` — 5 форков последовательно в
  UI-потоке, плюс построчное чтение `/proc/*`. На бюджетных устройствах (целевой рынок) — заметный
  тормоз cold-start, риск ANR.
- **Чинить:** `SystemProperties.get()` через рефлексию (без форка); весь скан off-main до kill.

---

## ВЫСОКИЕ — облако и панель

### CL-01 — Анти-брутфорс логина обходится подменой X-Forwarded-For + неатомарный счётчик `fail_count`
- **Файлы:** `cloud/lib/ratelimit.ts:17-21,38-53`; `cloud/api/admin/login.ts:38-43`; `cloud/lib/geo.ts:37-48`
- Ключ троттлинга = **самый левый** элемент XFF, который на Vercel контролирует клиент → новый IP
  на каждый запрос → счётчик не растёт. Плюс `recordFailure` делает read-modify-write двумя
  запросами (не атомарно) — параллельные запросы все читают `fail_count=0`. Защита CLOUD-03
  нейтрализована; IP в панели/гео тоже подделываем.
- **Чинить:** брать IP из `x-real-ip`/`x-vercel-forwarded-for` (или правый hop XFF); атомарный
  инкремент в БД (RPC `on conflict do update set fail_count = fail_count+1`); глобальный потолок
  по логину, не только per-IP.

### CL-02 — Список устройств и Excel-экспорт молча обрезаны до 50 устройств
- **Файлы:** `cloud/api/devices.ts:13`; `src/pages/Devices.tsx:80`; `src/lib/exportExcel.ts:20`
- `.limit(50)` без пагинации; заголовок «N ta qurilma» показывает 50 за весь парк. Excel (единственное
  «право» админа кроме просмотра) теряет всё после 50-го. При этом `/api/geo` отдаёт 2000 — карта и
  таблица расходятся. `/api/threats` так же обрезан до 100. Сработает ровно при росте пилота (Навои).
- **Чинить:** поднять лимит/пагинация + точный `count(*)`; экспорт выгребать страницами; пометка
  «ko'rsatilgan: 50 / jami: N».

### CL-03 — `demo.html` мёртв на проде: новый CSP блокирует unpkg и inline-скрипт
- **Файлы:** `cloud/public/demo.html:9,258-436`; `cloud/vercel.json:24`
- Новый `script-src 'self'` применяется ко всем путям, включая статичный demo.html, который грузит
  Leaflet с unpkg и держит весь код в inline-`<script>`. На проде — «скелет» без KPI, карты и ленты.
  Демо обычно показывают жюри/заказчику без логина → первый кандидат на публичный позор.
- **Чинить:** пересобрать демо как Vite-маршрут, либо отдельный `headers`-блок для `/demo.html`,
  либо удалить страницу. Проверить в браузере после деплоя.

### CL-04 — Панель показывает «Hozircha tahdid yo'q» при падении API (панельный false-SAFE)
- **Файлы:** `src/pages/Overview.tsx:190-226`; `src/pages/MapPage.tsx:427-428`; `src/components/NewsCarousel.tsx:67-68`
- При упавшем `/api/feed` рисуется Empty «угроз нет» вместо ошибки. Монитор безопасности с отвалившимся
  источником рапортует «всё чисто» — опасно в проекторном fullscreen-режиме на стене.
- **Чинить:** различать loading/error/empty; при `error && !data` — плашка «Oqim uzildi — qayta
  urinilmoqda…».

### CL-05 — Supabase-вьюхи обходят RLS, anon сохраняет дефолтный SELECT → инвариант «anon ничего не видит» ложен
- **Файлы:** `cloud/supabase/schema.sql:92-100,140-179`; `cloud/supabase/02_geo.sql:30-89`
- `v_map_points`, `v_recent_threats`, `v_devices_with_counts`, `v_stats_today` — обычные `create view`
  (Postgres 15+: `security_invoker=false` → исполняются от owner, **обходят RLS**), а anon по дефолту
  получает SELECT на новые relations. Нет ни `set (security_invoker=on)`, ни `revoke select from anon`
  (grep = 0). При утечке anon-ключа — полный слив IP/геолокации/имён устройств мимо API-авторизации.
  Сейчас прямой эксплуатации нет (anon-ключ не в бандле SPA), но весь периметр держится на ложном
  инварианте.
- **Чинить:** `alter view … set (security_invoker=on)` + `revoke select … from anon, authenticated`
  для всех вьюх; внести в миграцию.

### CL-06 — Дневная статистика по UTC: «bugungi» сбрасывается в 05:00 по местному времени
- **Файлы:** `cloud/supabase/schema.sql:140-155` (`v_stats_today: where scanned_at >= current_date`)
- Supabase в UTC, Узбекистан UTC+5 → «сегодняшние» счётчики (панель + Telegram `/stats` + лендинг)
  сбиты на 5 часов; скан в 02:00 локального попадает во «вчера».
- **Чинить:** `where scanned_at >= (now() at time zone 'Asia/Tashkent')::date …`.

### CL-07 — Сырые сообщения об ошибках БД утекают клиенту во всех endpoint'ах
- **Файлы:** `cloud/api/scan/upload.ts:124,145`; `threats.ts:85,116`; `devices.ts:15`; `scans.ts:26`; `stats.ts:50,60,78`; `news.ts`; `device/[id].ts:136`
- Почти везде `error: error.message` — сырой текст PostgREST (имена таблиц/колонок/constraints).
  `scan/upload` и `register` достижимы по device-secret → низкопривилегированный атакующий зондирует
  схему. Webhook эту утечку уже закрыл (generic) — в HTTP API осталась.
- **Чинить:** логировать `message` на сервере, клиенту — generic-код.

---

## ВЫСОКИЕ — python-бот (единственный публичный приём недоверенных файлов)

### PY-01 — Сэмплы не удаляются, нет квоты диска → любой пользователь навсегда выводит бота из строя
- **Файлы:** `telegram_bot/bot.py:52-53,115,186-188`; `apk_analyzer.py:159`
- Каждый файл сохраняется навсегда, нет TTL/ротации/проверки объёма; `_run_analyzer` ещё и копирует
  APK второй раз (удвоение диска) + распаковка до 500 МБ. README: хост 100 МБ. Несколько 20-МБ
  файлов заполняют диск → все последующие падают → бот мёртв безвозвратно.
- **Чинить:** жёсткая квота на `SAMPLES_DIR` **до** скачивания; ротация/TTL; не копировать APK
  второй раз; чистить `work_*` при старте.

### PY-02 — Zip-bomb через миллионы 0-байтных entry (лимит только по размеру)
- **Файлы:** `apk_analyzer.py:24-26,135-168`
- `MAX_TOTAL_UNPACKED` не срабатывает для нулевых entry (счётчик объёма не растёт), числа записей
  не считается. Архив с миллионами 0-байтных файлов → исчерпание inode, переполнение каталога; глубина
  пути не ограничена → `mkdir` вне try бросает `OSError`. PTB последователен → блокирует бота для всех
  до 120-с таймаута.
- **Чинить:** счётчик entry (≤5000), лимит глубины/длины пути, обернуть оба `mkdir` в try.

### PY-03 — Markdown-инъекция через имена entry внутри APK → вердикт XAVFLI может не доставиться
- **Файлы:** `apk_analyzer.py:288,72,79`; `bot.py:226-227,266-272,142`
- `_sanitize` оставляет `` ` ``, `_`, `[`, `]`; имя entry попадает в reasons и шлётся через
  `reply_markdown` без экранирования. Незакрытый markdown → `BadRequest can't parse entities`, нет
  try/except и error_handler → жертва **не получает предупреждение об опасности**, хотя движок угрозу
  распознал. (Скептик повысил severity до high.)
- **Чинить:** `escape_markdown` для имени и reasons; try/except с текстовым фолбэком; зарегистрировать
  `error_handler`.

---

## ВЫСОКИЕ — Telegram

### TG-01 — Поллер в офлайне крутится ~1/сек: нет network-constraint, заявленный backoff — мёртвый код
- **Файлы:** `TelegramCommandPoller.kt:24,43-48,69-76,91-99`; `TelegramBot.kt:286-289`
- Комментарий обещает `NetworkType.CONNECTED`, но `.setConstraints` не зовётся. Ветка `catch` с
  `delay(5000)` недостижима (getUpdates сам глотает `Throwable` → `emptyList`). Без сети → мгновенный
  `UnknownHostException` → reschedule через 1 с → горячий цикл WorkManager+OkHttp ~1/сек. Ровно тот
  перегрев, что чинили в ProtectionService.
- **Чинить:** добавить `setRequiredNetworkType(CONNECTED)`; экспоненциальный backoff при ошибке.

---

## UX-неудобства (выделено по запросу владельца)

### UX-01 — Карантин обещает откат за 7 дней, но UI восстановления не существует
- **Файлы:** `Quarantine.kt:128-170`; `NotificationHelper.kt:202`; `AutoScanActivity.kt:591`; `GuardWorker.kt:205`
- Во всех точках авто-карантина пользователю обещают «7 kun ichida tiklash mumkin», но
  `Quarantine.restore()/list()/purge()` **не вызываются нигде** — ни экрана, ни Telegram-команды.
  В режиме авто-удаления файл исчезает навсегда (а прошлый аудит фиксировал реальные false-DANGER на
  узбекских гос/финтех-приложениях). `reportQuarantineRestore` пишет «Foydalanuvchi o'zi tikladi» —
  событие, которое физически не может произойти.
- **Чинить:** экран «Karantin» (`list()` + «Tiklash»/«Butunlay o'chirish»), вход с дашборд-плитки;
  минимум — Telegram-команда `restore:<token>`; пока экрана нет — убрать фразу про откат.

### UX-02 — «Опциональное» согласие на обмен данными принудительно (тёмный паттерн)
- **Файлы:** `ConsentActivity.kt:89-97,115-123`; `strings.xml:413`
- `btnAccept.isEnabled = cbTerms && cbPrivacy && cbCommunity` — третий чекбокс community-share
  обязателен наравне с ToS (помечен «*»). Пользоваться антивирусом нельзя, не согласившись делиться
  данными, хотя весь продукт оформляет это как opt-in. Плюс `onAccept` сразу запрашивает геолокацию —
  хотя коммит «just-in-time permissions» перенёс её в опциональную строку → локацию просят дважды.
- **Чинить:** убрать `cbCommunity` из гейта, снять «*», передавать фактическое значение; запрос
  локации из `onAccept` удалить.

### UX-03 — Флагманские экраны недостижимы (только секретный long-press на сплэше)
- **Файлы:** `DiagnosticsActivity.kt:95,107`; `SplashActivity.kt:61-70`
- `HiddenThreatsActivity` (сканер установленных троянов) и `TelemetrySettingsActivity` (настройка
  Telegram) запускаются только из `DiagnosticsActivity`, который открывается лишь long-press по сплэшу
  (уходит через 1.5 с). В «Sozlamalar» и нижней навигации ссылок нет. `ProtectionStatusActivity` после
  первого «Davom etish» тоже не открыть (комментарий обещает «из Sozlamalar», строки нет). Функции
  собраны и работают, но навигационно мертвы.
- **Чинить:** в `SettingsActivity` строки-chevron: «Yashirin tahdidlar», «Telegram sozlamalari»,
  «Himoya holati».

### UX-04 — Поворот экрана закрывает окно предупреждения о вирусе
- **Файлы:** `AutoScanActivity.kt:121-126,200-211`; `InitialScanActivity`
- `AutoScanActivity` не фиксирует ориентацию; при повороте пересоздаётся, `isDuplicateLaunch` (companion-
  поля, окно 20 с) считает её дублем и `finish()`. Красный экран «Virus topildi» исчезает от случайного
  поворота, в режиме auto-delete отложенное удаление тоже отменяется. `InitialScanActivity` при повороте
  теряет прогресс и сканирует заново.
- **Чинить:** `screenOrientation="portrait"` для обоих; пропускать `isDuplicateLaunch` при
  `savedInstanceState != null`.

### UX-05 — Кнопка «Удалить приложение» удаляет только файл — установленный троян остаётся
- **Файлы:** `AutoScanActivity.kt:545-551,742-746`
- В `showUnscannableResult` локальная `installedPkg` затеняет поле класса: текст кнопки = «Ilovani
  o'chirish», но `onClick→deleteApk()` читает **поле** `this.installedPkg` (null в этом сценарии) →
  удаляется лишь файл, установленное (нечитаемое = самое подозрительное!) приложение остаётся. Ложное
  чувство безопасности в худшем случае.
- **Чинить:** `this.installedPkg = installedPkg ?: this.installedPkg` перед обработчиком, либо явный
  `if (installedPkg != null) uninstall… else deleteApk()`.

### UX-06 — Плитка «Karantin» на дашборде показывает число БЕЗОПАСНЫХ сканов
- **Файлы:** `DashboardNewActivity.kt:405,423-428`
- Плитка подписана «Karantin», но заливается `total_safe` (счётчик SAFE-вердиктов). Комментарий честно
  признаёт «treat as quarantined», но это не реализовано. У активного пользователя «Karantin: 154» при
  пустом карантине — пугает и врёт.
- **Чинить:** `Quarantine.list(this).size` (в IO); тап → экран карантина (UX-01).

### UX-07 — Тумблер «Fon xizmati» управляет авто-обновлением БД, а не службой; «5 sekund» против 2.5 с
- **Файлы:** `SettingsActivity.kt:111-115,167-171`; `strings.xml:609,612-613`; `AutoScanActivity.kt:474`
- Строка «Fon xizmati · WorkManager» привязана к `is/setAutoUpdateEnabled` (бамп штампа), а реально фон
  включает соседняя «Avtomatik skaner». Две строки обещают одно, ни одна подпись не соответствует
  действию; выключив «Fon xizmati», пользователь замораживает `ScanCache`-штамп. Автоудаление обещает
  «5 sekund», код ждёт 2500 мс; тост «Saqlash» (императив) вместо «Saqlandi».
- **Чинить:** переименовать в «Bazani avtomatik yangilash»; привести подпись/код к одному значению;
  строка «Saqlandi».

### UX-08 — Ручной скан заблокирован при выключенном фоне + 5-секундный таймаут на большие APK
- **Файлы:** `MainActivity.kt:303-311,92`
- Кнопка «Hozir tekshirish» работает только при `isBackgroundEnabled` — выключивший фон (ради батареи)
  теряет ручной скан (лишь тост). Тап по файлу заворачивает `scan` в `withTimeout(5000)`: большие APK
  на бюджетных телефонах не успевают → «Tekshirish juda ko'p vaqt oldi», тогда как через попап
  таймаута нет.
- **Чинить:** убрать зависимость ручного скана от фона; таймаут 30 с или индикатор без таймаута.

### UX-09 — Русская локаль наполовину узбекская (вирус-алерты и шлагбаум разрешений захардкожены)
- **Файлы:** `ProtectionStatusActivity.kt:92-213`; `HiddenThreatsActivity`; `AutoScanActivity.kt:484,518,556-559,581`; `NotificationHelper.kt:197-491`; `DashboardNewActivity.kt:216-268`
- Приложение предлагает выбор UZ/RU, есть полный `values-ru`, но целые новые поверхности минуют ресурсы:
  экран разрешений, вирусные алерты, уведомления захардкожены по-узбекски. Выбравший русский получает
  кашу из двух языков на критических экранах.
- **Чинить:** перенести литералы в `strings.xml` + `values-ru` (контекст уже локализуется), либо убрать
  выбор RU.

### UX-10 — Telegram «Skan boshla»: обещает «Natijalar bu yerga keladi», но часто молчит навсегда
- **Файлы:** `CommandRouter.kt:431-459`; `GuardWorker.kt:41-43,120-139`; `ApkScanner.kt:390-395`
- `startScan` ставит quick-scan топ-10. Результаты шлёт `finalizeResult`, который пропускается для
  закэшированных файлов → при повторе все 10 в кэше → ноль сообщений. Если APK нет / фон выключен →
  тоже тишина. Сообщения «Skan tugadi» не существует ни в одном пути.
- **Чинить:** флаг «отчитаться в TG» через inputData; итог «Skan tugadi: X tekshirildi…»; при
  выключенном фоне — «Fon himoyasi o'chirilgan».

### UX-11 — «Test xabar» всегда рапортует успех; ошибки видны только в logcat
- **Файлы:** `TelemetrySettingsActivity.kt:266-277`; `TelemetryReporter.kt:378-385`
- `sendTest` — fire-and-forget, сразу «Test yuborildi». При неверном токене/chat_id/боте-не-в-группе/
  без сети — тост одинаково победный, в группе пусто. Единственный инструмент диагностики не отличает
  «работает» от «всё сломано».
- **Чинить:** синхронный путь с парсингом `ok/description`: «✅ Yetkazildi» / «❌ Telegram xatosi: …».

### UX-12 — Тумблер сменил аккаунт/группу → панель молча заблокирована навсегда
- **Файлы:** `TelemetrySettingsActivity.kt:163-176`; `TelegramBot.kt:330-339`
- `save()` не трогает `tg_owner_user_id`/`tg_update_offset`. После фикса owner-gate (fail-closed) смена
  Telegram-аккаунта/группы/mis-pin → все команды молча игнорируются навсегда; в UI нет ни текущего
  owner'а, ни сброса. Смена токена так же не сбрасывает offset → команды нового бота не доходят.
- **Чинить:** в `save()` при смене chat_id/token — `remove(tg_owner_user_id)` и `remove(tg_update_offset)`;
  строка «Egasi: @… / biriktirilmagan» + кнопка сброса.

### UX-13 — «Chat ID avto-aniqlash» конфликтует с поллером: 409 → ложное «Bot tokeni xato»
- **Файлы:** `TelemetrySettingsActivity.kt:210-256`
- `autoDetectChatId` делает свой `getUpdates` тем же токеном при включённом listen → 409 Conflict →
  тост «Bot tokeni xato yoki bloklangan» (хотя токен верный); поллер съедает offset → auto-detect
  получает пусто → «Hech narsa topilmadi». Переезд в новую группу при включённом боте сломан.
- **Чинить:** перед auto-detect останавливать поллер; различать 409 («Bot band») от token-ошибки.

### UX-14 — Прочее (low, но раздражает)
- Тап по приложению на дашборде: до 8 с тишины без индикатора и без защиты от повторных тапов →
  стек экранов результата открывается «сам» (`DashboardNewActivity.kt:352-386`).
- На Android ≤10 кнопка «Ruxsat berish» в `InitialScan` — no-op → тупик на экране сканирования
  (`InitialScanActivity.kt:515-527`).
- Splash: возврат из «Все файлы» без гранта складывает два неубираемых диалога, на Android 11+ нет
  кнопки выхода (`SplashActivity.kt:227-260`).
- Бейдж непрочитанных новостей не гаснет после открытия страницы (`Layout.tsx:63-77`).
- Истечение сессии (8 ч) выкидывает на логин молча, без объяснения (`AuthContext.tsx:63-69`).
- Лендинг навсегда показывает «—» при сбое API, хотя комментарий обещает статичный фолбэк
  (`Landing.tsx:51-57`).
- «Mamlakat himoya darajasi» = 100% при `total_scans==0` (нет данных = «всё идеально»)
  (`Overview.tsx:57-64`).
- Ложная тревога «защиту убили» каждое утро после выключения телефона на ночь на OEM (`App.kt:66-90`).
- Лендинг «Hududlar bo'yicha qamrov» по сырой `city`: английские имена, дубли, завышенный счётчик
  регионов (`cloud/api/stats.ts:16-25`).
- Plain-fallback после ошибки Markdown показывает мусорные `\` (`TelegramBot.kt:92-103`).
- Remote «O'chirish» удаляет файл одним тапом без подтверждения (`CommandRouter.kt:102-147`).

---

## СРЕДНИЕ — прочее

- **PackageInstallReceiver** сканирует установленный APK в корутине без `goAsync()`/WorkManager —
  скан теряется при убийстве процесса (`PackageInstallReceiver.kt`).
- **`proguard-rules.pro` оставляет ВЕСЬ OkHttp с именами** (`-keep class okhttp3.**`) — Frida-хук
  `okhttp3.Request$Builder.header` снимает все исходящие секреты (x-device-secret/HMAC), обходя
  Shield и native. OkHttp 4.x keep-правил не требует (`proguard-rules.pro:92-93`).
- **`.gitignore` не покрывает `_competitor/` и `_samples/`** — `git add -A` закоммитит
  декомпилированный код конкурента (нарушение прав + улика RE) и малварь-сэмплы; `*.apk` спасает
  лишь частично (`.gitignore:18-37`).
- **`pathPattern ".*\.apk"`** не матчит имена с несколькими точками (`app-1.2.3.apk`,
  собственные сборки с timestamp) → «Открыть с помощью KiberQalqon» не появляется в старых
  файл-менеджерах (`AndroidManifest.xml:232-241`).
- **`InstalledAppsRescanWorker.take(50)`** без ротации — хвост списка приложений (>50) не
  пересканируется по новому blacklist никогда (`InstalledAppsRescanWorker.kt:59`).
- **Деградация фида seen_count>=2 / sybil-обход** — 2 поддельных device_token (через legacy-secret)
  набирают корроборацию и блокируют произвольный не-allowlist пакет fleet-wide (`threats.ts:94-96`).
- **`auth_attempts` без TTL** — в связке с подменой XFF растёт неограниченно (`12_auth_rate_limit.sql`).
- **Cloud webhook**: любой участник whitelisted-группы запускает `/devices /threats /last`
  (`cloud/api/telegram/webhook.ts`).
- **Опросы SPA не останавливаются в фоновой вкладке** → тысячи лишних вызовов Vercel/Supabase в день
  (`src/hooks/usePoll.ts`).
- **`NotificationAccessWatcher.checkNow` остался на `KEEP`** (фикс APPEND_OR_REPLACE применён только к
  `AccessibilityWatcher`) → быстрый второй ивент дропается, OTP-стилер ловится лишь 4-ч периодиком.
- **ScanCache ключ без хэша содержимого** — подмена файла той же длины + mtime → stale SAFE
  (low-предпосылка, но false-SAFE-направление) (`ScanCache.kt:62-72`).
- **`ObfuscatedSignatures` декрипт-имзо** матчит легит Android API-идентификаторы (`android.net.VpnService`,
  `TYPE_APPLICATION_OVERLAY`) substring'ом по всему entry → возможен false-DANGER над VERIFIED
  (`ObfuscatedSignatures.kt:97,100-103`).

---

## НИЗКИЕ / гигиена / info

- **Регулятор чувствительности (high/low) недостижим из UI** — `setSensitivityLevel` не вызывается
  нигде, всегда «medium»; при будущем подключении `sensitivity` не в `ScanCache.currentStamp` → stale
  (`Config.kt:108-113`).
- **`NativeLibAnalyzer` SAFE_LIB_NAMES** — обход переименованием .so в `libflutter.so`
  (`NativeLibAnalyzer.kt:41-46,73-74`).
- **IconImpersonation порог Хэмминга 10/64** — возможен false-DANGER над VERIFIED на простых иконках
  (`IconImpersonationDetector.kt:35,99`); + bitmap не recycle'ится в полном скане (память/GC).
- **`ZipEncryptionDetector` LFH-walk** обрывается на ZIP64 (`compSize=0xFFFFFFFF`) — смягчено CD-сканом
  (`ZipEncryptionDetector.kt:83,103-111`).
- **native `nSigInvalid` fail-closed при пустом Kotlin-const** — граничный сбой `Shield.dec` может дать
  молчаливый self-kill (`kqguard.c:57-65`; `SecurityGuard.kt:143,164`).
- **native `nAntiDebug` буфер 4096** может не вместить `TracerPid` → анти-дебаг тихо отключается
  (`kqguard.c:35-49`).
- **device_nonces ключ только по nonce (глобально), min 8 символов** — коллизия между устройствами
  роняет легит DANGER-отчёт (`10_device_auth.sql:17-21`).
- **Нет отзыва session-токенов** — украденный owner-токен валиден все 8 ч (`session.ts:10,35-55`).
- **release.keystore + пароли + боевые секреты в OneDrive-синхро-папке** — компрометация OneDrive =
  кража ключа подписи (корень доверия anti-RE) (`ApkGuard/keystore.properties`). В git не попали
  (проверено), но синхронизируются в облако MS.
- **R8 fullMode выключен с неверным комментарием** («для совместимости с Java 21» — флаг не про это),
  ослабляет обфускацию anti-RE-ветки (`gradle.properties:9-10`).
- **76 легаси `.txt/.bat` в корне `ApkGuard/`** — статусы старых версий + батники, предлагающие Java 21
  (проект на JDK 17); мусор и противоречивые инструкции для жюри/коллаборатора.
- `archivesBaseName` с `currentTimeMillis()` — новое имя APK на каждый Gradle-запуск, артефакты копятся
  (`build.gradle.kts:88`).
- `/api/stats?public=1` без rate-limit, кэш обходится произвольным query — амплификация на БД.
- `config.ts` хардкод `v:1` — anti-rollback подписанного RemoteConfig инертен.
- `readRaw` буферизует тело без cap (`rawbody.ts:15-24`).
- мёртвый `lib/password.ts` (scrypt) после удаления ролей.
- `regionOf/nearestCity` без порога расстояния — заграничное устройство приписывается к узбекскому
  вилояту (`uzRegions.ts:82-96`).
- `targetSdk 34` устарел для заявленной цели Play (требование Play — API 35 с авг. 2025).

---

## Статус ремедиации 2026-06-07

**Подтверждено FIXED (выборка):** FORENSIC-01 (GP-bit), SCAN-01 (markDatabaseUpdated при реальном
merge), CLOUD-01 (RPC `corroborated_threats` migr. 11 + NEVER_BLOCK allowlist + монотонная версия),
TG-01/02/03 (owner pin из первого `/` в whitelisted-чате, fail-closed), CLOUD-03 (ratelimit + migr. 12),
CC-03 (re-verify кэша), DET-02/DET-03, SCAN-02 (oversized-DEX inline), GuardWorker (popup-gate в
full_sweep + battery + dedup), SECGUARD-01, InitialScan «Keyinroq», VpnFilterService убран из манифеста,
CSP, тесты `ZipEncryptionDetectorTest` + `ApkScannerVerdictTest` (65/65 зелёные). Миграции 11+12
существуют, висячих обращений к колонкам нет.

**PARTIAL / UNFIXED:**
- **CLOUD-02** (PARTIAL): legacy `x-device-secret` логируется, но без rate-limit и без env-флага sunset.
- **CC-02** (UNFIXED): cert-пины CA-уровня, fail-open expiration `2027-06-01`, leaf/SPKI-пина нет.
- **FORENSIC-02** (PARTIAL): MaliciousHashes Shield-encoded, но хэш `A89122D1` (VID_23856) так и не
  добавлен; cross-check IOC↔детекторы не появился.
- **FORENSIC-03** (UNFIXED): 36 analysis-скриптов хардкодят `C:\Users\Muhammadali\…`, общего `_common.py`
  нет — конвейер невоспроизводим на текущей машине.
- **CC-01** (PARTIAL, клиент): cloud-пакет из фида = безусловный DANGER без исключений для
  trusted/system (`ApkScanner.kt:717-737`).
- **remote-delete без подтверждения** и **сброс owner-id при смене chat_id** — не сделаны (см. UX-12).
- **Тесты** (PARTIAL): закрыты ZIP + verdict, но DNS-хелперы / CloudBlacklist.verifyAndDecode /
  HiddenThreatScanner без юнит-тестов.

**Долг деплоя (из памяти проекта):** убедиться, что миграции 11+12 применены в Supabase и облако
передеплоено; проверить, что `DEVICE_TOKEN_SECRET` задан и **отличается** от `SESSION_SECRET/ADMIN_SECRET`
(см. CRIT-01).

---

## Рекомендуемый порядок исправления

1. **CRIT-01** — доменное разделение HMAC + распутать ключи (полный захват панели).
2. **BG-01/BG-02** — вернуть управляемость и восстановление real-time защиты (главный продукт).
3. **BG-03/BG-04** — убрать навязчивые попапы и восстановить кэш (перегрев + причина удаления).
4. **UX-01/UX-02/UX-03** — карантин-undo, принудительное согласие, недостижимые экраны.
5. **CL-01/CL-02/CL-03** — брутфорс панели, обрезка 50, мёртвое демо.
6. **ENG-01/ENG-03/ENG-04** — слепота к скрытым приложениям + false-DANGER на легит банк/MDM.
7. **PY-01/PY-02/PY-03** — DoS и подавление вердикта в публичном боте.
8. **SD-01..04** — перед любым шагом к Play + ложные kill'ы на целевом рынке.
9. Гигиена: `.gitignore` (`_competitor/`, `_samples/`), keystore вне OneDrive, OkHttp proguard.
