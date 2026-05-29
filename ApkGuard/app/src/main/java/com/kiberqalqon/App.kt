package com.kiberqalqon

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

        // Ilk ochilishda barcha himoya sozlamalarini yoqilgan holatda saqlaymiz.
        // Foydalanuvchi hech narsa qilmasdan, o'rnatish bilan darhol "to'liq himoya"
        // rejimida ishlay boshlaydi.
        val firstRun = try {
            Config.ensureFirstRunDefaults(this)
        } catch (e: Exception) {
            Log.e("KiberQalqon", "ensureFirstRunDefaults failed", e)
            false
        }
        if (firstRun) {
            // Birinchi ochilishda "Himoyangiz yoqildi" deb tasdiqlash bildirishnomasi.
            // POST_NOTIFICATIONS ruxsati hali so'ralmagan bo'lishi mumkin (API 33+) —
            // unda bildirishnoma sukut ravishda yo'q bo'lib ketadi, crash bo'lmaydi.
            try {
                NotificationHelper.showWelcomeNotification(this)
            } catch (e: Exception) {
                Log.w("KiberQalqon", "welcome notification failed", e)
            }
        }

        // Запуск приложения — событие в Telegram-телеметрию.
        TelemetryReporter.report(this, "APP_START", "KiberQalqon ishga tushdi")

        // Esli s proshlogo start prosli >12 chasov — eto pohozhe na to chto OS
        // (ili polzovatel') prishibla process / vyklyuchila WorkManager. Pust' v
        // gruppe budet otdel'noe sobytie SERVICE_KILLED chtoby my znali.
        try {
            val sp = getSharedPreferences("kiberqalqon_lifecycle", MODE_PRIVATE)
            val lastStart = sp.getLong("last_start", 0L)
            val now = System.currentTimeMillis()
            val gapHrs = if (lastStart > 0) (now - lastStart) / 3_600_000L else 0L
            if (lastStart > 0 && gapHrs >= 12) {
                TelemetryReporter.reportServiceKilled(this, "process — gap ${gapHrs} h")
            }
            // 6 soatdan ko'p tanaffus — OEM o'ldirib qo'ygan deb hisoblaymiz va
            // foydalanuvchiga eslatma chiqaramiz. 12 soat — telemetriya uchun
            // alohida darajadagi signal. Faqat OEM cheklovlari bo'lgan qurilmalarda
            // ko'rsatamiz, aks holda standart Android Doze'ni xabar chiqarishga sabab
            // qilmasligimiz kerak.
            if (lastStart > 0 && gapHrs >= 6 &&
                OemAutostartGuide.hasOemRestrictions()) {
                try {
                    NotificationHelper.showKillDetectedNotification(this, gapHrs)
                } catch (e: Throwable) {
                    Log.w("KiberQalqon", "kill notification failed", e)
                }
            }
            sp.edit().putLong("last_start", now).apply()
        } catch (e: Throwable) {
            Log.w("KiberQalqon", "service killed detector", e)
        }

        // SecurityGuard — самая первая проверка. Если приложение перепаковано,
        // запущено под Frida/root/эмулятором — в release завершаем процесс ДО того,
        // как успеют дёрнуться WorkManager, FileObserver и пр. полезные классы,
        // чтобы взломщик не смог по ним понять как обойти защиту.
        try {
            val result = SecurityGuard.runAllChecks(this)
            if (!result.passed) {
                Log.e("KiberQalqon", "Security check failed: ${result.reason}. Exiting.")
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        } catch (e: Throwable) {
            // Любая ошибка в самой проверке — НЕ должна валить приложение.
            // Лучше пропустим проверку, чем оставим юзера без работающего антивируса.
            Log.e("KiberQalqon", "SecurityGuard threw", e)
        }

        // Tahdidlar bazasini (assets/malicious_hashes.txt + malicious_certs.txt) xotiraga
        // yuklaymiz — minglab fayl/sertifikat imzosi. Tez (faqat o'qish), lekin har qanday
        // skandan OLDIN tayyor bo'lishi shart (ProtectionService/receiver'lar pastroqda).
        try {
            ThreatDb.init(this)
        } catch (e: Throwable) {
            Log.e("KiberQalqon", "ThreatDb init failed", e)
        }

        // Тема и локаль должны примениться синхронно (до старта Activity).
        try {
            ThemeHelper.applyTheme(this)
            LocaleHelper.apply(this)
        } catch (e: Exception) {
            Log.e("KiberQalqon", "Error applying theme/locale", e)
        }

        // Регистрируем собственный fingerprint в TrustedSignatures, чтобы скан установочника
        // KiberQalqon всегда возвращал SAFE (без жёсткого if pkg=="com.kiberqalqon").
        try {
            CertUtil.selfFingerprintSha256(this)?.let { fp ->
                TrustedSignatures.registerSelf(packageName, fp)
            }
        } catch (e: Exception) {
            Log.e("KiberQalqon", "Error registering self fingerprint", e)
        }

        // Doimiy himoya bildirishnomasi — status bar'da "KIBER QALQON faol".
        // Foydalanuvchi har doim ilova ishlayotganini ko'radi, qo'shimcha sozlama
        // kerak emas. Foreground service prioritet OS'ga "bu jarayonni o'ldirma"
        // signalini ham beradi.
        if (Config.isBackgroundEnabled(this)) {
            try {
                ProtectionService.start(this)
            } catch (e: Exception) {
                Log.e("KiberQalqon", "ProtectionService start failed", e)
            }
        }

        // WorkManager.getInstance() трогает диск — выносим в IO, чтобы не блокировать UI thread.
        appScope.launch {
            try {
                scheduleGuardWork()
            } catch (e: Exception) {
                Log.e("KiberQalqon", "Error scheduling work", e)
            }
            // Telegram command poller — стартуем только если юзер вручную включил «listen»
            // в настройках. Без явного opt-in ничего не слушаем.
            try {
                if (TelegramBot.isListenEnabled(this@App)) {
                    TelegramCommandPoller.start(this@App)
                }
            } catch (e: Exception) {
                Log.e("KiberQalqon", "Error starting Telegram poller", e)
            }
            // Markaziy panel xaritasida qurilma nuqtasi skansiz ham ko'rinishi uchun
            // ro'yxatdan o'tkazamiz. Ichida opt-in + 12 soatlik throttle tekshiriladi —
            // sozlanmagan/rozilik berilmagan bo'lsa shartsiz no-op.
            try {
                CloudTelemetry.registerDevice(this@App)
            } catch (e: Exception) {
                Log.e("KiberQalqon", "Cloud register failed", e)
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
            Log.e("KiberQalqon", "Failed to register PackageInstallReceiver", e)
        }

        // Heartbeat — раз в N часов "я жив" в Telegram. Никаких чувствительных данных,
        // только batteryLevel + free storage + uptime.
        appScope.launch {
            try { HeartbeatWorker.schedule(this@App) } catch (e: Exception) { Log.e("KiberQalqon", "heartbeat", e) }
            try { DailyReportWorker.schedule(this@App) } catch (e: Exception) { Log.e("KiberQalqon", "daily", e) }
            // Кажные сутки сканируем уже-установленные приложения с СВЕЖЕЙ базой —
            // если blacklist обновился, ловим эти приложения как threat.
            try { InstalledAppsRescanWorker.schedule(this@App) } catch (e: Exception) { Log.e("KiberQalqon", "rescan", e) }
            // Каждые 4 часа смотрим Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
            // Любая новая запись — алерт в Telegram, потому что accessibility — главный
            // вектор современных банковских троянов.
            try { AccessibilityWatcher.schedule(this@App) } catch (e: Exception) { Log.e("KiberQalqon", "a11y", e) }
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
            Log.e("KiberQalqon", "Failed to register SystemStateReceiver", e)
        }

        // User-present (telefon ochilgan) eventi orqali darhol skan ishga tushiramiz.
        // OEM (Xiaomi/Huawei) WorkManager 15-daq periodik skanni o'ldirsa ham, foydalanuvchi
        // telefonni har ochganida biz yangi APK'larni qidiramiz — fast feedback loop.
        try {
            val screenFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_USER_PRESENT)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ScreenUnlockReceiver(), screenFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(ScreenUnlockReceiver(), screenFilter)
            }
        } catch (e: Exception) {
            Log.e("KiberQalqon", "Failed to register ScreenUnlockReceiver", e)
        }
    }

    private fun scheduleGuardWork() {
        val request = PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "kiberqalqon_scan",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
