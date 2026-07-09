import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest

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

// KiberQalqon Cloud — imzolangan remote-config (verdikt chegaralari) uchun HMAC kaliti.
// Bo'sh bo'lsa client baked defaults'da qoladi (xavfsiz). Vercel ENV CONFIG_SIGNING_SECRET bilan bir xil.
val configSigningSecret: String = localProperties.getProperty("config.signing.secret", "").trim()

// Shield (keystream-XOR) shifrlovchisi — sirlarni BuildConfig'ga OCHIQ emas, hex-shifrda
// yozish uchun. Algoritm com.kiberqalqon.Shield va scripts/shield_encode.py bilan BAYT-MA-BAYT
// mos (kalit = SHA-256("qz7"+"4fx9"+"2k")). ShieldTest bu moslikni ushlab turadi — bu yerdagi
// algoritmni o'zgartirsangiz, Shield.kt + shield_encode.py ham AYNAN o'zgarishi shart.
fun shieldEnc(plain: String): String {
    if (plain.isEmpty()) return ""
    val key = MessageDigest.getInstance("SHA-256")
        .digest("qz74fx92k".toByteArray(Charsets.UTF_8))
    val data = plain.toByteArray(Charsets.UTF_8)
    val ks = ByteArray(data.size)
    val md = MessageDigest.getInstance("SHA-256")
    var off = 0
    var counter = 0
    while (off < data.size) {
        val ctr = byteArrayOf(
            (counter ushr 24).toByte(), (counter ushr 16).toByte(),
            (counter ushr 8).toByte(), counter.toByte()
        )
        md.reset(); md.update(key); md.update(ctr)
        val block = md.digest()
        val take = minOf(block.size, data.size - off)
        System.arraycopy(block, 0, ks, off, take)
        off += take; counter++
    }
    return buildString(data.size * 2) {
        for (i in data.indices) append("%02x".format((data[i].toInt() xor ks[i].toInt()) and 0xFF))
    }
}

android {
    namespace = "com.uzguard"
    compileSdk = 34
    // Native himoya kutubxonasi (libkqguard.so) NDK versiyasi. D: da, C:\Android\ndk\... junction.
    ndkVersion = "26.1.10909125"
    // Уникальное имя выходного APK по времени сборки — чтобы не конфликтовать
    // с залоченным предыдущим файлом (Windows AV держит свежий APK ~15-30 мин).
    setProperty("archivesBaseName", "kiberqalqon-${System.currentTimeMillis()}")
    defaultConfig {
        applicationId = "com.kiberqalqon"
        minSdk = 24
        targetSdk = 34
        versionCode = 84
        versionName = "8.4"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")

        // OPT-IN community threat sharing (dev's Telegram). Sirlar APK'da OCHIQ EMAS —
        // Shield (keystream-XOR) shifrida; runtime'da Secrets.kt ochadi. `strings` ko'rsatmaydi.
        // Bo'sh qiymat → shieldEnc("")="" → Secrets "" qaytaradi → funksiya o'chiq (fork no-op).
        buildConfigField("String", "DEV_TG_BOT_TOKEN_ENC", "\"${shieldEnc(devTgBotToken)}\"")
        buildConfigField("String", "DEV_TG_CHAT_ID_ENC", "\"${shieldEnc(devTgChatId)}\"")

        // KiberQalqon Cloud. CLOUD_BASE_URL — ochiq URL (sir emas), o'zgarmaydi.
        // CLOUD_DEVICE_SECRET — Shield shifrida (Secrets.cloudDeviceSecret() ochadi).
        buildConfigField("String", "CLOUD_BASE_URL", "\"$cloudBaseUrl\"")
        buildConfigField("String", "CLOUD_DEVICE_SECRET_ENC", "\"${shieldEnc(cloudDeviceSecret)}\"")

        // Remote-config HMAC kaliti — Shield shifrida (RemoteConfig.kt imzoni tekshirish uchun ochadi).
        buildConfigField("String", "CONFIG_SIGNING_SECRET_ENC", "\"${shieldEnc(configSigningSecret)}\"")

        // НЕ оставляем ARM64-only — иначе на armeabi-v7a/x86 APK не поставится.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // libkqguard.so build argumentlari. KQ_EXPECTED_SIG — release imzo sertifikat(lar)i
        // SHA-256'i (OCHIQ qiymat — APK'dan baribir hisoblab olsa bo'ladi), nativega
        // build vaqtida beriladi (qo'lda literal emas). SecurityGuard.kt'dagi Kotlin
        // fallback to'plami bilan AYNAN bir xil bo'lishi shart.
        // SD-01: Play App Signing yoqilganda Play Console → App Integrity'dagi
        // "App signing key certificate" SHA-256'ini VERGUL orqali ikkinchi qiymat
        // sifatida shu yerga (va SecurityGuard.kt ro'yxatiga) qo'shish SHART —
        // aks holda Play'dan o'rnatilgan ilova o'zini o'ldiradi (boot-loop).
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DKQ_EXPECTED_SIG=1CB3F378189D6EF38985B3AE234D859E750029AB353246FA496349A8FF14D983"
                )
            }
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
        // БЫСТРАЯ релизная сборка для проверки self-update: та же release-подпись и тот же
        // applicationId (com.kiberqalqon), но БЕЗ R8 — собирается в разы быстрее. Для
        // self-update важны лишь подпись-сертификат + пакет + versionCode; обфускация не
        // нужна. На Play / в прод по-прежнему идёт `release` (с R8).
        create("releasefast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
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
                // Faqat META-INF/*.txt (litsenziyalar) chiqarib tashlanadi. Avval keng `**.txt`
                // edi — kelajakda kerakli .txt resursni jim yo'qotishi mumkin edi (footgun).
                // Eslatma: assets/malicious_hashes.txt — bu Android ASSET, packaging.resources
                // unga umuman tegmaydi (faqat java-resurslarga).
                "META-INF/*.txt"
            )
        }
    }

    testOptions {
        // android.util.Log kabi stub'lar test'da exception emas, default qiymat qaytarsin.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }

    // Native himoya kutubxonasi (libkqguard.so) — CMake orqali quriladi.
    // NDK yo'q hamkor/CI sborkalari qurilishni shu yerda yiqitmasligi uchun gate qo'shildi:
    //   -Pkq.skipNative=true  → CMake umuman chaqirilmaydi (ilova .so'siz ham ishlaydi — Kotlin fallback).
    // Default (egasi sborkasi) — native YOQILGAN, .so o'z joyida qoladi.
    if ((project.findProperty("kq.skipNative") as String?)?.toBoolean() != true) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
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
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Shizuku — единственный способ удалить файл из чужой /Android/data песочницы
    // без root (запускает rm под uid=shell). api = клиент, provider = ContentProvider
    // для приёма биндера от сервиса Shizuku.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
