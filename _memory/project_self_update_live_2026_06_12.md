---
name: project-self-update-live-2026-06-12
description: "Self-update channel made LIVE 2026-06-12: hosted vc82 APK on Vercel public/, set UPDATE_* env, config serves update block. Key gotcha: debug build can't install release-signed update (cert mismatch 'imzo o'tmadi')."
metadata: 
  node_type: memory
  type: project
  originSessionId: 122b8a39-beb9-4466-9530-42a9146beac1
---

2026-06-12: активировал авто-обновление приложения с сервера (механизм SelfUpdate уже был в коде — см. [[project-self-update-2026-06-12]], был «спящий»). Юзер хотел «пусть обновление само приходит с сервера».

**Что сделано (всё проверено live):**
- Добавил buildType `releasefast` в app/build.gradle.kts: `initWith(release)` + minify/shrink=false → release-подпись (тот же keystore/cert) и тот же applicationId `com.kiberqalqon`, но БЕЗ R8 → собирается в разы быстрее. Для self-update R8 не нужен, важны лишь cert+пакет+versionCode. Прод по-прежнему `release` (с R8). **Сборка через `gradlew assembleReleasefast`.**
- Собрал releasefast vc81/8.1 (база для установки) и vc82/8.2 (раздаётся сервером). Оба на Рабочем столе: `kiberqalqon-8.1-release.apk`, `kiberqalqon-8.2-release.apk`. Бамп versionCode 81→82 в build.gradle (НЕ закоммичено на момент записи).
- Хостинг БЕЗ секретов: положил APK в `cloud/public/kq-update-82.apk` → Vite копирует в dist → раздаётся по `https://kiberqalqon-cloud.vercel.app/kq-update-82.apk` (200, 10.1MB). Создал `cloud/.vercelignore` (node_modules/.git/dist/.vercel/.env*) — он переопределяет root `.gitignore` (где `*.apk` на стр.18), иначе APK не загрузился бы в деплой. `gh` НЕ установлен; Supabase service key в Vercel замаскирован (env pull даёт len 2) — поэтому хостинг через public/, а не GitHub/Supabase.
- Vercel prod env (через `vercel env add … production`, value по stdin): `UPDATE_VERSION_CODE=82`, `UPDATE_APK_URL=…/kq-update-82.apk`, `UPDATE_APK_SHA256=dd3f983e58eec3ebc69d58e12771da07c1f82bc5a5c247570d707ddebf453f37`, `CONFIG_VERSION=2` (был не задан = дефолт 1). Редеплой сделан.
- **Проверено:** подписанный `/api/config` (device-secret) содержит `update`-блок {vc82, url, sha}; v=2. Hosted APK SHA == config SHA. Cert 8.1==8.2==`1cb3f378189d6ef38985b3ae234d859e750029ab353246fa496349a8ff14d983`. (`?app=1` требует ADMIN_SECRET — у Claude его нет.)

**ГЛАВНЫЙ ПОДВОДНЫЙ КАМЕНЬ (юзер на него напоролся):** на телефоне стоял **debug-сборка** (`com.kiberqalqon.debug`, cert `857fd1b3…`, versionName `8.1-DEBUG`). Она ТОЖЕ опрашивает конфиг и шлёт уведомление об обновлении, но при установке release-8.2 → «imzo o'tmadi» (подпись не совпала), Android отклоняет. **Self-update обновляет ТОЛЬКО сборку с тем же cert+пакетом.** Фикс юзеру: удалить debug, поставить `kiberqalqon-8.1-release.apk` (release `com.kiberqalqon`), обновляться из НЕЁ. Уведомление приходит ~через минуту после запуска (фоновый RemoteConfig.refresh, не мгновенно — это нормально, не баг).

**Финал (commit 27abc8e, pushed):** E2E доказан на эмуляторе debug-сборкой: cold start → config v применён → notif 7781 (kq_self_update) показан. **Release на эмуляторе НЕ работает** — SecurityGuard анти-эмулятор убивает процесс в цикле (`Security check failed: emulator`) — тестировать release только на реальном устройстве. Найден+исправлен реальный баг: checkAndNotify ставил KEY_NOTIFIED_VC даже без POST_NOTIFICATIONS разрешения (Android 13+) → уведомление терялось навсегда; теперь `areNotificationsEnabled()` гейт перед показом. Обе APK пересобраны с фиксом: 8.1 (vc81) хостится как `kq-install-81.apk` (для первичной установки на телефон по ссылке, без USB), 8.2 (vc82, sha `7ac3cd40…`) как `kq-update-82.apk`; CONFIG_VERSION=3. Всё проверено скачиванием: sha/cert/vc совпадают. NB: `vercel env rm` интерактивен — `echo y |` НЕ работает, нужен флаг `--yes`. NB2: APK в cloud/public НЕ в git (root *.apk ignore) — при деплое с чистого клона их надо положить заново.

Сборки на этой машине медленные/флейки из-за OneDrive (проект в `OneDrive/Desktop`, синк перехватывает файлы build/ → «Couldn't delete R.jar», тормоза). Лечение: `gradlew --stop` + удалить залоченную папку intermediates + пересобрать.

Связи: [[project-self-update-2026-06-12]], [[project-anti-re-hardening]] (CONFIG_SIGNING_SECRET, Shield), [[reference-vercel-deploy]], [[build-toolchain]].
