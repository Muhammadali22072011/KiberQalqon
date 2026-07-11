package com.uzguard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
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

        // PERF (qizish): getUpdates() MUVAFFAQIYAT (bo'sh long-poll) va XATO (TG bloklangan /
        // 429 / 401 / DNS-poison) holatlarining IKKALASIDA ham emptyList() qaytaradi. Ularni
        // VAQT bo'yicha ajratamiz: muvaffaqiyatli bo'sh long-poll ~25s BLOKLANADI; xato esa
        // tez (sekundlardan kam) qaytadi. Tez-bo'sh qaytish = xato → eksponensial backoff.
        // Aks holda (avvalgidek 1s'da qayta urinish) UZ'da Telegram bloklanganda WorkManager +
        // OkHttp har soniyada sikl ochib telefonni QIZDIRARDI.
        var failed = false
        val started = SystemClock.elapsedRealtime()
        try {
            val result = TelegramBot.getUpdates(ctx, longPollSec = LONG_POLL_SEC)
            val elapsedMs = SystemClock.elapsedRealtime() - started
            if (result.updates.isNotEmpty()) {
                for (u in result.updates) CommandRouter.handle(ctx, u)
            }
            failed = when {
                // Chaqiruvning O'ZI ishlamadi (TG bloklangan / 429 / 401 / DNS / config yo'q) → backoff.
                !result.httpOk -> true
                // Telegram XOM update qaytardi (rawCount>0), lekin owner-gate hammasini filtrladi
                // (guruhdagi begona a'zolar suhbati) → bu XATO EMAS, backoff qilmaymiz.
                result.rawCount > 0 -> false
                // Chinakam bo'sh, lekin long-poll vaqtidan ANCHA tez qaytdi → endpoint ishlamayapti.
                elapsedMs < LONG_POLL_SEC * 1000L - FAST_RETURN_MARGIN_MS -> {
                    Log.w(TAG, "getUpdates returned empty too fast (${elapsedMs}ms) — treating as failure")
                    true
                }
                // Chinakam bo'sh, to'liq long-poll kutdi → normal bo'sh sikl.
                else -> false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "poll cycle failed", e)
            failed = true
        }

        // Keyingi siklgacha kechikish: muvaffaqiyatda darhol (1s), xatoda eksponensial backoff.
        val delaySec = if (failed) {
            backoffSeconds(bumpFailCount(ctx))
        } else {
            resetFailCount(ctx)
            NORMAL_RESCHEDULE_SEC
        }

        // Pereregistriruem sebya. ExistingWorkPolicy.REPLACE — chtoby ne nakapilis' duplicates.
        rescheduleIfEnabled(ctx, delaySec)
        Result.success()
    }

    companion object {
        private const val TAG = "TgPoller"
        const val WORK_NAME = "tg_command_poller"

        // Long-poll davomiyligi (sekund). getUpdates ANCHA tezroq qaytsa va bo'sh bo'lsa — xato.
        private const val LONG_POLL_SEC = 25
        private const val FAST_RETURN_MARGIN_MS = 5_000L
        // Muvaffaqiyatdan keyin keyingi siklgacha. PERF (batareya): ilgari 1s edi — har 26s
        // (25s long-poll + 1s) da WorkManager + tarmoq uyg'onishi, ya'ni ~138 marta/soat, 24/7.
        // 8s'ga uzaytirildi → ~108s sikl, ~33 marta/soat (≈4× kam). Buyruq kechikishi bir
        // necha soniya ortadi (masofadan boshqaruvchi odam sezmaydi); skan/aniqlash o'zgarmaydi.
        // FAQAT "listen" opt-in YOQQAN foydalanuvchilarga tegishli (default O'CHIQ).
        private const val NORMAL_RESCHEDULE_SEC = 8L
        // Xato backoff'ining yuqori chegarasi.
        private const val MAX_BACKOFF_SEC = 300L
        private const val POLL_PREFS = "uzguard_tg_poll"
        private const val KEY_FAIL_COUNT = "fail_count"

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
        private fun rescheduleIfEnabled(ctx: Context, delaySeconds: Long) {
            if (!TelegramBot.isListenEnabled(ctx)) return
            val req = OneTimeWorkRequestBuilder<TelegramCommandPoller>()
                .setInitialDelay(delaySeconds.coerceAtLeast(NORMAL_RESCHEDULE_SEC), TimeUnit.SECONDS)
                .setConstraints(NETWORK_CONSTRAINT)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        private fun pollPrefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(POLL_PREFS, Context.MODE_PRIVATE)

        /** Ketma-ket xatolar sonini oshiradi va yangi qiymatni qaytaradi. */
        private fun bumpFailCount(ctx: Context): Int {
            val n = pollPrefs(ctx).getInt(KEY_FAIL_COUNT, 0) + 1
            pollPrefs(ctx).edit().putInt(KEY_FAIL_COUNT, n).apply()
            return n
        }

        private fun resetFailCount(ctx: Context) {
            if (pollPrefs(ctx).getInt(KEY_FAIL_COUNT, 0) != 0) {
                pollPrefs(ctx).edit().putInt(KEY_FAIL_COUNT, 0).apply()
            }
        }

        /** Eksponensial backoff: 5s, 10s, 20s, 40s, 80s, 160s, 300s (cap). */
        private fun backoffSeconds(fails: Int): Long {
            val shift = (fails - 1).coerceIn(0, 16)
            return (5L shl shift).coerceAtMost(MAX_BACKOFF_SEC)
        }
    }
}
