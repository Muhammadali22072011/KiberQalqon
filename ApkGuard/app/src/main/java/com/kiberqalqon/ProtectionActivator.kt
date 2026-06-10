package com.kiberqalqon

import android.content.Context
import android.util.Log

/**
 * Himoyani FAQAT u haqiqatan ishlay olganda yoqadi.
 *
 * MUAMMO (foydalanuvchi shikoyati): birinchi ochilishda, hali HECH QANDAY ruxsat berilmasdan
 * oldin ham "Himoyangiz yoqildi" va "KIBER QALQON faol · Telefoningiz himoyalangan · 24/7"
 * bildirishnomalari chiqardi — bu YOLG'ON, chunki fayl ruxsati yo'q ekan, skaner Telegram/Downloads
 * papkalarini ko'ra olmaydi. To'g'ri oqim (O'zbekistondagi oddiy/byudjet Android uchun):
 *   o'rnatish → ochish → RUXSAT berish → chiqish → endi fonda o'zi ishlaydi.
 *
 * Shuning uchun "himoyalangan" deган har qanday signal (foreground-xizmat + welcome) FAQAT
 * VersionCompat.hasFileScanAccess() rost bo'lganda (ya'ni "Barcha fayllarga ruxsat" berilganda)
 * paydo bo'ladi. Ungacha onboarding ekranlari "ruxsat bering" deydi, biz esa hech narsani
 * "himoyalangan" deb ko'rsatmaymiz.
 */
object ProtectionActivator {

    private const val TAG = "ProtectionActivator"

    /**
     * Himoya tayyor bo'lsa — xizmatni boshlaydi va BIR MARTA "Himoyangiz yoqildi" bildirishnomasini
     * ko'rsatadi. Idempotent — istalgan Activity onResume / App.onCreate'dan chaqirish bezarar.
     */
    fun activateIfReady(ctx: Context) {
        val c = ctx.applicationContext
        try {
            if (!Config.isBackgroundEnabled(c)) return
            // Fayl ruxsati YO'Q bo'lsa — himoya hali HAQIQIY emas. Xizmatni boshlamaymiz va
            // "himoyalangan" demaymiz. Onboarding (Splash/Himoya holati) ruxsat so'raydi.
            if (!VersionCompat.hasFileScanAccess(c)) return

            ProtectionService.start(c)

            if (!Config.isWelcomeShown(c)) {
                try {
                    NotificationHelper.showWelcomeNotification(c)
                } catch (e: Throwable) {
                    Log.w(TAG, "welcome notification failed", e)
                }
                Config.setWelcomeShown(c, true)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "activateIfReady failed", e)
        }
    }
}
