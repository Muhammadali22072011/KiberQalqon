package com.uzguard

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
        // BG-03/perf: faqat ACTION_USER_PRESENT (haqiqiy qulfdan chiqish). Avval ACTION_SCREEN_ON
        // ham tutilardi — ekran bildirishnomadan yonganda ham (foydalanuvchi tegmasa ham) har gal
        // 10s'gacha disk skani yurardi (kuniga 50-150 marta), bu anti-qizish fiksini yeb qo'yardi.
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        // Avval Config tekshiruv — foydalanuvchi background himoyani o'chirgan bo'lsa skipping.
        try {
            if (!Config.isBackgroundEnabled(context)) return
        } catch (_: Throwable) {
            // Config o'qib bo'lmasa ham davom etamiz
        }

        // Troттling: unlock skanlar orasida kamida MIN_INTERVAL. Tez-tez yoqib-o'chirilganda
        // (yoki tez qulflab-ochilganda) takroriy og'ir storage-walk'ni oldini olamiz.
        try {
            val sp = context.getSharedPreferences("uzguard_lifecycle", Context.MODE_PRIVATE)
            val last = sp.getLong("last_unlock_scan", 0L)
            val now = System.currentTimeMillis()
            if (now - last < MIN_SCAN_INTERVAL_MS) {
                Log.d(TAG, "Unlock scan throttled (${(now - last) / 1000}s < ${MIN_SCAN_INTERVAL_MS / 1000}s)")
                return
            }
            sp.edit().putLong("last_unlock_scan", now).apply()
        } catch (_: Throwable) {
            // Prefs o'qib bo'lmasa ham davom etamiz
        }

        Log.d(TAG, "User present — running one-shot scan")
        try {
            val request = OneTimeWorkRequestBuilder<GuardWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("screen_unlock_scan", ExistingWorkPolicy.KEEP, request)
            // Ekran ochilganda accessibility-abuse'ni ham darhol tekshiramiz (banker aynan
            // shu paytda "telefonni yangilash kerak" deb a11y so'raydi).
            AccessibilityWatcher.checkNow(context.applicationContext)
            NotificationAccessWatcher.checkNow(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to enqueue one-shot scan", e)
        }
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
        // Unlock skanlar orasidagi eng kichik interval (10 daqiqa) — fast-loop + 15 daq full-sweep
        // baribir qoplaydi, shu sabab har unlock'da skan shart emas.
        private const val MIN_SCAN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
