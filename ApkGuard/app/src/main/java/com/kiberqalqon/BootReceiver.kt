package com.kiberqalqon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-registered receiver. BOOT_COMPLETED dlya nego — edinstvennoe sobytie,
 * kotoroe gosudarstvenno garantirovano dostavlyaetsya dazhe esli process KiberQalqon
 * ne podnyat. My ispol'zuem ego dlya:
 *  1) Otpravit' event v Telegram chto telefon perezagruzilsya.
 *  2) Razbudit' WorkManager — chtoby HeartbeatWorker/GuardWorker prishli v dvizhenie
 *     dazhe esli posle reboot'a OS prishibla naskhi servisy.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        // BG-01: MY_PACKAGE_REPLACED (ilova yangilangach) ham shu yerda — bu broadcast fon'dan
        // foreground-service start cheklovidan ISTISNO, shuning uchun yangilanishdan keyin real-time
        // himoyani ishonchli qaytaradi (App.onCreate fon'dan startForegroundService chaqirsa Android
        // 12+ da rad etardi va himoya ilova qo'lда ochilmaguncha o'lik qolardi).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        Log.d(TAG, "Boot/package-replaced: ${intent.action}")
        val app = context.applicationContext
        try {
            TelemetryReporter.reportBootCompleted(app)
        } catch (e: Throwable) {
            Log.w(TAG, "boot telemetry failed", e)
        }
        // Boot tugagach doimiy himoya bildirishnomasini ham qaytadan ko'taramiz.
        // App.onCreate'da ham boshlanadi, lekin boot'dan keyin Application class
        // hali bir muncha vaqt ko'tarilmasligi mumkin — bu duplikat ham foydali.
        if (Config.isBackgroundEnabled(app)) {
            try {
                ProtectionService.start(app)
            } catch (e: Throwable) {
                Log.w(TAG, "ProtectionService start failed at boot", e)
            }
        }

        // Eski xato: boot'dan keyin foydalanuvchi MainActivity'ni ochmaguncha
        // FileObserver/WorkManager ishlamasdi. Boot signali — yagona kafolatlangan
        // hodisa, shuning uchun bu yerda kuzatuv tizimini darhol qaytadan ishga tushiramiz.
        try {
            HeartbeatWorker.schedule(app)
        } catch (e: Throwable) {
            Log.w(TAG, "heartbeat schedule failed", e)
        }
        try {
            DailyReportWorker.schedule(app)
        } catch (e: Throwable) {
            Log.w(TAG, "daily schedule failed", e)
        }
        try {
            InstalledAppsRescanWorker.schedule(app)
        } catch (e: Throwable) {
            Log.w(TAG, "installed rescan schedule failed", e)
        }
        try {
            AccessibilityWatcher.schedule(app)
        } catch (e: Throwable) {
            Log.w(TAG, "a11y watcher failed", e)
        }
        try {
            NotificationAccessWatcher.schedule(app)
        } catch (e: Throwable) {
            Log.w(TAG, "notif-access watcher failed", e)
        }
        // Periodik full-sweep GuardWorker (15 daqiqa) — App.scheduleGuardWork bilan BIR XIL
        // unique nom va flag (UPDATE policy → bitta zanjir, reboot'dan keyin ham to'liq skan).
        try {
            GuardWorker.schedulePeriodic(app)
        } catch (e: Throwable) {
            Log.w(TAG, "GuardWorker schedule failed", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
