# Handoff: UzGuard — Milliy + Cyber Redesign

> **For:** Claude Code working in `APK Virus Analysis/ApkGuard/` (Android Kotlin app)
> **From:** HTML/React design prototype
> **Goal:** Bring the existing Activities/layouts in line with the new design system shown in the prototype.

---

## 1. Read this first

The folder `design_html_reference/` contains an **HTML/React prototype** of the new design — 8 screens, light + dark themes, three accent colors. **Do not ship the HTML.** It exists as a pixel-level reference for what the real Android UI should look and feel like.

Your job: **edit the existing Kotlin/XML codebase to match the prototype.** The codebase already has all the Activities, layouts, drawables, and the `kq_*` color tokens from the design system — most of the work is replacing the contents of existing layout XML files and aligning theme/styles/drawables with the design tokens below.

Open `screenshots/` to see exactly what each screen should look like:

| # | File | Screen | Activity |
|---|---|---|---|
| 01 | `01-splash.png` | Splash (turquoise gradient + 8-point star pattern) | `SplashActivity` |
| 02 | `02-onboarding.png` | Onboarding (3 slides, pager dots) | `OnboardingActivity` |
| 03 | `03-dashboard-light.png` | Dashboard — light theme | `DashboardNewActivity` |
| 09 | `09-dashboard-dark.png` | Dashboard — dark theme | `DashboardNewActivity` |
| 04 | `04-apk-list.png` | APK list with filter chips | `MainActivity` / new ApkListActivity |
| 05 | `05-auto-scan-alert.png` | Full-screen auto-scan (terminal log) | `AutoScanActivity` |
| 06 | `06-scan-result-banker.png` | Scan result — Ajina.Banker detail | `ScanResultActivity` |
| 10 | `10-scan-result-dropper.png` | Scan result — RoundRift dropper detail | `ScanResultActivity` (variant) |
| 07 | `07-settings.png` | Settings (theme + accent + lang) | `SettingsActivity` |
| 08 | `08-statistics.png` | Statistics (7-day chart, 5 samples) | `ScanHistoryActivity` (or new StatsActivity) |

### Fidelity
**High-fidelity.** Use exact hex values, dp values, font weights, and corner radii from the tables below. The prototype is the source of truth — if the existing layout doesn't match, replace it.

---

## 2. Design system — exact tokens

### 2.1 Color palette

The design has **two themes** (light + dark) and **three accent variants** (Feruz / Za'faron / Anor). Default is `light` + `turquoise`.

Add or update these in `app/src/main/res/values/colors.xml` (light) and `values-night/colors.xml` (dark). Many `kq_*` tokens already exist — refine them to match these exact hex values (converted from oklch in the prototype's `styles.css`).

#### LIGHT theme (`values/colors.xml`)
```xml
<!-- ── UZGUARD · Milliy + Friendly (LIGHT) ───────── -->

<!-- Surface -->
<color name="kq_bg">#FBF8F3</color>           <!-- warm cream (oklch 0.985 0.012 85) -->
<color name="kq_bg_elev">#FFFFFF</color>      <!-- elevated cards -->
<color name="kq_bg_sunken">#F2EFE8</color>    <!-- sunken / filled inputs -->

<!-- Ink -->
<color name="kq_ink">#1B2530</color>          <!-- primary text -->
<color name="kq_ink_2">#5A6772</color>        <!-- secondary text -->
<color name="kq_ink_3">#8A969E</color>        <!-- hint / labels -->
<color name="kq_hairline">#E1E4E6</color>     <!-- borders -->
<color name="kq_hairline_strong">#C9CFD3</color>

<!-- Primary — Samarqand turquoise -->
<color name="kq_primary">#2D9CB8</color>        <!-- buttons, accents -->
<color name="kq_primary_2">#1F7B92</color>      <!-- pressed, gradient end -->
<color name="kq_primary_soft">#DCEEF1</color>   <!-- soft fills, chip bg -->
<color name="kq_on_primary">#FFFFFF</color>

<!-- Accent — Suzani saffron -->
<color name="kq_accent">#CC9442</color>
<color name="kq_accent_soft">#F4E8D2</color>
<color name="kq_accent_ink">#7E5B22</color>

<!-- Status -->
<color name="kq_safe">#4FAE6E</color>
<color name="kq_safe_bg">#E5F3E8</color>
<color name="kq_safe_ink">#236138</color>
<color name="kq_warn">#E8A03C</color>
<color name="kq_warn_bg">#FBEFD8</color>
<color name="kq_warn_ink">#7A5318</color>
<color name="kq_danger">#D74536</color>
<color name="kq_danger_bg">#F7DCD7</color>
<color name="kq_danger_ink">#7A2218</color>

<!-- Pattern overlay (Uzbek 8-point star) — kq_primary @ ~10% alpha -->
<color name="kq_pattern">#1A2D9CB8</color>
```

#### DARK theme (`values-night/colors.xml`) — cyber-security stil
```xml
<!-- ── UZGUARD · Cyber Security (DARK) ───────────── -->

<color name="kq_bg">#0F1820</color>            <!-- deep navy -->
<color name="kq_bg_elev">#192634</color>
<color name="kq_bg_sunken">#0A111A</color>

<color name="kq_ink">#F2F6F7</color>
<color name="kq_ink_2">#B5C2C9</color>
<color name="kq_ink_3">#7B8990</color>
<color name="kq_hairline">#26333F</color>
<color name="kq_hairline_strong">#3D4C5D</color>

<!-- Primary glows brighter on dark -->
<color name="kq_primary">#5BCBD3</color>
<color name="kq_primary_2">#A7E1E0</color>
<color name="kq_primary_soft">#163240</color>
<color name="kq_on_primary">#0B1A1F</color>

<color name="kq_accent">#E0A040</color>
<color name="kq_accent_soft">#3A2F18</color>
<color name="kq_accent_ink">#F0CC8A</color>

<color name="kq_safe">#5BCB8A</color>
<color name="kq_safe_bg">#1F3328</color>
<color name="kq_safe_ink">#A8E0B8</color>
<color name="kq_warn">#E8B860</color>
<color name="kq_warn_bg">#332A18</color>
<color name="kq_warn_ink">#F0CC8A</color>
<color name="kq_danger">#E25A4A</color>
<color name="kq_danger_bg">#36201C</color>
<color name="kq_danger_ink">#F0A89A</color>

<color name="kq_pattern">#245BCBD3</color>
```

#### Accent variants (Tweaks-driven, optional)
If you keep the Feruz/Za'faron/Anor switcher in Settings, store the accent as a SharedPref and dynamically override `kq_primary` / `kq_primary_2` / `kq_primary_soft` via a theme attribute or `ThemeHelper.kt` (which already exists).

| Variant | `kq_primary` (light) | `kq_primary` (dark) |
|---|---|---|
| `turquoise` (default) | `#2D9CB8` | `#5BCBD3` |
| `saffron` | `#CC9442` | `#E8B860` |
| `pomegranate` | `#C13A28` | `#E25A4A` |

### 2.2 Spacing scale (dp)
```
XS = 4dp · S = 8dp · M = 12dp · L = 16dp · XL = 20dp · XXL = 24dp · XXXL = 32dp
```
Padding inside cards: **14dp** (small cards), **18dp** (hero cards), **24dp** (screen edges on large hero areas — splash, onboarding text).

### 2.3 Corner radius
```
r_xs  = 8dp   <!-- chips, small fields -->
r_sm  = 12dp  <!-- buttons (square), inputs -->
r_md  = 16dp  <!-- small cards, icon tiles -->
r_lg  = 22dp  <!-- cards (main) -->
r_xl  = 28dp  <!-- hero card on dashboard -->
r_pill = 999dp <!-- buttons, chips -->
```

### 2.4 Typography

The HTML uses Google Fonts. On Android, request these in `res/font/`:

| Role | Font | Use cases |
|---|---|---|
| `font-display` | **Space Grotesk** (500–800) | Titles, buttons, numbers, big headings |
| `font-body` | **Manrope** (400–700) | Body copy, list items, labels |
| `font-mono` | **JetBrains Mono** (500–700) | Eyebrows, package names, hex values, terminal log |

Type scale (sp):

| Style | Size | Weight | Line height | Tracking | Family |
|---|---|---|---|---|---|
| Display XL | 38sp | 800 | 1.0 | -0.02em | display |
| H1 / Hero title | 26–28sp | 700 | 1.18 | -0.018em | display |
| H2 / Section title | 18sp | 700 | 1.2 | -0.01em | display |
| Eyebrow | 11sp | 600 | 1.0 | +0.12em UPPER | mono |
| Body | 14–15sp | 400 | 1.5 | 0 | body |
| Body small | 12.5sp | 400 | 1.4 | 0 | body |
| Label / button | 14–15sp | 600 | 1.0 | +0.01em | display |
| Caption / mono | 10.5–11.5sp | 500–600 | 1.4 | +0.04em | mono |
| Sev tag | 10sp | 700 | 1.0 | +0.08em UPPER | mono |

### 2.5 Shadows / elevations
- **Card (level 1):** elevation 2dp + soft drop shadow `rgba(20,40,60,0.06)` at 4dp blur, 1dp y-offset
- **Hero card (level 2):** elevation 6dp, shadow `rgba(20,40,60,0.10)` at 18dp blur
- **Modal/alert:** elevation 12dp

In XML use `app:cardElevation` on `MaterialCardView` and `android:elevation` on plain ViewGroups.

### 2.6 The Uzbek 8-point star pattern

This is the **brand signature**. It already exists as `kq_star_tile.xml` and `kq_star_tile_white.xml` — verify they render this exact path:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="80dp" android:height="80dp"
    android:viewportWidth="80" android:viewportHeight="80">
  <path
    android:pathData="M40 8 L48 32 L72 40 L48 48 L40 72 L32 48 L8 40 L32 32 Z
                      M40 18 L46 34 L62 40 L46 46 L40 62 L34 46 L18 40 L34 34 Z"
    android:strokeColor="@color/kq_primary"
    android:strokeWidth="1.2"
    android:fillColor="@android:color/transparent" />
  <path
    android:pathData="M0 0 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0
                      M80 0 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0
                      M0 80 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0
                      M80 80 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0"
    android:fillColor="@color/kq_primary" />
</vector>
```

Tile it as background via `BitmapDrawable` with `android:tileMode="repeat"`, OR as a layer on top of the gradient hero card. Opacity 10% on light, 14% on dark.

### 2.7 Shield brand mark

The shield logo (used on splash, onboarding, settings). Already exists as `kq_shield_mark.xml` / `kq_shield_mark_white.xml`. Verify shape:

```
Outer:  M50 6 L86 18 V46 C86 70 70 86 50 94 C30 86 14 70 14 46 V18 Z
Inner star (white): M0 -28 L8 -8 L28 0 L8 8 L0 28 L-8 8 L-28 0 L-8 -8 Z  (centered at 50,50)
Center dot: r=4 in kq_primary at (50,50)
```

---

## 3. Screen-by-screen specs

### 3.1 Splash (`SplashActivity` / `activity_splash.xml`)

**Screenshot:** `01-splash.png`

- **Background:** Linear gradient 160° from `kq_primary` → `kq_primary_2`. Even in light theme, splash is the **turquoise gradient with white type** — it doesn't react to dark mode.
- **Pattern overlay:** white 8-point star tile at 18% alpha, tiled across the whole screen.
- **Center stack (vertical, centered):**
  1. Pulse rings — two concentric circles (`kq_pulse_ring.xml`), 160×160 dp, white at 20% alpha, animation `kq_pulse_ring.xml` (already exists), one delayed 0.7s.
  2. Glass disc — 110×110 dp circle, white 15% alpha, 1dp white 30% border, `backdropBlur` if available (else just translucent).
  3. Shield logo (`kq_shield_mark_white.xml`), 68dp.
- **38dp below disc:** "UzGuard" — Space Grotesk 800, 38sp, white, letter-spacing -0.02em.
- **10dp below name:** "TELEFON HIMOYASI · v 7.5" — JetBrains Mono 500, 13sp, +0.24em tracking UPPER, white 85% alpha.
- **Bottom 70dp:** "Zararli APK fayllardan himoyalanish" — Manrope 500, 12sp, white 75% alpha.
- **Bottom 38dp:** 3 loading dots (use `kq_load_dot.xml`), animated with staggered delays (0, 0.15s, 0.3s).
- **Duration:** 2.4 seconds, then start `OnboardingActivity` if first run, else `DashboardNewActivity`.

### 3.2 Onboarding (`OnboardingActivity` / `activity_onboarding.xml` + `item_onboarding_page.xml`)

**Screenshot:** `02-onboarding.png`

Use a `ViewPager2` with 3 pages. Each page is `item_onboarding_page.xml`.

**Top bar (above pager, NOT inside item):**
- Right-aligned "O'tkazib yuborish" text button — Manrope 500, 14sp, `kq_ink_3`, padding 12dp.

**Each page:**
- **Top half — visual area** (~55% of screen height):
  - Background: subtle 8-point star tile (`@color/kq_pattern` over `kq_bg`) at ~8% opacity, NOT full hero gradient.
  - Centered visual per slide (see 3 variants below).
- **Bottom half — text + footer:**
  - Eyebrow (e.g. "01 · BOSHLASH") — mono 11sp +0.12em UPPER, color `kq_primary`.
  - Title — display 28sp, 700, -0.02em, `kq_ink`. **balance** text wrap.
  - Body — body 15sp 400, line-height 1.5, `kq_ink_2`.
  - Pager dots (use `kq_pager_dot_on.xml` / `kq_pager_dot_off.xml`). Active dot is 22dp wide.
  - CTA: `btn primary block` — pill-shaped, height 52dp, `kq_primary` bg, white text, full width. Label changes from "Davom etish" → "Boshlash · Himoyani yoqish" on last page.

**Three slide visuals:**

1. **Slide 1 (auto):** A 220×220 dp protection meter — ring chart filled to 92% with the brand gradient (`kq_primary` → `kq_accent`). Center: shield logo 60dp + "92%" (display 36sp) + "Faol" eyebrow.
   *(See `SpeedometerView.kt` — likely already implemented; just confirm colors.)*

2. **Slide 2 (threats):** 2×2 grid of cards (240dp wide total, gap 10dp).
   Each card: icon tile 28dp (bug icon, danger/warn bg), sev chip ("KRIT" / "YUQORI"), family name (display 12.5sp, 600).
   Families: "Ajina.Banker" (crit), "RoundRift" (crit), "SMS Stealer" (high), "Phish Overlay" (high).

3. **Slide 3 (control):** A single mock alert card (240dp wide):
   - Header row: red alert icon + "Xavfli APK aniqlandi" (700 14sp).
   - Body: 'Fayl **RASMLAR (18).apk** Telegram orqali yuklandi. SMS o'qish ruxsatini so'raydi.'
   - Two buttons row: filled "O'chirish" + outlined "Batafsil", height 40dp.

### 3.3 Dashboard (`DashboardNewActivity` / `activity_dashboard_new.xml`)

**Screenshots:** `03-dashboard-light.png`, `09-dashboard-dark.png`

Vertical scroll. Top bar is part of content, not a separate `Toolbar`.

**Top bar (16dp top padding):**
- Left: 32dp shield logo + 2-line stack ("UzGuard" 700 16sp / "HIMOYA · FAOL" mono 10sp +0.1em).
- Right: two 40dp `icon-btn` circles ("UZ" mono 10sp, bell icon).

**Hero card (kq_card with pattern overlay):**
- Background: linear gradient 160° from `kq_bg_elev` (0%) → `kq_primary_soft` (130%, ie a tinted edge).
- Border: 1dp `color-mix(kq_primary 18%, kq_hairline)` — use `bg_card_glow.xml` or new drawable.
- Big 8-point star ornament in top-right corner at 10% opacity (220×220, partially clipped).
- Padding 18dp.
- Left column:
  - Eyebrow "SIZNING TELEFONINGIZ"
  - Title "Himoyalangan" (display 22sp 700)
  - Sub "So'nggi tekshiruv 12 daqiqa oldin" (body 13sp, `kq_ink_2`)
- Right column: `SpeedometerView`, 140dp, value 92%, label "Faol".
- Bottom row (marginTop 14dp): button row [`btn primary` "Hozir tekshirish" with scan icon, flex 1] + [`btn ghost` 52×52 refresh icon].

**Stats row (3 tiles, equal width, gap 10dp):**
Each tile: card 12dp padding, value (display 24sp 700), eyebrow (mono 11sp +0.08em UPPER `kq_ink_3`).
1. "247 / Tekshirildi" — value in `kq_ink`
2. "5 / Bloklandi" — value in `kq_danger`
3. "12 / Karantin" — value in `kq_warn`

**"So'nggi xavflar" section** (marginTop 22dp):
- Head: eyebrow + title "Bugungi topilmalar" + right "Hammasi >" text button.
- Card list (use existing `item_apk.xml` styled to match `li-row`):
  - 3 rows. Each: 44dp icon tile (`kq_danger_bg` bg, `kq_danger_ink` icon), filename + family/source/time, severity chip on right.
  - Sample data: `RASMLAR (18).apk` / `VIDEO.20.01.2026.mp4.apk` / `toydanfotolar(9.jpg).apk`.

**"Himoya qatlamlari" section** (marginTop 22dp):
- 2×2 grid of cards (gap 10dp). Each card 12dp padding:
  - 32dp icon tile (`kq_primary_soft` bg, `kq_primary` icon).
  - 8dp status dot in top-right (`kq_safe` with halo glow).
  - Title "FileObserver" / "ZIP-evasion" / "Phish bloker" / "C2 qora ro'yhat" (display 13sp 700).
  - Subtitle one-liner (body 11sp, `kq_ink_3`).

**Bottom nav** (`bg_bottom_nav.xml`):
- 4 items: "Asosiy" (shield), "Skaner" (scan), "Statistika" (bars), "Sozlamalar" (gear).
- Active item: 56×32 pill with `kq_primary_soft` background, icon + label in `kq_primary`.
- Inactive: just icon + label in `kq_ink_3`.

### 3.4 APK list (`MainActivity` or new `ApkListActivity` / `activity_apk_list.xml`)

**Screenshot:** `04-apk-list.png`

- **Header:** eyebrow "SKANER" + title "APK fayllar" + subtitle "{N} ta fayl tekshirildi" + right upload icon button.
- **Filter chips row** (horizontal scrollable):
  - "Hammasi 8", "Xavfli 5", "Xavfsiz 3"
  - Active chip: `kq_primary_soft` bg, `kq_primary` text + `kq_primary` border. Count badge is `kq_primary` pill with white mono number.
  - Inactive: transparent bg, `kq_hairline_strong` border, `kq_ink_2` text.
- **List card** (single `MaterialCardView`, contents = vertical LinearLayout with rows separated by 1dp `kq_hairline`):
  - Each row uses **`item_apk.xml`** — update to match `.li-row`:
    - 44dp icon tile (danger or safe bg/color).
    - Title (display 14.5sp 600).
    - Package name (mono 12sp, `kq_ink_3`).
    - Below: sev chip + " size · source" mono 10.5sp.
    - Right: chevron-right icon.
- **Bottom CTA:** outlined button "Hammasi qayta tekshirilsin" with scan icon.

**Sample data (use this exact list — from `HISOBOT_FINAL.md`):**

| Filename | Package | Size | Source | Family | Sev |
|---|---|---|---|---|---|
| RASMLAR (18).apk | com.lzthzvxte.xazoalzxhr | 1.6 MB | Telegram | Ajina.Banker | crit |
| VIDEO.20.01.2026.mp4.apk | ydbllnjd.com | 2.1 MB | ilovekkksfm.com | RoundRift | crit |
| RASMLAR (8).apk | com.oktgkst.rrcpkge | 1.4 MB | Telegram | Ajina.Banker | crit |
| toydanfotolar(9.jpg).apk | com.yzsfnie.sjsztphpis | 1.5 MB | Telegram | Ajina.Banker | crit |
| VID_23856_21052026.apk | com.puhfvysb.nzbftunmqq | 1.5 MB | WhatsApp | Ajina.Banker | crit |
| Telegram.apk | org.telegram.messenger | 73.2 MB | Play Market | — | safe |
| Instagram.apk | com.instagram.android | 65.0 MB | Play Market | — | safe |
| MyTaxi.apk | uz.mytaxi.app | 42.1 MB | Play Market | — | safe |

### 3.5 Auto-scan alert (`AutoScanActivity` / `activity_auto_scan.xml`)

**Screenshot:** `05-auto-scan-alert.png`

This is a **full-screen, dark, cyber-themed** Activity that pops up over everything else when a new APK lands in Downloads. Two phases:

#### Phase A — scanning
- Background: linear gradient 180° from `#06141a` → `#02080b`. Always dark.
- 8-point star pattern overlay (white-on-transparent, 50% alpha tile).
- Top-to-bottom scan line: 2dp horizontal teal gradient, animated via `scan_beam_sweep.xml`, glowing.
- **Center stack:**
  - 220dp pulse-ring container (2 rings, teal 22% alpha, staggered).
  - Inside: 220dp progress ring (stroke 3dp, color `#5EEAD4`, sweeps 0→100% as scan progresses).
  - Inside that: 130dp glass disc with percentage display (700 38sp).
- **Below:** "Yangi APK tekshirilmoqda" (display 22sp 700 white) + filename (mono 13sp `#a8d5d9`).
- **Below text:** terminal log card (`bg_card_cyber.xml`-style):
  - bg `#0a141a`, border `rgba(20,184,200,0.2)`, radius 12dp, padding 12×14.
  - Text mono 11.5sp 500, lines like:
    ```
    # Telegram → Downloads/
    [+] FileObserver: CLOSE_WRITE
    [+] ZIP-evasion check… GP-flag=0x01
    [+] Manifest unpack… OK
    [+] DEX scan… ajina pattern
    [!] SMS_READ + ACCESSIBILITY /* danger */
    ```
  - Colors: `[+]`, `[!]` keywords → `#F0BF6C` (warn-saffron) / `#82E0A6` (safe-green); strings → `#82E0A6`; `#…` comment lines → `#6A7A83`.

#### Phase B — result (XAVFLI / SHUBHALI / XAVFSIZ)
- Background: radial gradient `rgba(224,57,75,0.55)` at 50% 30% → transparent 60% over `linear(#1B0A0D → #0B0608)`. (Use red for danger, switch the gradient hue for safe.)
- 96dp circular badge in `kq_danger` with white alert icon, glowing halo (3 concentric box-shadow rings).
- "XAVFLI APK!" (display 30sp 800 -0.02em white).
- Subtitle: 'Yuklab olingan fayl **{family}** bank troyani oilasiga tegishli.'
- **Info card** (full-width, white 6% alpha bg, 18dp radius, 14dp padding):
  - Top row: "Fayl" label (mono 10sp +0.16em UPPER `#FF9AA6`) + filename (display 14sp 700 white). Sev chip on the right.
  - Divider (1dp white 8%).
  - Mono details: `paket: {pkg}` / `sha1: {sha1}` / `oila: {family}` / `manba: {source}`.
- **Permissions chip cloud:** small label "Xavfli ruxsatlar" + a wrap of pill chips for each: bg `rgba(224,57,75,0.18)`, border `rgba(224,57,75,0.35)`, text `#FFD4D9`, mono 10.5sp.
- **Countdown line:** "5 soniyadan keyin avtomatik o'chiriladi…" (mono 11sp, white 50%).
- **Bottom button row:** filled danger "Hozir o'chirish" (flex 2) + ghost "Batafsil" (flex 1, white text, white 25% border).

**Behavior:**
- Auto-delete countdown should be a real `CountDownTimer`. After 5s, call `FileDeleter.delete()`.
- "Batafsil" → start `ScanResultActivity` with the sample.

### 3.6 Scan result (`ScanResultActivity` / `activity_scan_result.xml`)

**Screenshots:** `06-scan-result-banker.png`, `10-scan-result-dropper.png`

Standard Activity. Vertical scroll.

**Banner head** (gradient — RED for danger, TURQUOISE for safe):
- Danger gradient: 160° `#C12D3E` → `#7A1722`. Safe: `kq_primary` → `kq_primary_2`.
- White 8-point star tile at 16% alpha overlaid.
- Top row: back chevron (left, white icon-btn 40dp) + "Tahlil natijasi" (display 13sp 600 white) + upload icon (right).
- Below (18dp marginTop): 56dp icon tile (white 15% bg, 25% border) with bug or check icon + filename block (display 22sp 800 white -0.015em, word-break) with eyebrow source above (mono 10sp +0.16em UPPER).
- Sev chip row (white 18% bg) + family name pill (black 18% bg).

**Body** (16dp side padding):

1. **"Qisqacha" card** (14dp padding):
   - Body text: "Bu fayl **{family}** oilasiga tegishli {dropper / bank troyani}. SMS-OTP kodlarini o'g'irlash uchun ishlatiladi."
   - 2×2 mini grid: "Hajm", "Manba", "Aniqlangan", "Status" — each with eyebrow + value.

2. **"Anti-tahlil hiylalari"** (only for danger):
   - Card list with 4 rows (warn-bg 44dp icon tiles):
     - **ZIP-evasion** — "Har bir fayl GP-flag=0x01 (encrypted) ko'rsatilgan. Antiviruslar ocha olmaydi, lekin Android o'rnatadi."
     - **Anti-Frida** — "`/data/local/tmp/frida-server` va `/proc/self/maps` `frida/gadget/substrate` qidiriladi."
     - **Anti-Magisk** — "`com.topjohnwu.magisk` paketi tekshiriladi — ildiz topilsa ishga tushmaydi."
     - **Anti-VPN** — "`android.net.VpnService` faolligi tekshiriladi. HTML overlay'da 'Disable VPN' so'raydi."
   - Body in row description: 11.5sp body, `kq_ink_2`, NOT truncated.

3. **"XOR/AES kalitlari" terminal card** (mono 11.5sp 1.55 line-height, bg `#0A141A`, teal-tinted border, padding 12×14):
   - For Ajina.Banker (4 of 5 samples):
     ```
     /* Ajina.Banker — DEX'dan ajratildi */
     base_key = "JYTAs0m31lxvwkQE42Y10Ktm"
     top_key  = "3183701586F97GhYNSURErMM…"
     algo     = "2x XOR + Base64"
     strings  = 1310  // ochildi
     ```
   - For RoundRift (`VIDEO.20.01.2026.mp4.apk`):
     ```
     /* RoundRift dropper — native libdan */
     key    = "sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin"
     algo   = "XOR + Base64"
     symbol = "_ZL6encKey"
     class  = "ydbllnjd.com.core.KeyManager"
     strings = 41  // 15 base64, 26 xor
     ```
   - Coloring: `/* ... */` → `#6A7A83`, keywords → `#F0BF6C`, string values → `#82E0A6`.

4. **"Tarmoq IOC" card list** (3 rows for danger):
   - Each: 44dp danger-bg icon tile (globe), domain (mono 14.5sp 600, ellipsize), label "C2 (asosiy) · qora ro'yhatda", BLOK chip.
   - Domains: `elrxzx.com`, `ilovekkksfm.com`, `ilovekkksfm.com/video/dropper.html`.

5. **"HTML overlay" card** — a render of the fake-update dialog:
   - Inset card (12dp radius), bg gradient `#F4F4F6 → #E9E9EE`, dark text — looks like Material Design dialog on light bg even in dark theme.
   - Eyebrow "← FAYK OVERLAY (*.spe)" mono 10sp +0.12em.
   - Title "Yangilanish mavjud" (display 18sp 700).
   - Body "Ilovadan foydalanish uchun yangilanishni o'rnatishingiz kerak. Hajmi: 1.6 MB."
   - Two buttons row: blue filled "O'rnatish" + outlined "Batafsil", height 36dp.
   - Caption below the inset: "3 tilda (O'zbek · Русский · Français) tayyorlangan. WebView ↔ Java ko'prigi orqali bosish bankerni ishga tushiradi." (`kq_ink_2`)

6. **Bottom actions:**
   - Danger: filled `kq_danger` "Telefondan o'chirish" + ghost "Serverga yuborish (tahlil)".
   - Safe: filled `kq_primary` "Tushunarli".

### 3.7 Settings (`SettingsActivity` / `activity_settings_new.xml`)

**Screenshot:** `07-settings.png`

Standard scrolling Activity.

**Profile card** at top (gradient `kq_bg_elev` → `kq_primary_soft` 130%, hairline glow border):
- Row: 48dp `kq_primary` rounded square (14dp corners) with white shield → "UzGuard Pro" (display 15sp 700) + "VERSIYA 7.5 · TASHKENT" (mono 11sp +0.1em) → on right: safe chip "✓ FAOL".

**Sections** (each = eyebrow heading 8dp above + card list):

1. **HIMOYA** — 4 toggle rows:
   - Avtomatik skaner / "Har 15 daqiqada papkalarni tekshiradi"
   - Xavfli APK'ni o'chirish / "Topilgan zararli fayl 5 sekunddan keyin avtomatik o'chiriladi"
   - Fishing bildirishnomalari / "OTP kodli shubhali xabarlarni yashiradi"
   - Fon xizmati / "WorkManager · har 15 daqiqada"
   - Each row: 44dp `kq_primary_soft` icon tile + title + sub + custom Toggle (44×26 pill, white knob, animate left↔right).

2. **SERVER** — 2 rows:
   - Bulutga yuklash (toggle)
   - Server manzili (chevron) — sub shows `https://api.uzguard.uz` in mono.

3. **KO'RINISH** — single card, padding 14dp:
   - **Tema** row: title + sub + segmented switch (Kun / Tun, 2 segments with sun/moon icons, active = `kq_primary` bg). Use `tweaks_panel.jsx`'s segmented look.
   - Divider.
   - **Asosiy rang** title + sub + 3 color cards (flex equal):
     - Each: 28×28 swatch (rounded 8dp) over name ("Feruz" / "Za'faron" / "Anor").
     - Selected: `kq_bg_sunken` bg + 1dp `kq_primary` border.
   - **Wire:** changing accent updates a SharedPref and re-applies theme via `ThemeHelper.kt`.

4. **TIL** — 2 rows (radio behavior):
   - O'zbekcha / Русский. Each row has a 44dp `kq_bg_sunken` tile with mono flag code "UZ"/"RU". Right side: check icon on selected, chevron on unselected.

5. **HAQIDA** — 3 chevron rows: "Loyiha haqida", "Yordam markazi", "Maxfiylik siyosati".

6. **Footer:** centered mono caption 10.5sp 500 +0.08em "UZGUARD v7.5 · BUILD 9995 / © 2026 · Made in Tashkent".

### 3.8 Statistics (`ScanHistoryActivity` or new `StatsActivity` / `activity_scan_history.xml`)

**Screenshot:** `08-statistics.png`

1. **Header:** eyebrow "STATISTIKA" + title "So'nggi 7 kun" + sub.

2. **Big totals row (2 tiles):**
   - "JAMI 247 / tekshirilgan APK" — number in `kq_ink` (display 32sp 800).
   - "BLOKLANDI 5 / zararli oilalar" — number in `kq_danger`. Eyebrow in `kq_danger`.

3. **"Faollik · Tekshiruvlar / kun" chart card:**
   - Subhead row with "Hafta" outline chip on right.
   - 7 vertical bars in a `LinearLayout(horizontal, weightSum=7)`, height 130dp, alignBottom.
   - Each bar: rounded top 6dp, gradient `kq_primary` → `kq_primary_2` (or `kq_danger` if that day had a threat). Width fills weight.
   - Day with a threat: a small danger badge floats above the bar showing the count.
   - Day labels below: "Du, Se, Cho, Pa, Ju, Sh, Ya" — mono 10sp 600.
   - Data: `[Du 32/0, Se 41/1, Cho 38/0, Pa 52/2, Ju 47/1, Sh 28/0, Ya 9/1]`.
   - Replace existing `StatsGraphView.kt` rendering with this.

4. **"Aniqlangan tahdidlar" oilalar card:**
   - 4 horizontal bar rows. Each: 90dp label + flex track (10dp tall, 5dp radius, `kq_bg_sunken` bg) + 32dp value.
   - "Ajina.Banker 4/5" (danger color) · "RoundRift 1/5" (danger) · "SMS Stealer 0/5" (warn) · "Phish overlay 0/5" (warn).

5. **"5 namuna · Topilgan tahdidlar solishtirmasi" card** — list of all 5 dangerous samples, each row:
   - Filename (display 13sp 700) + package (mono 10.5sp `kq_ink_3`).
   - Sev chip on right.
   - Below: 3 mini tags (mono 10sp, `kq_bg_sunken` bg, 4dp radius): family / size / "SHA1: {short hash}".
   - Tap → open ScanResultActivity for that sample.

6. **"Tarmoq xaritasi · C2 manzillar" card** (140dp height):
   - Background: radial gradient `kq_primary_soft` at 65% 40% over `kq_bg_sunken`.
   - SVG-style world hint: 16×8 grid of small dot circles (`kq_ink_3` 25% alpha) — can be drawn with a `Canvas` view or `kq_pattern` tile.
   - 3 location markers: "TASHKENT" (`kq_primary` core + halo) and two C2 markers (`kq_danger` core + halo). Dashed lines from C2 to Tashkent.
   - Below the map: 2 rows for `elrxzx.com` (C2 asosiy · Ajina.Banker) and `ilovekkksfm.com` (Dropper · RoundRift). Each has a status dot + domain (mono 11.5sp) + sub (mono 10sp `kq_ink_3`) + BLOK chip.

7. **Bottom CTA:** ghost button "Hisobotni yangilash" with refresh icon.

---

## 4. Components reference

### 4.1 Custom Toggle (used in Settings)

Width 44dp, height 26dp, `cornerRadius 999dp`. Knob is 20dp circle, padding 3dp from edge. Animate `left` over 180ms cubic-bezier.

Implement as a custom `View` extending `Switch`/`SwitchCompat` styled, OR a `MaterialSwitch` with custom thumb/track tint:
- Track ON: `kq_primary`. Track OFF: `kq_hairline_strong`.
- Thumb: always white, drop shadow.

### 4.2 Sev chip (severity tag)

Inline-flex pill, height 22dp, padding 4dp × 8dp, radius 6dp.
- `crit`: bg `kq_danger_bg`, text `kq_danger_ink`. Label e.g. "XAVFLI".
- `high`: bg `kq_warn_bg`, text `kq_warn_ink`. "SHUBHALI".
- `low`: bg `kq_safe_bg`, text `kq_safe_ink`. "XAVFSIZ".
- Inside the chip: a 6dp circular dot in `currentColor` followed by uppercase mono 10sp 700 +0.08em label.

### 4.3 Eyebrow

A small `TextView` style:
```xml
<style name="Eyebrow">
  <item name="android:textSize">11sp</item>
  <item name="android:fontFamily">@font/jetbrains_mono</item>
  <item name="android:textStyle">bold</item>
  <item name="android:textColor">@color/kq_primary</item>
  <item name="android:letterSpacing">0.12</item>
  <item name="android:textAllCaps">true</item>
</style>
```

### 4.4 Buttons

Three variants, all height 52dp, pill-shaped (radius = height/2), 22dp horizontal padding, gap 8dp between icon + label.

- `BtnPrimary` — bg `kq_primary`, text `kq_on_primary`, no border. Subtle drop shadow `kq_primary @ 18% alpha, 20dp blur, -8dp y` (use elevation or layered drawable).
- `BtnGhost` — transparent bg, 1dp `kq_hairline_strong` border, text `kq_ink`.
- `BtnDanger` — bg `kq_danger`, white text, danger-tinted shadow.

Use `MaterialButton` with custom styles.

### 4.5 Bottom nav

Already wired as `bg_bottom_nav.xml`. Make sure:
- Inactive item: icon + label, color `kq_ink_3`.
- Active item: 56×32dp pill bg `kq_primary_soft`, icon + label colored `kq_primary`.

### 4.6 Card

`MaterialCardView`:
- `app:cardCornerRadius` = 22dp
- `app:cardElevation` = 0dp (we render shadow via `outlineAmbientShadowColor` + `outlineSpotShadowColor`)
- `app:strokeWidth` = 1dp
- `app:strokeColor` = `@color/kq_hairline`
- `cardBackgroundColor` = `kq_bg_elev`
- Background under modules can be the star tile (use a `FrameLayout` with the card + an ImageView with tiled `kq_star_tile` at 10% alpha).

---

## 5. Interactions & behavior

| Event | Action |
|---|---|
| Splash done (2.4s) | If first run → `OnboardingActivity`. Else → `DashboardNewActivity`. |
| Onboarding "Boshlash · Himoyani yoqish" | Save `onboarded=true`, request permissions, start `DashboardNewActivity`. |
| Dashboard "Hozir tekshirish" | Trigger manual scan → either show inline progress on the meter or open `AutoScanActivity` in manual mode. |
| Dashboard threat row tap | Open `ScanResultActivity` with `sampleId`. |
| APK list filter chip tap | Re-filter the RecyclerView. |
| APK list row tap | Safe → toast / Result with safe variant. Danger → `ScanResultActivity`. |
| AutoScanActivity foreground | Start with `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED + TURN_SCREEN_ON`. Already wired. |
| AutoScan countdown ends | Call `FileDeleter.delete(path)`, show toast, finish. |
| Settings theme switch | Save SharedPref `kq.theme = light|dark`, call `AppCompatDelegate.setDefaultNightMode(...)`, recreate. |
| Settings accent switch | Save SharedPref `kq.accent = turquoise|saffron|pomegranate`, call `ThemeHelper.applyAccent()`, recreate. |
| Settings lang switch | Save SharedPref `kq.lang = uz|ru`, call `LocaleHelper.setLocale()`, recreate. |
| Stats sample row tap | Open `ScanResultActivity` for that sampleId. |

---

## 6. Animations

Map prototype animations to existing files in `res/anim/`:

| Prototype effect | Use this Android anim |
|---|---|
| pulse-ring (splash, onboarding visual) | `kq_pulse_ring.xml` |
| fade-in on screen entry | `fade_in.xml` (or `fade_in_up.xml`) |
| scan-sweep beam (auto-scan) | `scan_beam_sweep.xml` |
| dot loaders (splash bottom) | `kq_load_dot.xml` (3 instances, staggered start delays 0 / 150ms / 300ms) |
| shield arc fill (meter) | `ObjectAnimator` on `progress` of `SpeedometerView`, duration 1200ms, `FastOutSlowInInterpolator` |

Duration scale: short 150ms · medium 300ms · long 500ms.

---

## 7. Strings (`values-uz/strings.xml` — primary)

Add or update these keys (sample — extend as needed):
```xml
<string name="kq_brand">UzGuard</string>
<string name="kq_tagline">Telefon himoyasi</string>
<string name="kq_eyebrow_status">SIZNING TELEFONINGIZ</string>
<string name="kq_status_safe">Himoyalangan</string>
<string name="kq_scan_now">Hozir tekshirish</string>
<string name="kq_threats_today">Bugungi topilmalar</string>
<string name="kq_modules">Himoya qatlamlari</string>
<string name="kq_sev_crit">XAVFLI</string>
<string name="kq_sev_high">SHUBHALI</string>
<string name="kq_sev_safe">XAVFSIZ</string>
<string name="kq_new_apk_scanning">Yangi APK tekshirilmoqda</string>
<string name="kq_auto_delete_5s">5 soniyadan keyin avtomatik o\'chiriladi…</string>
<string name="kq_delete_now">Hozir o\'chirish</string>
<string name="kq_details">Batafsil</string>
<string name="kq_settings_appearance">Ko\'rinish</string>
<string name="kq_theme_day">Kun</string>
<string name="kq_theme_night">Tun</string>
<string name="kq_accent">Asosiy rang</string>
<string name="kq_accent_turquoise">Feruz</string>
<string name="kq_accent_saffron">Za\'faron</string>
<string name="kq_accent_pomegranate">Anor</string>
<!-- … extend for every visible string in the prototype -->
```

Mirror everything in `values-ru/strings.xml` for Russian.

---

## 8. Implementation order (recommended)

1. **Tokens first.** Update `values/colors.xml` and `values-night/colors.xml` with the exact hex values above. Verify by recompiling.
2. **Splash + Onboarding.** These look most different from current — get them aligned first, end-to-end, with anims and fonts.
3. **Dashboard hero.** This is the brand showcase. Get the gradient + star pattern + meter perfect.
4. **List cards.** Update `item_apk.xml` to match the `li-row` design. Verify on both themes.
5. **Auto-scan.** This is dramatic and demo-able. Get the terminal log + glow rings right.
6. **Scan result.** Heavy content — port the sections one-by-one (summary → anti-analysis → crypto → IOC → overlay → actions).
7. **Settings.** Theme + accent + lang switchers. Wire to `ThemeHelper.kt` / `LocaleHelper.kt`.
8. **Stats.** Update `StatsGraphView` to the new bar style, then build the family bars + samples list + C2 map.

---

## 9. Files in this bundle

```
design_handoff_uzguard_redesign/
├── README.md                              ← this file
├── screenshots/                           ← target visual for every screen
│   ├── 01-splash.png
│   ├── 02-onboarding.png
│   ├── 03-dashboard-light.png
│   ├── 04-apk-list.png
│   ├── 05-auto-scan-alert.png
│   ├── 06-scan-result-banker.png
│   ├── 07-settings.png
│   ├── 08-statistics.png
│   ├── 09-dashboard-dark.png
│   └── 10-scan-result-dropper.png
└── design_html_reference/                 ← HTML prototype (open in a browser to interact)
    ├── UzGuard.html                   ← entry point — open this
    ├── styles.css                         ← all design tokens, source of truth for values
    ├── app.jsx                            ← router + tweaks panel wiring
    ├── ornaments.jsx                      ← shield, star tile, icons, ProtectionMeter
    ├── screens-onboarding.jsx
    ├── screens-main.jsx                   ← Dashboard + ApkList
    ├── screens-scan.jsx                   ← AutoScanAlert + ScanResult
    ├── screens-config.jsx                 ← Settings + Stats
    └── tweaks-panel.jsx                   ← (not needed in Android, just helper)
```

**To preview the HTML prototype locally:**
```bash
cd design_html_reference
python3 -m http.server 8000
# open http://localhost:8000/UzGuard.html
```

In the top-right corner of the prototype there's a "Tweaks" button — open it to switch theme, accent color, and jump between any of the 8 screens.

---

## 10. Source data (from `analysis/HISOBOT_FINAL.md`)

All threat data shown in the UI is real, statically-extracted IOC from the user's APK Virus Analysis project. The 5 dangerous samples are:

```
RASMLAR (18).apk    → com.lzthzvxte.xazoalzxhr  → Ajina.Banker
RASMLAR (8).apk     → com.oktgkst.rrcpkge       → Ajina.Banker
toydanfotolar.apk   → com.yzsfnie.sjsztphpis    → Ajina.Banker
VID_23856_*.apk     → com.puhfvysb.nzbftunmqq   → Ajina.Banker
VIDEO.20.01.*.apk   → ydbllnjd.com              → RoundRift (dropper)
```

C2 domains: `elrxzx.com`, `ilovekkksfm.com`, `ilovekkksfm.com/video/dropper.html`.

These should be hard-coded into a `MaliciousHashes.kt` / `MaliciousCerts.kt` / Config sample list when seeding mock data for the UI — DON'T fetch from network in the demo mode.

---

**Questions?** Open `UzGuard.html` in the browser, click the "Tweaks" button in the top-right, jump to any screen. The HTML prototype is the spec.
