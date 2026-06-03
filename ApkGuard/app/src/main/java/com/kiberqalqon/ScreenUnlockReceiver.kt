package com.kiberqalqon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Telefon ekrani ochilganda (ACTION_USER_PRESENT) darhol bir martalik GuardWorker
 * ishga tushiramiz. Bu — Xiaomi/MIUI/Huawei agressiv battery optimization'iga
 * qarshi yaxshi kompensatsiya: WorkManager periodik 15 daq'lik skanni o'ldirsa
 * ham, foydalanuvchi telefonni ochishi bilan biz yangi APK'larni qidiramiz.
 *
 * Dinamik ro'yxat — manifest'da ACTION_USER_PRESENT ishlatilmaydi, faqat
 * process tirik bo'lganida (WorkManager 15 daq'da uyg'otadi). Bu yetarli:
 * agar foydalanuvchi telefonni ochsa, demak yangi APK kelishi mumkin —
 * shu paytda biz allaqachon process'da turamiz.
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_USER_PRESENT &&
            intent.action != Intent.ACTION_SCREEN_ON) return

        // Avval Config tekshiruv — foydalanuvchi background himoyani o'chirgan bo'lsa skipping.
        try {
            if (!Config.isBackgroundEnabled(context)) return
        } catch (_: Throwable) {
            // Config o'qib bo'lmasa ham davom etamiz
        }

        Log.d(TAG, "User present / screen on — running one-shot scan")
        try {
            // #35: ACTION_SCREEN_ON qulflangan ekranda ham (USER_PRESENT'dan tashqari) har
            // gal o'qlanadi — enqueueUniqueWork(KEEP) bilan birlashtiramiz, shunda tez-tez
            // yoqib-o'chirilganda takroriy to'liq skanlar yig'ilib ketmaydi.
            val request = OneTimeWorkRequestBuilder<GuardWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("screen_unlock_scan", ExistingWorkPolicy.KEEP, request)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to enqueue one-shot scan", e)
        }
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }
}
