package com.kiberqalqon

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityReportProblemBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Muammoni yuborish" — foydalanuvchi xato/muammoni to'g'ridan-to'g'ri dasturchining
 * Telegramiga yuboradigan ekran. Sozlamalar → Yordam markazi dan ochiladi.
 *
 * Dizayn ilovaning umumiy tili bilan bir xil (kartalar, kq_* ranglar), kirish/chiqishda
 * silliq o'tish va yengil animatsiyalar (cascade kirish, pulse, bounce, shake).
 *
 * Yuborish CommunityReportClient.reportUserError orqali — bu ANIQ foydalanuvchi
 * harakati, shuning uchun community-share opt-in shart emas (faqat DEV_TG sozlangan
 * bo'lishi kerak). Diagnostika .txt fayl bo'lib ketadi.
 */
class ReportProblemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportProblemBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityReportProblemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { closeWithAnim() }
        binding.btnSend.setOnClickListener { send() }

        // Kirish animatsiyasi — bolalar ketma-ket (cascade) paydo bo'ladi,
        // keyin hero ikonkasi bir marta yengil "pulse" qiladi.
        AnimationHelper.cascadeChildren(binding.content, 70)
        binding.hero.postDelayed({ AnimationHelper.pulse(binding.hero, 900, repeat = false) }, 700)
    }

    private fun send() {
        val note = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (note.length < 3) {
            AnimationHelper.shake(binding.etMessage)
            Toast.makeText(this, getString(R.string.rp_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (Secrets.tgBotToken().isBlank() || Secrets.tgChatId().isBlank()) {
            Toast.makeText(this, getString(R.string.rp_not_configured), Toast.LENGTH_LONG).show()
            return
        }
        setSending(true)
        CommunityReportClient.reportUserError(this, note, collectDiagnostics()) { ok ->
            if (ok) onSent() else onFailed()
        }
    }

    private fun setSending(sending: Boolean) {
        binding.btnSend.isEnabled = !sending
        binding.etMessage.isEnabled = !sending
        binding.btnSend.text =
            getString(if (sending) R.string.rp_sending else R.string.rp_send)
    }

    private fun onSent() {
        binding.btnSend.text = getString(R.string.rp_sent)
        try { binding.btnSend.setIconResource(R.drawable.ic_check_circle) } catch (_: Throwable) {}
        AnimationHelper.bounce(binding.btnSend, 500)
        Toast.makeText(this, getString(R.string.rp_sent_toast), Toast.LENGTH_LONG).show()
        binding.btnSend.postDelayed({ closeWithAnim() }, 1100)
    }

    private fun onFailed() {
        setSending(false)
        AnimationHelper.shake(binding.btnSend)
        Toast.makeText(this, getString(R.string.rp_error_toast), Toast.LENGTH_LONG).show()
    }

    private fun closeWithAnim() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Yengil diagnostika to'plami — qurilma, ruxsatlar, sozlamalar, oxirgi crash. PII yo'q. */
    private fun collectDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== KiberQalqon muammo hisoboti ===")
        sb.appendLine("Vaqt: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()
        sb.appendLine("[Qurilma] ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("[Android] ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("[Ilova] ${info.versionName} (${info.versionCode}) — $packageName")
        } catch (_: Exception) {}
        sb.appendLine()
        sb.appendLine("[Ruxsatlar]")
        sb.appendLine("  storage=${VersionCompat.hasReadStorage(this)}")
        sb.appendLine("  overlay=${VersionCompat.hasOverlayPermission(this)}")
        sb.appendLine("  install=${VersionCompat.canRequestInstall(this)}")
        sb.appendLine("  notif=${VersionCompat.hasNotificationPermission(this)}")
        sb.appendLine()
        sb.appendLine("[Sozlamalar]")
        sb.appendLine("  bg=${Config.isBackgroundEnabled(this)}")
        sb.appendLine("  phishing=${Config.isPhishingBlockerEnabled(this)}")
        sb.appendLine("  upload=${Config.isUploadEnabled(this)}")
        sb.appendLine("  lang=${Config.getLanguage(this)}")
        sb.appendLine()
        sb.appendLine("[Oxirgi xato]")
        sb.append(readLastCrash())
        return sb.toString()
    }

    private fun readLastCrash(): String {
        val internal = File(filesDir, "crash_log.txt")
        if (internal.exists()) {
            return try {
                internal.readText().takeLast(2500)
            } catch (e: Exception) {
                "o'qib bo'lmadi: ${e.message}"
            }
        }
        return "yo'q"
    }
}
