# Ручные шаги владельца — чек-лист (обновлено 2026-06-11)

Эти шаги может сделать только владелец (нужны Supabase SQL Editor и панель).
Код в проде уже готов к ним и работает fail-soft — пока шаг не сделан, ничего
не ломается, просто соответствующая защита/функция не активна.

## 1. Прогнать миграции в Supabase SQL Editor (по порядку!)

Открыть Supabase → SQL Editor → вставить и выполнить содержимое файлов
из `ApkGuard/cloud/supabase/` в этом порядке (все идемпотентны — повторный
запуск не страшен):

| # | Файл | Что даёт |
|---|------|----------|
| 11 | `11_feed_corroboration.sql` | подтверждение угроз из фида (аудит #1) |
| 12 | `12_auth_rate_limit.sql` | **защита логина панели от перебора** — без неё rate-limit не действует! |
| 13 | `13_audit_2026_06_10.sql` | исправления аудита #2 |
| 14 | `14_threat_domains.sql` | домены для проверки ссылок + VPN C2-фильтра |
| 15 | `15_admin_audit_log.sql` | журнал действий админа (новое) |

## 2. Redeploy Vercel

После миграций передеплоить, чтобы новые ветки API (audit-лог, домен-фид) поднялись:

```
cd ApkGuard/cloud
vercel --prod
```

(аккаунт **muhammadali22072011**, не akobirturgunov!)

## 3. Удалить тестовое устройство

В панели (или Supabase → devices) удалить `kqtest_signed_000000000001` —
оно засоряет карту и статистику.

## 4. Проверить секреты

- `DEVICE_TOKEN_SECRET` в Vercel env — должен быть **уникальным**, не совпадать
  с `ADMIN_SECRET`/`SESSION_SECRET`/`DEVICE_SHARED_SECRET`.
- `ADMIN_PASSWORD` — не placeholder (код сам отказывает в логине, если значение дефолтное).

## 5. Просмотр нового журнала действий админа

После миграции 15 журнал смотрится так (только владелец):

```
GET https://kiberqalqon-cloud.vercel.app/api/stats?audit=1
заголовок: x-admin-secret: <токен владельца или ADMIN_SECRET>
```

Записывается: вход владельца/админа, неудачные попытки входа, создание/удаление/закрепление новостей (+ IP).

## 6. Перед релизом в Google Play (на будущее)

- Добавить Play-сертификат (App Signing key) в `TrustedSignatures` (SD-01),
  иначе приложение из Play не пройдёт собственную проверку подписи.
- FCM (мгновенные обновления чёрного списка) — нужен Firebase-проект и
  `google-services.json`; инструкция в `ApkGuard/FCM_SETUP.md`. Кода ещё нет.
- Play Integrity API (аттестация устройств для облака) — нужен Play Console.

## 7. Выпуск обновления приложения (self-update, добавлено 2026-06-12)

Телефоны сами показывают «Yangi versiya chiqdi», когда в подписанном конфиге
появляется блок `update`. Как выпустить новую версию:

1. Поднять `versionCode`/`versionName` в `ApkGuard/app/build.gradle.kts`,
   собрать **подписанный release**: `./gradlew assembleRelease`.
2. Запустить помощник — он посчитает versionCode и SHA-256 и напечатает все команды:
   ```
   bash ApkGuard/scripts/publish_update.sh
   ```
3. Загрузить APK в Supabase Storage: Dashboard → Storage → bucket `updates`
   (создать **public**, один раз) → Upload → скопировать public URL.
   (GitHub Releases НЕ подходит — репозиторий приватный, телефон не скачает.)
4. В Vercel env (Production) задать: `UPDATE_VERSION_CODE`, `UPDATE_APK_SHA256`,
   `UPDATE_APK_URL` (+ поднять `CONFIG_VERSION` на 1) — команды печатает скрипт.
5. `cd ApkGuard/cloud && vercel --prod`.

Безопасность: телефон ставит обновление ТОЛЬКО если совпали (а) HMAC-подпись
конфига, (б) SHA-256 файла, (в) подпись APK = подпись установленного приложения.
Чужой/подменённый APK молча удаляется.

## 8. Регистрация через Telegram-бота (добавлено 2026-08-13)

Перед главным экраном приложение теперь показывает шлагбаум «Ro'yxatdan o'tish»:
кнопка открывает Telegram-бота, тот спрашивает имя и берёт **подтверждённый**
номер через кнопку «Отправить номер». Пока шаг не пройден — на главный экран не
пускает. Панель → раздел **Foydalanuvchilar** показывает всю аналитику
(воронка, конверсия, тренд 14 дней, список, экспорт в Excel).

Что нужно сделать владельцу:

1. **Создать ОТДЕЛЬНОГО бота** у @BotFather (`/newbot`). Это второй бот — не тот,
   что отвечает `/stats` в панели. ✅ **Сделано 2026-08-13: @uzguardapp_bot** (id 8771917324).
   Не забыть `/setjoingroups → Disable`.
2. **Vercel env** (Production), 4 переменные:
   - `TELEGRAM_REG_BOT_TOKEN` — токен нового бота
   - `TELEGRAM_REG_BOT_USERNAME` — `uzguardapp_bot` (без `@`)
   - `TELEGRAM_REG_WEBHOOK_SECRET` — новый random 32 hex (**не тот же**, что
     `TELEGRAM_WEBHOOK_SECRET`)
   - `PUBLIC_BASE_URL` — `https://kiberqalqon-cloud.vercel.app` (для кнопки «вернуться
     в UzGuard»)
3. **Миграция**: Supabase → SQL Editor → выполнить
   `ApkGuard/cloud/supabase/18_tg_registration.sql`.
4. **Redeploy**: `cd ApkGuard/cloud && vercel --prod`.
5. **Привязать webhook**:
   ```
   $env:TELEGRAM_REG_BOT_TOKEN="456:AAH..."
   $env:TELEGRAM_REG_WEBHOOK_SECRET="новый-random-32-hex"
   $env:VERCEL_URL="https://kiberqalqon-cloud.vercel.app"
   node ApkGuard/cloud/scripts/set-webhook-reg.mjs
   ```
   Проверка: написать боту `/help` — должен ответить.

⚠️ **До релиза в Play**: этот шаг собирает **имя и номер телефона** — это
персональные данные. Нужно обновить политику конфиденциальности и форму
**Data safety** в Play Console (раздел «Personal info → Phone number», цель —
«App functionality / Communications»), иначе приложение снимут. Пока это не
сделано — не выкатывать сборку с включённым шлагбаумом в Play.

Если облако не настроено (`CLOUD_BASE_URL` пустой) — шлагбаум сам себя
выключает, приложение открывается как раньше.
