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
 * Наблюдатель за списком включённых Accessibility-сервисов.
 *
 * Большинство современных Android-трояны для UZ-рынка работают через
 * AccessibilityService — после установки они уговаривают пользователя
 * включить им accessibility в Settings, после чего могут:
 *   - читать содержимое любого экрана (банк, Telegram)
 *   - имитировать клики (подтверждать переводы)
 *   - блокировать диалог "удалить приложение"
 *
 * Мы не имеем самих accessibility-привилегий (нам они не нужны), но можем
 * читать Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES — это публичная
 * настройка, доступна без специальных permission.
 *
 * Раз в N часов сравниваем текущий список с прошлым. Если появилась новая
 * запись — алертим в Telegram + локальное уведомление.
 *
 * Whitelist: TalkBack и Switch Access от Google (com.google.android.marvin.talkback,
 * com.google.android.apps.accessibility.*) — они системные, не алертим.
 */
class AccessibilityWatcher(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val current = readEnabledServices(ctx)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prevRaw = prefs.getString(KEY_PREV, "") ?: ""
            val prev = if (prevRaw.isBlank()) emptySet() else prevRaw.split('|').toSet()
            val newServices = current - prev

            for (svc in newServices) {
                val pkg = svc.substringBefore('/')
                if (pkg in WHITELIST_PACKAGES) continue
                if (isSystemAccessibility(pkg)) continue

                Log.w(TAG, "New accessibility service: $svc")
                try {
                    TelemetryReporter.reportAccessibilityGranted(ctx, pkg)
                } catch (_: Throwable) {}
                // Telegram'dan tashqari — QURILMADA ham heads-up alert (foydalanuvchi
                // Telegram'ni sozlamagan bo'lishi mumkin; banker aynan shu paytda boshqaradi).
                val label = try {
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) { pkg }
                try {
                    NotificationHelper.showAccessibilityThreatNotification(ctx, pkg, label)
                } catch (_: Throwable) {}
            }

            prefs.edit().putString(KEY_PREV, current.joinToString("|")).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "accessibility check failed", e)
        }
        return Result.success()
    }

    private fun readEnabledServices(ctx: Context): Set<String> {
        return try {
            val raw = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (raw.isBlank()) emptySet() else raw.split(':').filter { it.isNotBlank() }.toSet()
        } catch (e: Throwable) {
            Log.w(TAG, "readEnabledServices failed", e)
            emptySet()
        }
    }

    private fun isSystemAccessibility(pkg: String): Boolean {
        return pkg.startsWith("com.google.android.") ||
                pkg.startsWith("com.android.") ||
                pkg.startsWith("com.samsung.accessibility")
    }

    companion object {
        private const val TAG = "AccessibilityWatcher"
        private const val PREFS = "uzguard_a11y"
        private const val KEY_PREV = "prev_services"
        private const val WORK_NAME = "uzguard_a11y_watch"
        private const val WORK_NOW = "uzguard_a11y_check_now"

        private val WHITELIST_PACKAGES = setOf(
            "com.google.android.marvin.talkback",
            "com.google.android.apps.accessibility.voiceaccess",
            "com.google.android.apps.accessibility.auditor",
            "com.android.settings",
            "com.samsung.accessibility"
        )

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AccessibilityWatcher>(
                4, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        /**
         * Bir martalik DARHOL tekshiruv — paket o'rnatilganda / ekran ochilganda chaqiriladi,
         * shunda accessibility-abuse 4 soat kutmasdan, real vaqtda ushlanadi. KEEP bilan
         * tez-tez chaqirilsa ham bir necha soniya ichida takrorlanmaydi.
         */
        fun checkNow(ctx: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<AccessibilityWatcher>().build()
                // APPEND_OR_REPLACE: ikkita ivent ketma-ket kelsa (paket o'rnatildi + darhol
                // accessibility yoqildi), KEEP ikkinchisini TASHLAB yuborardi va yangi xizmat
                // faqat 4 soatlik periodikda tutilardi. Endi har real-time ivent tekshiriladi.
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req
                )
            } catch (e: Throwable) {
                Log.w(TAG, "checkNow failed", e)
            }
        }
    }
}
