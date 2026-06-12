package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GuardWorker"

/**
 * Yagona fon skaneri. Uch rejimda ishlaydi (avval ikkita parallel 15 daqiqalik
 * worker bor edi — GuardWorker + PeriodicCheckWorker; ular birlashtirildi):
 *
 *  1) Aniq APK yo'llari berilgan (ProtectionService real-time aniqlagan) →
 *     hammasini darhol skanlaymiz (dedup yo'q — yangi kelgan faylni doim ko'ramiz).
 *  2) full_sweep=true (yagona periodik 15 daq ish) → butun telefonni rekursiv
 *     skanlash (FullPhoneScan) checked_paths dedup bilan — avvalgi PeriodicCheckWorker
 *     o'rnida. Faqat YANGI fayllar skanlanadi, shu sabab arzon.
 *  3) Bir martalik tezkor skan (ekran ochilishi / Telegram "skan") → mtime bo'yicha
 *     eng yangi 10 ta APK.
 *
 * Barcha rejimlar bitta [handleResult] orqali bir xil qayta ishlanadi:
 * DANGER → karantin/popup (+ upload), SUSPICIOUS → popup, SAFE → (yangi yuklangan
 * bo'lsa) bildirishnoma.
 */
class GuardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Bulut qora ro'yxati (hash/paket/domen) muzlab qolmasin: ilova kunlab sovuq
            // startsiz yashasa ham, panel qo'shgan yangi domen ≤30 daqiqada yetib keladi.
            // isBackgroundEnabled'dan OLDIN — havola qalqoni fon-skan o'chiq bo'lsa ham ishlaydi.
            // Fail-safe: oflayn/xato keshga tegmaydi.
            //
            // MUHIM: REAL-VAQT yo'lida (ProtectionService aniqlagan aniq APK = KEY_APK_PATHS)
            // tarmoqqa CHIQMAYMIZ — skan issiq yo'li oflayn qolishi shart (yangi zararli faylni
            // karantinlash sekin tarmoq tufayli ~25s kechikmasin). Feed allaqachon startda
            // (App.onCreate loadCached/refresh) yuklangan; bu yerda faqat davriy/to'liq rejim yangilaydi.
            val isRealtime = !inputData.getStringArray(KEY_APK_PATHS).isNullOrEmpty()
            if (!isRealtime) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        CloudBlacklist.refreshIfStale(applicationContext)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce  // bekor qilish yutilmasin — Worker to'xtatilsa skan davom etmasin
                } catch (e: Throwable) {
                    Log.w(TAG, "cloud blacklist refreshIfStale failed", e)
                }
            }

            if (!Config.isBackgroundEnabled(applicationContext)) {
                return Result.success()
            }

            val serverUrl = Config.getServerUrl(applicationContext)
            val uploadEnabled = Config.isUploadEnabled(applicationContext) && serverUrl.isNotBlank()

            val explicitPaths = inputData.getStringArray(KEY_APK_PATHS)
            val fullSweep = inputData.getBoolean(KEY_FULL_SWEEP, false)

            when {
                // 1) ProtectionService aniqlagan aniq YANGI APK yo'l(lar)i — real vaqtda hammasini.
                !explicitPaths.isNullOrEmpty() -> {
                    var uploaded = 0
                    var scanned = 0
                    for (p in explicitPaths) {
                        val f = File(p)
                        if (!f.exists()) continue
                        val item = ApkItem(f, f.name, f.absolutePath, f.length())
                        val result = try {
                            ApkScanner.scan(applicationContext, f.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Scan error for ${f.name}", e); continue
                        }
                        scanned++
                        uploaded += handleResult(item, result, uploadEnabled, serverUrl, allowSafeNotification = false)
                    }
                    Log.d(TAG, "Explicit scan: $scanned APKs, uploaded=$uploaded")
                    Result.success(workDataOf("scanned" to scanned, "uploaded" to uploaded))
                }

                // 2) Periodik to'liq tekshiruv (avvalgi PeriodicCheckWorker o'rnida) — butun telefon,
                //    checked_paths dedup bilan: faqat YANGI fayllar skanlanadi.
                fullSweep -> {
                    val apks = try {
                        FullPhoneScan.findAllApkFiles(applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Full sweep find error", e); return Result.success()
                    }

                    val prefs = applicationContext.getSharedPreferences("kiberqalqon_checked", Context.MODE_PRIVATE)
                    // getStringSet() immutable Set qaytaradi — to'g'ridan-to'g'ri .add() qilsa crash.
                    val checked = HashSet<String>().apply {
                        addAll(prefs.getStringSet("checked_paths", emptySet()) ?: emptySet())
                    }
                    var uploaded = 0
                    var scanned = 0
                    for (apk in apks) {
                        if (!apk.file.exists()) continue
                        // Dedup path+mtime+size bo'yicha: avval faqat path edi → o'sha yo'ldagi YANGI
                        // (o'zgargan) APK qayta skanlanmasdan o'tib ketardi. mtime/size o'zgarsa — qayta skan.
                        val key = "${apk.path}|${apk.file.lastModified()}|${apk.file.length()}"
                        if (checked.contains(key)) continue
                        val result = try {
                            ApkScanner.scan(applicationContext, apk.path)
                        } catch (e: Exception) {
                            Log.w(TAG, "Scan error for ${apk.name}", e); continue
                        }
                        checked.add(key)
                        scanned++
                        val recentlyDownloaded =
                            (System.currentTimeMillis() - apk.file.lastModified()) < 30 * 60 * 1000L
                        uploaded += handleResult(
                            apk, result, uploadEnabled, serverUrl,
                            allowSafeNotification = recentlyDownloaded,
                            suspiciousAsPopup = recentlyDownloaded
                        )
                    }
                    // Ro'yxat cheksiz o'smasligi uchun cheklaymiz.
                    val capped = if (checked.size > 5000) checked.take(5000).toHashSet() else checked
                    prefs.edit().putStringSet("checked_paths", capped).apply()

                    // BG-04: bu yerda Config.markDatabaseUpdated() ATAYIN CHAQIRILMAYDI. Avval har 15
                    // daqiqada (auto-update yoqilganda) shtamp bekorga yangilanib, BUTUN ScanCache'ni
                    // bekor qilardi — kesh maksimum 15 daqiqa yashab, har sweep'dan keyin katta APK'lar
                    // qayta SHA-256/ZIP/DEX bilan skanlanib telefon qizib turardi (anti-qizish fiksini
                    // bekor qiladi). Shtamp faqat baza HAQIQATAN o'zgarsa yangilanadi — uни CloudBlacklist
                    // (real merge bo'lsa) va APK versiyasi (versionCode shtampда) hal qiladi.
                    Log.d(TAG, "Full sweep: ${apks.size} APKs, new=$scanned, uploaded=$uploaded")
                    Result.success(workDataOf("scanned" to scanned, "uploaded" to uploaded))
                }

                // 3) Bir martalik tezkor skan (ekran ochilishi / Telegram "skan") — mtime bo'yicha top 10.
                else -> {
                    val list = try {
                        ApkScanner.findApkFiles(applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error finding APK files", e); return Result.success()
                    }
                    // BG-03: bu rejim har ekran ochilishida ishlaydi. Avval "uxlab yotgan" eski
                    // SUSPICIOUS/DANGER fayl uchun HAR unlock'da popup/tovushli full-screen alert
                    // chiqardi (antivirusni o'chirishning #1 sababi). Endi "allaqachon ogohlantirilgan"
                    // to'plamini (path|mtime|size) saqlaymiz: o'sha fayl uchun qayta alert chiqmaydi,
                    // faqat YANGI (yoki o'zgargan) fayl ogohlantiradi. Skan baribir bajariladi.
                    val prefs = applicationContext.getSharedPreferences("kiberqalqon_checked", Context.MODE_PRIVATE)
                    val warned = HashSet<String>().apply {
                        addAll(prefs.getStringSet("unlock_warned", emptySet()) ?: emptySet())
                    }
                    val now = System.currentTimeMillis()
                    var uploaded = 0
                    for (item in list.take(10)) {
                        if (!item.file.exists()) continue
                        val result = try {
                            ApkScanner.scan(applicationContext, item.file.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Scan error for ${item.name}", e); continue
                        }
                        val key = "${item.file.absolutePath}|${item.file.lastModified()}|${item.file.length()}"
                        val alreadyWarned = !warned.add(key)
                        val recentlyDownloaded = (now - item.file.lastModified()) < 30 * 60 * 1000L
                        uploaded += handleResult(
                            item, result, uploadEnabled, serverUrl,
                            allowSafeNotification = false,
                            // Eski (yangi yuklanmagan) SUSPICIOUS → jim bildirishnoma, popup emas.
                            suspiciousAsPopup = recentlyDownloaded,
                            // Bu fayl uchun avval ogohlantirilgan bo'lsa — qayta popup/tovush yo'q.
                            suppressAlerts = alreadyWarned
                        )
                    }
                    val capped = if (warned.size > 5000) warned.take(5000).toHashSet() else warned
                    prefs.edit().putStringSet("unlock_warned", capped).apply()
                    Log.d(TAG, "Quick scan: ${list.size} found, uploaded=$uploaded")
                    Result.success(workDataOf("scanned" to list.size, "uploaded" to uploaded))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in background worker", e)
            Result.failure()
        }
    }

    /**
     * Bitta skan natijasini qayta ishlash. Qaytaradi: serverga yuklangan fayllar soni (0/1).
     *
     * DANGER + "delete" rejimi → ekran qulflangan bo'lsa ham fonida darhol karantinga
     * ko'chiramiz (AutoScanActivity'ning 2.5s auto-delete'i faqat ekran ochiq bo'lsa
     * ishlaydi). Karantin xavfsiz: filesDir/quarantine/ ga ketadi, 7 kun tiklash mumkin.
     * SUSPICIOUS — hech qachon avto-o'chirilmaydi, faqat popup. SAFE — faqat
     * [allowSafeNotification] (yangi yuklab olingan) bo'lsa bildirishnoma.
     */
    private fun handleResult(
        item: ApkItem,
        result: ScanResult,
        uploadEnabled: Boolean,
        serverUrl: String,
        allowSafeNotification: Boolean,
        // SUSPICIOUS uchun OYNA ko'rsatilsinmi? Real-time/tezkor skanda — ha (yangi aniqlangan).
        // To'liq periodik sweep'da esa faqat YANGI yuklab olingan fayl uchun — aks holda antivirus
        // eski fayllar uchun o'zicha oyna ochib bezovta qiladi (DANGER esa doim popup/karantin).
        suspiciousAsPopup: Boolean = true,
        // BG-03: takroriy OGOHLANTIRISHLARNI (popup + bildirishnoma) bostiradi. DANGER+"delete"
        // rejimida avto-karantin (va birinchi upload) baribir bajariladi. Ekran ochilishidagi tezkor
        // skan SHU fayl uchun allaqachon ogohlantirgan bo'lsa — har unlock'da qayta popup/tovush
        // chiqmasin (xavfsizlik buzilmaydi: fayl o'sha-o'sha holatda).
        suppressAlerts: Boolean = false
    ): Int {
        var uploaded = 0
        val autoDelete = result.verdict == ScanResult.Verdict.DANGER &&
            Config.getAutoDeleteMode(applicationContext) == "delete"
        val shouldAlert = result.verdict == ScanResult.Verdict.DANGER ||
            result.verdict == ScanResult.Verdict.SUSPICIOUS

        if (autoDelete) {
            // Avval upload (agar yoqilgan) — fayl karantinga ketgach asl yo'l yo'qoladi.
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
                    if (!suppressAlerts) try {
                        showThreatHandledAlert(item.name, result.reason, item.file.absolutePath)
                    } catch (e: Throwable) {
                        Log.w(TAG, "threat-handled alert failed", e)
                    }
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
                    Log.w(TAG, "Auto-quarantine failed for ${item.name}: ${q.message}")
                    if (!suppressAlerts) try {
                        showAutoScanPopup(item.file.absolutePath, item.name)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not show popup", e)
                    }
                }
            }
        } else if (shouldAlert && !suppressAlerts) {
            val isDanger = result.verdict == ScanResult.Verdict.DANGER
            if (isDanger || suspiciousAsPopup) {
                try {
                    showAutoScanPopup(item.file.absolutePath, item.name)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show popup", e)
                }
            } else {
                // Eski (yangi yuklanmagan) SUSPICIOUS fayl — popup o'rniga jim bildirishnoma.
                try {
                    NotificationHelper.showFoundApkNotification(
                        applicationContext, item.file, result.verdict, result.reason
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "suspicious notification failed", e)
                }
            }

            if (uploadEnabled && result.verdict == ScanResult.Verdict.DANGER) {
                try {
                    val ok = ServerUpload.uploadApk(serverUrl, item.file, item.name)
                    if (ok) uploaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Upload error", e)
                }
            }
        } else if (allowSafeNotification) {
            // SAFE + yangi yuklab olingan → "tekshirildi, xavfsiz" bildirishnomasi.
            try {
                NotificationHelper.showFoundApkNotification(
                    applicationContext, item.file, result.verdict, result.reason
                )
            } catch (e: Throwable) {
                Log.w(TAG, "found-apk notification failed", e)
            }
        }
        return uploaded
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

    companion object {
        /** ProtectionService aniqlagan aniq APK yo'llari (String[]) — #10/#11. */
        const val KEY_APK_PATHS = "apk_paths"

        /** true bo'lsa — butun telefonni rekursiv skanlash (periodik ish). */
        const val KEY_FULL_SWEEP = "full_sweep"

        /** Yagona periodik ish nomi. App.onCreate va BootReceiver SHU nomdan foydalanadi. */
        const val UNIQUE_PERIODIC = "kiberqalqon_scan"

        /**
         * Yagona 15 daqiqalik periodik GuardWorker (full_sweep=true). Avvalgi alohida
         * PeriodicCheckWorker o'rnida butun telefonni dedup bilan skanlaydi. App.onCreate
         * ham, BootReceiver ham shu yerdan chaqiradi — bitta unique nom, bitta zanjir.
         * UPDATE policy: mavjud o'rnatishlarda ham yangi full_sweep flag qo'llanadi.
         */
        fun schedulePeriodic(context: Context) {
            cancelLegacyPeriodic(context)
            // To'liq tekshiruv og'ir — past zaryadda ishlamasin (avval bor edi, merge'da yo'qolgan).
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_FULL_SWEEP to true))
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Eski PeriodicCheckWorker o'chirildi, lekin YANGILANGAN qurilmalarda uning
         * "periodic_apk_check" nomli periodik ishi WorkManager bazasida qolib ketgan va
         * endi mavjud bo'lmagan klassni instansiyalashga urinib har safar yiqiladi.
         * Bir martalik (prefs-flag) tozalaymiz. (Yangi o'rnatishda bu ish umuman yo'q — no-op.)
         */
        private fun cancelLegacyPeriodic(context: Context) {
            try {
                val sp = context.getSharedPreferences("kiberqalqon_checked", Context.MODE_PRIVATE)
                if (sp.getBoolean("legacy_periodic_cancelled", false)) return
                WorkManager.getInstance(context).cancelUniqueWork("periodic_apk_check")
                sp.edit().putBoolean("legacy_periodic_cancelled", true).apply()
                Log.d(TAG, "legacy periodic_apk_check ishi bekor qilindi")
            } catch (e: Throwable) {
                Log.w(TAG, "legacy work cancel failed", e)
            }
        }
    }
}
