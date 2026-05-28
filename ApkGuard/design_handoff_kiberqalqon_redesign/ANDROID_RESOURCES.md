# Quick reference — Drop-in XML snippets

Common drawables/styles for the redesign. Paste these into the corresponding files in `app/src/main/res/`.

## values/colors.xml additions (LIGHT)

```xml
<!-- KiberQalqon · Milliy LIGHT -->
<color name="kq_bg">#FBF8F3</color>
<color name="kq_bg_elev">#FFFFFF</color>
<color name="kq_bg_sunken">#F2EFE8</color>
<color name="kq_ink">#1B2530</color>
<color name="kq_ink_2">#5A6772</color>
<color name="kq_ink_3">#8A969E</color>
<color name="kq_hairline">#E1E4E6</color>
<color name="kq_hairline_strong">#C9CFD3</color>
<color name="kq_primary">#2D9CB8</color>
<color name="kq_primary_2">#1F7B92</color>
<color name="kq_primary_soft">#DCEEF1</color>
<color name="kq_on_primary">#FFFFFF</color>
<color name="kq_accent">#CC9442</color>
<color name="kq_accent_soft">#F4E8D2</color>
<color name="kq_accent_ink">#7E5B22</color>
<color name="kq_safe">#4FAE6E</color>
<color name="kq_safe_bg">#E5F3E8</color>
<color name="kq_safe_ink">#236138</color>
<color name="kq_warn">#E8A03C</color>
<color name="kq_warn_bg">#FBEFD8</color>
<color name="kq_warn_ink">#7A5318</color>
<color name="kq_danger">#D74536</color>
<color name="kq_danger_bg">#F7DCD7</color>
<color name="kq_danger_ink">#7A2218</color>
```

## values-night/colors.xml additions (DARK · cyber)

```xml
<color name="kq_bg">#0F1820</color>
<color name="kq_bg_elev">#192634</color>
<color name="kq_bg_sunken">#0A111A</color>
<color name="kq_ink">#F2F6F7</color>
<color name="kq_ink_2">#B5C2C9</color>
<color name="kq_ink_3">#7B8990</color>
<color name="kq_hairline">#26333F</color>
<color name="kq_hairline_strong">#3D4C5D</color>
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
```

## values/styles.xml additions

```xml
<!-- Type -->
<style name="KQ.Display.Large" parent="">
    <item name="android:fontFamily">@font/space_grotesk_bold</item>
    <item name="android:textSize">38sp</item>
    <item name="android:textColor">@color/kq_ink</item>
    <item name="android:letterSpacing">-0.02</item>
    <item name="android:lineSpacingMultiplier">1.0</item>
</style>

<style name="KQ.H1" parent="">
    <item name="android:fontFamily">@font/space_grotesk_bold</item>
    <item name="android:textSize">26sp</item>
    <item name="android:textColor">@color/kq_ink</item>
    <item name="android:letterSpacing">-0.018</item>
    <item name="android:lineSpacingMultiplier">1.18</item>
</style>

<style name="KQ.H2" parent="">
    <item name="android:fontFamily">@font/space_grotesk_bold</item>
    <item name="android:textSize">18sp</item>
    <item name="android:textColor">@color/kq_ink</item>
    <item name="android:letterSpacing">-0.01</item>
</style>

<style name="KQ.Eyebrow" parent="">
    <item name="android:fontFamily">@font/jetbrains_mono_bold</item>
    <item name="android:textSize">11sp</item>
    <item name="android:textColor">@color/kq_primary</item>
    <item name="android:letterSpacing">0.12</item>
    <item name="android:textAllCaps">true</item>
</style>

<style name="KQ.Body" parent="">
    <item name="android:fontFamily">@font/manrope_regular</item>
    <item name="android:textSize">14sp</item>
    <item name="android:textColor">@color/kq_ink_2</item>
    <item name="android:lineSpacingMultiplier">1.5</item>
</style>

<style name="KQ.Mono.Caption" parent="">
    <item name="android:fontFamily">@font/jetbrains_mono</item>
    <item name="android:textSize">11sp</item>
    <item name="android:textColor">@color/kq_ink_3</item>
    <item name="android:letterSpacing">0.04</item>
</style>

<!-- Buttons -->
<style name="KQ.Button" parent="Widget.MaterialComponents.Button">
    <item name="android:minHeight">52dp</item>
    <item name="cornerRadius">26dp</item>
    <item name="android:fontFamily">@font/space_grotesk_bold</item>
    <item name="android:textSize">15sp</item>
    <item name="android:letterSpacing">0.01</item>
    <item name="android:textAllCaps">false</item>
    <item name="iconGravity">textStart</item>
    <item name="iconPadding">8dp</item>
</style>

<style name="KQ.Button.Primary" parent="KQ.Button">
    <item name="backgroundTint">@color/kq_primary</item>
    <item name="android:textColor">@color/kq_on_primary</item>
</style>

<style name="KQ.Button.Ghost" parent="KQ.Button">
    <item name="backgroundTint">@android:color/transparent</item>
    <item name="android:textColor">@color/kq_ink</item>
    <item name="strokeWidth">1dp</item>
    <item name="strokeColor">@color/kq_hairline_strong</item>
</style>

<style name="KQ.Button.Danger" parent="KQ.Button">
    <item name="backgroundTint">@color/kq_danger</item>
    <item name="android:textColor">@color/white</item>
</style>

<!-- Card -->
<style name="KQ.Card" parent="Widget.MaterialComponents.CardView">
    <item name="cardCornerRadius">22dp</item>
    <item name="cardBackgroundColor">@color/kq_bg_elev</item>
    <item name="strokeWidth">1dp</item>
    <item name="strokeColor">@color/kq_hairline</item>
    <item name="cardElevation">2dp</item>
</style>
```

## drawable/kq_sev_crit.xml (severity chip — danger)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/kq_danger_bg" />
    <corners android:radius="6dp" />
    <padding android:left="8dp" android:right="8dp"
             android:top="4dp" android:bottom="4dp" />
</shape>
```

Repeat for `kq_sev_high.xml` (`kq_warn_bg`) and add `kq_sev_safe.xml` (`kq_safe_bg`).

## drawable/kq_chip_filter_unselected.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@android:color/transparent" />
    <stroke android:width="1dp" android:color="@color/kq_hairline_strong" />
    <corners android:radius="18dp" />
</shape>
```

## drawable/kq_chip_filter_selected.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/kq_primary_soft" />
    <stroke android:width="1dp" android:color="@color/kq_primary" />
    <corners android:radius="18dp" />
</shape>
```

## drawable/kq_splash_gradient.xml (already exists — verify)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:type="linear"
        android:angle="290"
        android:startColor="@color/kq_primary"
        android:endColor="@color/kq_primary_2" />
</shape>
```

(Note: Android gradient angles: 0°=L→R, 90°=B→T, 180°=R→L, 270°=T→B. Design says 160° from top — closest Android value is **290°** which gives 160° clockwise from horizontal.)

## drawable/kq_hero_card_bg.xml (Dashboard hero card)

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <gradient
                android:type="linear"
                android:angle="290"
                android:startColor="@color/kq_bg_elev"
                android:endColor="@color/kq_primary_soft" />
            <corners android:radius="22dp" />
            <stroke android:width="1dp" android:color="@color/kq_primary_soft" />
        </shape>
    </item>
    <item android:right="-30dp" android:top="-30dp" android:width="200dp" android:height="200dp"
          android:gravity="end|top">
        <bitmap android:src="@drawable/kq_star_tile"
                android:alpha="26"
                android:tileMode="repeat" />
    </item>
</layer-list>
```

## res/font/ directory — add these XML font definitions

`font/space_grotesk_bold.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:app="http://schemas.android.com/apk/res-auto"
    app:fontProviderAuthority="com.google.android.gms.fonts"
    app:fontProviderPackage="com.google.android.gms"
    app:fontProviderQuery="name=Space+Grotesk&amp;weight=700"
    app:fontProviderCerts="@array/com_google_android_gms_fonts_certs" />
```

Mirror for `space_grotesk_regular.xml` (weight=500), `manrope_regular.xml` (weight=400), `manrope_bold.xml` (weight=600), `jetbrains_mono.xml` (weight=500), `jetbrains_mono_bold.xml` (weight=600).

Add the certs array to `values/font_certs.xml` from Google Fonts documentation.
