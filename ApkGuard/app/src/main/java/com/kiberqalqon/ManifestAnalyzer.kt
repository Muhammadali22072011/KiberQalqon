package com.uzguard

import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import java.util.zip.ZipFile

/**
 * Чтение AndroidManifest.xml сканируемого APK + извлечение НЕ ТОЛЬКО разрешений.
 * Permissions уже разбираются в [ApkScanner] напрямую через [PackageManager];
 * здесь мы добавляем то, что PackageManager не даёт удобно:
 *
 *  • allowBackup / debuggable / cleartextTraffic / usesCleartextTraffic — флаги <application>
 *  • exported компоненты без android:permission — intent-hijack vector
 *  • receivers для SMS_RECEIVED / BOOT_COMPLETED с priority > 100
 *  • Accessibility services (BIND_ACCESSIBILITY_SERVICE) — современный banking-trojan vector
 *  • DeviceAdminReceiver — ransomware almostly always declares one
 *  • <service> с FOREGROUND_SERVICE_TYPE_SPECIAL_USE без объяснения
 *  • Активити-aliases — попытка скрыть launcher
 *
 * Считаем "красные флаги" + "оранжевые флаги" — комбинируется в общий manifest_score.
 */
object ManifestAnalyzer {

    private const val TAG = "ManifestAnalyzer"

    data class Findings(
        val score: Int,
        val redFlags: List<String>,
        val orangeFlags: List<String>,
        val declaresAccessibility: Boolean,
        val declaresDeviceAdmin: Boolean,
        val highPriorityReceivers: List<String>,
        val exportedWithoutPermission: List<String>,
        // #16: notification-listener xizmati (OTP/bildirishnoma o'qish vektori). Default'i
        // bor — eski pozitsion konstruktor chaqiruvlari buzilmaydi.
        val declaresNotificationListener: Boolean = false
    )

    /**
     * Bu analizator talab qiladigan getPackageArchiveInfo flag to'plami. ApkScanner umumiy
     * (union) fetch'da SHULARNI QAMRAB olishi SHART — kamroq flag bilan kelgan PackageInfo'da
     * receivers/services/activities/providers null bo'lib, tahdid signallari JIM yo'qoladi
     * (false-SAFE). const emas (bitwise `or` compile-time const emas), oddiy val.
     */
    val MANIFEST_FLAGS = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA

    /** Главный API (path-based) — сам fetch'ит PackageInfo. Eski chaqiruvlar/testlar buzilmaydi. */
    fun analyze(pm: PackageManager, apkPath: String): Findings {
        val info = try {
            pm.getPackageArchiveInfo(apkPath, MANIFEST_FLAGS)
        } catch (e: Throwable) {
            Log.w(TAG, "getPackageArchiveInfo failed", e)
            null
        }
        return analyze(info, apkPath)
    }

    /**
     * OLDINDAN olingan [PackageInfo] bilan tahlil — ApkScanner umumiy union-flags natijani
     * uzatadi, shunda APK qayta tahlil qilinmaydi (issiqlik kamayadi). MUHIM: [info] kamida
     * [MANIFEST_FLAGS] (yoki superset) bilan olingan bo'lishi SHART. [info] == null bo'lsa —
     * eski yo'l bilan AYNAN bir xil: komponent topilmalari bo'sh/false, faqat readGlobalActions
     * (AXML) baribir ishlaydi.
     */
    fun analyze(info: PackageInfo?, apkPath: String): Findings {
        val red = mutableListOf<String>()
        val orange = mutableListOf<String>()
        var declaresAccessibility = false
        var declaresDeviceAdmin = false
        val highPrio = mutableListOf<String>()
        val exposed = mutableListOf<String>()

        // <application> уровневые флаги — берём напрямую из ApplicationInfo.flags.
        info?.applicationInfo?.let { app ->
            if (app.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0) {
                orange.add("allowBackup=true (ma'lumotlarni adb backup bilan o'g'irlash mumkin)")
            }
            if (app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                red.add("debuggable=true (production ilovada bo'lmasligi kerak)")
            }
            // FLAG_USES_CLEARTEXT_TRAFFIC = 1<<28 — на современных API.
            // Прямой константы нет до API 23, поэтому проверяем через рефлексию-безопасный путь:
            try {
                val cleartextField = android.content.pm.ApplicationInfo::class.java
                    .getDeclaredField("FLAG_USES_CLEARTEXT_TRAFFIC")
                val mask = cleartextField.getInt(null)
                if (app.flags and mask != 0) {
                    orange.add("usesCleartextTraffic=true (HTTP orqali parol uzatishi mumkin)")
                }
            } catch (_: Throwable) { /* старая API — игнорируем */ }
        }

        // Receivers — SMS_RECEIVED / BOOT_COMPLETED / DEVICE_ADMIN_ENABLED.
        // Чтение AXML на уровне отдельных <receiver>'ов без полного парсера невозможно,
        // поэтому делаем ОДИН глобальный сканик манифеста и атрибутируем найденное
        // манифесту в целом, а не каждому receiver-у. Это убирает раздутие score:
        // раньше каждый из N receivers получал тот же red-flag → score × N.
        val globalActions = readGlobalActions(apkPath)
        if (globalActions.any { it in SMS_RECEIVE_ACTIONS }) {
            // Имя первого receiver'а только для подсказки в UI; флаг и score = единичные.
            val firstName = info?.receivers?.firstOrNull()?.name ?: "?"
            red.add("SMS qabul qiluvchi yuqori priority bilan: $firstName")
            highPrio.add(firstName)
        }
        if ("android.intent.action.BOOT_COMPLETED" in globalActions) {
            orange.add("BOOT_COMPLETED qabul qiluvchi (avto-ishga tushish)")
        }
        if ("android.app.action.DEVICE_ADMIN_ENABLED" in globalActions) {
            declaresDeviceAdmin = true
            red.add("DeviceAdmin receiver — ransomware/wiper alomati")
        }

        // Services — ловим Accessibility-сервисы. APK с N accessibility-сервисами
        // раньше получал N red-флагов и score×N — теперь один red-флаг с именем
        // первого, declaresAccessibility выставляем по факту.
        val accessibilityServices = info?.services?.filter { isAccessibilityService(it) }.orEmpty()
        if (accessibilityServices.isNotEmpty()) {
            declaresAccessibility = true
            val firstName = accessibilityServices.first().name ?: "?"
            val suffix = if (accessibilityServices.size > 1) " (+${accessibilityServices.size - 1} ta)" else ""
            red.add("Accessibility xizmati e'lon qilingan: $firstName$suffix (banking trojan vektori)")
        }

        // Notification-listener xizmatlar — bank OTP/bildirishnomalarini o'qish vektori.
        var declaresNotificationListener = false
        val notifListeners = info?.services?.filter { isNotificationListener(it) }.orEmpty()
        if (notifListeners.isNotEmpty()) {
            declaresNotificationListener = true
            val firstName = notifListeners.first().name ?: "?"
            orange.add("Notification listener xizmati e'lon qilingan: $firstName (bildirishnoma/OTP o'qish)")
        }

        // Exported components без permission protection — потенциальные intent-hijack
        info?.activities?.forEach { collectExported(it, exposed) }
        info?.services?.forEach { collectExported(it, exposed) }
        info?.receivers?.forEach { collectExported(it, exposed) }
        info?.providers?.forEach { provider ->
            if (provider.exported && provider.readPermission.isNullOrBlank()
                && provider.writePermission.isNullOrBlank()) {
                exposed.add("provider:${provider.name}")
            }
        }

        if (exposed.size >= 5) {
            orange.add("${exposed.size} ta eksport qilingan komponent himoyasiz (intent-hijack riski)")
        }

        // Score: red flag = 30 ball, orange = 10 ball.
        // 60+ → DANGER, 30+ → SUSPICIOUS uplift.
        val score = red.size * 30 + orange.size * 10

        return Findings(
            score = score,
            redFlags = red,
            orangeFlags = orange,
            declaresAccessibility = declaresAccessibility,
            declaresDeviceAdmin = declaresDeviceAdmin,
            highPriorityReceivers = highPrio,
            exportedWithoutPermission = exposed,
            declaresNotificationListener = declaresNotificationListener
        )
    }

    private val SMS_RECEIVE_ACTIONS = setOf(
        "android.provider.Telephony.SMS_RECEIVED",
        "android.provider.Telephony.SMS_DELIVER",
        "android.provider.Telephony.WAP_PUSH_RECEIVED"
    )

    private fun collectExported(comp: ComponentInfo, dest: MutableList<String>) {
        if (!comp.exported) return
        // Defensive: PackageManager.ComponentInfo нет permission поле напрямую для всех типов.
        val perm: String? = when (comp) {
            is ServiceInfo -> comp.permission
            is android.content.pm.ActivityInfo -> comp.permission
            else -> null
        }
        if (perm.isNullOrBlank()) {
            dest.add(comp.name ?: "<unknown>")
        }
    }

    private fun isAccessibilityService(svc: ServiceInfo): Boolean {
        // android.permission.BIND_ACCESSIBILITY_SERVICE — обязательное permission
        // для зарегистрированного Accessibility-сервиса.
        return svc.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
    }

    private fun isNotificationListener(svc: ServiceInfo): Boolean {
        // BIND_NOTIFICATION_LISTENER_SERVICE — обязателен для NotificationListenerService.
        return svc.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    }

    /**
     * AndroidManifest.xml внутри APK — бинарный (AXML), парсить его без библиотек
     * сложно. PackageManager.getPackageArchiveInfo нам не даёт priority для receivers.
     *
     * Прагматичный путь: ищем в classes.dex упоминания SMS_RECEIVED/BOOT_COMPLETED
     * как маркер ИНТЕНТА (даже если в манифесте priority не виден). Если найдём
     * и компонент при этом receiver — считаем priority=100 для red flag.
     *
     * Для priority — берём heuristic: если упомянуты вместе SMS_RECEIVED и abortBroadcast —
     * это малварь, которая хочет первой получить SMS и отменить дальнейшие receivers.
     */
    /**
     * Глобальный (на уровне всего AndroidManifest.xml) поиск ключевых action-строк.
     * Без AXML-парсера мы не можем привязать action к конкретному <receiver>, но и
     * не должны: для скоринга достаточно факта, что зловред упоминает SMS_RECEIVED
     * или DEVICE_ADMIN_ENABLED где-то в манифесте. Возвращаем уникальный set, чтобы
     * не дублировать находки.
     */
    private fun readGlobalActions(apkPath: String): Set<String> {
        val found = mutableSetOf<String>()
        try {
            ZipFile(apkPath).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptySet()
                zip.getInputStream(manifestEntry).use { input ->
                    val bytes = input.readBytes()
                    val text = String(bytes, Charsets.ISO_8859_1)
                    val candidates = listOf(
                        "android.provider.Telephony.SMS_RECEIVED",
                        "android.provider.Telephony.SMS_DELIVER",
                        "android.provider.Telephony.WAP_PUSH_RECEIVED",
                        "android.intent.action.BOOT_COMPLETED",
                        "android.app.action.DEVICE_ADMIN_ENABLED"
                    )
                    for (action in candidates) {
                        // AXML строки → UTF-16 LE; plain XML (редко) → UTF-8/ISO-8859-1.
                        val utf16 = action.toByteArray(Charsets.UTF_16LE)
                        val containsUtf16 = bytes.indexOfSubarray(utf16) >= 0
                        val containsUtf8 = text.contains(action)
                        if (containsUtf16 || containsUtf8) found += action
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "manifest scan failed", e)
        }
        return found
    }

    private fun ByteArray.indexOfSubarray(needle: ByteArray): Int {
        if (needle.isEmpty() || this.size < needle.size) return -1
        outer@ for (i in 0..(this.size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
