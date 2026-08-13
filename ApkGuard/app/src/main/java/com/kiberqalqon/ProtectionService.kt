package com.uzguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Doimiy himoya foreground service'i.
 *
 * Status bar'da har doim ko'rinib turuvchi bildirishnoma:
 * "🛡️ UZGUARD faol · Telefoningiz himoyalangan"
 *
 * Bu — foydalanuvchiga eng kuchli signal: "himoya ishlayapti". Hech qanday
 * sozlama o'zgartirmasdan, ilovani o'rnatib ochish bilan darhol shu yozuv
 * ekranda paydo bo'ladi.
 *
 * Qo'shimcha imtiyozlar:
 *  - foreground service Android'da "important" prioritet oladi — OS uni
 *    oddiy fon ilovalarga qaraganda kamroq o'ldiradi
 *  - Xiaomi/Huawei kabi agressiv qurilmalarda WorkManager'ni "uxlatib" qo'ysa
 *    ham, foreground notification turgan ekan, jarayon ham tirik qoladi
 *  - foydalanuvchi bildirishnomani bossa Dashboard ochiladi
 *
 * Boshlanish: `ProtectionService.start(context)` — App.onCreate'da chaqiriladi.
 * To'xtatish: foydalanuvchi sozlamada o'chirsa, `stop(context)`.
 */
class ProtectionService : Service() {

    // Service umri davomida yashaydigan scope — real-time FileObserver shu yerda turadi.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Real-time kuzatuvchi: Downloads/Telegram/WhatsApp papkalariga yangi APK tushishi
    // bilan darhol AutoScanActivity ochadi. ILGARI faqat MainActivity'da yashar edi —
    // ilova yopilsa kuzatuv to'xtardi va Telegram'dan kelgan virus REAL VAQTDA
    // ushlanmasdi (faqat 15 daqiqalik GuardWorker keyinroq topardi). Endi doimiy
    // foreground service'da yashaydi — telefon 24/7 himoyalangan.
    private var fileObserver: MultiPathFileObserver? = null

    // Tezkor poll loop bir martagina ishga tushadi (onStartCommand bir necha bor chaqirilsa ham).
    @Volatile private var fastLoopStarted = false

    // Wi-Fi straj: ochiq (parolsiz) tarmoqqa ulanishni kuzatib, MITM xavfidan ogohlantiradi.
    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startFileWatcher()
        startFastScanLoop()
        startWifiWatch()
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: foreground service type kerak. UzGuard antivirus
                // bo'lgani uchun SPECIAL_USE eng yaqin kategoriya (DATA_SYNC ham bo'ladi
                // lekin biz hech narsa sync qilmaymiz — special_use to'g'riroq).
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // BG-08: startForeground rad etilsa (Android 12+ fon-start cheklovi, OEM, FGS-type),
            // "oddiy service sifatida davom etamiz" ISHLAMAYDI — startForegroundService→startForeground
            // shartnomasi bajarilmagani uchun tizim baribir RemoteServiceException/ANR bilan yiqitadi
            // (START_STICKY esa qayta-qayta urinib siklik crash beradi). To'g'ri yo'l: shartnomani
            // stopSelf bilan yopamiz va START_NOT_STICKY qaytaramiz; qayta urinish foreground-Activity
            // onResume'da yoki 15 daqiqalik WorkManager orqali bo'ladi.
            android.util.Log.w(TAG, "startForeground failed — stopping self to avoid system kill", t)
            try { stopSelf() } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { fileObserver?.stopWatching() } catch (t: Throwable) {
            android.util.Log.w(TAG, "stopWatching failed", t)
        }
        fileObserver = null
        try {
            wifiCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (_: Throwable) {}
        wifiCallback = null
        try { serviceScope.cancel() } catch (_: Throwable) {}
        // Service o'lgan bo'lsa, OS qayta tiklaydi (START_STICKY tufayli).
    }

    /**
     * Wi-Fi tarmoq o'zgarishini kuzatadi: ochiq (parolsiz) tarmoqqa ulanilganda
     * [WifiGuard] MITM xavfidan bir marta ogohlantiradi. Bir marta ro'yxatga olinadi
     * (onStartCommand takror chaqirilsa ham — wifiCallback != null bo'lsa o'tkazamiz).
     */
    private fun startWifiWatch() {
        if (wifiCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return
            val req = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            // API 31+ da FLAG_INCLUDE_LOCATION_INFO SHART: usiz transportInfo'dagi SSID
            // har doim "<unknown ssid>" bo'lib keladi va ochiq-tarmoq ogohlantirishi
            // HECH QACHON otmasdi (WifiGuard.cleanSsid null qaytarardi).
            val cb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                object : android.net.ConnectivityManager.NetworkCallback(
                    android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                ) {
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities
                    ) {
                        try { WifiGuard.onWifiCapabilities(applicationContext, caps) } catch (_: Throwable) {}
                    }
                }
            } else {
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        caps: android.net.NetworkCapabilities
                    ) {
                        try { WifiGuard.onWifiCapabilities(applicationContext, caps) } catch (_: Throwable) {}
                    }
                }
            }
            cm.registerNetworkCallback(req, cb)
            wifiCallback = cb
            android.util.Log.d(TAG, "Wi-Fi guard watcher registered")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "startWifiWatch failed", t)
        }
    }

    /**
     * Real-time FileObserver'ni ishga tushiradi. onStartCommand bir necha bor
     * chaqirilishi mumkin (START_STICKY restart, qayta start) — shuning uchun
     * fileObserver != null bo'lsa takror ishga tushirmaymiz (aks holda bitta APK
     * bir nechta observer'ga tushib, ikki marta xabar qiladi).
     */
    private fun startFileWatcher() {
        if (fileObserver != null) return
        try {
            fileObserver = MultiPathFileObserver(applicationContext, serviceScope).also {
                it.startWatching()
            }
            android.util.Log.d(TAG, "Real-time file watcher started (service-owned, 24/7)")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "startFileWatcher failed", t)
        }
    }

    /**
     * Tezkor avtomatik skan loop'i — FileObserver'ga BOG'LIQ EMAS.
     *
     * Telegram yangi APK'ni o'z papkasiga (/Android/media/org.telegram.messenger/...)
     * saqlaganda, ko'p qurilmalarda FileObserver inotify event bermaydi. Shuning uchun
     * vaqti-vaqti bilan (ADAPTIV interval — pastdagi POLL_INTERVAL_* ga qarang) MediaStore +
     * papka ro'yxati orqali yangi APK qidiramiz (bu ishonchli).
     * Yangi fayl topilsa — DARHOL AutoScanActivity oynasini ochamiz (jonli "tekshirilmoqda"
     * animatsiyasi → verdikt), xuddi FileObserver yo'li kabi. Ekran qulflangan / overlay
     * ruxsati yo'q bo'lsa — GuardWorker'ga o'tamiz (u skanlaydi, DANGER'ni karantinga oladi
     * va full-screen notification ko'rsatadi).
     *
     * MUHIM #1: Telegram papkasini ko'rish uchun "Barcha fayllarga ruxsat"
     * (MANAGE_EXTERNAL_STORAGE) berilgan bo'lishi SHART — aks holda Android boshqa
     * ilovaning papkasini o'qishga ruxsat bermaydi va hech narsa topilmaydi.
     *
     * MUHIM #2 (tuzatilgan xato): yangi faylni mtime "watermark" bilan emas, KO'RILGAN
     * yo'llar to'plami bilan aniqlaymiz. Telegram/WhatsApp/Bluetooth yuklamalari faylning
     * mtime'sini jo'natuvchining (eski) timestamp'iga qo'yadi — shuning uchun
     * "mtime > watermark" tekshiruvi yangi kelgan APK'ni butunlay o'tkazib yuborardi.
     */
    private fun startFastScanLoop() {
        if (fastLoopStarted) return
        fastLoopStarted = true
        serviceScope.launch {
            // Ko'rilgan APK yo'llari. Birinchi pollda mavjud fayllar bilan to'ldiriladi —
            // eski fayllar uchun oyna chiqarmaymiz; faqat SHUNDAN keyin paydo bo'lganlar uchun.
            // Yangilik aniqlash mantig'i NewApkDetector'da (sof funksiya, unit-test bilan qoplangan).
            val seenPaths = HashSet<String>(256)

            // BG-05: SEED'ni alohida SAXIY byudjet bilan qilamiz (loop ichidagi 800ms emas).
            // Sovuq startda (ребут/обновление/рестарт сервиса) I/O sekin — 800ms butun ro'yxatga
            // yetmay, eng ESKI fayllar (MediaStore DATE_MODIFIED DESC oxiri) seed'ga tushmasdi va
            // keyingi pollda "yangi" deb ochilib ketardi. To'liq listing tugaguncha seed qilamiz.
            //
            // MUHIM (flood tuzatuvi): SEED'ni FAQAT "Barcha fayllarga ruxsat" (hasFileScanAccess)
            // bor bo'lganda qilamiz. Aks holda service ruxsatsiz ishga tushsa (MainActivity/
            // SettingsActivity toggle-ON ni hasFileScanAccess tekshirmasdan start chaqiradi, yoki
            // ребут paytida MediaStore hali tayyor emas) — findApkFiles deyarli bo'sh qaytadi va
            // seenPaths bo'sh qoladi. Keyin foydalanuvchi ruxsat bersa, seenPaths eski (bo'sh)
            // holida qolgani uchun keyingi pollda qurilmadagi HAMMA eski APK "yangi" deb topilib,
            // AutoScanActivity oynalari + GuardWorker'lar toshib ketardi. Shuning uchun dostup
            // holatini kuzatamiz va yo'q→bor o'tishida seenPaths ni tozalab QAYTA seed qilamiz.
            var seeded = false
            var lastAccess = false
            try {
                if (Config.isBackgroundEnabled(applicationContext) &&
                    VersionCompat.hasFileScanAccess(applicationContext)) {
                    val initial = ApkScanner.findApkFiles(applicationContext, timeBudgetMs = SEED_SCAN_BUDGET_MS)
                        .filter { it.file.exists() }
                    NewApkDetector.seed(
                        initial.map { NewApkDetector.PathStamp(it.file.absolutePath, it.file.lastModified()) },
                        seenPaths,
                    )
                    seeded = true
                    lastAccess = true
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "seed scan failed", t)
            }

            // PERF (qizish): qimmat MediaStore + rekursiv obhodni FAQAT kuzatilayotgan
            // papkalardan biri o'zgargan bo'lsa (yoki har FORCE_FULL_FIND_MS da bir marta
            // zaxira sifatida) bajaramiz. Hech narsa o'zgarmaganda — 9 ta arzon
            // dir.lastModified() stat, butun xotira obhodi O'RNIGA. Bu — ekran ochiq turganda
            // har 45s da butun xotirani skanlash sababli telefon qizishini bartaraf etadi.
            // Real-vaqt aniqlash baribir inotify (MultiPathFileObserver) + 30 daqiqalik
            // GuardWorker zimmasida, shu sabab o'tkazib yuborilgan obhod hech narsani yo'qotmaydi.
            var lastDirSig = ""
            var lastFullFindAt = 0L
            while (isActive) {
                // Adaptiv interval: ekran ochiq bo'lsa tez-tez, aks holda kamdan-kam —
                // shunda fon'da telefon qizimaydi (eski qat'iy 1s loop asosiy qizish sababi edi).
                val interactive = isScreenInteractiveAndUnlocked(applicationContext)
                delay(if (interactive) POLL_INTERVAL_ACTIVE_MS else POLL_INTERVAL_IDLE_MS)

                // Yangiliklar (panel e'lonlari) — fon-SKANDAN MUSTAQIL, alohida coroutine'da
                // (NewsNotifier ichida 3 daqiqalik throttle + dedup). FAQAT ekran OCHIQ bo'lganda
                // shu yerdan tekshiramiz: foydalanuvchi telefonni ishlatyapti → yangi e'lon
                // ~3 daqiqada keladi (FCM/Google'siz "deyarli real-vaqt"). Ekran O'CHIQ bo'lsa
                // BU YERDA TEKSHIRMAYMIZ — Doze'da yetkazish NewsAlarmReceiver (siyrak, ~40 daq)
                // zimmasida; shunda telefon stolda yotganda behuda uyg'onmaydi va qizimaydi
                // (idle nagrev/batareya sababi shu takror tekshiruv edi). isBackgroundEnabled
                // gate'idan OLDIN — yangiliklar fon-skan o'chiq bo'lsa ham keladi.
                if (interactive) {
                    serviceScope.launch {
                        try { NewsNotifier.checkAndNotify(applicationContext) } catch (_: Throwable) {}
                    }
                }

                try {
                    if (!Config.isBackgroundEnabled(applicationContext)) continue

                    // Fayl-dostupi holatini kuzatamiz: yo'q→bor ga o'tsa (yoki service ruxsatsiz
                    // ishga tushib hali umuman seed qilinmagan bo'lsa) — seenPaths ni tozalab QAYTA
                    // seed qilamiz, so'ng shu iteratsiyani o'tkazamiz. Aks holda endigina ko'rinadigan
                    // bo'lgan eski APK'lar "yangi" deb topilib, oynalar/worker'lar toshib ketardi.
                    val access = VersionCompat.hasFileScanAccess(applicationContext)
                    if (access && (!seeded || !lastAccess)) {
                        try {
                            seenPaths.clear()
                            val reseed = ApkScanner.findApkFiles(applicationContext, timeBudgetMs = SEED_SCAN_BUDGET_MS)
                                .filter { it.file.exists() }
                            NewApkDetector.seed(
                                reseed.map { NewApkDetector.PathStamp(it.file.absolutePath, it.file.lastModified()) },
                                seenPaths,
                            )
                            seeded = true
                            lastAccess = true
                            // O'zgarish-imzosini ham reset qilamiz — keyingi iteratsiya toza boshlansin.
                            lastDirSig = ""
                            lastFullFindAt = SystemClock.elapsedRealtime()
                            android.util.Log.d(TAG, "Re-seeded after file access became available")
                        } catch (t: Throwable) {
                            android.util.Log.w(TAG, "re-seed after access grant failed", t)
                        }
                        continue
                    }
                    lastAccess = access

                    // Arzon o'zgarish-detektori: kuzatilayotgan papkalar mtime imzosi o'zgarmagan
                    // bo'lsa (va zaxira to'liq-obhod vaqti yetmagan bo'lsa) — qimmat obhodni
                    // O'TKAZAMIZ. Yangi APK papkaga tushganda katalog mtime'si o'zgaradi → obhod.
                    val nowMs = SystemClock.elapsedRealtime()
                    val sig = quickDirSignature(applicationContext)
                    val forceFull = nowMs - lastFullFindAt >= FORCE_FULL_FIND_MS
                    if (sig == lastDirSig && !forceFull) continue
                    lastDirSig = sig
                    lastFullFindAt = nowMs

                    val list = ApkScanner.findApkFiles(applicationContext, timeBudgetMs = FAST_SCAN_BUDGET_MS)
                        .filter { it.file.exists() }
                    val stamps = list.map {
                        NewApkDetector.PathStamp(it.file.absolutePath, it.file.lastModified())
                    }

                    val newPaths = NewApkDetector.pickNew(stamps, seenPaths, System.currentTimeMillis())
                    if (newPaths.isNotEmpty()) {
                        val newSet = newPaths.toHashSet()
                        val newItems = list.filter { it.file.absolutePath in newSet }
                        android.util.Log.d(TAG, "Fast loop: ${newItems.size} new APK(s) detected")
                        presentNewApks(newItems)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "fast scan loop iteration failed", t)
                }
            }
        }
    }

    /**
     * Yangi aniqlangan APK(lar)ni foydalanuvchiga KO'RSATISH.
     *
     * Ekran ochiq + qulfsiz + overlay ruxsati bor → eng yangi yangi faylni darhol
     * AutoScanActivity oynasida ochamiz (jonli skan → verdikt, BARCHA verdiktlar uchun,
     * SAFE ham — foydalanuvchi "tekshirildi" ni ko'radi). Bu — FileObserver yo'li bilan
     * bir xil xulq, dedup AutoScanActivity ichida (10s) hal qilinadi.
     *
     * Aks holda (ekran qulflangan / overlay yo'q / launch muvaffaqiyatsiz) → GuardWorker:
     * u skanlaydi, DANGER'ni karantinga oladi va full-screen-intent notification
     * ko'rsatadi (u ham aynan shu oynani ochadi).
     */
    private fun presentNewApks(newOnes: List<ApkItem>) {
        if (newOnes.isEmpty()) return
        val ctx = applicationContext
        // Eng yangisidan boshlab — jonli oynani aynan eng so'nggi kelgan fayl uchun ochamiz.
        val sorted = newOnes.sortedByDescending { it.file.lastModified() }
        val canLaunch = isScreenInteractiveAndUnlocked(ctx) &&
            ImprovedApkFileObserver.canLaunchActivityFromBackground(ctx)

        // #11: avval FAQAT eng yangi fayl ko'rsatilardi, qolgan bir vaqtda kelgan yangi
        //      APK'lar (jumladan backdated virus) seenPaths'ga belgilanib TASHLAB yuborilardi.
        //      Endi eng yangisini jonli oynada ko'rsatamiz, QOLGANLARINI GuardWorker'ga aniq
        //      yo'l bilan uzatamiz (skan + DANGER karantin + full-screen notification).
        val remaining = ArrayList<ApkItem>(sorted)
        if (canLaunch) {
            val newest = sorted.first()
            var launched = false
            try {
                val intent = Intent(ctx, AutoScanActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("apk_path", newest.file.absolutePath)
                    putExtra("apk_name", newest.name)
                }
                ctx.startActivity(intent)
                launched = true
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "direct AutoScan launch failed, falling back to GuardWorker", t)
            }
            // Jonli oynada ko'rsatilgan faylni ro'yxatdan olib tashlaymiz; launch
            // muvaffaqiyatsiz bo'lsa u ham worker'ga tushadi.
            if (launched) remaining.remove(newest)
        }

        // #10: qulflangan ekran / overlay yo'q / launch muvaffaqiyatsiz — aniq yo'llarni
        //      bevosita GuardWorker'ga beramiz (bare worker emas), shunda mtime bo'yicha
        //      10 talikdan tashqaridagi/backdated fayl ham aniq skanlanadi.
        if (remaining.isNotEmpty()) {
            try {
                val paths = remaining.map { it.file.absolutePath }.toTypedArray()
                val req = OneTimeWorkRequestBuilder<GuardWorker>()
                    .setInputData(androidx.work.workDataOf(GuardWorker.KEY_APK_PATHS to paths))
                    .build()
                WorkManager.getInstance(ctx).enqueue(req)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "GuardWorker enqueue failed", t)
            }
        }
    }

    /**
     * Ekran ayni paytda ochiq (interactive) VA qulfdan chiqarilganmi? Faqat shu holatda
     * fon'dan startActivity() real ko'rinadi; aks holda (qulflangan/o'chiq) MIUI uni jim
     * bloklaydi va notification ishlatish kerak.
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

    /**
     * Kuzatilayotgan yuklab-olish papkalarining arzon "o'zgarish imzosi" — har birining
     * lastModified() qiymati. Papkaga yangi fayl qo'shilsa/o'chsa katalog mtime'si o'zgaradi,
     * shuning uchun imzo o'zgargandagina qimmat to'liq obhod (MediaStore + rekursiv yurish)
     * qilamiz. 9 ta File.stat — butun xotira obhodidan ming barobar arzon, shu sabab ekran
     * ochiq turganda telefon endi qizimaydi.
     */
    private fun quickDirSignature(ctx: Context): String {
        return try {
            val ext = Environment.getExternalStorageDirectory() ?: return ""
            val dirs = mutableListOf<File>()
            val downloadDir = File(ext, "Download")
            dirs.add(downloadDir)
            if (downloadDir.exists()) {
                val subs = downloadDir.listFiles()
                if (subs != null) {
                    for (sub in subs) {
                        if (sub.isDirectory) {
                            dirs.add(sub)
                        }
                    }
                }
            }
            dirs.add(File(ext, "Telegram/Telegram Documents"))
            dirs.add(File(ext, "WhatsApp/Media/WhatsApp Documents"))
            dirs.add(File(ext, "Android/media/org.telegram.messenger/cache"))
            dirs.add(File(ext, "Android/media/org.telegram.messenger/Telegram/Telegram Documents"))
            dirs.add(File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"))
            dirs.add(File(ext, "Bluetooth"))
            // DCIM va xotira-ildizi — bu yerga ham APK saqlanishi mumkin. Bu faqat mtime
            // o'qish (stat), papkani OBHOD QILMAYDI — arzon, lekin poll shu joydagi yangi
            // APK'ni ~45s ichida ilg'aydi (5 daq FORCE_FULL kutmasdan).
            dirs.add(File(ext, "DCIM"))
            dirs.add(ext)

            val sb = StringBuilder(160)
            for (d in dirs) {
                if (d.exists()) sb.append(d.name).append(d.lastModified()).append('|')
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val TAG = "ProtectionService"
        private const val CHANNEL_ID = "kq_protection_status"
        private const val CHANNEL_NAME = "Himoya holati"
        const val NOTIFICATION_ID = 1010

        /**
         * Tezkor avtomatik skan oralig'i — ADAPTIV (batareya/qizish uchun muhim).
         *
         * ILGARI qat'iy 1s edi: har soniyada butun xotira MediaStore so'rovi + rekursiv
         * fayl yurish bilan skanlanardi → protsessor 24/7 yuklanib telefon QIZIRDI va
         * batareya tez tugardi. Aslida real-vaqt aniqlash MultiPathFileObserver (inotify)
         * zimmasida; bu poll faqat inotify ishlamaydigan papkalar (ba'zi qurilmalarda
         * Telegram media) uchun ZAXIRA. Shuning uchun:
         *   - ekran ochiq + qulfsiz (foydalanuvchi shu yerda, APK yuklab/o'rnatishi mumkin)
         *     → tez-tez tekshiramiz (lekin baribir 1s emas);
         *   - ekran o'chiq/qulflangan (foydalanuvchi APK o'rnatolmaydi) → kamdan-kam —
         *     FileObserver + 15 daqiqalik GuardWorker + ekran ochilishidagi bir martalik
         *     skan baribir qamrab oladi.
         */
        // PERF (qizish): bu poll — ZAXIRA yo'l. Real-vaqt aniqlash MultiPathFileObserver
        // (inotify) + 15 daqiqalik GuardWorker + ekran ochilishidagi bir martalik skan
        // zimmasida. Shuning uchun oraliqni uzaytirdik: ekran ochiq bo'lganda har 12s da
        // butun xotirani skanlash (≈5 obhod/min, 24/7) protsessorni isitardi. Endi 45s
        // (≈1.3 obhod/min) — inotify o'tkazib yuborgan kamdan-kam holat uchun ham yetarlicha
        // tez, lekin issiqlik ~73% kamayadi. Idle (ekran o'chiq/qulflangan — APK o'rnatib
        // bo'lmaydi) — yanada kamdan-kam.
        private const val POLL_INTERVAL_ACTIVE_MS = 45_000L
        // Idle (ekran o'chiq/qulflangan — APK o'rnatib bo'lmaydi): 10 daqiqa. Tunda telefon
        // stolda turganda kamroq uyg'onadi → batareya kam tugaydi. Fon karantini baribir
        // har siklda ishlaydi (presentNewApks → GuardWorker), faqat siyrakroq.
        private const val POLL_INTERVAL_IDLE_MS = 600_000L

        /**
         * mtime imzosi o'zgarmagan bo'lsa ham, kamida shu oraliqda bir marta to'liq obhod
         * qilamiz (zaxira: ba'zi OEM fayl tizimlarida katalog mtime'si yangi fayl
         * qo'shilganda yangilanmasligi mumkin).
         */
        private const val FORCE_FULL_FIND_MS = 5 * 60_000L

        /**
         * Bitta poll iteratsiyasi uchun vaqt byudjeti. Eng kichik oraliq (active)dan
         * sezilarli kichik bo'lishi SHART — aks holda skanlar bir-birining ustiga chiqadi.
         * MediaStore so'rovi odatda bundan ancha tez tugaydi.
         */
        private const val FAST_SCAN_BUDGET_MS = 800L

        /**
         * Birinchi (seed) listing uchun saxiyroq byudjet. Bu bir martalik — loop tezligiga ta'sir
         * qilmaydi, lekin sovuq startda butun ro'yxat seed'ga tushishini ta'minlaydi (BG-05).
         */
        private const val SEED_SCAN_BUDGET_MS = 10_000L

        /** Service'ni ishga tushiradi. Idempotent — qayta chaqirish bezarar. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, ProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "start failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ProtectionService::class.java))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "stop failed", t)
            }
        }

        /** Status matnini yangilash (scan tugagach yoki sozlama o'zgargach). */
        fun refresh(context: Context) {
            try {
                // BG-02: fon himoyasi O'CHIRILGAN bo'lsa "UZGUARD faol" bildirishnomasini
                // TIKLAMAYMIZ — aks holda foydalanuvchi himoyani o'chirgach ham har skandан keyin
                // belgi qayta paydo bo'lib, "o'chirdim-ku" degan holatga zid yolg'on ko'rsatardi.
                if (!Config.isBackgroundEnabled(context)) return
                ensureChannel(context)
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(NOTIFICATION_ID, buildNotification(context))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "refresh failed", t)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // IMPORTANCE_LOW — tovush yo'q, faqat status bar'da kichik ikona.
            // Foydalanuvchi "qoldiradigan" doimiy yozuv, bezovta qilmaslik kerak.
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "UzGuard doimiy himoya holati"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): android.app.Notification {
            // Bildirishnoma ustiga bosish — Dashboard ochiladi (Splash orqali,
            // u consent/initial-scan flag'larini o'zi tekshiradi).
            val openIntent = Intent(context, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                pendingIntentFlags(),
            )

            val statusText = computeStatusText(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("UZGUARD faol")
                .setContentText(statusText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)

            return builder.build()
        }

        private fun computeStatusText(context: Context): String {
            // ScanHistory'ning so'nggi 24 soatidagi eng yomon verdict'ga qarab matn.
            val now = System.currentTimeMillis()
            val dayAgo = now - 24L * 60 * 60 * 1000
            val recent = try {
                ScanHistory.all(context).take(30).filter { it.timestamp >= dayAgo }
            } catch (_: Throwable) { emptyList() }

            val danger = recent.count { it.verdict == ScanResult.Verdict.DANGER }
            val suspicious = recent.count { it.verdict == ScanResult.Verdict.SUSPICIOUS }

            return when {
                danger > 0 -> "$danger ta xavfli fayl topildi — bosib ko'ring"
                suspicious > 0 -> "$suspicious ta shubhali fayl bor — bosib ko'ring"
                else -> "Telefoningiz himoyalangan · 24/7"
            }
        }

        private fun pendingIntentFlags(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        }
    }
}
