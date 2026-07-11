package com.uzguard

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Улучшенный FileObserver с debounce и waitUntilStable
 * Надёжно ловит новые APK файлы
 */
class ImprovedApkFileObserver(
    private val context: Context,
    private val watchDir: File,
    private val scope: CoroutineScope
) : FileObserver(watchDir.absolutePath, CREATE or MOVED_TO or CLOSE_WRITE) {

    private val TAG = "ImprovedApkObserver"

    // Per-file debounce: har bir APK yo'li uchun ALOHIDA 700ms stabilizatsiya taymeri.
    // Ilgari bitta global .debounce(700) ishlatilardi — u faqat OXIRGI faylni qoldirardi,
    // shuning uchun bitta papkaga 700ms ichida bir nechta APK tushsa (Telegram/WhatsApp
    // ketma-ket yuklab bo'lsa yoki zararli APK boshqa fayl bilan birga kelsa), oldingilari
    // JIM tashlab yuborilardi va real-time skan/popup/telemetriya olmasdi. Endi har bir yo'l
    // mustaqil ishlanadi.
    private val pendingJobs = ConcurrentHashMap<String, Job>()

    override fun onEvent(event: Int, path: String?) {
        if (path.isNullOrBlank()) return

        val eventName = when (event) {
            CREATE -> "CREATE"
            MOVED_TO -> "MOVED_TO"
            CLOSE_WRITE -> "CLOSE_WRITE"
            else -> "OTHER"
        }

        Log.d(TAG, "Event: $eventName, path: $path")

        val file = File(watchDir, path)
        if (!file.extension.equals("apk", ignoreCase = true)) return
        schedule(file)
    }

    /**
     * Shu YO'L uchun 700ms stabilizatsiya taymerini qayta ishga tushiradi (per-file debounce).
     * Bir xil faylning bir nechta CREATE/MOVED_TO/CLOSE_WRITE hodisalari birlashtiriladi,
     * lekin TURLI fayllar bir-birini almashtirmaydi — har biri o'z jarayonida qayta ishlanadi.
     */
    private fun schedule(file: File) {
        val key = file.absolutePath
        // Shu yo'l uchun kutayotgan eski taymerni bekor qilamiz (faqat SHU faylning oldingi hodisasi).
        pendingJobs.remove(key)?.cancel()
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO) {
            try {
                delay(700) // Собираем события CREATE/MOVED_TO/CLOSE_WRITE (per-file)
                Log.d(TAG, "Processing file: ${file.name}")
                if (waitUntilStable(file)) {
                    Log.d(TAG, "File is stable: ${file.name}")
                    onApkReady(file)
                } else {
                    Log.d(TAG, "File is not stable: ${file.name}")
                }
            } finally {
                // Faqat SHU job hali xaritada bo'lsa olib tashlaymiz (yangi taymer o'rnatilgan bo'lsa tegmaymiz).
                pendingJobs.remove(key, job)
            }
        }
        pendingJobs[key] = job
    }

    /**
     * Ждём пока файл полностью скачается
     */
    private suspend fun waitUntilStable(file: File): Boolean {
        if (!file.exists()) {
            Log.d(TAG, "File does not exist: ${file.name}")
            return false
        }
        
        var lastSize = -1L
        // ~5s (20×250ms): sekin ulanishda katta APK yuklanishiga ko'proq vaqt beramiz.
        repeat(20) { attempt ->
            val currentSize = file.length()
            Log.d(TAG, "Attempt $attempt: size = $currentSize")

            if (currentSize > 0 && currentSize == lastSize) {
                Log.d(TAG, "File is stable at size: $currentSize")
                return true
            }

            lastSize = currentSize
            delay(250)
        }

        // Belgilangan vaqt ichida hajm barqarorlashmadi — fayl HALI ham yozilyapti (o'sib boryapti).
        // Uni "tayyor" deb hisoblamaymiz: yarim/qirqilgan APK skan qilinsa noto'g'ri SAFE (false
        // negative) berishi mumkin. false qaytaramiz — yozish tugagach CLOSE_WRITE hodisasi qayta
        // ishga tushiradi.
        Log.d(TAG, "File never stabilized (still growing): ${file.name}")
        return false
    }

    /**
     * Fayl tayyor - popup ochish strategiyasi.
     *
     * Android 10+ da fon'dan startActivity() JIM ishlamaydi (exception ham
     * tashlamaydi). Shuning uchun:
     *   1. Ekran ochiq+qulfsiz VA overlay ruxsati bor (SYSTEM_ALERT_WINDOW) — to'g'ridan ochamiz
     *      (qulflangan ekranda MIUI to'g'ridan-to'g'ri launch'ni JIM bloklaydi — shu sabab gate)
     *   2. Aks holda — full-screen-intent notification chiqaramiz (lock screen'da
     *      ham faollashadi, telefon ochilsa Activity avtomatik ochiladi)
     *
     * Eski xato: faqat startActivity sinardi, exception bo'lmasa fallback ham yo'q,
     * shuning uchun foydalanuvchi hech nima ko'rmasdi.
     */
    private fun onApkReady(file: File) {
        // BG-02: foydalanuvchi fon himoyani o'chirgan bo'lsa — real-time observer ham JIM turishi
        // kerak (avval observer tumblerni umuman tekshirmasdi: "o'chirilgan" deganда ham yangi APK
        // popup ochib, telemetriya yuborardi).
        try {
            if (!Config.isBackgroundEnabled(context)) return
        } catch (_: Throwable) { /* Config o'qib bo'lmasa — davom etamiz */ }

        try {
            TelemetryReporter.reportDownloadDetected(context, file.absolutePath, file.length())
        } catch (e: Throwable) {
            Log.w(TAG, "telemetry download_detected failed", e)
        }

        // Ekran ochiq+qulfsiz BO'LSAGINA to'g'ridan-to'g'ri oyna — aks holda MIUI startActivity'ni
        // JIM bloklaydi (exception yo'q) va ilgari shunda hech narsa ko'rinmasdi. Endi qulflangan/
        // o'chiq ekranda notification (full-screen-intent) yo'liga o'tamiz (GuardWorker bilan bir xil).
        val canStartActivity = isScreenInteractiveAndUnlocked() &&
            canLaunchActivityFromBackground(context)
        Log.d(TAG, "APK ready: ${file.name}, canStartActivity=$canStartActivity")

        if (canStartActivity) {
            try {
                val intent = Intent(context, AutoScanActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("apk_path", file.absolutePath)
                    putExtra("apk_name", file.name)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "startActivity failed", e)
            }
        }

        // Notification with full-screen-intent — Android tizimi telefon ochilsa
        // Activity'ni avtomatik ochadi, qulflangan bo'lsa lock screen ustida ko'rsatadi.
        try {
            NotificationHelper.showScanNotification(context, file)
        } catch (e: Throwable) {
            Log.e(TAG, "Notification fallback failed", e)
        }
    }

    /**
     * Ekran ayni paytda ochiq (interactive) VA qulfdan chiqarilganmi? Faqat shu holatda
     * fon'dan startActivity() real ko'rinadi; aks holda (qulflangan/o'chiq) MIUI uni JIM
     * bloklaydi va biz notification (full-screen-intent) yo'liga o'tamiz.
     */
    private fun isScreenInteractiveAndUnlocked(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            pm.isInteractive && !km.isKeyguardLocked
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        /**
         * Android 10+ da fon'dan Activity ochish faqat quyidagi hollarda ishlaydi:
         *  - SYSTEM_ALERT_WINDOW ruxsati berilgan
         *  - Ilova oldingi planda
         *  - Lock screen ustidagi notification orqali (alohida yo'l)
         *
         * Pre-Android-10 da hech qanday cheklov yo'q.
         */
        fun canLaunchActivityFromBackground(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return true
            return try {
                android.provider.Settings.canDrawOverlays(context)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
