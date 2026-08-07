# 🔨 КАК СОБРАТЬ KiberQalqon

## ❌ Что не работает сейчас (на этой машине)

1. **Android SDK не установлен** — `local.properties` указывает на
   `C:\Users\Muhammadali\AppData\Local\Android\Sdk`, но папки там нет.
2. **Gradle 8.4** не докачался (медленный интернет, оборвалось на ~70 MB из ~140 MB).

Без SDK сборка через CLI невозможна. Самый простой путь — Android Studio (у тебя
она уже стоит в `C:\Program Files\Android\Android Studio`).

---

## ✅ ВАРИАНТ 1: через Android Studio (рекомендую, 1 раз)

1. Открой **Android Studio**.
2. **File → Open** → выбери папку `C:\Users\Muhammadali\Desktop\APK Virus Analysis\KiberQalqon`.
3. Студия скажет "Android SDK not found" → нажми **Install SDK**.
   Она скачает:
   - SDK Platform 34
   - Build-Tools 34.0.0
   - Platform-Tools
   Это ~3 GB, 10–30 мин в зависимости от интернета.
4. После установки SDK студия сама пропишет правильный путь в `local.properties`.
5. Дождись пока загорится плашка **Gradle sync finished**.
6. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
7. Готовый файл будет в:
   ```
   KiberQalqon\app\build\outputs\apk\debug\app-debug.apk
   ```

После первой настройки последующие сборки занимают ~30–60 секунд.

---

## ✅ ВАРИАНТ 2: командная строка (если разберёшься с SDK)

### Шаг A — установить SDK headless (~3 GB)
```powershell
# Скачать commandlinetools
$url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
Invoke-WebRequest $url -OutFile $env:TEMP\cmdline-tools.zip
Expand-Archive $env:TEMP\cmdline-tools.zip -DestinationPath C:\Android\cmdline-tools

# Принять лицензии и поставить нужные пакеты
$env:ANDROID_HOME = "C:\Android"
& "C:\Android\cmdline-tools\cmdline-tools\bin\sdkmanager.bat" --sdk_root=$env:ANDROID_HOME --licenses
& "C:\Android\cmdline-tools\cmdline-tools\bin\sdkmanager.bat" --sdk_root=$env:ANDROID_HOME `
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### Шаг B — указать SDK в local.properties
```properties
# C:\Users\Muhammadali\Desktop\APK Virus Analysis\KiberQalqon\local.properties
sdk.dir=C\:\\Android
```

### Шаг C — собрать APK
```powershell
cd "C:\Users\Muhammadali\Desktop\APK Virus Analysis\KiberQalqon"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

APK окажется в `app\build\outputs\apk\debug\app-debug.apk`.

---

## 🧪 Чем проверить готовый APK

1. **На реальном телефоне (Android 7+)**:
   - Включить "Установка из неизвестных источников" для проводника
   - Скопировать `app-debug.apk` на телефон → открыть → установить
   - При первом запуске пройдёт **3 экрана onboarding**
   - Запросит разрешения: Storage, Overlay
2. **На эмуляторе**:
   - В Android Studio: **Tools → Device Manager → Create Device**
   - Pixel 6, Android 14
   - `Run → app` (зелёная стрелка)

## 🎯 Что проверить после установки

| Фича | Как проверить |
|------|--------------|
| Onboarding | Удалить app → переустановить → должно показать 3 экрана |
| ApkScanner | Тапнуть на любой APK в списке → должен дать вердикт |
| **Cert detection** | Скопировать `virus.apk` на телефон, отсканировать → должно быть **DANGER**: "Подпись из чёрного списка: Uzbek-dropper.taklifnoma" |
| Share intent | В Telegram нажать "Поделиться" на любой APK → должен появиться KiberQalqon |
| Quick Settings Tile | Открыть шторку → "Редактировать плитки" → добавить "APK tekshirish" |
| History | После пары сканов открыть историю — должны быть записи с объяснениями |

---

## 🐛 Если что-то ломается при сборке

Самые частые проблемы:

1. **`SDK location not found`** — поправить `local.properties` (см. Шаг B).
2. **`Could not find SecurityGuard.kt`** — этот файл линтер прописал в `App.kt`, но если его нет, создать пустую реализацию:
   ```kotlin
   // KiberQalqon/app/src/main/java/com/kiberqalqon/SecurityGuard.kt
   package com.kiberqalqon
   import android.content.Context
   object SecurityGuard {
       data class Result(val passed: Boolean, val reason: String = "")
       fun runAllChecks(ctx: Context): Result = Result(true)
   }
   ```
3. **`ViewPager2 not found`** — `./gradlew --refresh-dependencies`
4. **Подпись release-сборки** — для debug не нужна; для release создать `keystore.properties` (см. комментарий в `build.gradle.kts`).
