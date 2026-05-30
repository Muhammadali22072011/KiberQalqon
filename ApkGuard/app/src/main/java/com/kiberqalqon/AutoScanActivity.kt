package com.kiberqalqon

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.WindowManager
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.kiberqalqon.databinding.ActivityAutoScanBinding
import java.io.File

/**
 * Полноэкранное окно автоматического сканирования APK
 * Показывается автоматически при обнаружении нового APK файла
 */
class AutoScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutoScanBinding
    private var apkPath: String? = null
    private var apkName: String? = null

    /**
     * Agar bu APK allaqachon o'rnatilgan ilova bo'lsa — uning paket nomi.
     * PackageInstallReceiver o'rnatilgan tahdidni shu extra bilan uzatadi. Bo'sh bo'lsa —
     * bu oddiy yuklab olingan APK fayl (o'chirish = faylni o'chirish).
     */
    private var installedPkg: String? = null

    // Один общий Handler с очисткой в onDestroy — иначе postDelayed-колбэки выстреливают
    // после finish() и крашат app на binding.* (Activity destroyed but view accessed).
    private val handler = Handler(Looper.getMainLooper())

    /** True после того как scan завершился и результат показан — для разрешения back. */
    private var resultShown = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Регистрируем launcher для системного диалога "разрешить удалить файл?".
     * На Android 11+ MediaStore.createDeleteRequest возвращает PendingIntent — мы его сюда.
     * Результат RESULT_OK = юзер подтвердил, файл удалён; RESULT_CANCELED = отказался.
     */
    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onFileSuccessfullyDeleted()
        } else {
            // Юзер отказался — оставляем кнопку видимой, чтобы мог попробовать ещё раз.
            binding.tvResultMessage.text = getString(R.string.autoscan_delete_cancelled)
            binding.btnDelete.isEnabled = true
        }
    }

    /**
     * Runtime-запрос WRITE_EXTERNAL_STORAGE — на API ≤ 28 без этого file.delete()
     * молча возвращает false. С API 26 группы permissions больше не авто-гранятся,
     * поэтому WRITE надо запрашивать отдельно от READ.
     */
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            reportDelete("YOZISH ruxsati berildi — qayta urinilmoqda", apkPath)
            deleteApk()
        } else {
            // Юзер отказал. Даём прямую ссылку в системные настройки приложения,
            // чтобы можно было выдать руками без переустановки.
            reportDelete("YOZISH ruxsati rad etildi", apkPath)
            binding.tvResultMessage.text = getString(R.string.autoscan_need_write_perm)
            binding.btnDelete.text = getString(R.string.autoscan_open_settings_btn)
            binding.btnDelete.isEnabled = true
            binding.btnDelete.setOnClickListener {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    ).apply {
                        data = Uri.parse("package:$packageName")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("AutoScanActivity", "Open app settings failed", e)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Дедупликация повторных запусков по одному и тому же файлу. Раньше
        // MultiPathFileObserver триггерил CREATE/MOVED_TO/CLOSE_WRITE из нескольких
        // папок одновременно — пользователь видел 2-3 одинаковых скана подряд
        // и 2-3 копии community report в Telegram-канале. Здесь рубим на входе.
        val incomingPath = intent.getStringExtra("apk_path")
        if (incomingPath != null && isDuplicateLaunch(incomingPath)) {
            android.util.Log.d("AutoScanActivity", "skip dup launch: $incomingPath")
            finish()
            return
        }

        // Полноэкранный режим поверх всех окон
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityAutoScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkPath = incomingPath
        apkName = intent.getStringExtra("apk_name") ?: getString(R.string.autoscan_unknown_file)
        installedPkg = intent.getStringExtra("installed_pkg")?.takeIf { it.isNotBlank() }

        setupUI()
        // "already_handled" — fayl fonida (GuardWorker) allaqachon karantinga olingan/o'chirilgan.
        // Qayta skanlamaymiz (fayl yo'q): to'g'ridan-to'g'ri "virus topildi va o'chirildi" oynasi.
        if (intent.getBooleanExtra("already_handled", false)) {
            presentHandledResult()
        } else {
            startScanning()
        }
    }

    companion object {
        /** Сколько секунд считаем повторный запуск с тем же путём дубликатом. */
        private const val DEDUP_WINDOW_MS = 10_000L

        @Volatile private var lastLaunchedPath: String? = null
        @Volatile private var lastLaunchedAt: Long = 0L

        private fun isDuplicateLaunch(path: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            val sameAsLast = path == lastLaunchedPath && (now - lastLaunchedAt) < DEDUP_WINDOW_MS
            lastLaunchedPath = path
            lastLaunchedAt = now
            return sameAsLast
        }
    }

    private fun setupUI() {
        binding.tvApkName.text = apkName

        // X-кнопка ВСЕГДА видима и ВСЕГДА работает — раньше её скрывали и юзер
        // оказывался запертым в окне. Никаких "обязательного сканирования" — если
        // юзер хочет выйти, выходит мгновенно.
        binding.btnClose.visibility = View.VISIBLE
        binding.btnClose.setOnClickListener { finish() }

        binding.btnDelete.setOnClickListener { deleteApk() }

        // Анимация появления окна
        AnimationHelper.slideUp(binding.root, duration = 500)
    }
    
    // Кнопка Назад работает ВСЕГДА. Юзера нельзя запирать в окне.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        try { VoiceVerdict.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /**
     * Когда юзер возвращается из системных настроек разрешения — проверяем,
     * дали ли ему "Барча файлларга кириш". Если да, повторяем удаление автоматически.
     */
    override fun onResume() {
        super.onResume()
        if (waitingForStoragePermission && FileDeleter.hasFullStorage()) {
            waitingForStoragePermission = false
            apkPath?.let {
                binding.tvResultMessage.text = getString(R.string.autoscan_permission_granted_deleting)
                handler.postDelayed({ deleteApk() }, 300)
            }
        }
    }

    private var waitingForStoragePermission = false

    private fun startScanning() {
        binding.layoutScanning.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        AnimationHelper.fadeIn(binding.layoutScanning, duration = 300)
        binding.progressBar.let { AnimationHelper.pulse(it, duration = 1000, repeat = true) }
        animateProgress()

        // РЕАЛЬНОЕ сканирование в IO-потоке без фейкового postDelayed(2000).
        // Раньше юзер ждал ровно 2 секунды даже на пустом файле — теперь по факту.
        // Минимальное время показа анимации держим через scanStartedAt чтобы UI не
        // моргал, если скан очень быстрый (<300мс).
        val scanStartedAt = SystemClock.elapsedRealtime()
        scope.launch {
            val result: ScanResult? = withContext(Dispatchers.IO) {
                apkPath?.let { path ->
                    try {
                        ApkScanner.scan(applicationContext, path)
                    } catch (e: Exception) {
                        android.util.Log.e("AutoScanActivity", "Scan error", e)
                        null
                    }
                }
            }
            // Минимум 800мс анимации — иначе пользователь не успеет понять что произошло.
            val elapsed = SystemClock.elapsedRealtime() - scanStartedAt
            if (elapsed < 800) delay(800 - elapsed)
            if (!isFinishing && !isDestroyed) presentResult(result)
        }
    }

    private fun animateProgress() {
        val animator = ObjectAnimator.ofInt(binding.progressBar, "progress", 0, 100)
        animator.duration = 1200
        animator.start()
    }

    private fun presentResult(result: ScanResult?) {
        try {
            try { binding.layoutScanning.visibility = View.GONE } catch (_: Throwable) {}
            try { binding.layoutResult.visibility = View.VISIBLE } catch (_: Throwable) {}
            try { AnimationHelper.bounce(binding.cardResult, duration = 600) } catch (_: Throwable) {}

            // Phase B background swap per redesign §3.5 — the cyber-dark scanning
            // gradient yields to a deep-red danger gradient on DANGER verdicts.
            when (result?.verdict) {
                ScanResult.Verdict.DANGER -> {
                    try { binding.root.setBackgroundResource(R.drawable.kq_autoscan_danger_bg) } catch (_: Throwable) {}
                    safeShow { showDangerousResult(result) }
                }
                ScanResult.Verdict.SUSPICIOUS -> safeShow { showSuspiciousResult(result) }
                ScanResult.Verdict.SAFE -> safeShow { showSafeResult() }
                // result == null — skan ISTISNO bilan tugadi (fayl o'qilmadi / parse xatosi).
                // ANTIVIRUS ASOSIY QOIDASI: o'qib bo'lmagan fayl HECH QACHON "xavfsiz" emas.
                // Avval `else -> showSafeResult()` edi — bu soxta-XAVFSIZ buggi: skan crash
                // bo'lsa virus "✓ Fayl xavfsiz" deb ko'rsatilardi (Tekshirildi: 0). Endi → shubhali.
                null -> safeShow { showUnscannableResult() }
            }
            // Ovoz bilan verdict — yangi APK aniqlanganda foydalanuvchi telefonga
            // qaramasa ham eshitadi. Sozlamada o'chirilgan bo'lsa, VoiceVerdict sukut saqlaydi.
            try {
                // null (skan xatosi) → SUSPICIOUS ovozi beriladi, HECH QACHON SAFE emas.
                val v = result?.verdict ?: ScanResult.Verdict.SUSPICIOUS
                VoiceVerdict.init(this)
                VoiceVerdict.speak(this, v)
            } catch (_: Throwable) {}
            resultShown = true
        } catch (e: Throwable) {
            android.util.Log.e("AutoScanActivity", "presentResult crashed", e)
            // Не убиваем Activity — показываем минимальное сообщение через Toast.
            try {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.autoscan_cannot_show_result, e.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {}
        }
    }

    private inline fun safeShow(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            android.util.Log.e("AutoScanActivity", "show* crashed", e)
            try {
                binding.tvResultMessage.text = "❌ ${e.javaClass.simpleName}: ${e.message}"
            } catch (_: Throwable) {}
        }
    }

    /** Отправляет текстовое предупреждение друзьям через любое приложение (Telegram, WhatsApp, SMS). */
    private fun shareScanResult(verdict: ScanResult.Verdict) {
        try {
            val name = apkName ?: "?"
            val textRes = when (verdict) {
                ScanResult.Verdict.DANGER -> R.string.share_text_danger
                ScanResult.Verdict.SUSPICIOUS -> R.string.share_text_suspicious
                else -> return  // безопасными файлами не делимся
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                putExtra(android.content.Intent.EXTRA_TEXT, getString(textRes, name))
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_via)))
        } catch (e: Exception) {
            android.util.Log.e("AutoScanActivity", "share failed", e)
        }
    }

    /**
     * Populates the FAYL header (filename + sev chip) and the mono meta block
     * (paket / sha1 / oila / manba) with real data extracted from the scanned APK.
     * Called from showDangerousResult/showSuspiciousResult.
     */
    private fun populateInfoCard(result: ScanResult) {
        val path = apkPath ?: return
        val file = File(path)
        binding.tvAsScanFileName.text = file.name.ifBlank { apkName ?: "?" }

        val pkg = try {
            packageManager.getPackageArchiveInfo(path, 0)?.packageName ?: "?"
        } catch (_: Throwable) { "?" }
        val sha1 = sha1Short(file)
        val family = result.malwareSignatures.firstOrNull() ?: inferFamilyFromReason(result.reason)
        val source = sourceLabel()

        binding.tvAsScanMeta.text = buildString {
            append("paket: $pkg\n")
            append("sha1: $sha1\n")
            append("oila: ${family ?: "—"}\n")
            append("manba: $source")
        }
    }

    private fun sha1Short(file: File): String = try {
        if (!file.exists() || !file.canRead()) "—" else {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            hex.take(6) + "…" + hex.takeLast(5)
        }
    } catch (_: Throwable) { "—" }

    private fun inferFamilyFromReason(reason: String): String? {
        val r = reason.lowercase()
        return when {
            "ajina" in r -> "Ajina.Banker"
            "roundrift" in r -> "RoundRift"
            "sms" in r && "steal" in r -> "SMS Stealer"
            "phish" in r || "overlay" in r -> "Phish overlay"
            else -> null
        }
    }

    private fun sourceLabel(): String {
        val hint = sourceHint()?.lowercase() ?: return "—"
        return when {
            "telegram" in hint -> "Telegram"
            "whatsapp" in hint -> "WhatsApp"
            "download" in hint || "yuklab" in hint -> "Yuklab olishlar"
            else -> "—"
        }
    }

    private fun showDangerousResult(result: ScanResult) {
        // Badge → красный, dangerDetails показываем, populate реальными данными
        binding.resultBadge.setBackgroundResource(R.drawable.kq_danger_badge)
        binding.resultShield.setImageResource(R.drawable.ic_alert_triangle)
        binding.dangerDetails.visibility = View.VISIBLE
        binding.tvAutoDelete.visibility = View.VISIBLE
        populateInfoCard(result)

        binding.tvResultTitle.text = getString(R.string.auto_scan_dangerous_title)
        binding.tvResultTitle.setTextColor(getColor(android.R.color.white))

        val details = result.details.joinToString("\n• ", "• ")
        val context = sourceHint()
        binding.tvResultMessage.text = buildString {
            append(getString(R.string.autoscan_danger_body))
            append("\n\n")
            append(details)
            if (context != null) {
                append("\n\n")
                append(context)
            }
        }

        binding.btnDelete.visibility = View.VISIBLE
        // O'rnatilgan ilova bo'lsa — tugma "Ilovani o'chirish" (uninstall), fayl emas.
        if (installedPkg != null) binding.btnDelete.text = getString(R.string.uninstall_app)
        binding.tvDeleteHint.visibility = View.VISIBLE
        // Превращаем "подсказку" в кликабельный share — юзер одним тапом
        // отправляет друзьям предупреждение "этот APK — вирус, не ставьте".
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.DANGER) }

        // Анимация встряхивания для опасности
        AnimationHelper.shake(binding.cardResult, duration = 500)

        // Avtomatik o'chirish rejimi — Sozlamalardan keladi:
        //  "delete" → 2.5 sekunddan keyin avtomatik deleteApk() chaqiramiz
        //             (foydalanuvchi btnClose orqali to'xtata oladi).
        //  "ask"    → eski xulq-atvor: btnDelete pulsatsiya, kutamiz.
        //  "warn"   → faqat ogohlantirish, pulsatsiya yo'q (kam agressiv).
        val deleteMode = try { Config.getAutoDeleteMode(this) } catch (_: Throwable) { "ask" }
        when (deleteMode) {
            "delete" -> {
                binding.tvDeleteHint.text = getString(R.string.autoscan_auto_deleting)
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) deleteApk()
                }, 2500)
            }
            "warn" -> {
                // Tugma ko'rinadi lekin pulsatsiyasiz.
            }
            else -> {
                AnimationHelper.pulse(binding.btnDelete, duration = 1000, repeat = true)
            }
        }

        sendNotification("🔴 Virus topildi!", "$apkName fayli xavfli — o'chirish tavsiya etiladi", true)
    }

    private fun showSuspiciousResult(result: ScanResult) {
        // Badge → амбер, dangerDetails показываем, но без countdown.
        binding.resultBadge.setBackgroundResource(R.drawable.kq_warn_badge)
        binding.resultShield.setImageResource(R.drawable.ic_warning_alert)
        binding.dangerDetails.visibility = View.VISIBLE
        binding.tvAutoDelete.visibility = View.GONE
        populateInfoCard(result)

        binding.tvResultTitle.text = getString(R.string.autoscan_suspicious_title)
        binding.tvResultTitle.setTextColor(getColor(android.R.color.white))

        val details = result.details.joinToString("\n• ", "• ")
        val context = sourceHint()
        binding.tvResultMessage.text = buildString {
            append(getString(R.string.autoscan_suspicious_body))
            append("\n\n")
            append(details)
            append("\n\n")
            append(getString(R.string.autoscan_suspicious_recommend))
            if (context != null) {
                append("\n\n")
                append(context)
            }
        }

        binding.btnDelete.visibility = View.VISIBLE
        if (installedPkg != null) binding.btnDelete.text = getString(R.string.uninstall_app)
        binding.tvDeleteHint.visibility = View.VISIBLE
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.SUSPICIOUS) }

        sendNotification("🟠 Shubhali fayl", "$apkName faylida shubhali belgilar bor", false)
    }

    /**
     * Skan ISTISNO bilan tugadi (ApkScanner.scan() throw qildi yoki natija null).
     * ANTIVIRUS ASOSIY QOIDASI: o'qib bo'lmagan / xato bilan tugagan fayl HECH QACHON
     * "xavfsiz" deb ko'rsatilmaydi — har doim shubhali deb hisoblanadi va o'chirish
     * tavsiya etiladi. Avval bunday holatda showSafeResult() chaqirilardi (soxta-XAVFSIZ).
     */
    private fun showUnscannableResult() {
        binding.resultBadge.setBackgroundResource(R.drawable.kq_warn_badge)
        binding.resultShield.setImageResource(R.drawable.ic_warning_alert)
        binding.dangerDetails.visibility = View.GONE
        binding.tvAutoDelete.visibility = View.GONE

        binding.tvResultTitle.text = getString(R.string.autoscan_unscannable_title)
        binding.tvResultTitle.setTextColor(getColor(android.R.color.white))

        val context = sourceHint()
        binding.tvResultMessage.text = buildString {
            append(getString(R.string.autoscan_unscannable_body))
            if (context != null) {
                append("\n\n")
                append(context)
            }
        }

        val installedPkg = installedPackageForApk(apkPath)
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.isEnabled = true
        binding.btnDelete.text =
            if (installedPkg != null) getString(R.string.uninstall_app)
            else getString(R.string.delete_apk)
        binding.btnDelete.setOnClickListener { deleteApk() }
        binding.tvDeleteHint.visibility = View.VISIBLE
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.SUSPICIOUS) }

        sendNotification(
            "🟠 Faylni tekshirib bo'lmadi",
            "$apkName ni to'liq tekshirib bo'lmadi — ehtiyot bo'ling, o'chirish tavsiya etiladi",
            true
        )
    }

    /**
     * Fon'da (GuardWorker) DANGER fayl ALLAQACHON karantinga olingan holat.
     *
     * Foydalanuvchi shikoyati: "fayl fonida o'chiriladi-yu, lekin OYNA chiqmaydi".
     * Ilgari delete rejimida faqat notification ko'rsatilardi. Endi shu OYNA chiqadi:
     * fayl yo'q — qayta skanlamaymiz, o'chirish tugmasi ham kerak emas, faqat xabar + "Tushunarli".
     */
    private fun presentHandledResult() {
        try { binding.layoutScanning.visibility = View.GONE } catch (_: Throwable) {}
        try { binding.layoutResult.visibility = View.VISIBLE } catch (_: Throwable) {}
        try { binding.root.setBackgroundResource(R.drawable.kq_autoscan_danger_bg) } catch (_: Throwable) {}
        try { AnimationHelper.bounce(binding.cardResult, duration = 600) } catch (_: Throwable) {}

        binding.resultBadge.setBackgroundResource(R.drawable.kq_danger_badge)
        binding.resultShield.setImageResource(R.drawable.ic_alert_triangle)
        binding.dangerDetails.visibility = View.GONE
        binding.tvAutoDelete.visibility = View.GONE

        binding.tvResultTitle.text = "🛡️ Virus topildi va o'chirildi"
        binding.tvResultTitle.setTextColor(getColor(android.R.color.white))

        val reason = intent.getStringExtra("reason")?.takeIf { it.isNotBlank() }
        val ctxHint = sourceHint()
        binding.tvResultMessage.text = buildString {
            append("$apkName fayli xavfli deb topildi va avtomatik o'chirildi (karantin).")
            if (reason != null) {
                append("\n\nSabab: ${reason.take(300)}")
            }
            append("\n\nAgar bu xato bo'lsa, 7 kun ichida tiklash mumkin.")
            if (ctxHint != null) {
                append("\n\n")
                append(ctxHint)
            }
        }

        // O'chirish tugmasi KERAK EMAS — fayl allaqachon yo'q. "Tushunarli" → oynani yopish.
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.isEnabled = true
        binding.btnDelete.text = getString(R.string.btn_ok)
        binding.btnDelete.setOnClickListener { finish() }

        // Do'stlarni ogohlantirish (ulashish).
        binding.tvDeleteHint.visibility = View.VISIBLE
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.DANGER) }

        try { AnimationHelper.shake(binding.cardResult, duration = 500) } catch (_: Throwable) {}
        try {
            VoiceVerdict.init(this)
            VoiceVerdict.speak(this, ScanResult.Verdict.DANGER)
        } catch (_: Throwable) {}
        resultShown = true
    }

    private fun showSafeResult() {
        // Badge → зелёный, dangerDetails (info card + perm chips + countdown) скрываем.
        binding.resultBadge.setBackgroundResource(R.drawable.kq_safe_badge)
        binding.resultShield.setImageResource(R.drawable.ic_check_simple)
        binding.dangerDetails.visibility = View.GONE
        binding.tvAutoDelete.visibility = View.GONE

        binding.tvResultTitle.text = getString(R.string.status_safe)
        binding.tvResultTitle.setTextColor(getColor(android.R.color.white))

        // Проверяем — установлен ли уже этот APK на устройстве. PackageInstaller на
        // "переустановке поверх того же signature" часто молча отказывает (особенно
        // когда подписи разные или версия ниже), и юзер видит "ничего не происходит".
        // В этом случае куда полезнее просто открыть уже установленную программу —
        // что и просил пользователь.
        val installedPkg = installedPackageForApk(apkPath)
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.isEnabled = true
        binding.tvDeleteHint.visibility = View.GONE

        if (installedPkg != null) {
            binding.tvResultMessage.text = getString(R.string.autoscan_already_installed_subtitle)
            binding.btnDelete.text = getString(R.string.autoscan_open)
            binding.btnDelete.setOnClickListener { launchInstalledApp(installedPkg) }
        } else {
            binding.tvResultMessage.text = getString(R.string.autoscan_safe_subtitle)
            binding.btnDelete.text = getString(R.string.autoscan_install)
            binding.btnDelete.setOnClickListener { launchInstaller() }
        }

        sendNotification("✓ Fayl xavfsiz", "$apkName fayli tekshirildi va xavfsiz", false)
    }

    /**
     * Возвращает package name, если APK уже установлен на устройстве.
     * Парсим APK через getPackageArchiveInfo (не требует <queries>), а потом
     * пробуем найти его среди установленных. getLaunchIntentForPackage возвращает
     * null если приложение не установлено ИЛИ не имеет launcher activity — оба
     * случая нас не интересуют, оставляем обычный install-flow.
     */
    private fun installedPackageForApk(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val pkg = packageManager.getPackageArchiveInfo(path, 0)?.packageName ?: return null
            if (packageManager.getLaunchIntentForPackage(pkg) != null) pkg else null
        } catch (e: Throwable) {
            android.util.Log.w("AutoScanActivity", "installedPackageForApk failed", e)
            null
        }
    }

    /** Запускает уже установленное приложение и закрывает наше окно. */
    private fun launchInstalledApp(pkg: String) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                binding.tvResultMessage.text = getString(R.string.autoscan_launch_failed)
                return
            }
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            handler.postDelayed({ if (!isFinishing) finish() }, 300)
        } catch (e: Exception) {
            android.util.Log.e("AutoScanActivity", "launchInstalledApp failed", e)
            binding.tvResultMessage.text = getString(R.string.autoscan_launch_failed)
        }
    }

    /**
     * Запускает системный PackageInstaller для текущего APK.
     * Используем FileProvider — иначе на Android 7+ ContentProvider'ы кидают FileUriExposedException.
     * Должен быть объявлен в манифесте provider с authority "${applicationId}.fileprovider".
     */
    private fun launchInstaller() {
        val path = apkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            binding.tvResultMessage.text = getString(R.string.autoscan_file_not_found)
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
            // Закрываем своё окно — теперь PackageInstaller на экране.
            handler.postDelayed({ if (!isFinishing) finish() }, 300)
        } catch (e: Exception) {
            android.util.Log.e("AutoScanActivity", "launchInstaller failed", e)
            binding.tvResultMessage.text = getString(R.string.autoscan_install_failed, e.message ?: "")
        }
    }

    /**
     * O'rnatilgan ilovani o'chirish — tizimning standart uninstall oynasini ochadi.
     * (ACTION_DELETE + package: URI). Foydalanuvchi bitta "OK" bilan virusni o'chiradi.
     */
    private fun uninstallInstalledApp(pkg: String) {
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_DELETE,
                Uri.parse("package:$pkg")
            ).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK }
            startActivity(intent)
            reportDelete("O'rnatilgan ilova — uninstall oynasi ochildi", apkPath, extra = pkg)
            // O'z oynamizni yopamiz — endi ekranda tizim uninstall dialogi turadi.
            handler.postDelayed({ if (!isFinishing) finish() }, 300)
        } catch (e: Throwable) {
            android.util.Log.e("AutoScanActivity", "uninstallInstalledApp failed", e)
            binding.tvResultMessage.text = e.message ?: ""
            binding.btnDelete.isEnabled = true
        }
    }

    private fun deleteApk() {
        // O'rnatilgan ilovani faylsifat o'chirib BO'LMAYDI: uning base.apk yo'li /data/app/...
        // da, egasi — tizim. file.delete() har doim false qaytaradi. Shuning uchun bu yerda
        // tizimning uninstall oynasini ochamiz (foydalanuvchi bir tasdiq bilan o'chiradi).
        val pkg = installedPkg
        if (pkg != null) {
            uninstallInstalledApp(pkg)
            return
        }

        val path = apkPath
        if (path.isNullOrBlank()) {
            binding.tvResultMessage.text = getString(R.string.autoscan_no_path)
            reportDelete("Yo'l yo'q", path = "(null)")
            return
        }
        binding.btnDelete.isEnabled = false

        // На API ≤ 28: проверяем runtime WRITE_EXTERNAL_STORAGE ПЕРЕД попыткой
        // удаления. Без него file.delete() молча возвращает false и юзер видит
        // загадочное "Faylni o'chirib bo'lmadi". С API 26+ группа permissions
        // больше не авто-гранится через READ — WRITE нужен отдельно.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val hasWrite = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasWrite) {
                reportDelete("YOZISH ruxsati so'ralmoqda", path)
                binding.tvResultMessage.text = getString(R.string.autoscan_requesting_write_perm)
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }

        try {
            when (val result = FileDeleter.delete(this, path)) {
                FileDeleter.Result.Deleted -> {
                    reportDelete("O'chirildi", path)
                    onFileSuccessfullyDeleted()
                }

                is FileDeleter.Result.NeedsUserConsent -> {
                    reportDelete("Foydalanuvchi tasdiqlashi kerak (MediaStore)", path)
                    binding.tvResultMessage.text = getString(R.string.autoscan_confirm_in_system_dialog)
                    val req = IntentSenderRequest.Builder(result.sender).build()
                    deleteConsentLauncher.launch(req)
                }

                FileDeleter.Result.NeedsManageStorage -> {
                    reportDelete("To'liq xotira ruxsati kerak", path)
                    // Нет разрешения "Доступ ко всем файлам" — превращаем кнопку
                    // в "RUXSAT BERISH" и при нажатии открываем системные настройки.
                    binding.tvResultMessage.text = getString(R.string.autoscan_need_all_files)
                    binding.btnDelete.text = getString(R.string.autoscan_grant_btn)
                    binding.btnDelete.isEnabled = true
                    binding.btnDelete.setOnClickListener { openManageStorageSettings() }
                }

                is FileDeleter.Result.SandboxedByOwner -> {
                    reportDelete("Boshqa ilova papkasida", path, extra = result.ownerPackage)
                    // Файл в /Android/data/<owner>/ — даже с MANAGE_EXTERNAL_STORAGE
                    // Android отказывает. Юзеру надо удалять через само приложение.
                    binding.tvResultMessage.text =
                        getString(R.string.autoscan_sandboxed_owner, result.ownerPackage)
                    binding.btnDelete.text = getString(R.string.btn_ok)
                    binding.btnDelete.isEnabled = true
                    binding.btnDelete.setOnClickListener { finish() }
                }

                is FileDeleter.Result.Failed -> {
                    reportDelete("Bajarilmadi", path, extra = result.message)
                    binding.tvResultMessage.text = "❌ ${result.message}\n\nPapka: ${File(path).parent}"
                    binding.btnDelete.isEnabled = true
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("AutoScanActivity", "deleteApk crashed", e)
            reportDelete("Istisno xatosi", path, extra = "${e.javaClass.simpleName}: ${e.message}")
            binding.tvResultMessage.text = "❌ Xatolik: ${e.message}"
            binding.btnDelete.isEnabled = true
        }
    }

    /**
     * Шлёт диагностику удаления в dev-канал — чтобы видеть из Telegram, ПОЧЕМУ
     * delete не сработал на устройстве пользователя. Уважает opt-in флаги:
     * без согласия и без BuildConfig.DEV_TG_* ничего не уходит (см. CommunityReportClient).
     */
    private fun reportDelete(status: String, path: String?, extra: String? = null) {
        try {
            val file = path?.let { File(it) }
            val lines = linkedMapOf(
                "Holat" to status,
                "Fayl" to (file?.name ?: "?"),
                "Yo'l" to (path ?: "(null)"),
                "Hajm" to (file?.takeIf { it.exists() }?.length()?.toString() ?: "yo'q"),
                "Mavjud" to (file?.exists()?.toString() ?: "yo'q"),
                "Yozish mumkin" to (file?.parentFile?.canWrite()?.toString() ?: "yo'q"),
                "To'liq xotira" to FileDeleter.hasFullStorage().toString(),
                "Manba" to (apkPath?.let { ApkScanner.inferSource(it) } ?: "?")
            )
            if (!extra.isNullOrBlank()) lines["Tafsilot"] = extra
            CommunityReportClient.reportEvent(applicationContext, "O'chirish urinishi", lines)
        } catch (e: Throwable) {
            android.util.Log.w("AutoScanActivity", "reportDelete failed", e)
        }
    }

    /**
     * Открывает системные настройки "Все файлы" для нашего пакета.
     * После того как юзер вернётся, можно повторить удаление автоматически.
     */
    private fun openManageStorageSettings() {
        waitingForStoragePermission = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = Uri.parse("package:$packageName")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } else {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = Uri.parse("package:$packageName")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            // Возвращаем кнопку в исходное состояние, чтобы после грантa юзер мог снова удалить.
            binding.btnDelete.text = getString(R.string.autoscan_delete_btn)
            binding.btnDelete.setOnClickListener { deleteApk() }
        } catch (e: Exception) {
            android.util.Log.e("AutoScanActivity", "openManageStorageSettings failed", e)
        }
    }

    /** Вызывается из всех точек, где удаление подтверждено (как сразу, так и после consent). */
    private fun onFileSuccessfullyDeleted() {
        binding.tvResultMessage.text = getString(R.string.delete_success)
        binding.btnDelete.visibility = View.GONE
        binding.tvDeleteHint.text = getString(R.string.autoscan_delete_hint_done)

        // "Bloklandi" hisoblagichi skan paytida (ApkScanner.scan, DANGER) bir marta oshadi;
        // bu yerda qayta oshirmaymiz, aks holda bitta tahdid ikki marta sanalardi.

        handler.postDelayed({ if (!isFinishing) finish() }, 2000)
    }

    /**
     * Подсказка пользователю в зависимости от пути файла или явного intent extra "apk_source".
     * Когда APK пришёл через Telegram — это самый частый вектор атаки в УЗ, прямо так и пишем.
     */
    private fun sourceHint(): String? {
        val explicit = intent.getStringExtra("apk_source")
        val inferred = explicit ?: apkPath?.let { ApkScanner.inferSource(it) } ?: return null
        val resId = when (inferred) {
            "telegram" -> R.string.ctx_from_telegram
            "whatsapp" -> R.string.ctx_from_whatsapp
            "download" -> R.string.ctx_from_download
            "share" -> R.string.ctx_from_share
            else -> return null
        }
        return getString(resId)
    }

    private fun sendNotification(title: String, message: String, isDangerous: Boolean) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager == null) {
                android.util.Log.e("AutoScanActivity", "NotificationManager is null")
                return
            }
            
            val channelId = "kiberqalqon_scan"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val channel = NotificationChannel(
                        channelId,
                        "Tekshirish natijalari",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    notificationManager.createNotificationChannel(channel)
                } catch (e: Exception) {
                    android.util.Log.e("AutoScanActivity", "Channel creation error", e)
                }
            }
            
            // Статистика — bildirishnoma uchun ko'rinish qiymatlari (kanonik kalitlardan).
            try {
                val prefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
                val scanned = prefs.getInt("total_scanned", 0)
                val blocked = prefs.getInt("total_blocked", 0)

                val statsText = "Tekshirildi: $scanned | Bloklandi: $blocked"
                
                val notification = NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle()
                        .bigText("$message\n\n📊 $statsText"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                
                // ID на базе hash от apk_path — уникален для каждого файла, нет коллизий
                // с другими уведомлениями приложения. Внутри одного файла перезаписывается старое.
                val notifId = (apkPath ?: apkName ?: title).hashCode() and 0x7FFFFFFF
                notificationManager.notify(notifId, notification)
            } catch (e: Exception) {
                android.util.Log.e("AutoScanActivity", "Notification error", e)
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoScanActivity", "Critical notification error", e)
        }
    }
}