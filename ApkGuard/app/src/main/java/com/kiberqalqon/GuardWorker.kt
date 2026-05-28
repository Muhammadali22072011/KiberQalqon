package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

private const val TAG = "GuardWorker"

class GuardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!Config.isBackgroundEnabled(applicationContext)) {
                return Result.success()
            }
            
            val list = try {
                ApkScanner.findApkFiles(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error finding APK files", e)
                return Result.success()
            }
            
            var uploaded = 0
            val serverUrl = Config.getServerUrl(applicationContext)
            val uploadEnabled = Config.isUploadEnabled(applicationContext) && serverUrl.isNotBlank()

            // Проверяем максимум 10 файлов за раз
            for (item in list.take(10)) {
                try {
                    if (!item.file.exists()) continue
                    
                    val result = try {
                        ApkScanner.scan(applicationContext, item.file.absolutePath)
                    } catch (e: Exception) {
                        Log.w(TAG, "Scan error for ${item.name}", e)
                        continue
                    }
                    
                    // DANGER + foydalanuvchi "delete" rejimini tanlagan (default) → ekran qulflangan
                    // bo'lsa ham fonida darhol karantinga ko'chiramiz: AutoScanActivity'ning 2.5s
                    // auto-delete'i faqat ekran ochiq bo'lsa ishlaydi. Karantin xavfsiz: file
                    // filesDir/quarantine/ ga ketadi, 7 kun davomida Telegram orqali tiklash mumkin.
                    //
                    // SUSPICIOUS — hech qachon avto-o'chirilmaydi, faqat popup'da foydalanuvchi qaror qiladi.
                    val autoDelete = result.verdict == ScanResult.Verdict.DANGER &&
                        Config.getAutoDeleteMode(applicationContext) == "delete"

                    val shouldAlert = result.verdict == ScanResult.Verdict.DANGER ||
                        result.verdict == ScanResult.Verdict.SUSPICIOUS

                    if (autoDelete) {
                        // Avval upload (agar yoqilgan bo'lsa) — fayl quarantine'ga ketgach
                        // asl yo'l yo'qoladi, server'ga endi yuborib bo'lmaydi.
                        if (uploadEnabled) {
                            try {
                                val ok = ServerUpload.uploadApk(serverUrl, item.file, item.name)
                                if (ok) uploaded++
                            } catch (e: Exception) {
                                Log.w(TAG, "Upload error", e)
                            }
                        }

                        val q = Quarantine.quarantine(
                            applicationContext,
                            item.file,
                            verdict = "DANGER",
                            reason = result.reason
                        )
                        when (q) {
                            is Quarantine.Result.Ok -> {
                                Log.w(TAG, "Auto-quarantined: ${item.name} — ${result.reason}")
                                // "Bloklandi" skan paytida sanalgan (ApkScanner, DANGER) — bu yerda qayta emas.
                                try {
                                    TelemetryReporter.report(
                                        applicationContext,
                                        TelemetryReporter.Cat.DELETE,
                                        "🛡️ Avtomatik karantin:\n" +
                                            "📦 ${item.name}\n" +
                                            "Sabab: ${result.reason.take(300)}\n" +
                                            "(7 kun ichida tiklash mumkin)"
                                    )
                                } catch (_: Throwable) {}
                            }
                            is Quarantine.Result.Failed -> {
                                // Karantinga ko'chirib bo'lmadi (masalan, /Android/data/<pkg>/ sandbox)
                                // → eski xulq: popup ko'rsatamiz, foydalanuvchi qo'lda hal qiladi.
                                Log.w(TAG, "Auto-quarantine failed for ${item.name}: ${q.message}")
                                try {
                                    showAutoScanPopup(item.file.absolutePath, item.name)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not show popup", e)
                                }
                            }
                        }
                    } else if (shouldAlert) {
                        try {
                            showAutoScanPopup(item.file.absolutePath, item.name)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not show popup", e)
                        }

                        if (uploadEnabled && result.verdict == ScanResult.Verdict.DANGER) {
                            try {
                                val ok = ServerUpload.uploadApk(serverUrl, item.file, item.name)
                                if (ok) uploaded++
                            } catch (e: Exception) {
                                Log.w(TAG, "Upload error", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing ${item.name}", e)
                }
            }

            Log.d(TAG, "Background scan: ${list.size} APKs, uploaded=$uploaded")
            Result.success(workDataOf(
                "scanned" to list.size,
                "uploaded" to uploaded
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in background worker", e)
            Result.failure()
        }
    }
    
    /**
     * Popup ochish. Android 10+ da fon'dan startActivity() jim ishlamaydi —
     * shuning uchun avval canDrawOverlays() tekshirib, ruxsat yo'q bo'lsa
     * full-screen-intent notification chiqaramiz (lock-screen ustida ko'rinadi,
     * telefon ochilsa Activity avtomatik faollashadi).
     */
    private fun showAutoScanPopup(apkPath: String, apkName: String) {
        val ctx = applicationContext
        val file = java.io.File(apkPath)
        if (ImprovedApkFileObserver.canLaunchActivityFromBackground(ctx)) {
            try {
                val intent = Intent(ctx, AutoScanActivity::class.java).apply {
                    putExtra("apk_path", apkPath)
                    putExtra("apk_name", apkName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startActivity failed, fallback to notification", e)
            }
        }
        try {
            NotificationHelper.showScanNotification(ctx, file)
        } catch (e: Throwable) {
            Log.e(TAG, "Notification fallback failed", e)
        }
    }
}
