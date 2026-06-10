# ANOR QALQON · v4 «Milliy Kiber Himoya» — Android implementation spec

Это инструкция для агентов, переносящих дизайн v4 (HTML/JSX-прототип) в Android-приложение
`C:\Users\User\OneDrive\Desktop\KiberQalqon\ApkGuard` (Kotlin, package `com.kiberqalqon`,
minSdk 24, ViewBinding ON, Material Components).

## Источник истины (читать ОБЯЗАТЕЛЬНО перед работой)

Папка `C:\Users\User\OneDrive\Desktop\KiberQalqon\design_v4_extracted\`:
- `page.html` — CSS-токены (темы light/dark, акценты anor/feruz/zafaron) + все компоненты (.card, .li, .btn, .tag, .chip, .toggle, .bnav, .eyebrow, .h-title, .sec-title и т.д.)
- `screens1.jsx` — экраны Language, Splash, Onboarding, Dashboard, Apps(Skaner), TopBar
- `screens2.jsx` — Settings, Stats, Quarantine, Protection, About, Report (+Row/Toggle/SecTitle helpers)
- `screens3.jsx` — AutoScan, ScanResult, InitialScan, Permissions
- `data.jsx` — примерные данные и ВСЕ тексты дизайна (узбекский, без жаргона)
- `icons.jsx` — иконки и компоненты Logo/LogoDisc/Ring
- `registry.jsx` — роутер: какие экраны имеют нижнюю навигацию (dashboard/apps/stats/settings) и статус-бар

Точность: повторять дизайн МАКСИМАЛЬНО близко (отступы, размеры, радиусы, цвета, тексты),
но данные подключать РЕАЛЬНЫЕ из существующего кода (ScanHistory, Quarantine, Config и т.д.) —
дизайнные THREATS/APPS это только примеры наполнения.

## Уже готовый фундамент (НЕ создавать заново, использовать)

### Цвета (авто light/dark через values-night)
| CSS токен | Android |
|---|---|
| --bg | `@color/kq_bg` |
| --bg-2 | `@color/kq_bg_sunken` |
| --surface | `@color/kq_bg_elev` |
| --surface-2 | `@color/kq_surface_2` |
| --ink / --ink-2 / --ink-3 | `@color/kq_ink` / `kq_ink_2` / `kq_ink_3` |
| --hairline / --hairline-2 | `@color/kq_hairline` / `kq_hairline_strong` |
| --safe, --safe-bg, --safe-ink | `@color/kq_safe`, `kq_safe_bg`, `kq_safe_ink` |
| --warn… / --danger… | `kq_warn*` / `kq_danger*` |
| --primary | `@color/kq_primary` (state-list → ?attr/kqPrimary, меняется с акцентом) |
| --primary-2 | `@color/kq_primary_2` |
| --primary-soft | `@color/kq_primary_soft` |
| --primary-ink | `@color/kq_primary_ink` |
| --on-primary | `@color/kq_on_primary` |

В drawable, где нельзя state-list — `?attr/kqPrimary`, `?attr/kqPrimarySoft`, `?attr/kqPrimary2`, `?attr/kqPrimaryInk`.

### Шрифты
- Onest: `@font/kq4_display` (family 400–800) или прямо `@font/onest_regular|medium|semibold|bold|extrabold`
- Spline Sans Mono: `@font/kq4_mono` или `@font/ssmono_medium|semibold|bold`
- Тема уже ставит Onest по умолчанию на все TextView.
- CSS `font: 700 28px` → `fontFamily=@font/onest_bold` + `textSize=28sp`. px≈dp/sp 1:1.

### Текстовые стили
`@style/KQ4.HTitle` (28sp/700/-0.025), `KQ4.HSub` (15sp/400/ink-2/1.5), `KQ4.SecTitle` (19sp/700),
`KQ4.Eyebrow` (12sp/700/UPPERCASE/kq_primary), `KQ4.RowTitle` (15.5sp/600), `KQ4.RowSub` (13sp/ink-3),
`KQ4.Tag` (12.5sp/700), `KQ4.Mono` (mono 11sp ls .12).

### Кнопки (MaterialButton)
`@style/KQ4.Button.Primary|Safe|Danger|Ghost|Soft` — pill h54. Большая `lg` (h58): добавить `android:minHeight="58dp"`, textSize 17sp. Малая `sm` (h44): minHeight 44dp, textSize 14.5sp, paddingHorizontal 16dp.
Не-Material фоны: `@drawable/kq4_btn_primary|safe|danger|ghost|soft` (ripple+pill).

### Карты
- `@style/KQ4.Card` (MaterialCardView: r24, surface, hairline 1dp, elevation 2) / `KQ4.Card.Flat`
- Drawable-фоны: `@drawable/kq4_card`, `kq4_card_sunken` (surface-2), `kq4_card_safe|warn|danger` (мягкие фоны), `kq4_card_selected` (primary-soft + primary stroke 1.5)
- card white на тёмном баннере (AutoScan result): `@drawable/kq4_card_white07`

### Иконки (37 шт, stroke=2, lucide-стиль из icons.jsx)
`@drawable/ic4_<name>`: shield, shield_check, shield_alert, check, check_circle, x, x_circle, alert,
scan, search, bell, gear, chart, trash, refresh, chevron, chev_down, back, phone, download, file,
folder, lock, key, eye, message, globe, clock, sun, moon, heart, star, card, wifi, layers, user, help.
Цвет задаётся `app:tint` на ImageView (иначе kq_ink).
Маппинг JSX: `I.shieldCheck` → `ic4_shield_check`, `I.chevDown` → `ic4_chev_down` и т.п.

### Прочие drawable
- `.av` чипы 48dp r18: `@drawable/kq4_av_primary|safe|warn|danger|neutral` (av danger = danger_bg фон + ic tint kq_danger)
- `.tag` пилюли h30: `@drawable/kq4_tag_safe|warn|danger|soft|outline|primary` (+ точка `@drawable/kq4_dot` 7dp с tint цвета текста)
- `.icon-btn` 44dp: `@drawable/kq4_icon_btn` (+ на цветном баннере `kq4_icon_btn_onbanner`)
- nav pill: `@drawable/kq_nav_active_pill`
- toggle: SwitchCompat c `android:track="@drawable/kq4_toggle_track"` `android:thumb="@drawable/kq4_toggle_thumb"` (50×30)
- баннер результата: `@drawable/kq4_banner_danger` (градиент danger→#8E1D12, низ r26)
- сплэш: `@drawable/kq4_splash_bg` (градиент primary→primary-2)
- скан-фон тёмный: `@drawable/kq4_scan_bg`
- орнамент 8-конечная звезда: `@drawable/kq4_star` (78dp, тонкая обводка #A23A2C). Узор `.pattern`: повторить звезду 2–4 раза декоративно (alpha 0.05 light / 0.08 dark) или ImageView+alpha — не обязательно тайлить.
- круги: `kq4_circle_danger`, `kq4_circle_danger_soft`, `kq4_circle_safe_soft`, `kq4_circle_primary_soft`, `kq4_circle_surface`, `kq4_circle_white`
- ввод (textarea): `@drawable/kq4_input`

### Компоненты
- Кольцо (design Ring): `com.kiberqalqon.KqRingView` — `setValue(0..100f)`, `ringColor`, `trackColor`, `strokeWidthDp`. Контент поверх — через FrameLayout.
- TopBar (Dashboard): `<include layout="@layout/inc_kq4_topbar"/>` — id: kq4TopTitle, kq4TopSub, kq4TopDot, kq4BtnLang, kq4LangCode, kq4BtnBell.
- Нижняя навигация: `<include layout="@layout/view_kq_bottom_nav"/>` + `KqBottomNav.attach(this, KqBottomNav.Tab.HOME|SCAN|STATS|SETTINGS)`. Уже в стиле v4 — НЕ трогать её файлы.
- Логотип: `@drawable/kq_logo_anor` (реальный PNG гранат-щит). LogoDisc = белый круг `kq4_circle_white` + ImageView логотипа внутри (84% размера).

## Правила (НАРУШАТЬ НЕЛЬЗЯ)

1. **Только свои файлы.** Каждому агенту назначены конкретные layout/activity. НЕ редактировать общие файлы: colors.xml, themes.xml, styles.xml, attrs.xml, strings.xml (основной!), view_kq_bottom_nav.xml, KqBottomNav.kt, inc_kq4_topbar.xml, AndroidManifest.xml.
2. **Строки** — только в СВОЁМ файле `res/values/strings_kq4_<экран>.xml` (узбекский, тексты брать из data.jsx/JSX-экранов) + перевод `res/values-ru/strings_kq4_<экран>.xml` (русский). Уже существующие подходящие ключи из strings.xml переиспользовать можно. Апострофы экранировать: `O\'zbekcha`. В коде никаких хардкод-строк UI.
3. **Новые drawable** — только с префиксом `kq4_<экран>_*`. Общие kq4_* выше — использовать как есть.
4. **Функциональность сохранить.** Это рескин: все обработчики, интенты, данные, coroutine-логика остаются. Если у активити есть привязки к id — либо сохранить id, либо аккуратно обновить Kotlin. ViewBinding: id `fooBar` → `binding.fooBar`.
5. **Никогда не возвращать ложный SAFE** — не менять логику вердиктов.
6. **Узбекский для всего видимого пользователю**; RU-перевод обязателен для каждого нового ключа.
7. Активити-паттерн: `attachBaseContext(LocaleHelper.apply(newBase))` + `ThemeHelper.applyAccent(this)` ДО setContentView — сохранить как есть.
8. Скролл-экраны: ScrollView/NestedScrollView, `clipToPadding=false`, paddingBottom ≥ 90dp если есть нижняя навигация.
9. Экран `.screen-pad` = padding 14dp top / 18dp horizontal / 30dp bottom.
10. Тени: на Android достаточно elevation у MaterialCardView (2dp) — не изобретать.
11. Не удалять старые файлы/ресурсы — они могут использоваться другими экранами.
12. После правок НЕ запускать gradle build (долго); достаточно консистентности XML/Kotlin — финальную сборку делает оркестратор.

## Соответствие экранов

| Дизайн (JSX) | Activity | Layout |
|---|---|---|
| Language | LanguageSelectActivity | activity_language_select.xml |
| Splash | SplashActivity | activity_splash.xml |
| Onboarding | OnboardingActivity | activity_onboarding.xml |
| InitialScan | InitialScanActivity | activity_initial_scan.xml |
| Dashboard | DashboardNewActivity | activity_dashboard_new.xml |
| Apps («Skaner» tab) | MainActivity | activity_main.xml |
| AutoScan | AutoScanActivity | activity_auto_scan.xml |
| ScanResult | ScanResultActivity | activity_scan_result.xml |
| Permissions | PermissionsDetailActivity (скелет готов) | создать activity_permissions_detail.xml |
| Stats | ScanHistoryActivity | activity_scan_history.xml |
| Settings | SettingsActivity | activity_settings_new.xml |
| Quarantine | QuarantineActivity | UI строится в коде — перевести на стиль v4 |
| Protection | ProtectionStatusActivity | UI в коде — стиль v4 |
| About | (см. activity_about.xml — найти владельца) | activity_about.xml |
| Report | ReportProblemActivity | activity_report_problem.xml |

Экранов, которых нет в дизайне (Consent, TelemetrySettings, Diagnostics, HiddenThreats, ApkList и др.) —
оформлять в том же языке дизайна: фон kq_bg, карточки KQ4.Card, заголовок-паттерн
(icon-btn back + eyebrow + h-title), кнопки KQ4.Button.*, иконки ic4_*.
