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
                                // MUHIM: foydalanuvchiga TELEFONDA ko'rsatamiz. Ekran ochiq bo'lsa —
                                // popup OYNA ("Virus topildi va o'chirildi"), aks holda full-screen
                                // notification (u ham shu oynani ochadi). Ilgari faqat notification
                                // edi — foydalanuvchi "oyna chiqmadi" deb shikoyat qilgan.
                                try {
                                    showThreatHandledAlert(
                                        item.name, result.reason, item.file.absolutePath
                                    )
                                } catch (e: Throwable) {
                                    Log.w(TAG, "threat-handled alert failed", e)
                                }
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
     * Tahdid haqida ogohlantirish.
     *
     * MUHIM TARIXIY XATO (Xiaomi/MIUI'da topildi, ADB bilan tasdiqlandi):
     * ilgari bu funksiya `canDrawOverlays()` true bo'lsa darhol startActivity()
     * chaqirardi va notification'ni FAQAT startActivity() exception bersa ko'rsatardi.
     * Lekin MIUI ekran QULFLANGAN bo'lsa Activity'ni JIM bloklaydi
     * (`MIUILOG- Permission Denied Activity KeyguardLocked`) — exception BERMAYDI.
     * Natijada: popup ham chiqmaydi, fallback notification ham chiqmaydi →
     * foydalanuvchi HECH NARSA ko'rmaydi. Aynan foydalanuvchi shikoyat qilgan holat.
     *
     * Yangi mantiq:
     *  • Ekran ochiq VA qulfsiz → to'g'ridan-to'g'ri popup (eng tezkor, eng aniq).
     *  • Ekran qulflangan/o'chiq → full-screen-intent notification (MIUI lock ekran
     *    ustida ham ruxsat beradi, tovush+tebranish bilan; telefon ochilsa Activity
     *    avtomatik ochiladi). Bu — MIUI'da yagona ishonchli kanal.
     */
    private fun showAutoScanPopup(apkPath: String, apkName: String) {
        val ctx = applicationContext
        val file = java.io.File(apkPath)
        val screenUsable = isScreenInteractiveAndUnlocked(ctx)
        if (screenUsable && ImprovedApkFileObserver.canLaunchActivityFromBackground(ctx)) {
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
        // Qulflangan/o'chiq ekran (yoki startActivity muvaffaqiyatsiz) → notification.
        try {
            NotificationHelper.showScanNotification(ctx, file)
        } catch (e: Throwable) {
            Log.e(TAG, "Notification fallback failed", e)
        }
    }

    /**
     * Fon'da DANGER fayl avtomatik karantinga olingach foydalanuvchiga KO'RSATISH.
     *
     * Foydalanuvchi shikoyati: "fayl fonida o'chiriladi-yu, lekin OYNA chiqmaydi".
     * Ilgari bu yerda faqat notification (showQuarantinedNotification) bor edi,
     * popup OYNA umuman ochilmasdi. Endi:
     *   • Ekran ochiq + qulfsiz → to'g'ridan-to'g'ri AutoScanActivity OYNA
     *     ("Virus topildi va o'chirildi"; already_handled — qayta skanlamaydi).
     *   • Ekran qulflangan/o'chiq → full-screen-intent notification; u ham AYNAN shu
     *     oynani ochadi (telefon yoqilganda), Splash emas.
     */
    private fun showThreatHandledAlert(apkName: String, reason: String, originalPath: String) {
        val ctx = applicationContext
        if (isScreenInteractiveAndUnlocked(ctx) &&
            ImprovedApkFileObserver.canLaunchActivityFromBackground(ctx)) {
            try {
                val intent = Intent(ctx, AutoScanActivity::class.java).apply {
                    // fayl o'chgan, lekin yo'l matni manbani aniqlashga (Telegram/WhatsApp) kerak.
                    putExtra("apk_path", originalPath)
                    putExtra("apk_name", apkName)
                    putExtra("already_handled", true)
                    putExtra("verdict", "DANGER")
                    putExtra("reason", reason)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "handled popup startActivity failed, fallback to notification", e)
            }
        }
        // Qulflangan/o'chiq ekran (yoki startActivity muvaffaqiyatsiz) → full-screen-intent
        // notification (u ham shu "Virus o'chirildi" oynasini ochadi).
        try {
            NotificationHelper.showQuarantinedNotification(ctx, apkName, reason, originalPath)
        } catch (e: Throwable) {
            Log.w(TAG, "quarantine notification failed", e)
        }
    }

    /**
     * Ekran ayni paytda ochiq (interactive) VA qulfdan chiqarilganmi?
     * Faqat shu holatda fon'dan startActivity() real ko'rinadi. Aks holda
     * (qulflangan/o'chiq) MIUI uni jim bloklaydi — notification ishlatamiz.
     */
    private fun isScreenInteractiveAndUnlocked(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            pm.isInteractive && !km.isKeyguardLocked
        } catch (_: Throwable) {
            false
        }
    }
}
