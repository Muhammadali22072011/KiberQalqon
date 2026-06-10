package com.kiberqalqon

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Опциональная отправка minimal threat data в KiberQalqon community DB.
 *
 * STRICT preconditions (если любое НЕ выполнено — НИЧЕГО не отправляем):
 *   1. Config.hasUserConsent — юзер вообще принял ToS+Privacy
 *   2. Config.hasCommunityShareConsent — юзер ОТДЕЛЬНО opt-in на community sharing
 *   3. BuildConfig.DEV_TG_BOT_TOKEN и DEV_TG_CHAT_ID не пустые (билд сконфигурён)
 *   4. Verdict не SAFE (нет смысла шарить безопасные)
 *
 * Что отправляется (точно, без скрытых полей):
 *   - APK SHA-256 hash (24 байта fingerprint)
 *   - Package name
 *   - Verdict (DANGER/SUSPICIOUS)
 *   - Reason (signature matched)
 *   - Device manufacturer + model
 *   - Android version
 *
 * Что НЕ отправляется ни при каких условиях:
 *   - APK файл сам
 *   - IMEI, серийник, MAC, IP, GPS
 *   - Имя/телефон пользователя
 *   - Список других установленных приложений
 *   - История сканирований
 *
 * Throttle 5 секунд между отправками (на случай batch-detect множества APK).
 */
object CommunityReportClient {

    private const val TAG = "CommunityReport"
    private const val MIN_GAP_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)  // sendDocument дольше для больших APK
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private const val MAX_APK_BYTES = 50L * 1024 * 1024  // Telegram bot limit

    @Volatile private var lastSentAt: Long = 0L
    private val sendLock = Any()

    fun reportThreat(ctx: Context, apkPath: String, result: ScanResult) {
        try {
            // Strict preconditions — все 4 должны выполниться.
            if (!Config.hasUserConsent(ctx)) return
            if (!Config.hasCommunityShareConsent(ctx)) return
            if (result.verdict == ScanResult.Verdict.SAFE) return

            val token = Secrets.tgBotToken()
            val chatId = Secrets.tgChatId()
            if (token.isBlank() || chatId.isBlank()) {
                // Build не сконфигурён под community sharing — это норма для форков.
                Log.d(TAG, "skip: build has no DEV_TG_* configured")
                return
            }

            val file = File(apkPath)
            val pkg = inferPackage(ctx, apkPath)
            val hash = sha256OfFile(file) ?: "?"
            val verdictIcon = when (result.verdict) {
                ScanResult.Verdict.DANGER -> "🚫"
                ScanResult.Verdict.SUSPICIOUS -> "⚠️"
                else -> "✓"
            }

            // Регистрируем action token чтобы inline-кнопки могли сослаться на этот файл
            // через короткий callback_data (Telegram лимит 64 байта). Полный путь не влезает.
            val actionToken = ThreatActions.register(
                ctx, apkPath, file.name, result.verdict.name
            )

            // Точный набор полей. Никаких "лишних" — если хочешь добавить,
            // обнови Privacy Policy + получи новое согласие.
            val text = buildString {
                append("$verdictIcon Hamjamiyat tahdid hisoboti\n")
                append("Hash (SHA-256): `$hash`\n")
                append("Paket: `${pkg ?: "?"}`\n")
                append("Xulosa: ${TelemetryReporter.verdictUz(result.verdict.name)}\n")
                append("Sabab: ${result.reason.take(300)}\n")
                if (result.malwareSignatures.isNotEmpty()) {
                    append("Imzolar: ${result.malwareSignatures.take(5).joinToString(", ")}\n")
                }
                append("Qurilma: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            // Inline-кнопки под отчётом — тап в TG → действие на устройстве.
            // Обрабатываются в CommandRouter (префиксы del:/rescan:/info:).
            val keyboardJson = InlineKeyboard(
                rows = listOf(
                    listOf(
                        InlineButton("🗑 O'chirish", "del:$actionToken")
                    ),
                    listOf(
                        InlineButton("🔄 Qayta skan", "rescan:$actionToken"),
                        InlineButton("ℹ️ Batafsil", "info:$actionToken")
                    )
                )
            ).toJson().toString()

            scope.launch {
                sendThrottled(token, chatId, text, keyboardJson)
                // Privacy Policy v2 — после текстового отчёта шлём сам APK файл.
                // Только если файл существует, читабельный и в пределах лимита.
                if (file.exists() && file.canRead() && file.length() in 1..MAX_APK_BYTES) {
                    sendDocument(token, chatId, file,
                        "$verdictIcon ${file.name}\nXulosa: ${TelemetryReporter.verdictUz(result.verdict.name)}\nHash: $hash")
                } else if (file.length() > MAX_APK_BYTES) {
                    Log.d(TAG, "APK too big (${file.length()}b) — skip file upload")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reportThreat failed", e)
        }
    }

    /**
     * Diagnostic event → dev. Используется для отладки на реальных устройствах:
     * шлёт короткое сообщение в DEV-канал с подробностями шага (например,
     * "почему delete не сработал на J4"). Те же opt-in гарантии что и у threat report.
     *
     * Никаких PII не шлём — только имя файла, путь, статус операции и device/Android.
     */
    fun reportEvent(ctx: Context, category: String, lines: Map<String, String>) {
        try {
            if (!Config.hasUserConsent(ctx)) return
            if (!Config.hasCommunityShareConsent(ctx)) return
            val token = Secrets.tgBotToken()
            val chatId = Secrets.tgChatId()
            if (token.isBlank() || chatId.isBlank()) return

            val text = buildString {
                append("🔧 $category\n")
                for ((k, v) in lines) {
                    if (v.isBlank()) continue
                    append("$k: ${v.take(400)}\n")
                }
                append("Qurilma: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("Ilova: ${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})")
            }
            scope.launch { sendThrottled(token, chatId, text) }
        } catch (e: Throwable) {
            Log.w(TAG, "reportEvent failed", e)
        }
    }

    /**
     * Crash log → dev. Вызывается из CrashHandler.
     * Шлёт stacktrace + device/Android/app версии. Без PII.
     * Это эквивалент Firebase Crashlytics / Sentry для этого приложения.
     *
     * DEBUG-сборка: consent gate байпасится — крашы всегда летят в dev TG, чтобы
     * во время разработки не нужно было каждый раз проходить ConsentActivity на
     * новом эмуляторе/устройстве. Privacy policy уже декларирует это поведение
     * для opt-in юзеров; в release-сборке gate остаётся.
     */
    fun reportCrash(ctx: Context, stackTrace: String) {
        try {
            // Production: требуется явное opt-in. Debug: всегда отправляем
            // (юзер билдит проект сам и явно хочет crashlytics-стиль логи).
            if (!BuildConfig.DEBUG) {
                if (!Config.hasUserConsent(ctx)) return
                if (!Config.hasCommunityShareConsent(ctx)) return
            }

            val token = Secrets.tgBotToken()
            val chatId = Secrets.tgChatId()
            if (token.isBlank() || chatId.isBlank()) return

            val text = buildString {
                append("💥 Anor Qalqon ilova yiqildi")
                if (BuildConfig.DEBUG) append("  [DEBUG]")
                append("\n")
                append("Ilova: ${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})\n")
                append("Qurilma: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("\n")
                append(stackTrace.take(3500))
            }
            // На DEBUG отправляем синхронно: процесс умирает прямо после
            // CrashHandler.save(), и фоновая корутина успевает только запустить
            // POST, но не дождаться его. В release остаётся async чтобы не
            // зависнуть UI thread на 10-секундном таймауте.
            if (BuildConfig.DEBUG) {
                sendBlocking(token, chatId, text)
            } else {
                scope.launch { sendThrottled(token, chatId, text) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reportCrash failed", e)
        }
    }

    /**
     * Foydalanuvchi O'ZI "Xatoni yuborish" tugmasini bosgani → dev TG.
     *
     * reportEvent/reportThreat dan farqi: bu ANIQ foydalanuvchi harakati (tugma bosildi),
     * shuning uchun community-share opt-in SHART EMAS — tugmani bosishning o'zi shu bitta
     * yuborishga rozilik. DEV_TG_* baribir sozlangan bo'lishi shart (aks holda yuboradigan
     * joy yo'q → onResult(false)).
     *
     * @param userNote     foydalanuvchi yozgan izoh (ixtiyoriy)
     * @param diagnostics  to'liq diagnostika matni — .txt fayl bo'lib ketadi (kesilmaydi)
     * @param onResult     main thread'da chaqiriladi: true=yuborildi, false=sozlanmagan/xato
     */
    fun reportUserError(
        ctx: Context,
        userNote: String,
        diagnostics: String,
        onResult: (Boolean) -> Unit
    ) {
        val token = Secrets.tgBotToken()
        val chatId = Secrets.tgChatId()
        if (token.isBlank() || chatId.isBlank()) {
            runMain { onResult(false) }
            return
        }
        val appCtx = ctx.applicationContext
        scope.launch {
            var ok = false
            try {
                val summary = buildString {
                    append("🐞 Foydalanuvchi xato hisoboti\n")
                    if (userNote.isNotBlank()) append("Izoh: ${userNote.take(1500)}\n")
                    append("Ilova: ${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})\n")
                    append("Qurilma: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                }
                ok = postMessage(token, chatId, summary)
                // To'liq diagnostika 4096 belgilik Telegram limitiga sig'masligi mumkin,
                // shuning uchun alohida .txt fayl qilib yuboramiz — hech narsa yo'qolmaydi.
                if (diagnostics.isNotBlank()) {
                    val f = File(appCtx.cacheDir, "kq_error_report.txt")
                    try {
                        f.writeText(diagnostics)
                        if (sendFile(token, chatId, f, "Diagnostika", "text/plain")) ok = true
                    } finally {
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "reportUserError failed", e)
            }
            runMain { onResult(ok) }
        }
    }

    /**
     * Отправка краш-репорта пока процесс ещё жив, но вот-вот умрёт.
     *
     * Нельзя просто `client.newCall(...).execute()` — если краш случился на UI
     * thread (типичный сценарий InflateException), то network в той же thread'е
     * кидает NetworkOnMainThreadException и репорт молча теряется.
     *
     * Решение: спавним отдельный Thread для сетевого вызова, потом join'имся к
     * нему с таймаутом 6 секунд. Network гарантированно идёт с background thread,
     * а crashed thread всё равно ждёт результата до того как Android прибьёт процесс.
     */
    private fun sendBlocking(token: String, chatId: String, text: String) {
        val worker = Thread({
            try {
                // POST + JSON body — uzun stacktrace GET querystring'ga sig'maydi
                // (~8KB request-line limiti → 414/400 → krash hisoboti yo'qoladi).
                // Token path'da qoladi, matn body'da. Mirror: postMessage().
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text.take(4096))
                    put("disable_web_page_preview", true)
                }
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Telegram returned ${resp.code} (sync crash)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "sendBlocking worker failed", e)
            }
        }, "kq-crash-report")
        worker.isDaemon = false
        worker.start()
        try {
            worker.join(6_000L)
        } catch (_: InterruptedException) {
            /* let process die */
        }
    }

    private fun sendThrottled(
        token: String,
        chatId: String,
        text: String,
        replyMarkupJson: String? = null
    ) {
        // read-modify-write lastSentAt synchronized blokda — parallel coroutine'lar
        // 5s rate gate'ni birga o'tib ketmasligi uchun. Sleep ham blok ichida.
        synchronized(sendLock) {
            val now = System.currentTimeMillis()
            val gap = now - lastSentAt
            if (gap < MIN_GAP_MS) {
                try { Thread.sleep(MIN_GAP_MS - gap) } catch (_: InterruptedException) { return }
            }
            lastSentAt = System.currentTimeMillis()
        }

        try {
            // POST+JSON (avval GET edi — uzun "text" URL'ni 414/400 ga olib kelib, xabarni jim
            // tashlardi). Token YO'L'da qoladi (invariant #2 — query'da emas), postMessage bilan bir xil.
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("disable_web_page_preview", true)
                if (!replyMarkupJson.isNullOrBlank()) {
                    try { put("reply_markup", JSONObject(replyMarkupJson)) } catch (_: Throwable) {}
                }
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Telegram returned ${resp.code}")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "send failed", e)
        }
    }

    /** Sam APK fayl yuborish — multipart/form-data sendDocument. */
    private fun sendDocument(token: String, chatId: String, file: File, caption: String) {
        sendFile(token, chatId, file, caption, "application/vnd.android.package-archive")
    }

    /** sendDocument har qanday fayl + mime turi uchun. true — muvaffaqiyatli. */
    private fun sendFile(
        token: String,
        chatId: String,
        file: File,
        caption: String,
        mime: String
    ): Boolean {
        return try {
            val mediaType = mime.toMediaTypeOrNull()
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart("document", file.name, file.asRequestBody(mediaType))
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendDocument")
                .post(multipart)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendFile ${resp.code}: ${resp.body?.string()?.take(200)}")
                    false
                } else true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendFile failed", e)
            false
        }
    }

    /**
     * sendMessage POST (JSON) — uzun matn uchun ishonchli (token URL'da emas, path'da;
     * matn JSON body'da, shuning uchun GET URL uzunligi limitiga tushmaydi).
     * true — muvaffaqiyatli yuborildi.
     */
    private fun postMessage(token: String, chatId: String, text: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4096))
                put("disable_web_page_preview", true)
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Log.w(TAG, "postMessage ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Throwable) {
            Log.w(TAG, "postMessage failed", e)
            false
        }
    }

    private fun runMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    /** SHA-256 файла. Streaming — не грузим в память. */
    private fun sha256OfFile(f: File): String? {
        if (!f.exists() || !f.canRead()) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "sha256 failed", e); null
        }
    }

    /** Извлекаем package name из APK без распаковки. */
    private fun inferPackage(ctx: Context, apkPath: String): String? {
        return try {
            val info = ctx.packageManager.getPackageArchiveInfo(apkPath, 0)
            info?.packageName
        } catch (_: Throwable) { null }
    }
}
