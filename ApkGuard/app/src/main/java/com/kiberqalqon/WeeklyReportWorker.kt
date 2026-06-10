package com.kiberqalqon

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Haftalik xulosa bildirishnomasi (taxminan har kuni 20:00 tekshiriladi, lekin
 * faqat oxirgi yuborishdan ≥7 kun o'tgan bo'lsa bir marta chiqadi):
 *   - hafta davomida nechta fayl tekshirildi (Statistics.weekCounts)
 *   - nechta xavfli (DANGER) topildi (oxirgi 7 kunlik ScanHistory)
 *   - nechta karantinda (Quarantine.list)
 *
 * DailyReportWorker shaklini takrorlaydi: 24 soatlik davriy ish + keyingi 20:00 gacha
 * dastlabki kechikish + prefs guard'i orqali haftada bir marta. doWork() har doim
 * Result.success() qaytaradi — hisobot xatosi himoyani buzmasligi kerak.
 */
class WeeklyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            // Foydalanuvchi haftalik hisobotni o'chirgan bo'lsa — hech narsa qilmaymiz.
            if (!Config.isWeeklyReportEnabled(ctx)) {
                return Result.success()
            }

            // Prefs guard: oxirgi yuborishdan ≥7 kun o'tgan bo'lsagina davom etamiz.
            // PeriodicWork 24 soatlik bo'lsa-da, bu haftada faqat bir marta chiqishini kafolatlaydi.
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
            if (lastTs != 0L && now - lastTs < SEVEN_DAYS_MS) {
                return Result.success()
            }

            // Tekshirilgan: joriy hafta kunlik hisoblagichlari yig'indisi.
            val scanned = try {
                Statistics.weekCounts(ctx).sum()
            } catch (e: Throwable) {
                Log.w(TAG, "weekCounts failed", e)
                0
            }

            // Bloklangan: oxirgi 7 kunlik ScanHistory'dagi DANGER verdictlar soni.
            val cutoff = now - SEVEN_DAYS_MS
            val blocked = try {
                ScanHistory.all(ctx)
                    .filter { it.timestamp >= cutoff }
                    .count { it.verdict == ScanResult.Verdict.DANGER }
            } catch (e: Throwable) {
                Log.w(TAG, "history count failed", e)
                0
            }

            // Karantinda: hozirgi haqiqiy karantin ro'yxati hajmi (statistikadan emas).
            val quarantined = try {
                Quarantine.list(ctx).size
            } catch (e: Throwable) {
                Log.w(TAG, "quarantine count failed", e)
                0
            }

            // Hech narsa tekshirilmagan bo'lsa — bildirishnoma bermaymiz (xabar qiladigan narsa yo'q),
            // lekin ts'ni baribir yangilaymiz, aks holda har kuni qayta urinaveradi.
            if (scanned == 0) {
                prefs.edit().putLong(KEY_LAST_TS, now).apply()
                return Result.success()
            }

            NotificationHelper.showWeeklyReportNotification(ctx, scanned, blocked, quarantined)
            prefs.edit().putLong(KEY_LAST_TS, now).apply()
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "weekly report failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "WeeklyReportWorker"
        const val WORK_NAME = "kiberqalqon_weekly_report"

        // weekly_report_last_ts to'g'ridan-to'g'ri shu worker tomonidan o'qiladi/yoziladi.
        private const val PREFS = "kiberqalqon_prefs"
        private const val KEY_LAST_TS = "weekly_report_last_ts"
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L

        /**
         * Taxminan 20:00 lokal vaqtga qo'yamiz. PeriodicWorkRequest aniq cron'ni
         * qo'llamaydi, shuning uchun keyingi 20:00 gacha initial delay + 24 soatlik
         * davr beramiz — WorkManager shu vaqt atrofida ishga tushiradi. Haftada bir
         * martalik chegara doWork() ichidagi prefs guard'i bilan ta'minlanadi.
         */
        fun schedule(ctx: Context) {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val initialDelayMin = (target.timeInMillis - now.timeInMillis) / 1000 / 60

            val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMin.coerceAtLeast(1), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
