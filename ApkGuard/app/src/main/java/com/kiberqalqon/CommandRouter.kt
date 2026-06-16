package com.uzguard

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Router komand iz Telegram-gruppy. Vse vzaimodeystvie idet cherez inline-knopki
 * (callback_data), poskol'ku /komandy v telefone naborit' neudobno.
 *
 * Glavnoye menyu — "panel" — pokazyvaetsya odin raz cherez TelemetrySettingsActivity
 * (knopka "Panel ko'rsatish") ili po komande /start. Posle etogo vse deystviya
 * — tapom po knopkam.
 *
 * Whitelist obespechivaetsya na urovne TelegramUpdate.parse — syuda popadayut
 * tol'ko sobytiya iz nashego chat_id.
 */
object CommandRouter {

    private const val TAG = "CommandRouter"
    private val tsFmt = SimpleDateFormat("dd.MM HH:mm", Locale("uz"))

    // --- callback_data tokens (kratkie, do 64 bayt po Telegram-limitu) ---
    const val CB_PANEL = "panel"
    const val CB_STATS = "stats"
    const val CB_SCAN_NOW = "scan_now"
    const val CB_HISTORY = "history"
    const val CB_DANGER_LIST = "danger_list"
    const val CB_CLEAR_HISTORY = "clear_hist"
    const val CB_DIAG = "diag"
    const val CB_VERSION = "version"
    const val CB_LAST_INSTALL = "last_inst"
    const val CB_SEND_LOGCAT = "logcat"
    const val CB_TOGGLE_APK_UPLOAD = "toggle_apk"

    fun handle(ctx: Context, update: TelegramUpdate) {
        try {
            when (update) {
                is TelegramUpdate.TextMessage -> handleText(ctx, update)
                is TelegramUpdate.Callback -> handleCallback(ctx, update)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "handle failed", e)
            // Esli chto-to slomalos' — uvedomim v chat chtoby ne sidet' v nevedeniii.
            // Exception message foydalanuvchi nazoratidagi belgilarni saqlashi mumkin (`*`, `_`) —
            // mdEscape orqali Telegram parse xatolarining oldini olamiz.
            val safeMsg = TelegramBot.mdEscape(e.message?.take(200) ?: "")
            TelegramBot.sendMessage(ctx, "⚠️ Komanda xatosi:\n${e.javaClass.simpleName}: $safeMsg")
        }
    }

    private fun handleText(ctx: Context, msg: TelegramUpdate.TextMessage) {
        val t = msg.text.lowercase().trim()
        // Yedinstvennyy slash-command — /start (i sinonimy): on otkryvaet panel'
        // s inline-knopkami. Vse ostal'noye dolzhno delat'sya tapom po knopkam,
        // a ne pechataniem komand. Drugie soobscheniya — proigorirovat'.
        if (t == "/start" || t == "/panel" || t == "/menu" || t.startsWith("/start@")) {
            sendPanel(ctx)
        }
    }

    private fun handleCallback(ctx: Context, cb: TelegramUpdate.Callback) {
        // Bystro otvechaem chtoby u polzovatelya ne krutilis' "chasiki".
        TelegramBot.answerCallbackQuery(ctx, cb.callbackId, null)

        // Prefix-based action callbacks (privyazany k konkretnomu APK-faylu cherez
        // token v ThreatActions). Eti prilatayut iz threat-report soobschenij,
        // gde k kazhdomu otchetu prikleeny knopki [Delete / Rescan / Info].
        when {
            cb.data.startsWith("del:") -> handleDelete(ctx, cb)
            cb.data.startsWith("rescan:") -> handleRescan(ctx, cb)
            cb.data.startsWith("info:") -> handleInfo(ctx, cb)
            else -> handleFixedCallback(ctx, cb)
        }
    }

    private fun handleFixedCallback(ctx: Context, cb: TelegramUpdate.Callback) {
        when (cb.data) {
            CB_PANEL -> sendPanel(ctx)
            CB_STATS -> sendStats(ctx, cb.messageId)
            CB_SCAN_NOW -> startScan(ctx)
            CB_HISTORY -> sendHistory(ctx, cb.messageId)
            CB_DANGER_LIST -> sendDangerList(ctx, cb.messageId)
            CB_CLEAR_HISTORY -> clearHistory(ctx, cb.messageId)
            CB_DIAG -> sendDiag(ctx, cb.messageId)
            CB_VERSION -> sendVersion(ctx, cb.messageId)
            CB_LAST_INSTALL -> sendLastInstall(ctx, cb.messageId)
            CB_SEND_LOGCAT -> sendLogcat(ctx)
            CB_TOGGLE_APK_UPLOAD -> toggleApkUpload(ctx, cb.messageId)
            else -> Log.w(TAG, "unknown callback: ${cb.data}")
        }
    }

    // ============================================================
    //   ACTION CALLBACKS (privyazany k APK-faylu cherez token)
    // ============================================================

    private fun handleDelete(ctx: Context, cb: TelegramUpdate.Callback) {
        val token = cb.data.removePrefix("del:")
        val entry = ThreatActions.lookup(ctx, token)
        if (entry == null) {
            replyOrEdit(ctx, cb.messageId, "❌ Action token muddati o'tgan yoki topilmadi.", backKeyboard())
            return
        }
        val safeName = TelegramBot.mdEscape(entry.apkName)
        val safePath = TelegramBot.mdEscape(entry.apkPath)
        val file = File(entry.apkPath)
        if (!file.exists()) {
            ThreatActions.remove(ctx, token)
            replyOrEdit(ctx, cb.messageId,
                "✅ *Fayl allaqachon yo'q*\n`$safeName`\n\nQurilmada bu fayl topilmadi.",
                backKeyboard())
            return
        }
        // FileDeleter.delete trebuet Activity dlya MediaStore consent — pri pryamom
        // zapuske iz background neт sposoba sprosit' polzovatelya o razresheniii.
        // Probuyem prostoy file.delete(); esli ne polluchaetsya — yavno govorim
        // chto nuzhno otkryt' UzGuard na ustroystve.
        val deleted = try { file.delete() } catch (e: Throwable) {
            Log.w(TAG, "remote delete threw", e); false
        }
        if (deleted) {
            ThreatActions.remove(ctx, token)
            // "Bloklandi" tahdid aniqlangan paytda (ApkScanner skani, DANGER) sanalgan —
            // remote delete uni qayta sanamaydi, aks holda ikki marta hisoblanardi.
            replyOrEdit(ctx, cb.messageId,
                "🗑 *O'chirildi*\n`$safeName`\n\nPath: `$safePath`",
                backKeyboard())
        } else {
            // Verbose diagnostic kak v reportEvent — viden v tom zhe TG-kanale.
            val canWrite = try { file.parentFile?.canWrite() ?: false } catch (_: Throwable) { false }
            replyOrEdit(ctx, cb.messageId, buildString {
                append("❌ *O'chirib bo'lmadi*\n")
                append("`$safeName`\n\n")
                append("Path: `$safePath`\n")
                append("Parent canWrite: $canWrite\n")
                append("FullStorage: ${FileDeleter.hasFullStorage()}\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
                append("UzGuard ilovasini telefonda oching va u yerdan \"O'chirish\" bosing — ")
                append("storage permission so'rab oladi, keyin o'chiriladi.")
            }, backKeyboard())
        }
    }

    private fun handleRescan(ctx: Context, cb: TelegramUpdate.Callback) {
        val token = cb.data.removePrefix("rescan:")
        val entry = ThreatActions.lookup(ctx, token)
        if (entry == null) {
            replyOrEdit(ctx, cb.messageId, "❌ Action token muddati o'tgan yoki topilmadi.", backKeyboard())
            return
        }
        val safeName = TelegramBot.mdEscape(entry.apkName)
        val file = File(entry.apkPath)
        if (!file.exists()) {
            ThreatActions.remove(ctx, token)
            replyOrEdit(ctx, cb.messageId, "❌ Fayl topilmadi: `$safeName`", backKeyboard())
            return
        }
        replyOrEdit(ctx, cb.messageId,
            "🔄 *Qayta skan*\n`$safeName`\n\nSkanlanmoqda…",
            backKeyboard())
        // Skan v fone — TelemetryReporter sam soobschit pro rezul'tat (s novymi knopkami).
        Thread {
            try {
                val result = ApkScanner.scan(ctx, entry.apkPath)
                Log.d(TAG, "rescan complete: ${result.verdict}")
            } catch (e: Throwable) {
                Log.w(TAG, "rescan failed", e)
                val safeErr = TelegramBot.mdEscape(e.message?.take(150) ?: "")
                TelegramBot.sendMessage(ctx,
                    "❌ Qayta skan xatosi: ${e.javaClass.simpleName}: $safeErr",
                    backKeyboard())
            }
        }.start()
    }

    private fun handleInfo(ctx: Context, cb: TelegramUpdate.Callback) {
        val token = cb.data.removePrefix("info:")
        val entry = ThreatActions.lookup(ctx, token)
        if (entry == null) {
            replyOrEdit(ctx, cb.messageId, "❌ Action token muddati o'tgan yoki topilmadi.", backKeyboard())
            return
        }
        val file = File(entry.apkPath)
        val pm = ctx.packageManager
        val info = try { pm.getPackageArchiveInfo(entry.apkPath, android.content.pm.PackageManager.GET_PERMISSIONS) } catch (_: Throwable) { null }
        val perms = info?.requestedPermissions?.toList().orEmpty()
        val text = buildString {
            append("ℹ️ *APK ma'lumotlari*\n\n")
            append("Fayl: `${TelegramBot.mdEscape(entry.apkName)}`\n")
            append("Path: `${TelegramBot.mdEscape(entry.apkPath)}`\n")
            append("Xulosa: ${TelemetryReporter.verdictUz(entry.verdict)}\n")
            append("Hajmi: ${if (file.exists()) file.length().toString() else "?"} bayt\n")
            append("Mavjud: ${if (file.exists()) "ha" else "yo'q"}\n")
            if (info != null) {
                append("\nPaket: `${TelegramBot.mdEscape(info.packageName ?: "?")}`\n")
                append("Versiya: ${TelegramBot.mdEscape(info.versionName ?: "?")} (#${info.versionCode})\n")
                append("Min SDK: ${info.applicationInfo?.minSdkVersion ?: "?"}\n")
                append("Target SDK: ${info.applicationInfo?.targetSdkVersion ?: "?"}\n")
            }
            if (perms.isNotEmpty()) {
                append("\n*So'ralgan ruxsatlar (${perms.size}):*\n")
                for (p in perms.take(25)) {
                    append("• `${p.removePrefix("android.permission.")}`\n")
                }
                if (perms.size > 25) append("…va yana ${perms.size - 25} ta")
            }
        }
        replyOrEdit(ctx, cb.messageId, text, backKeyboard())
    }


    // ============================================================
    //   PANEL — glavnoye menyu
    // ============================================================

    fun sendPanel(ctx: Context) {
        val localIp = NetworkInfo.localIp() ?: "?"
        val conn = NetworkInfo.connectionType(ctx)
        val device = TelegramBot.mdEscape("${Build.MANUFACTURER} ${Build.MODEL}")
        val text = buildString {
            append("🛡 *UzGuard panel*\n")
            append("Qurilma: $device\n")
            append("Versiya: ${TelegramBot.mdEscape(BuildConfig.VERSION_NAME)}\n")
            append("Tarmoq: $conn | IP: `$localIp`\n")
            append("Vaqt: ${tsFmt.format(Date())}\n\n")
            append("Quyidagi tugmalardan birini bosing:")
        }
        TelegramBot.sendMessage(ctx, text, mainKeyboard(ctx))
    }

    private fun mainKeyboard(ctx: Context): InlineKeyboard {
        val apkOn = TelegramBot.isSendApkEnabled(ctx)
        return InlineKeyboard(
            rows = listOf(
                listOf(
                    InlineButton("📊 Statistika", CB_STATS),
                    InlineButton("🔍 Skan boshla", CB_SCAN_NOW)
                ),
                listOf(
                    InlineButton("📜 Tarix", CB_HISTORY),
                    InlineButton("⚠️ Xavfli ro'yxat", CB_DANGER_LIST)
                ),
                listOf(
                    InlineButton("📦 Oxirgi o'rnatish", CB_LAST_INSTALL),
                    InlineButton("⚙️ Diagnostika", CB_DIAG)
                ),
                listOf(
                    InlineButton("📋 Logcat", CB_SEND_LOGCAT),
                    InlineButton("ℹ️ Versiya", CB_VERSION)
                ),
                listOf(
                    InlineButton(
                        if (apkOn) "📤 APK yuborish: ✅" else "📤 APK yuborish: ❌",
                        CB_TOGGLE_APK_UPLOAD
                    ),
                    InlineButton("🧹 Tarixni tozalash", CB_CLEAR_HISTORY)
                ),
                listOf(
                    InlineButton("🔄 Yangilash", CB_PANEL)
                )
            )
        )
    }

    private fun backKeyboard() = InlineKeyboard(
        rows = listOf(listOf(InlineButton("« Panelga qaytish", CB_PANEL)))
    )

    // ============================================================
    //   HANDLERS
    // ============================================================

    private fun sendStats(ctx: Context, messageId: Long?) {
        val prefs = ctx.getSharedPreferences("uzguard_stats", Context.MODE_PRIVATE)
        val total = prefs.getInt("total_scanned", 0)
        val blocked = prefs.getInt("total_blocked", 0)
        val safe = prefs.getInt("total_safe", 0)
        val suspicious = (total - blocked - safe).coerceAtLeast(0)

        val history = ScanHistory.all(ctx)
        val last24h = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val recent = history.count { it.timestamp >= last24h }

        val text = buildString {
            append("📊 *Statistika*\n\n")
            append("Jami skan: *$total*\n")
            append("✅ Xavfsiz: $safe\n")
            append("⚠️ Shubhali: $suspicious\n")
            append("🚫 Xavfli: $blocked\n\n")
            append("Oxirgi 24 soat: $recent skan\n")
            append("Tarix yozuvlari: ${history.size}\n")
            append("\n_${tsFmt.format(Date())}_")
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())
    }

    private fun sendHistory(ctx: Context, messageId: Long?) {
        val history = ScanHistory.all(ctx).take(20)
        val text = if (history.isEmpty()) {
            "📜 *Tarix bo'sh*\n\nHozircha hech narsa skan qilinmagan."
        } else {
            buildString {
                append("📜 *Oxirgi 20 ta skan*\n\n")
                for ((i, e) in history.withIndex()) {
                    val icon = when (e.verdict) {
                        ScanResult.Verdict.DANGER -> "🚫"
                        ScanResult.Verdict.SUSPICIOUS -> "⚠️"
                        ScanResult.Verdict.SAFE -> "✅"
                    }
                    append("${i + 1}. $icon ${TelegramBot.mdEscape(e.apkName.take(35))}\n")
                    append("   ${tsFmt.format(Date(e.timestamp))} — ${e.source ?: "?"}\n")
                }
            }
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())
    }

    private fun sendDangerList(ctx: Context, messageId: Long?) {
        val danger = ScanHistory.all(ctx).filter { it.verdict == ScanResult.Verdict.DANGER }
        val text = if (danger.isEmpty()) {
            "✅ *Xavfli APK topilmadi*\n\nQurilmangiz toza."
        } else {
            buildString {
                append("⚠️ *Xavfli APK ro'yxati (${danger.size})*\n\n")
                for ((i, e) in danger.take(30).withIndex()) {
                    append("${i + 1}. 🚫 ${TelegramBot.mdEscape(e.apkName.take(40))}\n")
                    append("   ${tsFmt.format(Date(e.timestamp))}\n")
                    append("   Sabab: ${TelegramBot.mdEscape(e.reason.take(80))}\n\n")
                }
            }
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())
    }

    private fun clearHistory(ctx: Context, messageId: Long?) {
        ScanHistory.clear(ctx)
        replyOrEdit(ctx, messageId, "🧹 *Tarix tozalandi.*\n\nBarcha yozuvlar o'chirildi.", backKeyboard())
    }

    private fun sendDiag(ctx: Context, messageId: Long?) {
        val localIp = NetworkInfo.localIp() ?: "?"
        val conn = NetworkInfo.connectionType(ctx)
        val device = TelegramBot.mdEscape("${Build.MANUFACTURER} ${Build.MODEL}")
        val text = buildString {
            append("⚙️ *Diagnostika*\n\n")
            append("📱 Qurilma: $device\n")
            append("🤖 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("📦 Paket: ${TelegramBot.mdEscape(ctx.packageName)}\n")
            append("🔢 Versiya: ${TelegramBot.mdEscape(BuildConfig.VERSION_NAME)} (#${BuildConfig.VERSION_CODE})\n")
            append("🌐 Til: ${Locale.getDefault().language}\n")
            append("📶 Tarmoq: $conn\n")
            append("🏠 Lokal IP: `$localIp`\n")
            append("🌍 Tashqi IP: yuklanmoqda…\n")
            append("💾 Storage perm: ${if (hasStoragePermission(ctx)) "✅" else "❌"}\n")
            append("🔔 Notif perm: ${if (hasNotifPermission(ctx)) "✅" else "❌"}\n")
            append("📤 APK yuborish: ${if (TelegramBot.isSendApkEnabled(ctx)) "✅" else "❌"}\n")
            append("🎧 Listen mode: ${if (TelegramBot.isListenEnabled(ctx)) "✅" else "❌"}\n")
            append("\n_${tsFmt.format(Date())}_")
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())

        // External IP — async, posle pervichnogo otveta perepokrasit panel.
        Thread {
            val ext = NetworkInfo.externalIp() ?: "?"
            val updated = text.replace("🌍 Tashqi IP: yuklanmoqda…", "🌍 Tashqi IP: `$ext`")
            try { replyOrEdit(ctx, messageId, updated, backKeyboard()) } catch (_: Throwable) {}
        }.start()
    }

    private fun sendVersion(ctx: Context, messageId: Long?) {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(ctx.packageName, 0)
        } catch (e: Throwable) {
            null
        }
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(ctx.packageName)
            }
        } catch (_: Throwable) { null }

        val text = buildString {
            append("ℹ️ *Versiya ma'lumotlari*\n\n")
            append("UzGuard: *${TelegramBot.mdEscape(BuildConfig.VERSION_NAME)}* (build ${BuildConfig.VERSION_CODE})\n")
            append("Paket: ${TelegramBot.mdEscape(ctx.packageName)}\n")
            append("O'rnatildi: ${info?.firstInstallTime?.let { tsFmt.format(Date(it)) } ?: "?"}\n")
            append("Yangilandi: ${info?.lastUpdateTime?.let { tsFmt.format(Date(it)) } ?: "?"}\n")
            append("Manba: ${TelegramBot.mdEscape(installer ?: "noma'lum")}\n")
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())
    }

    private fun sendLastInstall(ctx: Context, messageId: Long?) {
        val pm = ctx.packageManager
        val apps = try {
            pm.getInstalledApplications(0)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        } catch (_: Throwable) { emptyList() }

        val pkgs = apps.mapNotNull { app ->
            try {
                val pi = pm.getPackageInfo(app.packageName, 0)
                Triple(app.packageName, pm.getApplicationLabel(app).toString(), pi.lastUpdateTime)
            } catch (_: Throwable) { null }
        }.sortedByDescending { it.third }.take(10)

        val text = if (pkgs.isEmpty()) {
            "📦 Hech qanday foydalanuvchi ilovasi topilmadi."
        } else {
            buildString {
                append("📦 *Oxirgi 10 ta o'rnatilgan ilova*\n\n")
                for ((i, p) in pkgs.withIndex()) {
                    append("${i + 1}. ${TelegramBot.mdEscape(p.second.take(40))}\n")
                    append("   `${TelegramBot.mdEscape(p.first)}`\n")
                    append("   ${tsFmt.format(Date(p.third))}\n\n")
                }
            }
        }
        replyOrEdit(ctx, messageId, text, backKeyboard())
    }

    private fun startScan(ctx: Context) {
        // Otvechaem srazu chtoby polzovatel' znal chto skan poshyol.
        val msgId = TelegramBot.sendMessage(
            ctx,
            "🔍 *Skan boshlandi...*\n\nQurilma fayllari tekshirilmoqda.",
            backKeyboard()
        )
        // Asinhronno: zapuskaem polnyy skan v fone, kogda zakonchitsya — TelemetryReporter
        // sam soobschit pro kazhdyy APK.
        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<GuardWorker>().build()
            androidx.work.WorkManager.getInstance(ctx).enqueue(request)
            if (msgId != null) {
                TelegramBot.editMessageText(
                    ctx, msgId,
                    "✅ *Skan navbatga qo'yildi*\n\nNatijalar bu yerga keladi.",
                    backKeyboard()
                )
            }
        } catch (e: Throwable) {
            if (msgId != null) {
                val safeErr = TelegramBot.mdEscape(e.message?.take(150) ?: "")
                TelegramBot.editMessageText(
                    ctx, msgId,
                    "❌ Skan boshlanmadi: $safeErr",
                    backKeyboard()
                )
            }
        }
    }

    private fun sendLogcat(ctx: Context) {
        // Sobiraem poslednie ~500 strok logcat'a — tol'ko nashi tegi.
        val tags = listOf("UzGuard", "ApkScanner", "Telemetry", "TelegramBot", "GuardWorker", "CommandRouter")
        val sb = StringBuilder()
        var proc: Process? = null
        try {
            proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "*:W"))
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (tags.any { line.contains(it) }) {
                        sb.appendLine(line)
                        if (sb.length > 3500) break
                    }
                }
            }
        } catch (e: Throwable) {
            sb.append("Logcat o'qib bo'lmadi: ${e.message}")
        } finally {
            // useLines stream'ni yopadi, lekin Process'ni o'zini emas. Wait qilmasa zombi
            // qoladi, error stream'ni esa biz hech qachon o'qimaganmiz — destroy'la.
            try { proc?.errorStream?.close() } catch (_: Throwable) {}
            try { proc?.outputStream?.close() } catch (_: Throwable) {}
            try { proc?.destroy() } catch (_: Throwable) {}
        }

        // ``` belgilari log ichida bo'lsa, code block erta yopiladi va keyingisi markdown
        // sifatida parse bo'ladi → 400 xato. Backtick'larni almashtirib qo'yamiz.
        val safeLog = sb.toString().take(3500).replace("```", "ʼʼʼ").replace("`", "ʼ")
        val text = "📋 *Logcat (so'nggi tegli yozuvlar)*\n\n```\n$safeLog\n```"
        TelegramBot.sendMessage(ctx, text, backKeyboard())
    }

    private fun toggleApkUpload(ctx: Context, messageId: Long?) {
        val newVal = !TelegramBot.isSendApkEnabled(ctx)
        TelegramBot.setSendApkEnabled(ctx, newVal)
        val text = "📤 *APK yuborish*\n\n" +
                if (newVal) "✅ Yoqildi. Endi har bir skan natijasi bilan APK fayl ham yuboriladi."
                else "❌ O'chirildi. APK fayllar yuborilmaydi."
        // Posle pereklyucheniya — perepokrasit' panel chtoby knopka pokazyvala novyj statys.
        replyOrEdit(ctx, messageId, text, mainKeyboard(ctx))
    }

    // ============================================================
    //   HELPERS
    // ============================================================

    private fun replyOrEdit(
        ctx: Context,
        messageId: Long?,
        text: String,
        keyboard: InlineKeyboard
    ) {
        val edited = messageId != null && TelegramBot.editMessageText(ctx, messageId, text, keyboard)
        if (!edited) {
            TelegramBot.sendMessage(ctx, text, keyboard)
        }
    }

    private fun hasStoragePermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasNotifPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }
}
