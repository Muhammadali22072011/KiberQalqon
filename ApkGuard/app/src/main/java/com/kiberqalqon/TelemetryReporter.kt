package com.uzguard

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Отправка ВСЕХ событий приложения в Telegram-группу через Bot API.
 *
 * Базовые события:
 *  - APP_START — приложение запустилось
 *  - SCAN — APK просканирован, с вердиктом
 *  - INSTALL — установлено новое приложение (PackageInstallReceiver)
 *  - DELETE — попытка удаления + результат
 *  - CRASH — crash log
 *  - SHARE — юзер поделился результатом
 *  - ERROR — любая ошибка которая поймана
 *
 * Расширенные события (см. Cat.*):
 *  - Threat: THREAT_FOUND, REALTIME_BLOCK, DOWNLOAD_DETECTED, PERMISSION_ABUSE,
 *            OVERLAY_DETECTED, ACCESSIBILITY_GRANTED, DEVICE_ADMIN_GRANTED
 *  - Lifecycle: PACKAGE_REPLACED, PACKAGE_UNINSTALLED, UNKNOWN_SOURCES
 *  - Health: HEARTBEAT, SERVICE_KILLED, FILEOBSERVER_LOST, PERMISSION_REVOKED,
 *            BATTERY_OPTIMIZATION, SIGNATURE_DB_UPDATE
 *  - User: QUARANTINE_RESTORE, WHITELIST_ADD, SETTINGS_CHANGED, MANUAL_SCAN
 *  - Theft: SIM_CHANGED, BOOT_COMPLETED, AIRPLANE_MODE, WRONG_PIN
 *  - Reports: DAILY_REPORT, WEEKLY_REPORT, STORAGE_WARN
 *
 * Конфигурация хранится в SharedPreferences:
 *  - tg_bot_token: токен бота от @BotFather
 *  - tg_chat_id: ID группы/чата куда слать (с минусом для групп)
 *  - tg_enabled: true/false
 *
 * Throttle 1 сек между сообщениями — Telegram режет ботов с rate > 30/сек.
 */
object TelemetryReporter {

    private const val TAG = "Telemetry"
    private const val PREFS = "uzguard_telemetry"
    private const val KEY_TOKEN = "tg_bot_token"
    private const val KEY_CHAT_ID = "tg_chat_id"
    private const val KEY_ENABLED = "tg_enabled"
    private const val MIN_GAP_MS = 1100L

    /** Категории событий — используем чтобы не плодить magic-строки по коду. */
    object Cat {
        // base
        const val APP_START = "APP_START"
        const val SCAN = "SCAN"
        const val INSTALL = "INSTALL"
        const val DELETE = "DELETE"
        const val CRASH = "CRASH"
        const val SHARE = "SHARE"
        const val ERROR = "ERROR"
        const val TEST = "TEST"
        // threat
        const val THREAT_FOUND = "THREAT_FOUND"
        const val REALTIME_BLOCK = "REALTIME_BLOCK"
        const val DOWNLOAD_DETECTED = "DOWNLOAD_DETECTED"
        const val PERMISSION_ABUSE = "PERMISSION_ABUSE"
        const val OVERLAY_DETECTED = "OVERLAY_DETECTED"
        const val ACCESSIBILITY_GRANTED = "ACCESSIBILITY_GRANTED"
        const val DEVICE_ADMIN_GRANTED = "DEVICE_ADMIN_GRANTED"
        // lifecycle
        const val PACKAGE_REPLACED = "PACKAGE_REPLACED"
        const val PACKAGE_UNINSTALLED = "PACKAGE_UNINSTALLED"
        const val UNKNOWN_SOURCES = "UNKNOWN_SOURCES"
        // self-health
        const val HEARTBEAT = "HEARTBEAT"
        const val SERVICE_KILLED = "SERVICE_KILLED"
        const val FILEOBSERVER_LOST = "FILEOBSERVER_LOST"
        const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
        const val BATTERY_OPTIMIZATION = "BATTERY_OPTIMIZATION"
        const val SIGNATURE_DB_UPDATE = "SIGNATURE_DB_UPDATE"
        // user actions
        const val QUARANTINE_RESTORE = "QUARANTINE_RESTORE"
        const val WHITELIST_ADD = "WHITELIST_ADD"
        const val SETTINGS_CHANGED = "SETTINGS_CHANGED"
        const val MANUAL_SCAN = "MANUAL_SCAN"
        // theft / system signals
        const val SIM_CHANGED = "SIM_CHANGED"
        const val BOOT_COMPLETED = "BOOT_COMPLETED"
        const val AIRPLANE_MODE = "AIRPLANE_MODE"
        const val WRONG_PIN = "WRONG_PIN"
        // reports
        const val DAILY_REPORT = "DAILY_REPORT"
        const val WEEKLY_REPORT = "WEEKLY_REPORT"
        const val STORAGE_WARN = "STORAGE_WARN"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var lastSentAt: Long = 0L
    private val sendLock = Any()

    /** Включить телеметрию с переданными токеном + чатом. */
    fun configure(ctx: Context, token: String, chatId: String, enabled: Boolean = true) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TOKEN, token.trim())
            putString(KEY_CHAT_ID, chatId.trim())
            putBoolean(KEY_ENABLED, enabled)
        }
    }

    fun isConfigured(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_ENABLED, false) &&
                !p.getString(KEY_TOKEN, null).isNullOrBlank() &&
                !p.getString(KEY_CHAT_ID, null).isNullOrBlank()
    }

    /** Главный API — кидаем событие, оно асинхронно улетит в Telegram. */
    fun report(ctx: Context, category: String, message: String) {
        try {
            val app = ctx.applicationContext
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_ENABLED, false)) return
            val token = p.getString(KEY_TOKEN, null)?.trim().orEmpty()
            val chatId = p.getString(KEY_CHAT_ID, null)?.trim().orEmpty()
            if (token.isEmpty() || chatId.isEmpty()) return

            val text = formatMessage(app, category, message)
            scope.launch { send(token, chatId, text) }
        } catch (e: Throwable) {
            // Никогда не валим вызывающий код из-за телеметрии.
            Log.w(TAG, "report failed", e)
        }
    }

    // ===========================================================
    //  HELPERS — короткие обёртки для конкретных типов событий.
    //  Все без исключения вызывают report() и работают только если
    //  телеметрия включена (внутри report() стоит ранний выход).
    // ===========================================================

    fun reportThreat(ctx: Context, pkg: String, label: String, verdict: String, reason: String, hash: String? = null) {
        val body = buildString {
            append("🚫 Tahdid topildi!\n")
            append("📦 $label ($pkg)\n")
            append("Xulosa: ${verdictUz(verdict)}\n")
            append("Sabab: ${reason.take(400)}")
            if (!hash.isNullOrBlank()) append("\nSHA256: ${hash.take(16)}…")
        }
        report(ctx, Cat.THREAT_FOUND, body)
    }

    fun reportRealtimeBlock(ctx: Context, pkg: String, source: String) {
        report(ctx, Cat.REALTIME_BLOCK, "🛡️ Real-time bloklash:\n📦 $pkg\nManba: $source")
    }

    fun reportDownloadDetected(ctx: Context, path: String, sizeBytes: Long) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        report(ctx, Cat.DOWNLOAD_DETECTED, "⬇️ Yangi APK yuklab olindi:\n📦 $name\nHajm: ${humanSize(sizeBytes)}\nYo'l: $path")
    }

    fun reportPermissionAbuse(ctx: Context, pkg: String, label: String, perms: List<String>) {
        val short = perms.map { it.substringAfterLast('.') }.take(8).joinToString(", ")
        report(ctx, Cat.PERMISSION_ABUSE, "⚠️ Xavfli ruxsatlar:\n📦 $label ($pkg)\nRuxsatlar: $short")
    }

    fun reportOverlayDetected(ctx: Context, pkg: String) {
        report(ctx, Cat.OVERLAY_DETECTED, "🪟 Overlay aniqlandi (SYSTEM_ALERT_WINDOW):\n📦 $pkg")
    }

    fun reportAccessibilityGranted(ctx: Context, pkg: String) {
        report(ctx, Cat.ACCESSIBILITY_GRANTED, "♿ Accessibility berildi:\n📦 $pkg\n(Trojanlar shunday boshqaradi telefonni)")
    }

    fun reportDeviceAdminGranted(ctx: Context, pkg: String) {
        report(ctx, Cat.DEVICE_ADMIN_GRANTED, "👑 Device Admin berildi:\n📦 $pkg\n(Bunday ilovani o'chirish qiyin bo'ladi)")
    }

    fun reportPackageReplaced(ctx: Context, pkg: String, label: String, verdict: String) {
        report(ctx, Cat.PACKAGE_REPLACED, "🔁 Ilova yangilandi:\n📦 $label ($pkg)\nXulosa: ${verdictUz(verdict)}")
    }

    fun reportPackageUninstalled(ctx: Context, pkg: String) {
        val warn = if (pkg == ctx.packageName || pkg == "${ctx.packageName}.debug") "  ⚠️ BU UzGuard!" else ""
        report(ctx, Cat.PACKAGE_UNINSTALLED, "🗑️ Ilova o'chirildi:\n📦 $pkg$warn")
    }

    fun reportUnknownSources(ctx: Context, enabled: Boolean) {
        val state = if (enabled) "YOQILDI ⚠️" else "o'chirildi"
        report(ctx, Cat.UNKNOWN_SOURCES, "🔓 Noma'lum manbalardan o'rnatish: $state")
    }

    fun reportHeartbeat(ctx: Context, battery: Int, freeMb: Long, uptimeMin: Long) {
        report(
            ctx, Cat.HEARTBEAT,
            "💓 Tirikman.\n🔋 ${battery}%\n💾 ${freeMb} MB free\n⏱️ uptime ${uptimeMin} min"
        )
    }

    fun reportServiceKilled(ctx: Context, service: String) {
        report(ctx, Cat.SERVICE_KILLED, "💀 Service o'lgan, qayta tirildi:\n$service")
    }

    fun reportFileObserverLost(ctx: Context, path: String) {
        report(ctx, Cat.FILEOBSERVER_LOST, "👁️ FileObserver uzilib qoldi:\n$path")
    }

    fun reportPermissionRevoked(ctx: Context, permission: String) {
        report(ctx, Cat.PERMISSION_REVOKED, "🚪 Ruxsat olib qo'yildi:\n$permission")
    }

    fun reportBatteryOptimization(ctx: Context, whitelisted: Boolean) {
        val s = if (whitelisted) "UzGuard battery optimization'dan chiqarildi ✅" else "UzGuard battery optimization ichida ⚠️"
        report(ctx, Cat.BATTERY_OPTIMIZATION, s)
    }

    fun reportSignatureDbUpdate(ctx: Context, added: Int, total: Int) {
        report(ctx, Cat.SIGNATURE_DB_UPDATE, "📚 Imzo bazasi yangilandi:\n+$added yangi (jami $total)")
    }

    fun reportQuarantineRestore(ctx: Context, apkName: String) {
        report(ctx, Cat.QUARANTINE_RESTORE, "♻️ Karantin'dan tiklandi:\n📦 $apkName\n(Foydalanuvchi o'zi tikladi)")
    }

    fun reportWhitelistAdd(ctx: Context, pkg: String) {
        report(ctx, Cat.WHITELIST_ADD, "⚪ Oq ro'yxatga qo'shildi:\n📦 $pkg")
    }

    fun reportSettingsChanged(ctx: Context, key: String, value: String) {
        report(ctx, Cat.SETTINGS_CHANGED, "⚙️ Sozlama o'zgardi:\n$key → $value")
    }

    fun reportManualScan(ctx: Context, where: String) {
        report(ctx, Cat.MANUAL_SCAN, "▶️ Qo'lda skanlash boshlandi:\n$where")
    }

    fun reportSimChanged(ctx: Context, oldSerial: String?, newSerial: String?) {
        report(
            ctx, Cat.SIM_CHANGED,
            "📡 SIM almashtirildi!\nEski: ${oldSerial?.takeLast(6) ?: "yo'q"}\nYangi: ${newSerial?.takeLast(6) ?: "yo'q"}"
        )
    }

    fun reportBootCompleted(ctx: Context) {
        report(ctx, Cat.BOOT_COMPLETED, "🔄 Telefon qayta yuklandi.")
    }

    fun reportAirplaneMode(ctx: Context, on: Boolean) {
        val s = if (on) "YOQILDI ✈️" else "o'chirildi"
        report(ctx, Cat.AIRPLANE_MODE, "Airplane mode: $s")
    }

    fun reportWrongPin(ctx: Context, attempts: Int) {
        report(ctx, Cat.WRONG_PIN, "🔐 Noto'g'ri PIN urinishi ($attempts marta)")
    }

    fun reportDailyReport(ctx: Context, body: String) {
        report(ctx, Cat.DAILY_REPORT, "📊 Kunlik hisobot:\n$body")
    }

    fun reportWeeklyReport(ctx: Context, body: String) {
        report(ctx, Cat.WEEKLY_REPORT, "📈 Haftalik hisobot:\n$body")
    }

    fun reportStorageWarn(ctx: Context, freeMb: Long) {
        report(ctx, Cat.STORAGE_WARN, "💽 Joy kam qoldi:\n$freeMb MB bo'sh")
    }

    private fun humanSize(b: Long): String {
        if (b <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, u[i])
    }

    private fun formatMessage(ctx: Context, category: String, message: String): String {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        // Telegram MarkdownV2 капризный — простой plain text надёжнее.
        return buildString {
            append("[${labelOf(category)}]\n")
            append(message.take(3500))  // Telegram лимит 4096
            append("\n— $device")
        }
    }

    /** Uzbekcha yorliqlar — Cat.* konstantalari kod tarafida o'zgarmaydi, lekin chatda foydalanuvchi uzbekcha ko'radi. */
    private fun labelOf(category: String): String = when (category) {
        Cat.APP_START -> "ISHGA TUSHDI"
        Cat.SCAN -> "SKANER"
        Cat.INSTALL -> "O'RNATILDI"
        Cat.DELETE -> "O'CHIRILDI"
        Cat.CRASH -> "DASTUR YIQILDI"
        Cat.SHARE -> "ULASHILDI"
        Cat.ERROR -> "XATO"
        Cat.TEST -> "TEST"
        Cat.THREAT_FOUND -> "TAHDID"
        Cat.REALTIME_BLOCK -> "REAL-TIME BLOK"
        Cat.DOWNLOAD_DETECTED -> "YUKLAB OLINDI"
        Cat.PERMISSION_ABUSE -> "RUXSAT SUIISTE'MOLI"
        Cat.OVERLAY_DETECTED -> "OVERLAY"
        Cat.ACCESSIBILITY_GRANTED -> "ACCESSIBILITY BERILDI"
        Cat.DEVICE_ADMIN_GRANTED -> "DEVICE ADMIN BERILDI"
        Cat.PACKAGE_REPLACED -> "ILOVA YANGILANDI"
        Cat.PACKAGE_UNINSTALLED -> "ILOVA O'CHIRILDI"
        Cat.UNKNOWN_SOURCES -> "NOMA'LUM MANBA"
        Cat.HEARTBEAT -> "TIRIK"
        Cat.SERVICE_KILLED -> "XIZMAT O'LDI"
        Cat.FILEOBSERVER_LOST -> "KUZATUVCHI UZILDI"
        Cat.PERMISSION_REVOKED -> "RUXSAT BEKOR"
        Cat.BATTERY_OPTIMIZATION -> "BATAREYA OPT."
        Cat.SIGNATURE_DB_UPDATE -> "BAZA YANGILANDI"
        Cat.QUARANTINE_RESTORE -> "KARANTINDAN TIKLANDI"
        Cat.WHITELIST_ADD -> "OQ RO'YXAT"
        Cat.SETTINGS_CHANGED -> "SOZLAMA O'ZGARDI"
        Cat.MANUAL_SCAN -> "QO'LDA SKAN"
        Cat.SIM_CHANGED -> "SIM ALMASHTIRILDI"
        Cat.BOOT_COMPLETED -> "QAYTA YUKLANDI"
        Cat.AIRPLANE_MODE -> "AIRPLANE REJIMI"
        Cat.WRONG_PIN -> "NOTO'G'RI PIN"
        Cat.DAILY_REPORT -> "KUNLIK HISOBOT"
        Cat.WEEKLY_REPORT -> "HAFTALIK HISOBOT"
        Cat.STORAGE_WARN -> "XOTIRA OZ"
        else -> category
    }

    /** Verdict enum nomini Telegramda foydalanuvchi tushunadigan o'zbekchaga aylantiradi. */
    fun verdictUz(verdict: String): String = when (verdict.uppercase()) {
        "SAFE" -> "Xavfsiz ✅"
        "SUSPICIOUS" -> "Shubhali ⚠️"
        "DANGER" -> "Xavfli 🚫"
        else -> verdict
    }

    /**
     * Отправка с throttle.
     *
     * Throttle: read-modify-write `lastSentAt` synchronized blokda — parallel
     * report() chaqiruvlari throttle'ni o'tib ketmasligi uchun. Sleep ham shu
     * blok ichida — keyingi yuboruvchi avvalgisi tugagunicha kutadi.
     *
     * Yuborish: POST + JSON body — avvalgi GET querystring varianti uzun emoji'li
     * matnda URL limitidan oshib ketardi va token URL'da ko'rinardi. POST'da
     * token faqat path'da (HTTPS encrypts), text body'da xavfsiz.
     */
    private fun send(token: String, chatId: String, text: String) {
        synchronized(sendLock) {
            val now = System.currentTimeMillis()
            val gap = now - lastSentAt
            if (gap < MIN_GAP_MS) {
                try { Thread.sleep(MIN_GAP_MS - gap) } catch (_: InterruptedException) { return }
            }
            lastSentAt = System.currentTimeMillis()
        }

        try {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4000))
                put("disable_web_page_preview", true)
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Telegram returned ${resp.code}: ${resp.message}")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "send failed", e)
        }
    }
}
