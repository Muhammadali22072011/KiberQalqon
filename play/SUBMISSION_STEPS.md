# UzGuard — подача в Google Play: пошагово (сверху вниз)

Иди по порядку, ставь ✅. Готовые ассеты/тексты лежат рядом в папке `play/`.
Каждый **англоязычный блок в рамке** — это текст, который **копируешь-вставляешь** прямо в форму Play Console.

---

## Что уже готово (не надо делать)

| Файл | Куда пойдёт |
|---|---|
| `UzGuard-PLAY-8.7-87.aab` (Desktop) | загрузка приложения (AAB) |
| `playstore_512.png` | иконка 512×512 |
| `play/feature_graphic.png` | feature graphic 1024×500 |
| `play/screenshot_1..4.png` | скриншоты (4 шт, 9:16) |
| `play/store_listing_uz.txt` / `_ru.txt` | название + описания |
| `play/privacy_policy.html` | политика конфиденциальности (нужно захостить) |
| `play/data_safety_content_rating.md` | ответы Data Safety + рейтинг |
| `play/PLAY_SUBMISSION.md` | полное досье (все обоснования §3) |

Контакт везде: **Telegram @zimdevuz** (личный email не указывать).

---

## ЭТАП 1. Аккаунт Play Console (делается 1 раз)

- [ ] Зайти на **play.google.com/console**, оплатить регистрацию **$25** (разово).
- [ ] Тип аккаунта: **Личный (Personal)** — для соло проще. (Организация = нужен D-U-N-S номер, ~7–30 дней, только если есть юрлицо.)
- [ ] **Верификация личности:** загрузить скан паспорта/ID + указать реальный адрес + телефон. **Имя в аккаунте = имя в документе** (иначе отклонят).
- [ ] Настроить **платёжный профиль**.
- [ ] Дождаться подтверждения аккаунта (может занять несколько дней). Публикация возможна только после.

---

## ЭТАП 2. Захостить privacy policy (нужно до форм)

- [ ] Выложить `play/privacy_policy.html` на любой публичный https-URL.
  - Проще всего: положить в `ApkGuard/cloud/public/privacy.html` и задеплоить (тогда `https://kiberqalqon-cloud.vercel.app/privacy.html`), **или** любой бесплатный хостинг (GitHub Pages/Netlify).
- [ ] Открыть URL в браузере — должна открываться страница. Сохранить ссылку.

---

## ЭТАП 3. Создать приложение

- [ ] Console → **Create app**.
- [ ] App name: **UzGuard** · Default language: **O'zbek (uz)** (или Русский) · Type: **App** · **Free**.
- [ ] Принять Developer Program Policies + US export laws.

---

## ЭТАП 4. Сначала — «Внутреннее тестирование» (Internal testing)

> ВАЖНО: не публикуй сразу в Production. Сначала internal — правила мягче, обкатаешь на своём телефоне.

- [ ] **Testing → Internal testing → Create new release**.
- [ ] **Play App Signing:** принять (Google хранит ключ подписи; твой keystore = upload key). Это чинит проблему подписи — см. Этап 8.
- [ ] Загрузить **AAB**: `Desktop/UzGuard-PLAY-8.7-87.aab`.
- [ ] Release notes (что нового) — коротко на узбекском.
- [ ] Добавить тестеров (список email) → сохранить.

---

## ЭТАП 5. App content (Контент приложения) — все формы обязательны

### 5.1 Privacy policy
- [ ] Вставить URL из Этапа 2.

### 5.2 App access
- [ ] Выбрать: **All functionality is available without special access** (логина/аккаунта нет).

### 5.3 Ads
- [ ] **No**, приложение не содержит рекламы.

### 5.4 Content rating (анкета IARC)
- [ ] Email для IARC.
- [ ] Категория: **Utility / Productivity** (не игра).
- [ ] Ответы — из `play/data_safety_content_rating.md` (насилия/секса/наркотиков нет → рейтинг низкий).

### 5.5 Target audience & content
- [ ] Возраст: **не для детей** (13+/18+). "Appealing to children" → **No**.

### 5.6 Data safety ❗ (заполнять ТОЧНО по факту)
- [ ] Заполнить строго по `play/data_safety_content_rating.md`. Ключевое:
  - Location — **собирается**, опционально, цель «Безопасность/функциональность», шифруется в передаче, пользователь может отключить.
  - Device/other IDs, app activity — по файлу.
  - Данные **не продаются**.
- ⚠️ Несовпадение с реальным поведением = **бан аккаунта**, не просто отказ.

### 5.7 Прочие декларации
- [ ] **Government apps:** ❗ **Нет**, это НЕ государственное приложение.
- [ ] **Financial features:** Нет.
- [ ] **Health:** Нет.
- [ ] **News:** Нет.

---

## ЭТАП 6. Sensitive permissions (Policy → App content → чувствительные разрешения)

Вставляй обоснования (полный список — `PLAY_SUBMISSION.md §3`). Главные:

**All files access (MANAGE_EXTERNAL_STORAGE)** — выбрать «Antivirus/Security» + **приложить демо-видео** (см. Этап 9):
> UzGuard is an offline on-device antivirus. It must enumerate and read APK files across shared storage (Download, Telegram, file-manager folders) to scan them for banking-trojan malware before installation, and delete confirmed-malicious APKs. Scoped storage / MediaStore expose only media collections and cannot reliably reach the arbitrary Download/Telegram paths where malicious APKs land, so broad file access is essential to the core scanning feature.

**QUERY_ALL_PACKAGES** — категория «Device security / anti-malware»:
> UzGuard is an on-device antivirus that must inspect every installed package — including malware that hides its launcher icon (a primary Ajina/dropper persistence trick) — to detect malicious packages, run daily re-scans, and evaluate dangerous permission combinations. The <queries> LAUNCHER filter returns only apps with a launcher icon, so it cannot see icon-hidden threats; full package visibility is core to detection.

**VpnService (BIND_VPN_SERVICE)**:
> The optional, user-enabled VPN is a purely local DNS sinkhole that runs entirely on-device to block known malware command-and-control domains. It does not route traffic to any remote server, does not inspect or collect user traffic, and fails open if upstream DNS is unreachable so connectivity is never lost.

**USE_FULL_SCREEN_INTENT**:
> Full-screen intent is used only to surface a critical, time-sensitive malware-detected warning so the user can cancel installation immediately, including when the screen is locked. Falls back to a high-priority notification if refused.

**Foreground service (FOREGROUND_SERVICE_SPECIAL_USE)** — тип `specialUse`:
> ProtectionService provides continuous, always-on antivirus protection: it watches for newly installed/downloaded APKs and performs real-time malware scanning. No standard foreground service type describes on-device security monitoring, so specialUse is the only accurate type.

(REQUEST_INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW, LOCATION, CAMERA, Shizuku — обоснования в `PLAY_SUBMISSION.md §3`.)

---

## ЭТАП 7. Store listing (Витрина)

- [ ] **Main store listing** → язык Uzbek:
  - Title / Short / Full description — из `play/store_listing_uz.txt`.
- [ ] Добавить язык Russian → из `play/store_listing_ru.txt`.
- [ ] **App icon:** `playstore_512.png`.
- [ ] **Feature graphic:** `play/feature_graphic.png`.
- [ ] **Phone screenshots:** `play/screenshot_1..4.png` (мин. 2, у нас 4).

---

## ЭТАП 8. После первой загрузки — вернуть проверку подписи (важно для безопасности)

Сейчас в play-сборке проверка подписи выключена (иначе бут-луп). После загрузки:
- [ ] Play Console → **App integrity → App signing** → скопировать **«App signing key certificate» SHA-256** (убрать двоеточия, верхний регистр).
- [ ] Вписать этот cert в `SecurityGuard.kt` и `app/build.gradle.kts` (инструкция — `PLAY_SUBMISSION.md §2.2`).
- [ ] Пересобрать `./gradlew bundlePlayReleasefast`, поднять versionCode, загрузить новую версию.

---

## ЭТАП 9. Демо-видео для All-files-access

- [ ] Готово: **`play/all_files_demo_voiced.mp4`** (33 сек, 1080×1920, **с узбекской озвучкой** edge-tts Madina) — объясняет использование all-files (скан APK в Download/Telegram + английские подписи для ревьюера). Приложить в форме All-files-access (Этап 6) или загрузить на YouTube (unlisted) и дать ссылку. (Без озвучки: `play/all_files_demo.mp4`, 20 сек. Текст озвучки: `play/all_files_demo_voiceover.txt`.)
- 💡 Сильнее всего — **живая запись экрана** на телефоне: открыть UzGuard → Diagnostika (тест-вирус) → скан находит → удалить. Если запишешь — используй её вместо слайд-версии.

---

## ЭТАП 10. Отправить на ревью → Production

- [ ] Internal testing: **Review release → Start rollout**. Установить по opt-in ссылке, **проверить на реальном телефоне**.
- [ ] Когда всё ок → **Promote to Production** (или Closed testing). Ревью может идти неделю+.

---

## Красные флаги (частые отказы/баны) — держи в голове

1. **Data Safety ≠ реальность** → бан. Заполняй честно.
2. **Гос-принадлежность** («национальная/Республики…») → отказ. Листинг уже безопасный — не меняй.
3. **All-files без демо-видео** → отклонят декларацию.
4. **Accessibility/notification-listener** — в play-сборке их НЕТ (вырезаны), не включай обратно.
5. Шанс на Production невысокий (all-files + query-all-packages) → **начинай с internal/closed**, sideload+self-update остаётся основным каналом.
