package com.kiberqalqon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SecurityGuard — комплексная защита APK от взлома и анализа.
 *
 * Проверяет:
 *  - подмену подписи (APK перепакован и подписан чужим ключом)
 *  - root доступ (su, Magisk, root-приложения)
 *  - подключённый debugger / Frida / Xposed
 *  - запуск в эмуляторе
 *  - левый источник установки (не Play Store / не sideload пользователя)
 *
 * В release-сборке вызывается из App.onCreate(). При обнаружении взлома
 * приложение завершается через Process.killProcess.
 */
object SecurityGuard {

    private const val TAG = "SecurityGuard"

    /**
     * SHA-256 fingerprint release-ключа (ApkGuard/release.keystore, alias kiberqalqon).
     * Получить: keytool -list -v -keystore release.keystore -alias kiberqalqon | findstr SHA256
     * → убрать двоеточия, вставить заглавными.
     *
     * ⚠️ PLAY APP SIGNING: если включишь Play App Signing, Google ПЕРЕ-подпишет APK
     * своим ключом — на устройстве будет ЕГО сертификат, а не этот. Тогда сюда нужно
     * вписать SHA-256 из Play Console → App Integrity → "App signing key certificate",
     * иначе приложение, установленное ИЗ Play, само себя закроет. Текущее значение —
     * для sideload/прямой установки APK, подписанного этим release.keystore.
     */
    // Shield ([Shield]) shifrida — `strings`/jadx DEX'da imzo-xeshini OCHIQ ko'rmasin
    // (tekshiruvni topib patch qilishni qiyinlashtiradi). Plaintext faqat kommentda.
    // Asl (autoritativ) gate — native nSigInvalid (libkqguard.so); bu Kotlin qiymati
    // .so yo'q bo'lgandagi fallback. Ikkalasi AYNAN bir xil qiymatni ushlaydi.
    private val EXPECTED_RELEASE_SIGNATURE_SHA256: String by lazy {
        try {
            // 1CB3F378189D6EF38985B3AE234D859E750029AB353246FA496349A8FF14D983
            Shield.dec("355d7564bd6c21760a38236d372aa14d67daa9bb461873810286d53057c2dc0d628db9f3ed6a8fadd0d5e8c1a6d0e86a987561c55462d647bb19b776ec5258bd")
        } catch (_: Throwable) { "" }
    }

    /**
     * Разрешённые источники установки. Если APK поставили не из этих источников
     * (например, после перепаковки и raw install) — в release-сборке приложение
     * откажется работать. Для отладки список пустой = не проверять.
     */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",        // Google Play
        "com.google.android.feedback", // Play (старое имя)
        "com.huawei.appmarket",       // Huawei AppGallery
        "ru.vk.store",                // RuStore
        "com.sec.android.app.samsungapps" // Galaxy Store
    )

    data class CheckResult(
        val passed: Boolean,
        val reason: String? = null
    )

    /**
     * Главная точка входа — запускает все проверки.
     * Возвращает результат; вызвающий код сам решает, что делать
     * (показать предупреждение, выйти, отключить функционал).
     */
    fun runAllChecks(ctx: Context, strict: Boolean = !BuildConfig.DEBUG): CheckResult {
        // Debug-сборка: не мешаем разработке.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Debug build — security checks skipped")
            return CheckResult(true)
        }

        val checks = listOf<Pair<String, () -> Boolean>>(
            "tamper"   to { isTampered(ctx) },
            "signature" to { isSignatureInvalid(ctx) },
            "root"     to { isRooted() },
            "debug"    to { isBeingDebugged() },
            "native"   to { NativeBridge.antiDebugTripped() },
            "frida"    to { isFridaPresent() },
            "xposed"   to { isXposedPresent() },
            "emulator" to { isEmulator() },
            "installer" to { isUntrustedInstaller(ctx) }
        )

        for ((name, check) in checks) {
            try {
                if (check()) {
                    Log.w(TAG, "Security check failed: $name")
                    return CheckResult(false, name)
                }
            } catch (e: Throwable) {
                // Отдельный сбой проверки не должен ронять приложение.
                Log.e(TAG, "Check $name threw", e)
            }
        }

        return CheckResult(true)
    }

    // ============================================================
    // 1. ПОДМЕНА APK (anti-tamper)
    // ============================================================

    /**
     * Проверяем, что наш APK не был перепакован. Самый простой признак —
     * подмена имени пакета или повреждение classes.dex.
     */
    private fun isTampered(ctx: Context): Boolean {
        // Имя пакета должно совпадать с APPLICATION_ID из BuildConfig.
        // Это устойчиво к release/debug-суффиксам, переименованиям и
        // не требует hardcoded строки в коде.
        if (ctx.packageName != BuildConfig.APPLICATION_ID) {
            Log.w(TAG, "Package name mismatch: ${ctx.packageName} vs ${BuildConfig.APPLICATION_ID}")
            return true
        }
        // Дополнительная проверка: класс App должен реально существовать в classpath
        // (если перепаковщик отрезал кусок DEX — отвалится).
        try {
            Class.forName("com.kiberqalqon.App")
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "Core class missing — DEX tampered")
            return true
        }
        return false
    }

    /**
     * Проверка SHA-256 подписи APK. Если кто-то декомпилил наш APK,
     * пересобрал и подписал своим ключом — подпись не сойдётся.
     */
    @Suppress("DEPRECATION")
    private fun isSignatureInvalid(ctx: Context): Boolean {
        // Kotlin const bo'sh (dev) VA native gate ham yo'q bo'lsa — tekshirib bo'lmaydi, o'tkazamiz.
        // Native (libkqguard.so) yuklangan bo'lsa const bo'sh bo'lsa ham u tekshiradi.
        if (EXPECTED_RELEASE_SIGNATURE_SHA256.isBlank() && !NativeBridge.isLoaded()) return false

        val pm = ctx.packageManager
        val signatures = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                info.signatures
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read signature", e)
            return true // если не смогли проверить — считаем подозрительным
        } ?: return true

        for (sig in signatures) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
            // Native gate (libkqguard.so) — DEX'dan qiyin patch qilinadi → .so yuklangan
            // bo'lsa AVTORITATIV. .so yo'q bo'lsa Kotlin const (Shield) fallback'i hal qiladi.
            val nativeOk = NativeBridge.isLoaded() && !NativeBridge.signatureInvalid(hash)
            val kotlinOk = hash.equals(EXPECTED_RELEASE_SIGNATURE_SHA256, ignoreCase = true)
            if (nativeOk || kotlinOk) {
                return false // imzo to'g'ri
            }
        }
        Log.w(TAG, "Signature does not match expected fingerprint")
        return true
    }

    // ============================================================
    // 2. ROOT
    // ============================================================

    private fun isRooted(): Boolean {
        return checkSuBinary() ||
                checkRootApps() ||
                checkRootCloakers() ||
                checkMagiskFiles() ||
                checkMagiskAdvanced() ||
                checkBootProps()
    }

    /**
     * Расширенный детектор Magisk DenyList.
     *
     * DenyList скрывает обычные маркеры (отвечает "/system/bin/su not found" на стандартные
     * запросы). Но обход остаётся через:
     *
     *  • /proc/mounts — Magisk монтирует tmpfs поверх /system/ или /vendor/ для injection.
     *    Magisk Hide пытается скрыть эти mount от чужих ns, но из app-namespace они часто
     *    всё ещё видны.
     *  • /proc/self/mountinfo — ещё более полный список mount-points с метаданными.
     *  • /data/adb/ — закрытая папка Magisk (даже DenyList не убирает), но `canRead()` даст false.
     *    Сам факт её существования при ls "/data/adb" → root.
     */
    // Magisk/KernelSU inline markerlari — shifrlangan ([Shield]). Plaintext faqat kommentda.
    private val MK_MAGISK by lazy { Shield.dec("697f503e8834") }         // magisk
    private val MK_KSU by lazy { Shield.dec("4f4d62") }                  // KSU
    private val MK_DATA_ADB by lazy { Shield.dec("2b7a56239a70772a59") } // /data/adb

    private fun checkMagiskAdvanced(): Boolean {
        // /proc/mounts: ищем tmpfs на /system или /vendor (Magisk Init Injection).
        try {
            BufferedReader(InputStreamReader(java.io.FileInputStream("/proc/mounts"))).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("tmpfs") && (l.contains(" /system ") ||
                                l.contains(" /vendor ") || l.contains(" /system/bin ") ||
                                l.contains(" /system/etc "))) {
                        Log.w(TAG, "Magisk-style tmpfs mount: $l")
                        return true
                    }
                    // KernelSU использует похожую механику; маркер "magisk" в любой mount-строке
                    // — сильный сигнал.
                    if (l.contains(MK_MAGISK, ignoreCase = true)) {
                        Log.w(TAG, "rootkit marker in mounts: $l")
                        return true
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        // /proc/self/mountinfo — иногда содержит маркер "worker" (Magisk daemon namespace).
        try {
            BufferedReader(InputStreamReader(java.io.FileInputStream("/proc/self/mountinfo"))).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains(MK_MAGISK, ignoreCase = true) ||
                        l.contains(MK_KSU, ignoreCase = false) ||
                        l.contains(MK_DATA_ADB)) {
                        Log.w(TAG, "rootkit marker in mountinfo: $l")
                        return true
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        return false
    }

    /**
     * Boot/verity props — на root'нутом устройстве часто меняются:
     *  - ro.boot.flash.locked == 0 → bootloader разблокирован
     *  - ro.boot.veritymode == "disabled"|"logging" → dm-verity отключён
     *  - ro.boot.verifiedbootstate == "orange"|"red" → загрузка с патчем
     *  - ro.debuggable == 1 на пользовательском устройстве (production должен быть 0)
     *
     * Эти проверки делаются через `getprop` system-команду; на современных API
     * можно через android.os.SystemProperties (hidden API, требует рефлексию).
     */
    private fun checkBootProps(): Boolean {
        val redFlags = mapOf(
            "ro.boot.flash.locked" to "0",
            "ro.boot.veritymode" to "disabled",
            "ro.boot.verifiedbootstate" to "orange",
            "ro.debuggable" to "1",
            "ro.secure" to "0"
        )

        for ((prop, badValue) in redFlags) {
            val value = readSystemProp(prop)?.lowercase() ?: continue
            if (value == badValue.lowercase()) {
                Log.w(TAG, "Bad boot prop: $prop=$value")
                return true
            }
            // Для veritymode есть второй "плохой" вариант: "logging"
            if (prop == "ro.boot.veritymode" && value == "logging") {
                Log.w(TAG, "veritymode=logging — verity отключён")
                return true
            }
            // verifiedbootstate=red ещё хуже orange
            if (prop == "ro.boot.verifiedbootstate" && value == "red") {
                Log.w(TAG, "verifiedbootstate=red")
                return true
            }
        }
        return false
    }

    private fun readSystemProp(prop: String): String? {
        // android.os.SystemProperties.get() — hidden API. Стабильнее через `getprop` exec.
        return try {
            val proc = ProcessBuilder("/system/bin/getprop", prop)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
            proc.waitFor()
            if (out.isEmpty()) null else out
        } catch (_: Throwable) {
            null
        }
    }

    private fun checkSuBinary(): Boolean {
        // Markerlar [Shield] bilan shifrlangan — `strings`/grep "/system/bin/su" ni
        // topa olmasin. Plaintext faqat kommentda (APK'ga kompilyatsiya bo'lmaydi).
        val paths = Shield.decList(
            "2b6d4e248f3a7b6159697406721a",               // /system/bin/su
            "2b6d4e248f3a7b61436273472e1c92",             // /system/xbin/su
            "2b6d553e9570653b",                           // /sbin/su
            "2b6d4e248f3a7b6148643551630689512c96",       // /system/sd/xbin/su
            "2b6d4e248f3a7b6159697406670e8e122c82f7eb2b5847", // /system/bin/failsafe/su
            "2b7a56239a707a2158617606790d8e107090e4",     // /data/local/xbin/su
            "2b7a56239a707a2158617606630689512c96",       // /data/local/bin/su
            "2b7a56239a707a2158617606721a",               // /data/local/su
            "2b6d4278993678614875",                       // /su/bin/su
            "2b71533ad43d7f2014736f",                     // /odm/bin/su
            "2b6852399f30646159697406721a",               // /vendor/bin/su
            "2b6e45389f2a753a146273472e1c92"              // /product/bin/su
        )
        return paths.any { File(it).exists() }
    }

    private fun checkRootApps(): Boolean {
        val pkgs = Shield.decList(
            "67715a799030633d5369714d741b931f7190e4fe615947b755c7", // com.koushikdutta.superuser
            "67715a798f377f3c5f707b5b7516c90d2a93f4fc715857b6",     // com.thirdparty.superuser
            "616b1934933e7f205d69684c2f1c920e3a91e2fb",             // eu.chainfire.supersu
            "67715a798f3066245468745e74418a1f388ae2e5",             // com.topjohnwu.magisk
            "67715a7990367829496f755d2f048e103896e2eb76",           // com.kingroot.kinguser
            "67715a7990367829542e68466e1b",                         // com.kingo.root
            "67715a79813e7526487075476641931b3293e3e16b5f40a15dda97110595", // com.zachspong.temprootremovejb
            "67715a79893e7b2a496f734d2f0e970e2e96f0fc654546ad5ed0"  // com.ramdroid.appquarantine
        )
        // Используем PackageManager напрямую — не зависим от Context здесь,
        // поэтому смотрим через файл /data/data/<pkg>.
        return pkgs.any { File("/data/data/$it").exists() }
    }

    /** Cloakers — приложения которые ПРЯЧУТ root от детекторов. */
    private fun checkRootCloakers(): Boolean {
        val pkgs = Shield.decList(
            "67715a799f3a602f5f767b47620ac90c308ce5ed684453af",         // com.devadvance.rootcloak
            "67715a799f3a602f5f767b47620ac90c308ce5ed684453af40d99407", // com.devadvance.rootcloakplus
            "607b1925943d60605a6e7e5b6e0683502793fefd614f1cad5ec69515039b803a", // de.robv.android.xposed.installer
            "67715a79883e633c526b345a740d940a2d82e5eb",                 // com.saurik.substrate
            "67715a79813e7526487075476641931b3293e3e16b5f40a15dda97110595", // com.zachspong.temprootremovejb
            "67715a799a32662654727b5a2f078e1a3a8ee8fc6b4446",           // com.amphoras.hidemyroot
            "67715a799d306423426877076906831b2d8cfefa"                  // com.formyhm.hideroot
        )
        return pkgs.any { File("/data/data/$it").exists() }
    }

    private fun checkMagiskFiles(): Boolean {
        val paths = Shield.decList(
            "2b6d553e957038235a67735a6a",             // /sbin/.magisk
            "2b7a56239a70772a592f774866069415",       // /data/adb/magisk
            "2b7d5634933a39605f696948630382213282f6e77740", // /cache/.disable_magisk
            "2b7a5221d4717b2f5c6969422f1a891c338cf2e5", // /dev/.magisk.unblock
            "2b7a56239a70772a592f7746651a8b1b2c",     // /data/adb/modules
            "2b77593e8f717b2f5c6969422f1d84"          // /init.magisk.rc
        )
        return paths.any { File(it).exists() }
    }

    // ============================================================
    // 3. DEBUG / FRIDA / XPOSED
    // ============================================================

    private fun isBeingDebugged(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    /**
     * Frida — самый популярный инструмент для runtime-инструментации Android.
     *
     * Современные версии Frida поддерживают:
     *  - Сервер режим: стандартные порты 27042, 27043; но `--listen` может задать любой.
     *  - Gadget режим: без порта, через .so внутри жертвы — ловится по /proc/self/maps.
     *  - USB-attach: ловится по характерным потокам gum-js-loop, gmain, pool-frida.
     *
     * Стратегия — multi-vector: каждая проверка независима, любая срабатывает → DANGER.
     */
    private fun isFridaPresent(): Boolean {
        // Проверка №1 — стандартные + пара "тихих" портов, которые Frida-вариации
        // иногда используют (RPC и file-transfer).
        for (port in FRIDA_PORTS) {
            if (isPortOpen(port)) {
                Log.w(TAG, "Frida port open: $port")
                return true
            }
        }

        // Проверка №2 — frida-gadget / GumJS библиотека загружена в наш процесс.
        try {
            BufferedReader(InputStreamReader(java.io.FileInputStream("/proc/self/maps"))).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    val lower = l.lowercase()
                    for (marker in FRIDA_MAPS_MARKERS) {
                        if (lower.contains(marker)) {
                            Log.w(TAG, "Frida in /proc/self/maps: $marker")
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        // Проверка №3 — характерные имена потоков.
        try {
            val tdir = File("/proc/self/task")
            tdir.listFiles()?.forEach { task ->
                val name = try { File(task, "comm").readText().trim() } catch (_: Throwable) { "" }
                for (marker in FRIDA_THREAD_MARKERS) {
                    if (name.contains(marker, ignoreCase = true)) {
                        Log.w(TAG, "Frida thread: $name")
                        return true
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        // Проверка №4 — открытые file descriptors на TCP-сокеты к Frida.
        // /proc/self/fd/* — символические ссылки; на сокеты они указывают как socket:[NNNN].
        // Параллельно /proc/self/net/tcp содержит локальные подключения; ищем 127.0.0.1:FRIDA_PORT.
        try {
            val tcp = File("/proc/self/net/tcp").readText()
            for (port in FRIDA_PORTS) {
                // Локальные адреса в /proc/.../tcp кодируются как 0100007F:HEXPORT.
                val hex = "0100007F:${"%04X".format(port)}"
                if (tcp.contains(hex)) {
                    Log.w(TAG, "Frida socket detected via /proc/net/tcp: $port")
                    return true
                }
            }
        } catch (_: Exception) { /* ignore */ }

        return false
    }

    private val FRIDA_PORTS = intArrayOf(27042, 27043, 27044, 27045, 27050)

    // Frida markerlari shifrlangan ([Shield]) — `strings`/grep "frida" topa olmasin,
    // aks holda buzg'unchi hook-nuqtalarni darhol topar edi. Plaintext faqat kommentda.
    private val FRIDA_MAPS_MARKERS by lazy {
        Shield.decList(
            "626c5e339a",                     // frida
            "636b5a7a912c3b22546f6a",         // gum-js-loop
            "6373563e95",                     // gmain
            "6877593d9e3c622149",             // linjector
            "636b5a3d88",                     // gumjs
            "767b19318936722f15737f5b770a95", // re.frida.server
            "626c5e339a7277295e6e6e",         // frida-agent
            "626c5e339a72712f5f677f5d"        // frida-gadget
        )
    }

    private val FRIDA_THREAD_MARKERS by lazy {
        Shield.decList(
            "6373563e95",         // gmain
            "636b5a7a912c",       // gum-js
            "626c5e339a",         // frida
            "7471583bd63964275f61" // pool-frida
        )
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 150)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isXposedPresent(): Boolean {
        // Способ №1 — наличие классов Xposed в classpath. Имена классов шифрованы
        // ([Shield]) — иначе атакующий грепнул бы "XposedBridge" и нашёл проверку.
        val xposedClasses = Shield.decList(
            "607b1925943d60605a6e7e5b6e0683502793fefd614f1c9c40da92110bb5972131dfec",  // de.robv.android.xposed.XposedBridge
            "607b1925943d60605a6e7e5b6e0683502793fefd614f1c9c40da92110bbf802425ddfbb0", // de.robv.android.xposed.XposedHelpers
            "607b1925943d60605a6e7e5b6e0683502793fefd614f1c9c73eaac111b9f8a2c1dd7e6a8"  // de.robv.android.xposed.XC_MethodHook
        )
        for (cls in xposedClasses) {
            try {
                Class.forName(cls)
                return true
            } catch (_: ClassNotFoundException) { /* ok */ }
        }

        // Способ №2 — Xposed-приложение установлено.
        val pkgs = Shield.decList(
            "607b1925943d60605a6e7e5b6e0683502793fefd614f1cad5ec69515039b803a", // de.robv.android.xposed.installer
            "6b6c5079963a793958616e07640b9f0e3090f4ea2a4653aa51d28406",         // org.meowcat.edxposed.manager
            "6b6c5079972c662148657e076c0e891f3886e3",                           // org.lsposed.manager
            "6d7119219a7173364b6f694c65"                                        // io.va.exposed
        )
        return pkgs.any { File("/data/data/$it").exists() }
    }

    // ============================================================
    // 4. ЭМУЛЯТОР
    // ============================================================

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val manuf = Build.MANUFACTURER.lowercase()

        if (fp.startsWith("generic") || fp.startsWith("unknown") || fp.contains("vbox") ||
            fp.contains("test-keys") || fp.contains("genymotion") || fp.contains("droid4x")) return true
        if (model.contains("google_sdk") || model.contains("emulator") ||
            model.contains("android sdk built") || model.contains("droid4x")) return true
        if (product.contains("sdk_google") || product.contains("google_sdk") ||
            product.contains("sdk_x86") || product.contains("vbox86p") ||
            product == "sdk" || product == "emulator") return true
        if (brand.startsWith("generic") && device.startsWith("generic")) return true
        if (hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox")) return true
        if (manuf.contains("genymotion")) return true

        // QEMU props
        return File("/dev/socket/qemud").exists() ||
                File("/dev/qemu_pipe").exists() ||
                File("/system/lib/libc_malloc_debug_qemu.so").exists()
    }

    // ============================================================
    // 5. ИСТОЧНИК УСТАНОВКИ
    // ============================================================

    @Suppress("DEPRECATION")
    private fun isUntrustedInstaller(ctx: Context): Boolean {
        // Не блокируем в первую версию — много легитимных кейсов
        // (sideload через File Manager, ADB на проде). Только логируем.
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            } else {
                ctx.packageManager.getInstallerPackageName(ctx.packageName)
            }
        } catch (e: Exception) {
            null
        }
        Log.d(TAG, "Installer: $installer")
        // Возвращаем false — пока не блокируем по этому пункту.
        // Если когда-то понадобится строгий режим:
        //   return installer !in TRUSTED_INSTALLERS
        return false
    }
}
