package com.uzguard

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Raz v sutki (priblizitel'no 21:00) shlet svodku v Telegram:
 *   - skol'ko APK proskaniroval
 *   - skol'ko THREAT/SUSPICIOUS/SAFE
 *   - top podozritel'nyh paketov za den'
 *
 * Beret dannye iz ScanHistory (poslednie 24 chasa).
 */
class DailyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            if (!TelemetryReporter.isConfigured(ctx)) {
                return Result.success()
            }
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val entries = try { ScanHistory.all(ctx) } catch (_: Throwable) { emptyList() }
            val last24h = entries.filter { it.timestamp >= cutoff }

            val total = last24h.size
            val danger = last24h.count { it.verdict == ScanResult.Verdict.DANGER }
            val susp = last24h.count { it.verdict == ScanResult.Verdict.SUSPICIOUS }
            val safe = last24h.count { it.verdict == ScanResult.Verdict.SAFE }
            val topRisk = last24h
                .filter { it.verdict != ScanResult.Verdict.SAFE }
                .groupBy { it.apkName }
                .entries.sortedByDescending { it.value.size }
                .take(5)
                .joinToString("\n") { (name, list) -> "  • $name (${list.size})" }

            val body = buildString {
                append("📦 Skanlash: $total\n")
                append("🚫 Tahdid: $danger\n")
                append("⚠️ Shubhali: $susp\n")
                append("✅ Xavfsiz: $safe")
                if (topRisk.isNotBlank()) append("\n\nTOP shubhali:\n$topRisk")
            }
            TelemetryReporter.reportDailyReport(ctx, body)
            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "daily report failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "DailyReportWorker"
        private const val WORK_NAME = "uzguard_daily_report"

        /**
         * Stavyem na ~21:00 lokalnogo vremeni. PeriodicWorkRequest ne podderzhivaet
         * tochnyj cron, no my mozhem postavit' initial delay do blizhajshego 21:00,
         * a potom period 24 chasa — WorkManager budet primerno popadat' v eto vremya.
         */
        fun schedule(ctx: Context) {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val initialDelayMin = (target.timeInMillis - now.timeInMillis) / 1000 / 60

            val req = PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
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
