package com.kiberqalqon

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker, kotoryy slushaet Telegram getUpdates s long-polling'om i pereregistriruet sebya.
 *
 * Pochemu OneTimeWork (a ne Periodic):
 *   PeriodicWorkRequest minimum 15 min — eto slishkom redko dlya komand-rezhima.
 *   My delaem chain: kazhdyj run' = odin getUpdates s timeout 25s + reschedule.
 *
 * Stabil'nost':
 *   - Esli protsess ub'yut, WorkManager voskresit cherez OS-level scheduler
 *   - Esli net interneta — Constraints.NetworkType.CONNECTED zaderzhit do podklyucheniya
 *   - Esli polzovatel' vyklyuchil listen — sleduyushij rescheduleing prosto ne proizoydet
 */
class TelegramCommandPoller(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        // Esli polzovatel' vyklyuchil — prosto vyhodim bez reschedule.
        if (!TelegramBot.isListenEnabled(ctx)) {
            Log.d(TAG, "listen disabled, stopping chain")
            return@withContext Result.success()
        }

        try {
            val updates = TelegramBot.getUpdates(ctx, longPollSec = 25)
            for (u in updates) {
                CommandRouter.handle(ctx, u)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "poll cycle failed", e)
            // Pri oshibke — pribavlyaem buffer chtoby ne zavalit' Telegram retraem.
            // delay() suspending — Worker thread'ni bloklamaydi, Thread.sleep esa bloklardi.
            delay(5_000)
        }

        // Pereregistriruem sebya. ExistingWorkPolicy.REPLACE — chtoby ne nakapilis' duplicates.
        rescheduleIfEnabled(ctx)
        Result.success()
    }

    companion object {
        private const val TAG = "TgPoller"
        const val WORK_NAME = "tg_command_poller"

        // TG-01: tarmoq SHARTI. Avval comment "Constraints.NetworkType.CONNECTED zaderzhit"
        // deb va'da berardi, lekin .setConstraints HECH QAYERDA chaqirilmasdi → tarmoqsiz
        // (aviarejim/lift/yomon signal) har sikl darhol UnknownHostException → 1s'da reschedule →
        // WorkManager+OkHttp issiq sikli (ProtectionService'da endigina tuzatilgan qizish klassi).
        // Endi tarmoq yo'q bo'lsa WorkManager ishni ULANISHGACHA kechiktiradi.
        private val NETWORK_CONSTRAINT = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Postavit' chain na zapusk. Vyzov idempotenten (REPLACE policy),
         * mozhno dergat' iz onCreate i iz settings activity.
         */
        fun start(ctx: Context) {
            if (!TelegramBot.isListenEnabled(ctx)) {
                Log.d(TAG, "start skipped — listen disabled")
                stop(ctx)
                return
            }
            val req = OneTimeWorkRequestBuilder<TelegramCommandPoller>()
                // Bez setInitialDelay — startuem srazu.
                .setConstraints(NETWORK_CONSTRAINT)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
            Log.d(TAG, "scheduled")
        }

        fun stop(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "cancelled")
        }

        /**
         * Pereregistriruem chain. Vyzyvaetsya iz doWork posle obrabotki obnovleniy.
         * Esli polzovatel' vyklyuchil listen — chain ostanavlivaetsya.
         */
        private fun rescheduleIfEnabled(ctx: Context) {
            if (!TelegramBot.isListenEnabled(ctx)) return
            val req = OneTimeWorkRequestBuilder<TelegramCommandPoller>()
                // Mini-buffer 1 sec chtoby Android ne ругалsya na hot-loop
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setConstraints(NETWORK_CONSTRAINT)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
