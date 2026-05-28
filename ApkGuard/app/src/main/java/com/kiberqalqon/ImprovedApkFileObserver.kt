package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import java.io.File

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
    private val events = MutableSharedFlow<File>(extraBufferCapacity = 128)

    init {
        scope.launch(Dispatchers.IO) {
            events
                .filter { it.extension.equals("apk", ignoreCase = true) }
                .debounce(700) // Собираем события CREATE/MOVED_TO/CLOSE_WRITE
                .collect { file ->
                    Log.d(TAG, "Processing file: ${file.name}")
                    if (waitUntilStable(file)) {
                        Log.d(TAG, "File is stable: ${file.name}")
                        onApkReady(file)
                    } else {
                        Log.d(TAG, "File is not stable: ${file.name}")
                    }
                }
        }
    }

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
        events.tryEmit(file)
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
        repeat(8) { attempt ->
            val currentSize = file.length()
            Log.d(TAG, "Attempt $attempt: size = $currentSize")
            
            if (currentSize > 0 && currentSize == lastSize) {
                Log.d(TAG, "File is stable at size: $currentSize")
                return true
            }
            
            lastSize = currentSize
            delay(250)
        }
        
        val finalSize = file.length()
        Log.d(TAG, "Final check: size = $finalSize")
        return finalSize > 0
    }

    /**
     * Fayl tayyor - popup ochish strategiyasi.
     *
     * Android 10+ da fon'dan startActivity() JIM ishlamaydi (exception ham
     * tashlamaydi). Shuning uchun:
     *   1. Agar overlay ruxsati bor (SYSTEM_ALERT_WINDOW) — to'g'ridan ochamiz
     *   2. Aks holda — full-screen-intent notification chiqaramiz (lock screen'da
     *      ham faollashadi, telefon ochilsa Activity avtomatik ochiladi)
     *
     * Eski xato: faqat startActivity sinardi, exception bo'lmasa fallback ham yo'q,
     * shuning uchun foydalanuvchi hech nima ko'rmasdi.
     */
    private fun onApkReady(file: File) {
        try {
            TelemetryReporter.reportDownloadDetected(context, file.absolutePath, file.length())
        } catch (e: Throwable) {
            Log.w(TAG, "telemetry download_detected failed", e)
        }

        val canStartActivity = canLaunchActivityFromBackground(context)
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
