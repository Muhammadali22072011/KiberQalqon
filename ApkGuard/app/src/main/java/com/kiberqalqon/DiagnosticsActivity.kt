package com.kiberqalqon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import java.io.File

/**
 * Диагностический экран — собирает в один большой текст:
 *  - Версию Android и устройство
 *  - Статус ВСЕХ разрешений KiberQalqon
 *  - Последний крэш (если есть)
 *  - Кнопку "Скопировать всё" → юзер шлёт скрин/текст разработчику.
 *
 * Никаких хитрых layout-ов — голый код, чтобы дёргалось даже на самом сломанном телефоне.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "🩺 DIAGNOSTIKA"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(title)

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            addView(output)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val btnCopy = Button(this).apply {
            text = "📋 Nusxa olish"
            setOnClickListener { copyToClipboard() }
        }
        root.addView(btnCopy)

        val btnShare = Button(this).apply {
            text = "✉️ Yuborish"
            setOnClickListener { shareDiagnostics() }
        }
        root.addView(btnShare)

        val btnSendDev = Button(this).apply {
            text = "📨 Dasturchiga yuborish (Telegram)"
            setOnClickListener { showSendToDevDialog() }
        }
        root.addView(btnSendDev)

        val btnRefresh = Button(this).apply {
            text = "🔄 Yangilash"
            setOnClickListener { refresh() }
        }
        root.addView(btnRefresh)

        val btnTelemetry = Button(this).apply {
            text = "🤖 Telegram telemetriya"
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(this@DiagnosticsActivity, TelemetrySettingsActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(this@DiagnosticsActivity, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnTelemetry)

        // 🧪 Test scanner — генерирует синтетические "вирусы" и прогоняет через
        // наш ApkScanner. ТОЛЬКО в debug: TestVirusGenerator встраивает реальные IOC
        // (elrxzx.com, ydbllnjd.com, frida-server…) как тестовые образцы. В release
        // это — единственная ссылка на класс, поэтому R8 вырежет TestVirusGenerator
        // целиком вместе с его строками (чтобы `strings` их не показал в проде).
        if (BuildConfig.DEBUG) {
            val btnTestScanner = Button(this).apply {
                text = "🧪 Skaner sinovi (test virus)"
                setOnClickListener {
                    output.text = "Tekshiruvchi sinov ishlamoqda…\n(6 ta sintetik APK yaratiladi va skanerga uzatiladi)"
                    Thread {
                        val report = try {
                            TestVirusGenerator.runAndReport(this@DiagnosticsActivity)
                        } catch (e: Throwable) {
                            "❌ Test crashed: ${e.javaClass.simpleName}: ${e.message}"
                        }
                        runOnUiThread { output.text = report }
                    }.start()
                }
            }
            root.addView(btnTestScanner)
        }

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        output.text = buildDiagnostics()
    }

    private fun buildDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== KiberQalqon DIAGNOSTIKA ===")
        sb.appendLine("Vaqt: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine("[Qurilma]")
        sb.appendLine("  Brand:        ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("  Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  Build:        ${Build.DISPLAY}")
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("  KiberQalqon:     ${info.versionName} (${info.versionCode})")
        } catch (_: Exception) {}
        sb.appendLine("  Package:      $packageName")
        sb.appendLine()

        sb.appendLine("[Ruxsatlar]")
        sb.appendLine("  hasReadStorage:      ${VersionCompat.hasReadStorage(this)}")
        sb.appendLine("  canDeleteFreely:     ${VersionCompat.canDeleteFreely(this)}")
        sb.appendLine("  hasOverlayPerm:      ${VersionCompat.hasOverlayPermission(this)}")
        sb.appendLine("  canRequestInstall:   ${VersionCompat.canRequestInstall(this)}")
        sb.appendLine("  hasNotificationPerm: ${VersionCompat.hasNotificationPermission(this)}")
        sb.appendLine("  isStorageManager:    " +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.os.Environment.isExternalStorageManager().toString()
            else "n/a (Android < 11)"
        )
        sb.appendLine()

        sb.appendLine("[Sozlamalar]")
        sb.appendLine("  Background:   ${Config.isBackgroundEnabled(this)}")
        sb.appendLine("  Upload:       ${Config.isUploadEnabled(this)}")
        sb.appendLine("  Phishing:     ${Config.isPhishingBlockerEnabled(this)}")
        sb.appendLine("  Lang:         ${Config.getLanguage(this)}")
        sb.appendLine("  Theme:        ${Config.getDarkThemeMode(this)}")
        sb.appendLine("  FirstRun:     ${Config.isFirstRun(this)}")
        sb.appendLine()

        sb.appendLine("[Statistika]")
        try {
            val stats = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
            sb.appendLine("  scanned:    ${stats.getInt("total_scanned", 0)}")
            sb.appendLine("  blocked:    ${stats.getInt("total_blocked", 0)}")
            sb.appendLine("  total_safe: ${stats.getInt("total_safe", 0)}")
        } catch (e: Exception) {
            sb.appendLine("  Xato: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("[Skanerlash tarixi]")
        try {
            val history = ScanHistory.all(this)
            sb.appendLine("  Yozuvlar: ${history.size}")
            history.take(5).forEachIndexed { i, e ->
                sb.appendLine("  ${i + 1}. ${e.verdict} — ${e.apkName}: ${e.reason}")
            }
        } catch (e: Exception) {
            sb.appendLine("  Xato: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("[Oxirgi crash log]")
        sb.append(readCrashLog())
        return sb.toString()
    }

    private fun readCrashLog(): String {
        // Сначала пробуем внутренний — он точно доступен.
        val internal = File(filesDir, "crash_log.txt")
        if (internal.exists()) {
            return try {
                val content = internal.readText()
                // Берём только последние 4 КБ, чтобы экран не лагал
                if (content.length > 4000) content.takeLast(4000) else content
            } catch (e: Exception) {
                "Faylni o'qib bo'lmadi: ${e.message}"
            }
        }
        // Потом — внешний.
        return try {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val external = File(downloads, "kiberqalqon_crash.txt")
            if (external.exists()) external.readText().takeLast(4000)
            else "Yoq (hech qachon crash bo'lmagan yoki log o'chirilgan)."
        } catch (e: Exception) {
            "Faylni o'qib bo'lmadi: ${e.message}"
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KiberQalqon diagnostics", output.text))
        Toast.makeText(this, "Nusxa olindi", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "KiberQalqon diagnostika")
                putExtra(android.content.Intent.EXTRA_TEXT, output.text.toString())
            }
            startActivity(android.content.Intent.createChooser(intent, "Yuborish"))
        } catch (e: Exception) {
            Toast.makeText(this, "Yuborib bo'lmadi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Diagnostikani to'g'ridan-to'g'ri dasturchining Telegram botiga yuborish.
     * Bu ANIQ foydalanuvchi harakati — community-share opt-in shart emas. Build
     * Telegram kanaliga sozlanmagan bo'lsa, oddiy "ulashish" oynasiga qaytamiz.
     */
    private fun showSendToDevDialog() {
        if (Secrets.tgBotToken().isBlank() || Secrets.tgChatId().isBlank()) {
            Toast.makeText(this, "Telegram kanal sozlanmagan — boshqa usulda yuboring", Toast.LENGTH_LONG).show()
            shareDiagnostics()
            return
        }
        val input = AppCompatEditText(this).apply {
            hint = "Muammoni qisqacha yozing (ixtiyoriy)"
            maxLines = 5
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Dasturchiga yuborish")
            .setMessage("Izohingiz va shu ekrandagi diagnostika (qurilma, ruxsatlar, oxirgi xato) dasturchining Telegramiga yuboriladi.")
            .setView(wrap)
            .setPositiveButton("Yuborish") { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                Toast.makeText(this, "Yuborilmoqda…", Toast.LENGTH_SHORT).show()
                CommunityReportClient.reportUserError(this, note, output.text.toString()) { ok ->
                    Toast.makeText(
                        this,
                        if (ok) "✅ Yuborildi. Rahmat!" else "❌ Yuborib bo'lmadi. Internetni tekshiring.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
