package com.uzguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.io.File

/**
 * Диагностический экран — собирает в один большой текст:
 *  - Версию Android и устройство
 *  - Статус ВСЕХ разрешений UzGuard
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
        ThemeHelper.applyAccent(this)

        // v4 «Milliy Kiber Himoya» reskin — fon kq_bg, eyebrow + h-title sarlavha,
        // mono chiqish kartasi (card sunken r24), pill tugmalar. Logika o'zgarmagan.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.kq_bg))
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_diag_eyebrow)
            isAllCaps = true
            textSize = 12f
            typeface = font(R.font.onest_bold)
            letterSpacing = 0.02f
            setTextColor(c(R.color.kq_primary))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_diag_title)
            textSize = 24f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.02f
            setTextColor(c(R.color.kq_ink))
            setPadding(0, dp(6), 0, dp(12))
        })

        output = TextView(this).apply {
            textSize = 11.5f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(R.color.kq_ink_2))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_sunken)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipToOutline = true
            addView(output)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { bottomMargin = dp(12) }
        )

        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_copy), primary = true) { copyToClipboard() })
        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_share)) { shareDiagnostics() })
        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_send_dev)) { showSendToDevDialog() })
        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_refresh)) { refresh() })
        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_telemetry)) {
            try {
                startActivity(android.content.Intent(this@DiagnosticsActivity, TelemetrySettingsActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this@DiagnosticsActivity, getString(R.string.toast_error_generic, e.message), Toast.LENGTH_LONG).show()
            }
        })
        root.addView(v4Btn(getString(R.string.kq4_misc_diag_btn_hidden)) {
            try {
                startActivity(android.content.Intent(this@DiagnosticsActivity, HiddenThreatsActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this@DiagnosticsActivity, getString(R.string.toast_error_generic, e.message), Toast.LENGTH_LONG).show()
            }
        })

        // 🧪 Test scanner — генерирует синтетические "вирусы" и прогоняет через
        // наш ApkScanner. ТОЛЬКО в debug: TestVirusGenerator встраивает реальные IOC
        // (elrxzx.com, ydbllnjd.com, frida-server…) как тестовые образцы. В release
        // это — единственная ссылка на класс, поэтому R8 вырежет TestVirusGenerator
        // целиком вместе с его строками (чтобы `strings` их не показал в проде).
        if (BuildConfig.DEBUG) {
            root.addView(v4Btn("🧪 Skaner sinovi (test virus)") {
                output.text = "Tekshiruvchi sinov ishlamoqda…\n(6 ta sintetik APK yaratiladi va skanerga uzatiladi)"
                Thread {
                    val report = try {
                        TestVirusGenerator.runAndReport(this@DiagnosticsActivity)
                    } catch (e: Throwable) {
                        "❌ Test crashed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                    runOnUiThread { output.text = report }
                }.start()
            })
        }

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        output.text = buildDiagnostics()
    }

    private fun buildDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== UzGuard DIAGNOSTIKA ===")
        sb.appendLine("Vaqt: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine("[Qurilma]")
        sb.appendLine("  Brand:        ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("  Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  Build:        ${Build.DISPLAY}")
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("  UzGuard:     ${info.versionName} (${info.versionCode})")
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
            val stats = getSharedPreferences("uzguard_stats", Context.MODE_PRIVATE)
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
            val external = File(downloads, "uzguard_crash.txt")
            if (external.exists()) external.readText().takeLast(4000)
            else "Yoq (hech qachon crash bo'lmagan yoki log o'chirilgan)."
        } catch (e: Exception) {
            "Faylni o'qib bo'lmadi: ${e.message}"
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UzGuard diagnostics", output.text))
        Toast.makeText(this, getString(R.string.kq4_misc_toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.kq4_misc_diag_share_subject))
                putExtra(android.content.Intent.EXTRA_TEXT, output.text.toString())
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.kq4_misc_diag_send)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.kq4_misc_diag_toast_share_fail, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Diagnostikani to'g'ridan-to'g'ri dasturchining Telegram botiga yuborish.
     * Bu ANIQ foydalanuvchi harakati — community-share opt-in shart emas. Build
     * Telegram kanaliga sozlanmagan bo'lsa, oddiy "ulashish" oynasiga qaytamiz.
     */
    private fun showSendToDevDialog() {
        if (Secrets.tgBotToken().isBlank() || Secrets.tgChatId().isBlank()) {
            Toast.makeText(this, getString(R.string.kq4_misc_diag_toast_no_channel), Toast.LENGTH_LONG).show()
            shareDiagnostics()
            return
        }
        val input = AppCompatEditText(this).apply {
            hint = getString(R.string.kq4_misc_diag_dev_hint)
            maxLines = 5
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_misc_diag_dev_title)
            .setMessage(R.string.kq4_misc_diag_dev_msg)
            .setView(wrap)
            .setPositiveButton(R.string.kq4_misc_diag_send) { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                Toast.makeText(this, getString(R.string.kq4_misc_diag_toast_sending), Toast.LENGTH_SHORT).show()
                CommunityReportClient.reportUserError(this, note, output.text.toString()) { ok ->
                    Toast.makeText(
                        this,
                        getString(
                            if (ok) R.string.kq4_misc_diag_toast_sent_ok
                            else R.string.kq4_misc_diag_toast_sent_fail
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ───────────────────── v4 dizayn yordamchilari ─────────────────────

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun font(id: Int): android.graphics.Typeface? =
        try { ResourcesCompat.getFont(this, id) } catch (_: Exception) { null }

    /** v4 pill tugma: kq4_btn_primary / kq4_btn_soft fon, Onest Bold, h≥46dp. */
    private fun v4Btn(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14.5f
            typeface = font(R.font.onest_bold)
            background = AppCompatResources.getDrawable(
                context,
                if (primary) R.drawable.kq4_btn_primary else R.drawable.kq4_btn_soft
            )
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(46)
            minimumHeight = dp(46)
            setTextColor(c(if (primary) R.color.kq_on_primary else R.color.kq_ink))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
}
