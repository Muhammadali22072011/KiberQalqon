/*
 *  #### #  # #### #  #    #  # #### #  #     ← BEGINNING
 *  #    #  # #    # #     #  # #  # #  #
 *  ###  #  # #    ##      #### #  # #  #
 *  #    #  # #    # #       #  #  # #  #
 *  #    #### #### #  #      #  #### ####
 *  Bu kod Muhammadaliniki. O'g'irlama. — UzGuard
 */
package com.uzguard

import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : android.app.Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // САМАЯ ПЕРВАЯ строка: ставим CrashHandler чтобы поймать ВСЁ что упадёт ниже.
        CrashHandler.install(this)

        // Agar sirena chalinayotganda jarayon nobud bo'lgan bo'lsa — o'zgartirilgan ALARM
        // balandligini tiklaymiz (saqlangan qiymat bo'lsa; aks holda no-op).
        try { AlarmSiren.recover(this) } catch (_: Throwable) {}

        // Native himoya kutubxonasini (libkqguard.so) erta yuklab qo'yamiz, SecurityGuard'gacha.
        // Yuklanmasa (test JVM / ABI mos emas / NDK'siz build) crash BO'LMAYDI — NativeBridge
        // ichida ushlanadi, chaqiruvchilar Kotlin fallback'iga tushadi.
        NativeBridge.init()

        // Salom dekompilyatorlarga (logcat + DEX string). Foydalanuvchi ko'rmaydi.
        EasterEgg.stamp()

        // Ilk ochilishda barcha himoya sozlamalarini yoqilgan holatda saqlaymiz.
        // Foydalanuvchi hech narsa qilmasdan, o'rnatish bilan darhol "to'liq himoya"
        // rejimida ishlay boshlaydi.
        // Standart sozlamalarni "запекаем" (fon, tovush, sensitivity va h.k.) — lekin "Himoyangiz
        // yoqildi" bildirishnomasi BU YERDA CHIQARILMAYDI. Avval birinchi ochilishda ruxsatdan OLDIN
        // chiqib, yolg'on "himoyalangan" ko'rsatardi. Endi welcome FAQAT himoya haqiqatan faollashganda
        // (ruxsat berilgach) [ProtectionActivator] orqali bir marta ko'rsatiladi.
        try {
            Config.ensureFirstRunDefaults(this)
        } catch (e: Exception) {
            Log.e("UzGuard", "ensureFirstRunDefaults failed", e)
        }

        // Запуск приложения — событие в Telegram-телеметрию.
        TelemetryReporter.report(this, "APP_START", "UzGuard ishga tushdi")

        // Esli s proshlogo start prosli >12 chasov — eto pohozhe na to chto OS
        // (ili polzovatel') prishibla process / vyklyuchila WorkManager. Pust' v
        // gruppe budet otdel'noe sobytie SERVICE_KILLED chtoby my znali.
        try {
            val sp = getSharedPreferences("uzguard_lifecycle", MODE_PRIVATE)
            val lastStart = sp.getLong("last_start", 0L)
            val now = System.currentTimeMillis()
            val gapMs = if (lastStart > 0) now - lastStart else 0L
            val gapHrs = gapMs / 3_600_000L
            // BG: tanaffusni "o'ldirildi" deb sanashdan oldin TELEFON O'CHIQ bo'lganini chiqarib
            // tashlaymiz. elapsedRealtime() = ребутдан beri o'tgan vaqt (telefon o'chsa nolга tushadi).
            // Agar gap > uptime bo'lsa, demak shu tanaffus ichida qurilma o'chgan/ребут bo'lgan —
            // bu OEM-kill emas (kechasi o'chirib qo'yilgan telefon har erta "himoyani o'ldirdi" deb
            // qo'rqitmasin). BootReceiver bunday holatda himoyani allaqachon ko'taradi.
            val wasPoweredOff = gapMs > android.os.SystemClock.elapsedRealtime()
            if (lastStart > 0 && gapHrs >= 12 && !wasPoweredOff) {
                TelemetryReporter.reportServiceKilled(this, "process — gap ${gapHrs} h")
            }
            // 6 soatdan ko'p UZLUKSIZ tanaffus (telefon yoniq turib) — OEM o'ldirib qo'ygan deb
            // hisoblaymiz va eslatma chiqaramiz. Faqat OEM cheklovlari bor qurilmalarda.
            if (lastStart > 0 && gapHrs >= 6 && !wasPoweredOff &&
                OemAutostartGuide.hasOemRestrictions()) {
                try {
                    NotificationHelper.showKillDetectedNotification(this, gapHrs)
                } catch (e: Throwable) {
                    Log.w("UzGuard", "kill notification failed", e)
                }
            }
            sp.edit().putLong("last_start", now).apply()
        } catch (e: Throwable) {
            Log.w("UzGuard", "service killed detector", e)
        }

        // SecurityGuard — самая первая проверка. Если приложение перепаковано,
        // запущено под Frida/root/эмулятором — в release завершаем процесс ДО того,
        // как успеют дёрнуться WorkManager, FileObserver и пр. полезные классы,
        // чтобы взломщик не смог по ним понять как обойти защиту.
        try {
            val result = SecurityGuard.runAllChecks(this)
            if (!result.passed) {
                Log.e("UzGuard", "Security check failed: ${result.reason}. Exiting.")
                // SD-02: jim o'ldirishdan oldin foydalanuvchiga sababni узбекча tushuntiramiz.
                try { NotificationHelper.showSecurityBlockNotification(this, result.reason) } catch (_: Throwable) {}
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        } catch (e: Throwable) {
            // Любая ошибка в самой проверке — НЕ должна валить приложение.
            // Лучше пропустим проверку, чем оставим юзера без работающего антивируса.
            Log.e("UzGuard", "SecurityGuard threw", e)
        }

        // Tahdidlar bazasini (assets/malicious_hashes.txt + malicious_certs.txt = ~730 KB,
        // ~9700 qator) xotiraga yuklaymiz. PERF (lag): avval bu MAIN thread'da onCreate ichida
        // sinxron parse qilinardi → sovuq start sekinlashardi (time-to-first-frame yo'lida).
        // Endi FON thread'da yuklaymiz. XAVFSIZLIK: skan bu bazaga tayyor bo'lishidan oldin
        // ishlamasligi SHART (aks holda feed'dagi virus o'tib ketib false-SAFE bo'lishi mumkin).
        // Buni ApkScanner.scan() boshida ThreatDb.init(context) qayta chaqirib kafolatlaymiz —
        // init() idempotent + synchronized, shuning uchun u yuklash oynasida skan thread'ini
        // KUTTIRADI (faqat birinchi skan, u ham fon thread'da — UI bloklanmaydi).
        appScope.launch(Dispatchers.IO) {
            try {
                ThreatDb.init(this@App)
                // Bulutdan yangilanadigan qora ro'yxat (keshlangan) — assets ustiga qo'shamiz.
                // Tarmoq YO'Q: faqat avval tekshirilgan keshni ThreatDb'ga merge qiladi.
                CloudBlacklist.loadCached(this@App)
            } catch (e: Throwable) {
                Log.e("UzGuard", "ThreatDb init failed", e)
            }
        }

        // Тема и локаль должны примениться синхронно (до старта Activity).
        try {
            ThemeHelper.applyTheme(this)
            LocaleHelper.apply(this)
        } catch (e: Exception) {
            Log.e("UzGuard", "Error applying theme/locale", e)
        }

        // Регистрируем собственный fingerprint в TrustedSignatures, чтобы скан установочника
        // UzGuard всегда возвращал SAFE (без жёсткого if pkg=="com.uzguard").
        try {
            CertUtil.selfFingerprintSha256(this)?.let { fp ->
                TrustedSignatures.registerSelf(packageName, fp)
            }
        } catch (e: Exception) {
            Log.e("UzGuard", "Error registering self fingerprint", e)
        }

        // Doimiy himoya xizmati — FAQAT himoya haqiqatan ishlay olganda (fon yoqilgan + fayl ruxsati
        // bor) boshlanadi va shundagina "UZGUARD faol · himoyalangan" bildirishnomasi chiqadi.
        // Ruxsatdan oldin yolg'on "himoyalangan" KO'RSATILMAYDI (foydalanuvchi talabi). Ruxsat
        // berilgach, Activity onResume (Splash/Dashboard/Main/Himoya holati) shu yerdan qayta yoqadi.
        // (Birinchi ochilishda fon'dan startForegroundService Android 12+ da rad etilishi mumkin —
        //  shuning uchun asosiy ishonchli yoqish nuqtasi — foreground Activity onResume.)
        ProtectionActivator.activateIfReady(this)

        // DNS C2-filtri (opt-in): foydalanuvchi Settings'da yoqqan VA tizim VPN ruxsati
        // hali amal qilsa (prepare == null) — qayta ishga tushiramiz (reboot/process-kill'dan
        // keyin). Ruxsat bekor qilingan bo'lsa jim qolamiz — tizim oynasini faqat
        // foydalanuvchining o'zi (Settings toggle) ochadi.
        try {
            if (Config.isVpnFilterEnabled(this) && VpnFilterService.prepareIntent(this) == null) {
                VpnFilterService.start(this)
            }
        } catch (e: Throwable) {
            Log.w("UzGuard", "VPN filter autostart failed", e)
        }

        // WorkManager.getInstance() диск, CloudBlacklist.refresh/RemoteConfig — сеть (OkHttp .execute),
        // captureInstalledTrusted — PackageManager IO. Всё это блокирующее → Dispatchers.IO, а не
        // Default (CPU-пул): не занимаем вычислительные потоки сетевым ожиданием.
        appScope.launch(Dispatchers.IO) {
            try {
                scheduleGuardWork()
            } catch (e: Exception) {
                Log.e("UzGuard", "Error scheduling work", e)
            }
            // Telegram command poller — стартуем только если юзер вручную включил «listen»
            // в настройках. Без явного opt-in ничего не слушаем.
            try {
                if (TelegramBot.isListenEnabled(this@App)) {
                    TelegramCommandPoller.start(this@App)
                }
            } catch (e: Exception) {
                Log.e("UzGuard", "Error starting Telegram poller", e)
            }
            // Markaziy panel xaritasida qurilma nuqtasi skansiz ham ko'rinishi uchun
            // ro'yxatdan o'tkazamiz. Ichida opt-in + 12 soatlik throttle tekshiriladi —
            // sozlanmagan/rozilik berilmagan bo'lsa shartsiz no-op.
            try {
                CloudTelemetry.registerDevice(this@App)
                // #3: ilova ochilishi = masofaviy buyruqlarni (masofadan qayta skan) DARHOL
                // olish imkoniyati. Fon yo'li — HeartbeatWorker (~6 soat). Alohida tez tsikl yo'q.
                CloudTelemetry.pollCommands(this@App)
            } catch (e: Exception) {
                Log.e("UzGuard", "Cloud register failed", e)
            }
            // Imzolangan masofaviy config (verdikt chegaralari) ni fonda yangilaymiz — skan
            // chegaralari APK ichida ANIQ turmasin. Xato/oflayn/imzo noto'g'ri → baked standartlar.
            try {
                RemoteConfig.refresh(this@App)
            } catch (e: Throwable) {
                Log.w("UzGuard", "RemoteConfig refresh failed", e)
            }
            // Bulut qora ro'yxatini fonda yangilaymiz (yangi hash/paketlar ilovani
            // yangilamasdan bloklanadi). Imzo majburiy; xato/oflayn → assets bazasi qoladi.
            try {
                CloudBlacklist.refresh(this@App)
            } catch (e: Throwable) {
                Log.w("UzGuard", "CloudBlacklist refresh failed", e)
            }
            // Panel joylagan yangi e'lon bo'lsa — ilova ochilishida ham darhol tekshiramiz
            // (GuardWorker'gacha kutmasdan). Ichki throttle/seed/dedup spamга yo'l qo'ymaydi.
            try {
                NewsNotifier.checkAndNotify(this@App)
                // Ekran o'chiq / Doze'da ham yetkazish: AlarmManager uyg'otish zanjirini boshlaymiz
                // (ProtectionService loop'i CPU uxlaganda muzlaydi — alarm uni qoplaydi).
                NewsNotifier.scheduleNext(this@App)
            } catch (e: Throwable) {
                Log.w("UzGuard", "News notify check failed", e)
            }
            // Ilovaning O'ZI uchun yangi versiya bormi (imzolangan config'dagi "update" bloki) —
            // bo'lsa bir martalik bildirishnoma. Sideload'da Play yo'q, yangilanish shu yo'l bilan.
            try {
                SelfUpdate.checkAndNotify(this@App)
            } catch (e: Throwable) {
                Log.w("UzGuard", "SelfUpdate check failed", e)
            }
            // Qurilmadagi o'rnatilgan ishonchli ilovalarning (Play'dan) sertifikatini pin
            // qilamiz — offline skanda false-positive'ni kamaytiradi (TrustedSignatures).
            try {
                TrustedSignatures.captureInstalledTrusted(this@App)
            } catch (e: Throwable) {
                Log.w("UzGuard", "TrustedSignatures capture failed", e)
            }
        }

        // Слушаем установку/обновление/удаление пакетов. Зарегистрирован динамически,
        // потому что на API 26+ broadcasts PACKAGE_ADDED/REPLACED/REMOVED не приходят
        // к manifest-receivers (защита Android от broadcast-wakeup всех приложений).
        // Пока процесс жив (а наш WorkManager поднимает его каждые 15 минут) — мы получаем события.
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
                addAction(android.content.Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
            // На API 33+ обязательно указать exported/not-exported. Системные broadcasts.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(PackageInstallReceiver(), filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(PackageInstallReceiver(), filter)
            }
        } catch (e: Exception) {
            Log.e("UzGuard", "Failed to register PackageInstallReceiver", e)
        }

        // Heartbeat — раз в N часов "я жив" в Telegram. Никаких чувствительных данных,
        // только batteryLevel + free storage + uptime.
        appScope.launch {
            try { HeartbeatWorker.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "heartbeat", e) }
            try { DailyReportWorker.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "daily", e) }
            // Haftalik hisobot bildirishnomasi (skanlar/bloklangan/karantin) — har hafta 20:00.
            try { WeeklyReportWorker.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "weekly schedule", e) }
            // Кажные сутки сканируем уже-установленные приложения с СВЕЖЕЙ базой —
            // если blacklist обновился, ловим эти приложения как threat.
            try { InstalledAppsRescanWorker.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "rescan", e) }
            // Каждые 4 часа смотрим Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
            // Любая новая запись — алерт в Telegram, потому что accessibility — главный
            // вектор современных банковских троянов.
            try { AccessibilityWatcher.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "a11y", e) }
            // Каждые 4 часа смотрим enabled_notification_listeners — кража OTP через доступ
            // к уведомлениям (банкеры воруют коды без RECEIVE_SMS).
            try { NotificationAccessWatcher.schedule(this@App) } catch (e: Exception) { Log.e("UzGuard", "notifaccess", e) }
        }

        // Slushaem state SIM/airplane/screen — vse v odnom dinamicheskom receivere,
        // potomu chto Android 8+ ne dostavlyaet ih cherez manifest. BOOT_COMPLETED
        // ne nuzhen tut (eto manifest-only event), pust' ostaetsya v BootReceiver.
        try {
            val sysFilter = android.content.IntentFilter().apply {
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(SystemStateReceiver(), sysFilter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(SystemStateReceiver(), sysFilter)
            }
        } catch (e: Exception) {
            Log.e("UzGuard", "Failed to register SystemStateReceiver", e)
        }

        // User-present (telefon QULFDAN chiqarilgan) eventi orqali darhol skan ishga tushiramiz.
        // OEM (Xiaomi/Huawei) WorkManager 15-daq periodik skanni o'ldirsa ham, foydalanuvchi
        // telefonni har ochganida biz yangi APK'larni qidiramiz — fast feedback loop.
        // BG-03/perf: ACTION_SCREEN_ON OLIB TASHLANDI (ekran bildirishnomadan yonsa ham yurardi);
        // faqat USER_PRESENT + receiver ichida 10 daqiqalik trottling.
        try {
            val screenFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ScreenUnlockReceiver(), screenFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(ScreenUnlockReceiver(), screenFilter)
            }
        } catch (e: Exception) {
            Log.e("UzGuard", "Failed to register ScreenUnlockReceiver", e)
        }
    }

    private fun scheduleGuardWork() {
        // Yagona periodik full-sweep skaner (avvalgi GuardWorker + PeriodicCheckWorker birlashtirildi).
        GuardWorker.schedulePeriodic(this)
    }
}
