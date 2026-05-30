import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись release-сборки. Чтобы это работало, рядом с проектом создайте
// файл keystore.properties (он добавлен в .gitignore):
//   storeFile=../release.keystore
//   storePassword=...
//   keyAlias=kiberqalqon
//   keyPassword=...
// Сам keystore сгенерируется командой:
//   keytool -genkey -v -keystore release.keystore -alias kiberqalqon \
//           -keyalg RSA -keysize 2048 -validity 10000
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}
val storeFilePath: String? = keystoreProperties.getProperty("storeFile")
val hasReleaseKeystore: Boolean = keystorePropertiesFile.exists() &&
        storeFilePath != null && File(storeFilePath).exists()

// Dev's Telegram bot for OPTIONAL community-threat sharing.
// Set in local.properties (gitignored). Empty -> community feature is a no-op
// even if the user opts in. See local.properties.example.
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}
val devTgBotToken: String = localProperties.getProperty("dev.tg.bot.token", "").trim()
val devTgChatId: String = localProperties.getProperty("dev.tg.chat.id", "").trim()

// KiberQalqon Cloud (Vercel + Supabase) — markaziy monitoring paneli uchun.
// Bo'sh qoldirsangiz cloud telemetriya umuman ishlamaydi (forklar uchun no-op).
// cloud.device.secret = Vercel'dagi DEVICE_SHARED_SECRET (yozish endpointlari uchun).
// DIQQAT: bu ADMIN_SECRET EMAS — admin kaliti hech qachon APK ichiga qo'yilmaydi.
val cloudBaseUrl: String = localProperties.getProperty("cloud.base.url", "").trim()
val cloudDeviceSecret: String = localProperties.getProperty("cloud.device.secret", "").trim()

android {
    namespace = "com.kiberqalqon"
    compileSdk = 34
    // Уникальное имя выходного APK по времени сборки — чтобы не конфликтовать
    // с залоченным предыдущим файлом (Windows AV держит свежий APK ~15-30 мин).
    setProperty("archivesBaseName", "kiberqalqon-${System.currentTimeMillis()}")
    defaultConfig {
        applicationId = "com.kiberqalqon"
        minSdk = 24
        targetSdk = 34
        versionCode = 79
        versionName = "7.9"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")

        // OPT-IN community threat sharing endpoint (dev's Telegram).
        // Empty values disable the feature entirely.
        buildConfigField("String", "DEV_TG_BOT_TOKEN", "\"$devTgBotToken\"")
        buildConfigField("String", "DEV_TG_CHAT_ID", "\"$devTgChatId\"")

        // KiberQalqon Cloud — markaziy panel/xarita uchun telemetriya endpointi.
        // Bo'sh bo'lsa — funksiya o'chiq (CloudTelemetry hech narsa yubormaydi).
        buildConfigField("String", "CLOUD_BASE_URL", "\"$cloudBaseUrl\"")
        buildConfigField("String", "CLOUD_DEVICE_SECRET", "\"$cloudDeviceSecret\"")

        // НЕ оставляем ARM64-only — иначе на armeabi-v7a/x86 APK не поставится.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                // v2 + v3 — обязательны для Android 9+, мешают перепаковщикам.
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8 — обфусцирует и сжимает байткод
            isShrinkResources = true     // Удаляет неиспользуемые ресурсы
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            renderscriptOptimLevel = 3

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Применяем release-подпись только если keystore настроен —
            // иначе CI/коллабораторы получат сборку без подписи (лучше так,
            // чем падать при assemble).
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"  // дебажная сборка ставится рядом с релизной
            versionNameSuffix = "-DEBUG"
        }
    }

    // Дополнительно: запрет дебаг-флага во ВСЕХ buildTypes
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/proguard/**",
                "kotlin/**",
                "**.txt"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
