package com.uzguard

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.WindowManager
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import com.uzguard.databinding.ActivityAutoScanBinding
import java.io.File

/**
 * Полноэкранное окно автоматического сканирования APK
 * Показывается автоматически при обнаружении нового APK файла
 *
 * v4 «Milliy Kiber Himoya» рескин (design_v4_extracted/screens3.jsx → AutoScan):
 * фаза скана — тёмный экран с KqRingView + 4 шага; фаза результата —
 * красное свечение, 96dp круг, карточка «Bu fayl nima qiladi», countdown.
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

    /**
     * Skanlangan fayl bizning kesh ichidagi NUSXAmi (ShareReceiver content URI'ni
     * cacheDir/shared ga ko'chirib bergan). Bunda nusxani o'chirib "o'chirildi" deyish
     * yolg'on bo'ladi — asl fayl manba ilovasida (Telegram va h.k.) qoladi.
     */
    private var apkIsCopy: Boolean = false

    /** Asl manba content URI (nusxa bo'lsa) — uni ham o'chirishga urinib ko'ramiz. */
    private var originUri: String? = null

    // Один общий Handler с очисткой в onDestroy — иначе postDelayed-колбэки выстреливают
    // после finish() и крашат app на binding.* (Activity destroyed but view accessed).
    private val handler = Handler(Looper.getMainLooper())

    /** True после того как scan завершился и результат показан — для разрешения back. */
    private var resultShown = false

    /** Skan jarayoni halqasini boshqaradigan animator (0..90%, natijada 100%). */
    private var progressAnimator: ValueAnimator? = null

    /** Oxirgi skan natijasi — «Batafsil» tugmasi ScanResultActivity'ga uzatadi. */
    private var lastResult: ScanResult? = null

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
        apkIsCopy = intent.getBooleanExtra("apk_is_copy", false)
        originUri = intent.getStringExtra("apk_origin_uri")

        setupUI()
        // "already_handled" — fayl fonida (GuardWorker) allaqachon karantinga olingan/o'chirilgan.
        // Qayta skanlamaymiz (fayl yo'q): to'g'ridan-to'g'ri "virus topildi va o'chirildi" oynasi.
        if (intent.getBooleanExtra("already_handled", false)) {
            presentHandledResult()
        } else {
            startScanning()
        }
    }

    /**
     * #3: Activity launchMode=singleTop + FLAG_ACTIVITY_SINGLE_TOP bilan ishga tushiriladi,
     * lekin avval onNewIntent YO'Q edi — natija oynasi (masalan SAFE) ochiq turganda
     * KELGAN YANGI APK (masalan virus) jim TASHLAB yuborilardi, umuman skanlanmasdan.
     * Endi yangi intent kelganda eski (tugamagan) skanni to'xtatib, yangisini boshlaymiz.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val incomingPath = intent.getStringExtra("apk_path")
        // Bir xil fayl 20s ichida qayta kelsa — e'tiborsiz (oynani yopmaymiz).
        if (incomingPath != null && isDuplicateLaunch(incomingPath)) {
            android.util.Log.d("AutoScanActivity", "skip dup onNewIntent: $incomingPath")
            return
        }

        // Avvalgi skan korutinalari va kechiktirilgan kolbeklar — bekor.
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext.cancelChildren()
        progressAnimator?.cancel()

        resultShown = false
        lastResult = null
        apkPath = incomingPath
        apkName = intent.getStringExtra("apk_name") ?: getString(R.string.autoscan_unknown_file)
        installedPkg = intent.getStringExtra("installed_pkg")?.takeIf { it.isNotBlank() }
        apkIsCopy = intent.getBooleanExtra("apk_is_copy", false)
        originUri = intent.getStringExtra("apk_origin_uri")

        setupUI()
        if (intent.getBooleanExtra("already_handled", false)) {
            presentHandledResult()
        } else {
            startScanning()
        }
    }

    companion object {
        /**
         * Сколько секунд считаем повторный запуск с тем же путём дубликатом.
         * Должно быть БОЛЬШЕ интервала fast-scan poll (ProtectionService = 15s):
         * inotify-FileObserver ловит файл за ~1s, а poll-loop — на следующем тике (≤15s),
         * и без широкого окна один и тот же SAFE-файл показался бы дважды.
         */
        private const val DEDUP_WINDOW_MS = 20_000L

        // v4 oq rang darajalari (qorong'i natija ekrani). const emas — .toInt()
        // compile-time konstanta hisoblanmaydi.
        private val WHITE = 0xFFFFFFFF.toInt()
        private val WHITE_82 = 0xD1FFFFFF.toInt()
        private val WHITE_70 = 0xB3FFFFFF.toInt()
        private val WHITE_60 = 0x99FFFFFF.toInt()

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

        // v4 halqa konfiguratsiyasi — design: stroke 6, track rgba(255,255,255,.10), ring #EF5D72.
        binding.ringScan.strokeWidthDp = 6f
        binding.ringScan.trackColor = 0x1AFFFFFF
        binding.ringScan.ringColor = 0xFFEF5D72.toInt()
        setScanProgress(0)

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
        progressAnimator?.cancel()
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
        // Avvalgi natija qizil fon qoldirgan bo'lishi mumkin (onNewIntent) — qayta tiklash.
        binding.root.setBackgroundResource(R.drawable.kq4_scan_bg)
        binding.layoutScanning.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        AnimationHelper.fadeIn(binding.layoutScanning, duration = 300)
        AnimationHelper.pulse(binding.scanPulse, duration = 1000, repeat = true)
        startProgressAnimation()

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
            finishScanProgress()
            if (!isFinishing && !isDestroyed) presentResult(result)
        }
    }

    /**
     * Skan davomida halqa 0→90% «yurib turadi» (real skan tugashini bildirmaydi),
     * natija kelganda finishScanProgress() 100% ga yetkazadi. Shu tarzda progress
     * real skan davomiyligiga bog'lanadi: tez skan — tez 100%, uzun skan — halqa kutadi.
     */
    private fun startProgressAnimation() {
        progressAnimator?.cancel()
        setScanProgress(0)
        progressAnimator = ValueAnimator.ofInt(0, 90).apply {
            duration = 2600
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { a ->
                if (!isFinishing && !isDestroyed) setScanProgress(a.animatedValue as Int)
            }
            start()
        }
    }

    private fun finishScanProgress() {
        progressAnimator?.cancel()
        progressAnimator = null
        try { setScanProgress(100) } catch (_: Throwable) {}
    }

    /** % matni + halqa + 4 qadam holatini bitta joydan yangilaydi. */
    private fun setScanProgress(p: Int) {
        binding.tvProgress.text = getString(R.string.kq4_as_percent, p)
        binding.ringScan.setValue(p.toFloat(), animate = false)
        // design (screens3.jsx): done = floor(p/25) + (p%25 > 12 ? 1 : 0)
        val done = if (p >= 100) 4 else ((p / 25) + if (p % 25 > 12) 1 else 0).coerceIn(0, 4)
        applySteps(done)
    }

    private fun applySteps(done: Int) {
        val rows = arrayOf(binding.stepRow1, binding.stepRow2, binding.stepRow3, binding.stepRow4)
        val circles = arrayOf(binding.stepCircle1, binding.stepCircle2, binding.stepCircle3, binding.stepCircle4)
        val checks = arrayOf(binding.stepCheck1, binding.stepCheck2, binding.stepCheck3, binding.stepCheck4)
        val dots = arrayOf(binding.stepDot1, binding.stepDot2, binding.stepDot3, binding.stepDot4)
        for (i in 0..3) {
            val isDone = i < done
            rows[i].alpha = if (isDone) 1f else 0.4f
            circles[i].setBackgroundResource(
                if (isDone) R.drawable.kq4_autoscan_step_done else R.drawable.kq4_autoscan_step_todo
            )
            checks[i].visibility = if (isDone) View.VISIBLE else View.GONE
            dots[i].visibility = if (isDone) View.GONE else View.VISIBLE
        }
    }

    private fun presentResult(result: ScanResult?) {
        try {
            lastResult = result
            try { binding.layoutScanning.visibility = View.GONE } catch (_: Throwable) {}
            try { binding.layoutResult.visibility = View.VISIBLE } catch (_: Throwable) {}
            try { AnimationHelper.bounce(binding.cardResult, duration = 600) } catch (_: Throwable) {}
            // «Ishonaman» havolasi faqat SHUBHALI'da — har render oldidan reset.
            try { binding.tvTrustHint.visibility = View.GONE } catch (_: Throwable) {}

            // Fon almashinuvi — design §AutoScan result: DANGER qizil nur, SAFE odatiy fon,
            // qolganlari qorong'i skan foni.
            when (result?.verdict) {
                ScanResult.Verdict.DANGER -> {
                    try { binding.root.setBackgroundResource(R.drawable.kq4_autoscan_result_bg) } catch (_: Throwable) {}
                    safeShow { showDangerousResult(result) }
                }
                ScanResult.Verdict.SUSPICIOUS -> {
                    try { binding.root.setBackgroundResource(R.drawable.kq4_scan_bg) } catch (_: Throwable) {}
                    safeShow { showSuspiciousResult(result) }
                }
                ScanResult.Verdict.SAFE -> safeShow { showSafeResult() }
                // result == null — skan ISTISNO bilan tugadi (fayl o'qilmadi / parse xatosi).
                // ANTIVIRUS ASOSIY QOIDASI: o'qib bo'lmagan fayl HECH QACHON "xavfsiz" emas.
                // Avval `else -> showSafeResult()` edi — bu soxta-XAVFSIZ buggi: skan crash
                // bo'lsa virus "✓ Fayl xavfsiz" deb ko'rsatilardi (Tekshirildi: 0). Endi → shubhali.
                null -> {
                    try { binding.root.setBackgroundResource(R.drawable.kq4_scan_bg) } catch (_: Throwable) {}
                    safeShow { showUnscannableResult() }
                }
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

    /**
     * Qorong'i natija «хром»и: oq sarlavha/matn, qizil danger tugma + ghost «Batafsil»,
     * qorong'i X tugmasi. SAFE keyin onNewIntent bilan qayta kirilganda ham
     * hammasi qayta to'g'ri bo'yaladi.
     */
    private fun applyDarkChrome() {
        binding.tvResultTitle.setTextColor(WHITE)
        binding.tvResultMessage.setTextColor(WHITE_82)
        binding.btnDelete.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_danger))
        binding.btnDelete.setTextColor(WHITE)
        binding.btnDelete.iconTint = ColorStateList.valueOf(WHITE)
        binding.btnDelete.icon = AppCompatResources.getDrawable(this, R.drawable.ic4_trash)
        binding.btnDelete.text = getString(R.string.kq_delete_now)
        binding.btnDetails.setBackgroundResource(R.drawable.kq4_autoscan_btn_ghost)
        binding.btnDetails.setTextColor(WHITE)
        binding.btnDetails.text = getString(R.string.kq_details)
        binding.btnClose.setBackgroundResource(R.drawable.kq4_autoscan_iconbtn)
        binding.btnClose.imageTintList = ColorStateList.valueOf(WHITE_70)
        binding.tvDeleteHint.setTextColor(WHITE_60)
        binding.resultShield.imageTintList = ColorStateList.valueOf(WHITE)
    }

    /** SAFE natija «хром»и — odatiy (yorug'/tema) fon, primary tugma, ink matnlar. */
    private fun applySafeChrome() {
        binding.root.setBackgroundResource(R.color.kq_bg)
        binding.tvResultTitle.setTextColor(getColor(R.color.kq_ink))
        binding.tvResultMessage.setTextColor(getColor(R.color.kq_ink_2))
        binding.btnDelete.backgroundTintList = ContextCompat.getColorStateList(this, R.color.kq_primary)
        binding.btnDelete.setTextColor(getColor(R.color.kq_on_primary))
        binding.btnDelete.icon = null
        binding.btnDetails.setBackgroundResource(R.drawable.kq4_btn_soft)
        binding.btnDetails.setTextColor(getColor(R.color.kq_ink))
        binding.btnDetails.text = getString(R.string.kq4_as_close)
        binding.btnDetails.setOnClickListener { finish() }
        binding.btnClose.setBackgroundResource(R.drawable.kq4_icon_btn)
        binding.btnClose.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_ink))
    }

    /** «Batafsil» — to'liq natija ekrani (ScanResultActivity) real natija bilan. */
    private fun openDetails() {
        val res = lastResult ?: return
        val path = apkPath ?: return
        try {
            startActivity(
                ScanResultActivity.intent(this, path, res, installedPkg, apkIsCopy, originUri)
            )
        } catch (e: Throwable) {
            android.util.Log.e("AutoScanActivity", "openDetails failed", e)
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
     * Oq-shaffof kartani real ma'lumot bilan to'ldiradi: fayl nomi, «manba · hajm»
     * qatori va «Bu fayl nima qiladi» ro'yxati (real ruxsatlar/signaturalardan,
     * data.jsx DOES uslubida inson tilida).
     */
    private fun populateInfoCard(result: ScanResult) {
        val file = apkPath?.let { File(it) }
        binding.tvAsScanFileName.text =
            file?.name?.takeIf { it.isNotBlank() } ?: (apkName ?: "?")

        val sizeText = file?.takeIf { it.exists() }?.let {
            android.text.format.Formatter.formatShortFileSize(this, it.length())
        }
        val src = sourceLabel().takeIf { it != "—" }
        binding.tvAsScanSource.text =
            listOfNotNull(src, sizeText).joinToString(" · ").ifBlank { "—" }

        val rows = arrayOf(binding.doesRow1, binding.doesRow2, binding.doesRow3, binding.doesRow4)
        val icons = arrayOf(binding.doesIcon1, binding.doesIcon2, binding.doesIcon3, binding.doesIcon4)
        val texts = arrayOf(binding.doesText1, binding.doesText2, binding.doesText3, binding.doesText4)
        val entries = doesEntries(result)
        for (i in 0..3) {
            if (i < entries.size) {
                rows[i].visibility = View.VISIBLE
                icons[i].setImageResource(entries[i].first)
                texts[i].text = entries[i].second
            } else {
                rows[i].visibility = View.GONE
            }
        }
        val hasRows = entries.isNotEmpty()
        binding.tvDoesLabel.visibility = if (hasRows) View.VISIBLE else View.GONE
        binding.viewDoesDivider.visibility = if (hasRows) View.VISIBLE else View.GONE
    }

    /**
     * Real skan belgilari (ruxsatlar + reason + signaturalar) → inson tilidagi
     * «nima qiladi» qatorlari (maks 4). Hech narsa mos kelmasa — skanerning
     * o'z izohlarini (details) ko'rsatamiz, ya'ni har doim real ma'lumot.
     */
    private fun doesEntries(result: ScanResult): List<Pair<Int, CharSequence>> {
        val perms = result.dangerousPermissions.joinToString(" ").uppercase()
        val hay = (result.reason + " " +
                result.malwareSignatures.joinToString(" ") + " " +
                result.details.joinToString(" ")).lowercase()

        val picked = mutableListOf<Pair<Int, Int>>()
        fun add(icon: Int, res: Int) {
            if (picked.none { it.second == res }) picked.add(icon to res)
        }
        if ("SMS" in perms || "NOTIFICATION_LISTENER" in perms || "sms" in hay)
            add(R.drawable.ic4_message, R.string.kq4_as_does_sms)
        if ("SYSTEM_ALERT_WINDOW" in perms || "overlay" in hay || "bank" in hay || "ajina" in hay || "phish" in hay)
            add(R.drawable.ic4_card, R.string.kq4_as_does_bankpass)
        if ("ACCESSIBILITY" in perms || "accessibility" in hay)
            add(R.drawable.ic4_eye, R.string.kq4_as_does_screen)
        if ("INSTALL_PACKAGES" in perms || "dropper" in hay)
            add(R.drawable.ic4_download, R.string.kq4_as_does_download)
        if ("DEVICE_ADMIN" in perms || "yashir" in hay || "hidden" in hay)
            add(R.drawable.ic4_lock, R.string.kq4_as_does_hide)
        if ("c2" in hay || "masofa" in hay || "remote" in hay)
            add(R.drawable.ic4_globe, R.string.kq4_as_does_remote)
        if ("CONTACTS" in perms)
            add(R.drawable.ic4_user, R.string.kq4_as_does_contacts)
        if ("CALL" in perms)
            add(R.drawable.ic4_phone, R.string.kq4_as_does_calls)

        val mapped: List<Pair<Int, CharSequence>> =
            picked.take(4).map { it.first to (getString(it.second) as CharSequence) }
        if (mapped.isNotEmpty()) return mapped
        return result.details.take(3).map { R.drawable.ic4_alert to (it as CharSequence) }
    }

    /**
     * Tahdid tavsifi — data.jsx THREATS.short shablonlaridan real turga qarab
     * tanlanadi (bank o'g'risi / dropper / SMS o'g'risi / umumiy).
     */
    private fun threatShortText(result: ScanResult): String {
        val hay = (result.reason + " " +
                result.malwareSignatures.joinToString(" ") + " " +
                result.details.joinToString(" ")).lowercase()
        val perms = result.dangerousPermissions.joinToString(" ").uppercase()
        return when {
            "ajina" in hay || "bank" in hay || "overlay" in hay || "phish" in hay ->
                getString(R.string.kq4_as_short_bank)
            "dropper" in hay || "INSTALL_PACKAGES" in perms ->
                getString(R.string.kq4_as_short_dropper)
            "sms" in hay || "SMS" in perms ->
                getString(R.string.kq4_as_short_sms)
            else -> getString(R.string.kq4_as_short_generic)
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
        applyDarkChrome()
        binding.resultBadge.setBackgroundResource(R.drawable.kq4_circle_danger)
        binding.resultShield.setImageResource(R.drawable.ic4_alert)
        binding.haloOuter.visibility = View.VISIBLE
        binding.haloInner.visibility = View.VISIBLE
        binding.dangerDetails.visibility = View.VISIBLE
        populateInfoCard(result)

        binding.tvResultTitle.text = getString(R.string.kq4_as_danger_title)
        // Tafsilotlar (result.details) endi kartada + «Batafsil» ekranida — bu yerda
        // dizayndagi qisqa, jargonsiz tushuntirish.
        binding.tvResultMessage.text = threatShortText(result)

        binding.btnDelete.visibility = View.VISIBLE
        // O'rnatilgan ilova bo'lsa — tugma "Ilovani o'chirish" (uninstall), fayl emas.
        if (installedPkg != null) binding.btnDelete.text = getString(R.string.uninstall_app)
        binding.btnDetails.visibility = View.VISIBLE
        binding.btnDetails.setOnClickListener { openDetails() }
        binding.tvDeleteHint.visibility = View.VISIBLE
        // Превращаем "подсказку" в кликабельный share — юзер одним тапом
        // отправляет друзьям предупреждение "этот APK — вирус, не ставьте".
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.DANGER) }

        // Анимация встряхивания для опасности
        AnimationHelper.shake(binding.cardResult, duration = 500)

        // Avtomatik o'chirish rejimi — Sozlamalardan keladi:
        //  "delete" → real obratniy otschot (soat qatori) tugagach deleteApk()
        //             (foydalanuvchi btnClose orqali to'xtata oladi).
        //  "ask"    → eski xulq-atvor: btnDelete pulsatsiya, kutamiz.
        //  "warn"   → faqat ogohlantirish, pulsatsiya yo'q (kam agressiv).
        val deleteMode = try { Config.getAutoDeleteMode(this) } catch (_: Throwable) { "ask" }
        when (deleteMode) {
            "delete" -> startAutoDeleteCountdown()
            "warn" -> {
                // Tugma ko'rinadi lekin pulsatsiyasiz.
                binding.rowAutoDelete.visibility = View.GONE
            }
            else -> {
                binding.rowAutoDelete.visibility = View.GONE
                AnimationHelper.pulse(binding.btnDelete, duration = 1000, repeat = true)
            }
        }

        sendNotification("🔴 Virus topildi!", "$apkName fayli xavfli — o'chirish tavsiya etiladi", true)
    }

    /**
     * Real avto-o'chirish hisoblagichi (design: «N soniyadan keyin avtomatik
     * o'chiriladi» → «O'chirilmoqda…»). handler onDestroy/onNewIntent'da tozalanadi,
     * shuning uchun yopilgan oynada otmaydi.
     */
    private fun startAutoDeleteCountdown(seconds: Int = 3) {
        binding.rowAutoDelete.visibility = View.VISIBLE
        binding.tvAutoDelete.text = getString(R.string.kq4_as_autodelete_in, seconds)
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                left--
                if (left <= 0) {
                    binding.tvAutoDelete.text = getString(R.string.kq4_as_deleting)
                    deleteApk()
                } else {
                    binding.tvAutoDelete.text = getString(R.string.kq4_as_autodelete_in, left)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private fun showSuspiciousResult(result: ScanResult) {
        applyDarkChrome()
        binding.resultBadge.setBackgroundResource(R.drawable.kq4_autoscan_circle_warn)
        binding.resultShield.setImageResource(R.drawable.ic4_alert)
        binding.haloOuter.visibility = View.GONE
        binding.haloInner.visibility = View.GONE
        binding.dangerDetails.visibility = View.VISIBLE
        binding.rowAutoDelete.visibility = View.GONE
        populateInfoCard(result)

        binding.tvResultTitle.text = getString(R.string.autoscan_suspicious_title)

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
        binding.btnDetails.visibility = View.VISIBLE
        binding.btnDetails.setOnClickListener { openDetails() }
        binding.tvDeleteHint.visibility = View.VISIBLE
        binding.tvDeleteHint.text = getString(R.string.share_result)
        binding.tvDeleteHint.setOnClickListener { shareScanResult(ScanResult.Verdict.SUSPICIOUS) }

        // «Bu faylga ishonaman» — oq ro'yxatga qo'shish (faqat SHUBHALI'da; DANGER'da BO'LMAYDI).
        binding.tvTrustHint.visibility = View.VISIBLE
        binding.tvTrustHint.text = getString(R.string.kq4_trust_mark)
        binding.tvTrustHint.setOnClickListener { confirmTrustFile() }

        sendNotification("🟠 Shubhali fayl", "$apkName faylida shubhali belgilar bor", false)
    }

    /**
     * Foydalanuvchi SHUBHALI faylga "ishonaman" dedi — tasdiq so'raymiz, so'ng faylning
     * SHA-256 + (package, imzo-cert) juftligini [UserWhitelist] ga yozamiz. Hash/cert fon
     * thread'da hisoblanadi (katta faylda UI qotmasin). DANGER'da bu havola CHIQMAYDI.
     */
    private fun confirmTrustFile() {
        val path = apkPath ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.kq4_trust_confirm_title)
            .setMessage(R.string.kq4_trust_confirm_msg)
            .setPositiveButton(R.string.kq4_trust_confirm_yes) { _, _ ->
                Thread {
                    val label = apkName ?: File(path).name
                    val sha = try { CertUtil.apkFileSha256(path) } catch (_: Throwable) { null }
                    val cert = try { CertUtil.fingerprintSha256(this, path) } catch (_: Throwable) { null }
                    val pkg = try {
                        packageManager.getPackageArchiveInfo(path, 0)?.packageName
                    } catch (_: Throwable) { null }
                    val okFile = UserWhitelist.addFile(this, sha, label)
                    val okApp = if (pkg != null && cert != null) {
                        UserWhitelist.addApp(this, pkg, cert, label)
                    } else false
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (okFile || okApp) {
                            android.widget.Toast.makeText(
                                this, R.string.kq4_trust_added, android.widget.Toast.LENGTH_LONG
                            ).show()
                            finish()
                        } else {
                            android.widget.Toast.makeText(
                                this, R.string.kq4_trust_failed, android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(R.string.kq4_btn_later, null)
            .show()
    }

    /**
     * Skan ISTISNO bilan tugadi (ApkScanner.scan() throw qildi yoki natija null).
     * ANTIVIRUS ASOSIY QOIDASI: o'qib bo'lmagan / xato bilan tugagan fayl HECH QACHON
     * "xavfsiz" deb ko'rsatilmaydi — har doim shubhali deb hisoblanadi va o'chirish
     * tavsiya etiladi. Avval bunday holatda showSafeResult() chaqirilardi (soxta-XAVFSIZ).
     */
    private fun showUnscannableResult() {
        applyDarkChrome()
        binding.resultBadge.setBackgroundResource(R.drawable.kq4_autoscan_circle_warn)
        binding.resultShield.setImageResource(R.drawable.ic4_alert)
        binding.haloOuter.visibility = View.GONE
        binding.haloInner.visibility = View.GONE
        binding.dangerDetails.visibility = View.GONE
        binding.rowAutoDelete.visibility = View.GONE
        // Skan natijasi yo'q — «Batafsil» ekraniga uzatadigan ma'lumot ham yo'q.
        binding.btnDetails.visibility = View.GONE

        binding.tvResultTitle.text = getString(R.string.autoscan_unscannable_title)

        val context = sourceHint()
        binding.tvResultMessage.text = buildString {
            append(getString(R.string.autoscan_unscannable_body))
            if (context != null) {
                append("\n\n")
                append(context)
            }
        }

        // UX-05: avval lokal `installedPkg` MAYDONNI (this.installedPkg) soyalab qo'yardi — tugma
        // matni "Ilovani o'chirish" bo'lar, lekin onClick'dagi deleteApk() MAYDONNI o'qib (null edi)
        // faqat faylni o'chirib, O'RNATILGAN (eng shubhali!) ilovani qoldirardi. Endi maydonni
        // aniqlangan paket bilan to'ldiramiz, shunda deleteApk() haqiqatan uninstall qiladi.
        val detectedPkg = installedPackageForApk(apkPath)
        if (detectedPkg != null) installedPkg = detectedPkg
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.isEnabled = true
        binding.btnDelete.text =
            if (detectedPkg != null) getString(R.string.uninstall_app)
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
        try { binding.root.setBackgroundResource(R.drawable.kq4_autoscan_result_bg) } catch (_: Throwable) {}
        try { AnimationHelper.bounce(binding.cardResult, duration = 600) } catch (_: Throwable) {}

        applyDarkChrome()
        binding.resultBadge.setBackgroundResource(R.drawable.kq4_circle_danger)
        binding.resultShield.setImageResource(R.drawable.ic4_alert)
        binding.haloOuter.visibility = View.VISIBLE
        binding.haloInner.visibility = View.VISIBLE
        binding.dangerDetails.visibility = View.GONE
        binding.rowAutoDelete.visibility = View.GONE
        binding.btnDetails.visibility = View.GONE

        binding.tvResultTitle.text = getString(R.string.kq4_as_handled_title)

        val reason = intent.getStringExtra("reason")?.takeIf { it.isNotBlank() }
        val ctxHint = sourceHint()
        binding.tvResultMessage.text = buildString {
            append(getString(R.string.kq4_as_handled_body, apkName ?: "?"))
            if (reason != null) {
                append("\n\n")
                append(getString(R.string.kq4_as_handled_reason, reason.take(300)))
            }
            append("\n\n")
            append(getString(R.string.kq4_as_handled_restore))
            if (ctxHint != null) {
                append("\n\n")
                append(ctxHint)
            }
        }

        // O'chirish tugmasi KERAK EMAS — fayl allaqachon yo'q. "Tushunarli" → oynani yopish.
        binding.btnDelete.visibility = View.VISIBLE
        binding.btnDelete.isEnabled = true
        binding.btnDelete.icon = null
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
        // Yashil natija — design: odatiy fon, kq4_circle_safe_soft doira,
        // ic4_check_circle (kq_safe), Primary tugma. Yashil HECH QACHON kq_primary'da emas.
        applySafeChrome()
        binding.resultBadge.setBackgroundResource(R.drawable.kq4_circle_safe_soft)
        binding.resultShield.setImageResource(R.drawable.ic4_check_circle)
        binding.resultShield.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_safe))
        binding.haloOuter.visibility = View.GONE
        binding.haloInner.visibility = View.GONE
        binding.dangerDetails.visibility = View.GONE
        binding.rowAutoDelete.visibility = View.GONE
        binding.btnDetails.visibility = View.VISIBLE

        binding.tvResultTitle.text = getString(R.string.kq4_as_safe_title)

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
            // Bu fayl XAVFSIZ deb topildi va foydalanuvchi o'rnatishni o'zi tanladi —
            // jonli qalqon (InstallShieldService) shu paketni o'tkazishi uchun TASDIQ yozamiz.
            try {
                val pkg = packageManager.getPackageArchiveInfo(path, 0)?.packageName
                InstallApproval.approve(this, pkg)
            } catch (_: Throwable) { /* tasdiqsiz ham o'rnatishni davom ettiramiz */ }

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
        // Virus «Qurilma administratori» huquqini olgan bo'lsa — Android uni o'chirtirmaydi.
        // Avval foydalanuvchini admin huquqini o'chirishga yo'naltiramiz, so'ng o'chiradi.
        if (DeviceAdminUtil.isActiveAdmin(this, pkg)) {
            try {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.devadmin_block_title)
                    .setMessage(R.string.devadmin_block_msg)
                    .setPositiveButton(R.string.kq4_prot_autostart_open) { _, _ ->
                        DeviceAdminUtil.openDeviceAdminSettings(this)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: Throwable) {
                DeviceAdminUtil.openDeviceAdminSettings(this)
            }
            binding.btnDelete.isEnabled = true
            return
        }
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
                    // Файл в /Android/data/<owner>/ — НИКТО (даже с MANAGE_EXTERNAL_STORAGE) удалить
                    // не может, это аппаратное ограничение Android. Но он БЕЗОПАСЕН: пока не установлен,
                    // не вредит, а установку мы блокируем (ShareReceiver + PackageInstallReceiver).
                    // Вместо тупикового "OK" даём одну кнопку "Открыть <owner>" для ручной очистки.
                    val owner = ownerAppLabel(result.ownerPackage)
                    binding.tvResultMessage.text = getString(R.string.sandboxed_inert_msg, owner)
                    binding.btnDelete.text = getString(R.string.sandboxed_open_owner, owner)
                    binding.btnDelete.isEnabled = true
                    binding.btnDelete.setOnClickListener {
                        // Ikki yo'l: egasi ilovani ochish YOKI Shizuku bilan haqiqatan o'chirish.
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.sandboxed_dialog_title))
                            .setMessage(getString(R.string.sandboxed_inert_msg, owner))
                            .setPositiveButton(getString(R.string.sandboxed_open_owner, owner)) { _, _ ->
                                openOwnerApp(result.ownerPackage)
                            }
                            .setNeutralButton(getString(R.string.shizuku_real_delete)) { _, _ ->
                                ShizukuSetup.promptRealDelete(this, path) { deleted ->
                                    if (deleted) {
                                        reportDelete("Shizuku o'chirdi", path)
                                        onFileSuccessfullyDeleted()
                                    } else {
                                        android.widget.Toast.makeText(
                                            this, getString(R.string.shizuku_not_deleted),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
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

    /** Egasi ilovaning inson o'qiy oladigan nomi (Telegram, WhatsApp...). Topilmasa — paket nomi. */
    private fun ownerAppLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    /**
     * Egasi ilovani ochadi (foydalanuvchi u yerda faylni/keshni tozalashi uchun). Sandbox
     * (/Android/data/<owner>/) faylini Android boshqa hech kimga o'chirtirmaydi, lekin u
     * o'rnatilmaguncha xavfsiz — bu tugma faqat tozalashni qulaylashtiradi. Ishga tushirish
     * intent'i bo'lmasa — ilova sozlamalari (Xotira → Tozalash) ekraniga o'tamiz.
     */
    private fun openOwnerApp(pkg: String) {
        // Ilovaga o'tishdan oldin qanday o'chirishni ko'rsatamiz.
        android.widget.Toast.makeText(
            this,
            getString(R.string.sandboxed_open_owner_hint, ownerAppLabel(pkg)),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            }
        } catch (_: Throwable) {}
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Throwable) {
            android.util.Log.w("AutoScanActivity", "openOwnerApp failed", e)
        }
    }

    /** Вызывается из всех точек, где удаление подтверждено (как сразу, так и после consent). */
    private fun onFileSuccessfullyDeleted() {
        // Agar biz faqat keshdagi NUSXAni o'chirgan bo'lsak (share/content URI orqali kelgan),
        // asl fayl hali manba ilovasida turishi mumkin. Avval uni ham o'chirishga urinamiz;
        // bo'lmasa — "o'chirildi / xavfsiz" deb YOLG'ON aytmaymiz, balki halol ogohlantirish
        // ko'rsatamiz (asl faylni o'sha ilovada qo'lda o'chirish kerak). Bu — antivirusning
        // eng muhim qoidasi: hech qachon soxta muvaffaqiyat ko'rsatmaslik.
        if (isScratchCopy() && !tryDeleteOrigin()) {
            binding.tvResultMessage.text = getString(R.string.autoscan_copy_deleted_original_remains)
            binding.rowAutoDelete.visibility = View.GONE
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnDelete.isEnabled = true
            binding.btnDelete.icon = null
            // Manba ilovasi aniqlansa (Telegram/WhatsApp) — bir tap bilan o'sha ilovani ochamiz,
            // foydalanuvchi asl faylni/keshni o'sha yerda o'chiradi. Aniqlanmasa — oddiy "OK".
            val srcPkg = originSourcePkg()
            if (srcPkg != null) {
                binding.btnDelete.text = getString(R.string.open_source_app_t)
                binding.btnDelete.setOnClickListener { InstallProtectionGuide.openClearCache(this, srcPkg); finish() }
            } else {
                binding.btnDelete.text = getString(R.string.btn_ok)
                binding.btnDelete.setOnClickListener { finish() }
            }
            binding.tvDeleteHint.visibility = View.VISIBLE
            binding.tvDeleteHint.text = getString(R.string.share_result)
            binding.tvDeleteHint.setOnClickListener {
                shareScanResult(lastResult?.verdict ?: ScanResult.Verdict.DANGER)
            }
            reportDelete("Nusxa o'chirildi, asl fayl manba ilovasida qoldi", apkPath, extra = originUri)
            return
        }

        binding.tvResultMessage.text = getString(R.string.delete_success)
        binding.btnDelete.visibility = View.GONE
        binding.rowAutoDelete.visibility = View.GONE
        binding.tvDeleteHint.text = getString(R.string.autoscan_delete_hint_done)

        // "Bloklandi" hisoblagichi skan paytida (ApkScanner.scan, DANGER) bir marta oshadi;
        // bu yerda qayta oshirmaymiz, aks holda bitta tahdid ikki marta sanalardi.

        handler.postDelayed({ if (!isFinishing) finish() }, 2000)
    }

    /**
     * Skanlangan fayl bizning kesh ichidagi vaqtinchalik NUSXAmi? ShareReceiver content
     * URI'ni cacheDir/shared ga ko'chirib beradi — bunda asl fayl boshqa joyda (Telegram
     * va h.k.) bo'ladi, biz esa faqat nusxani o'chira olamiz.
     */
    private fun isScratchCopy(): Boolean {
        if (apkIsCopy) return true
        val p = apkPath ?: return false
        return try {
            p.startsWith(cacheDir.absolutePath) || p.contains("/cache/")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Asl manba faylini content URI orqali o'chirishga urinish (eng yaxshi imkoniyat).
     * Fayl-menejer ulashgan MediaStore fayli uchun ishlashi mumkin; Telegram singari
     * faqat-o'qish grant berganda — ishlamaydi va false qaytaradi (shunda halol
     * ogohlantirish chiqaramiz). HECH QACHON istisno bilan qulamaydi.
     */
    private fun tryDeleteOrigin(): Boolean {
        val raw = originUri ?: return false
        return try {
            val u = Uri.parse(raw)
            val ok = if (android.provider.DocumentsContract.isDocumentUri(this, u)) {
                android.provider.DocumentsContract.deleteDocument(contentResolver, u)
            } else {
                contentResolver.delete(u, null, null) > 0
            }
            if (ok) reportDelete("Asl manba URI orqali o'chirildi", apkPath, extra = raw)
            ok
        } catch (e: Throwable) {
            android.util.Log.w("AutoScanActivity", "tryDeleteOrigin failed", e)
            false
        }
    }

    /**
     * Asl manba ilovasini (Telegram/WhatsApp) content URI authority'sidan aniqlaydi —
     * "asl faylni o'sha yerda o'ching" tugmasi o'sha ilovani ochishi uchun. Aniqlanmasa null.
     */
    private fun originSourcePkg(): String? {
        val auth = try { Uri.parse(originUri ?: return null).authority } catch (_: Throwable) { null }
            ?: return null
        val a = auth.lowercase()
        return when {
            a.contains("telegram") && a.contains("plus") -> "org.telegram.plus"
            a.contains("telegram") -> "org.telegram.messenger"
            a.contains("whatsapp") -> "com.whatsapp"
            else -> null
        }
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

            val channelId = "uzguard_scan"

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
                val prefs = getSharedPreferences("uzguard_stats", Context.MODE_PRIVATE)
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
