---
name: project-anor-redesign-2026-06-10
description: "2026-06-10 redesign saga: v1 mockup.html reskin REJECTED by user; superseded same day by v4 'Milliy Kiber Himoya' (user's standalone HTML design) applied to ALL screens, build-verified, NOT committed"
metadata: 
  node_type: memory
  type: project
  originSessionId: 110b3c4d-ea6a-40aa-b028-ab8902d064ca
---

2026-06-10: полный редизайн приложения под Anor-бренд РЕАЛИЗОВАН (по ApkGuard/brand/mockup.html), `assembleDebug` собрался, **не закоммичен** (вместе с более ранней незакоммиченной правкой overlay-разрешения в ProtectionStatusActivity).

Что сделано:
- Палитра: kq_anor = #C2143D (light) / #E11D48 (dark); тёмная тема по макету (bg #141319, карточки #26262E, линии #33333D). Легаси-блок цветов (primary/surface/...) и ~40 drawable перекрашены массово из бирюзы в anor.
- Дефолты: акцент anor, тема dark (Config.getAccent/getDarkThemeMode), ThemeHelper else→Anor. Сплэш-градиент = anor (E11D48→8E0E2C).
- Логотип: kq_shield_mark / _white / ic_launcher_foreground = гранатовый щит из brand/kiberqalqon_logo.svg; adaptive icon переключён с PNG-мипмапов на вектор. **PNG-фоллбеки (API 24–25) остались со старым лого.**
- Dashboard: герой по макету — кольцо по центру (SpeedometerView переписан: полный круг, цвет = СТАТУС зелёный/янтарный/красный, щит+галочка внутри), tvHeroStatus/tvHeroSub, CTA kq_cta_anor (градиент 18dp).
- ProtectionStatusActivity: карточки разрешений по макету (эмодзи-чип + бейдж ✓/+/!, карточка кликабельна), прогресс-полоски, заголовок «Himoyani yoqamiz».
- Дизайн-язык закреплён: **красный = бренд/действие, зелёный = статус безопасности** (kq_banner_safe принудительно зелёный — не вешать SAFE на kq_primary!).

ОБНОВЛЕНИЕ (та же дата):
- **Имя проекта переименовано: KiberQalqon → "Anor Qalqon"** во всём пользовательском тексте (3 локали strings.xml, ~50 Kotlin-литералов кроме Log-тегов/TAG/User-Agent, cloud SPA, pitch/*.html, mockup). НЕ трогали: пакет com.kiberqalqon, t.me/kiberqalqon_alerts_bot, kiberqalqon-cloud.vercel.app, Log.e("KiberQalqon"...)/CrashHandler TAG.
- **Логотип — РЕАЛЬНЫЙ PNG, не рисованный.** Утверждён ApkGuard/logo-anor.png (1254², гранат-щит с зёрнами-каплями + зелёный check). Сгенерён из него Python+PIL (PIL есть, ImageMagick/inkscape НЕТ): прозрачная версия flood-fill от 4 углов (внутр. белое окно сохранено) → res/drawable-nodpi/kq_logo_anor.png + kq_shield_mark.png + kq_shield_mark_white.png (старые векторные .xml УДАЛЕНЫ, имена сохранены — layout не трогали). Лаунчер: ic_launcher_foreground.png 5 плотностей (safe-zone 0.62) + legacy ic_launcher/_round.png (cream-плашка); adaptive anydpi → @mipmap/ic_launcher_foreground. Сплэш: добавлена кремовая подложка kq_logo_plate под красный логотип (иначе сливался с anor-фоном).
- Первое сгенерённое лого (щит с 8 иконками угроз) ОТКЛОНЕНО — каша на мелком размере; выбран вариант с зёрнами.
- Финальный build: kiberqalqon-1781069705532-debug.apk. По-прежнему НЕ закоммичено.

ОБНОВЛЕНИЕ 2 (та же дата, вечер) — **v1-редизайн ОТВЕРГНУТ юзером, применён дизайн v4**:
- Юзер: «дизайн вообще не понравился, весь» — отверг и v1 (mockup.html), и мои mockup2/mockup3. Принёс СВОЙ готовый дизайн: `D:\Загрузки\Anor Qalqon (standalone).html` (React-standalone «Anor Qalqon · Milliy Kiber Himoya») и велел применить 1-в-1 ко всем экранам, не спрашивая.
- Дизайн распакован в `KiberQalqon/design_v4_extracted/` (page.html = CSS-токены, screens1-3.jsx = все 15 экранов, data.jsx = тексты, ANDROID_SPEC.md = моя спека) — это теперь ИСТОЧНИК ИСТИНЫ дизайна, НЕ brand/mockup.html.
- v4 = тёплый светлый («кремовый», light по умолчанию!) + тёплый тёмный; шрифты **Onest + Spline Sans Mono** вшиты в res/font; акценты Anor #C52A3E / Feruz #1F9489 / Za'faron #C5871F (+ kqPrimaryInk attr); иконки ic4_* (37 шт, lucide-стиль), KqRingView, токены kq_* перезаписаны (light и night).
- Все ~15 экранов рескинены мульти-агентным workflow (11 групп) + верификационный workflow нашёл и починил 19 crit/major. Новый экран PermissionsDetailActivity (разрешения KRITIK/DIQQAT/ODDIY человеческим языком).
- **Грабля Android**: `<gradient android:angle>` принимает ТОЛЬКО кратные 45°, иначе InflateException — дизайнерские 165°/290° пришлось округлять до 270°/315°.
- Финальный build зелёный: kiberqalqon-1781107118797-debug.apk. ~435 файлов в working tree, НЕ закоммичено. На устройстве ещё не проверялось.

ОБНОВЛЕНИЕ 3 (2026-06-11) — **ЗАКОММИЧЕНО + ЗАПУШЕНО**: весь v4 (Android + cloud SPA reskin) закоммичен в `c5f8628` ("design: v4 «Milliy Kiber Himoya» reskin"), пушнут в `feat/anti-re-hardening` (d686bcc). Перед коммитом перепроверено: `assembleDebug` зелёный (kiberqalqon-1781125132356-debug.apk), cloud api/lib + SPA tsc чисто. Временные файлы исключены через .gitignore: `/fonts_tmp/` (исходные variable-шрифты, статика уже в res/font) и `/pitch/SaveVid_Net_*.mp4` (скачанное видео, нигде не подключено). **На реальном устройстве v4 всё ещё НЕ проверялся** — следующий шаг. Переименование "Anor Qalqon" + реальный PNG-лого по-прежнему в силе.

ОБНОВЛЕНИЕ 4 (2026-06-11, позже) — **регрессия темы найдена и исправлена** (`359d2f2`): в Config.getDarkThemeMode дефолт остался `"dark"` с комментарием про ОТКЛОНЁННЫЙ v1 brand/mockup.html, хотя v4 = light по умолчанию (сам коммит-месседж c5f8628 говорил "light default"). Теперь `"light"`. Также проверено пробами: **v4 SPA уже живёт на проде** kiberqalqon-cloud.vercel.app (деплой случился сразу после пуша — похоже, Vercel Git auto-deploy).

Связано: [[project-perf-brand-2026-06-09]] (бренд-решение), [[project-audit-2026-06-10]] (находки аудита не чинились в этой волне).
