package com.uzguard

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Наблюдатель за приложениями с доступом к УВЕДОМЛЕНИЯМ
 * (`enabled_notification_listeners`).
 *
 * Современные банкеры крадут OTP БЕЗ разрешения RECEIVE_SMS — они читают пуш-уведомления
 * банка/Telegram через NotificationListenerService. Доступ к уведомлениям — публичная
 * настройка, читается без спец-permission (как и [AccessibilityWatcher]). Появился новый
 * non-system listener → алерт в Telegram + локальный heads-up.
 *
 * [checkNow] вызывается в реальном времени (установка пакета / разблокировка), а не только
 * раз в 4 часа. Whitelist: сам UzGuard (PhishingNotificationService) + системные пакеты.
 */
class NotificationAccessWatcher(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val current = readEnabledListeners(ctx)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prevRaw = prefs.getString(KEY_PREV, "") ?: ""
            val prev = if (prevRaw.isBlank()) emptySet() else prevRaw.split('|').toSet()
            val newOnes = current - prev

            for (comp in newOnes) {
                val pkg = comp.substringBefore('/')
                if (pkg == ctx.packageName || pkg == "${ctx.packageName}.debug") continue
                if (isSystem(pkg)) continue

                Log.w(TAG, "New notification listener: $comp")
                val label = try {
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) { pkg }
                try {
                    TelemetryReporter.report(
                        ctx, "NOTIF_ACCESS",
                        "🔔 Bildirishnomalarga ruxsat berildi:\n" +
                            "📦 $label ($pkg)\n" +
                            "(Banker OTP kodlarini shu yo'l bilan o'g'irlaydi)"
                    )
                } catch (_: Throwable) {}
                try {
                    NotificationHelper.showNotificationAccessThreatNotification(ctx, pkg, label)
                } catch (_: Throwable) {}
            }

            prefs.edit().putString(KEY_PREV, current.joinToString("|")).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "notif-access check failed", e)
        }
        return Result.success()
    }

    private fun readEnabledListeners(ctx: Context): Set<String> = try {
        // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS @hide ba'zi API'larda — literal kalit ishonchli.
        val raw = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        if (raw.isBlank()) emptySet() else raw.split(':').filter { it.isNotBlank() }.toSet()
    } catch (e: Throwable) {
        Log.w(TAG, "readEnabledListeners failed", e)
        emptySet()
    }

    private fun isSystem(pkg: String): Boolean =
        pkg.startsWith("com.google.android.") ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.samsung.") ||
            pkg.startsWith("com.sec.") ||
            pkg.startsWith("com.miui.") ||
            pkg.startsWith("com.xiaomi.")

    companion object {
        private const val TAG = "NotifAccessWatcher"
        private const val PREFS = "uzguard_notiflisten"
        private const val KEY_PREV = "prev_listeners"
        private const val WORK_NAME = "uzguard_notif_access_watch"
        private const val WORK_NOW = "uzguard_notif_access_now"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<NotificationAccessWatcher>(4, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }

        /** Bir martalik DARHOL tekshiruv (paket o'rnatilganda / ekran ochilganda). */
        fun checkNow(ctx: Context) {
            try {
                // AccessibilityWatcher kabi APPEND_OR_REPLACE: "paket o'rnatildi" + ketма-ket
                // "bildirishnoma ruxsati berildi" ikki yaqin ivent kelganda, KEEP ikkinchisini
                // tashlab yuborardi (OTP-stiler faqat 4 soatlik periodikда ushlanardi).
                val req = OneTimeWorkRequestBuilder<NotificationAccessWatcher>().build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req
                )
            } catch (e: Throwable) {
                Log.w(TAG, "checkNow failed", e)
            }
        }
    }
}
