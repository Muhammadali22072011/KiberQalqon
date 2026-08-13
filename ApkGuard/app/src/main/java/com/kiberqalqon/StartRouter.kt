package com.uzguard

import android.content.Context

/**
 * ILK ISHGA TUSHISH marshruti — bitta joyda.
 *
 * Ilgari bu zinapoya to'rt joyda (Splash, Consent, Onboarding, InitialScan) qo'lda
 * takrorlanardi va ular allaqachon bir-biridan farq qila boshlagan edi: Onboarding
 * tugagach to'g'ridan-to'g'ri InitialScan'ga sakrardi va yangi shlagbaumlarni
 * ko'rmasdi. Telegram ro'yxati SHLAGBAUM bo'lgani uchun (o'tmasdan bosh ekran
 * ochilmaydi) uni bitta manbadan boshqarish shart — aks holda istalgan ekran uni
 * chetlab o'tib yuboradi.
 *
 * Zinapoya (yuqoridan pastga, birinchi mos kelgani g'olib):
 *   1. ToS+Privacy roziligi yo'q        → ConsentActivity
 *   2. Birinchi ishga tushish            → OnboardingActivity
 *   3. Telegram ro'yxatidan o'tilmagan   → TgRegisterActivity
 *   4. Ilk skan qilinmagan               → InitialScanActivity
 *   5. Himoya holati tasdiqlanmagan yoki majburiy ruxsat o'chirilgan → ProtectionStatusActivity
 *   6. Aks holda                         → DashboardNewActivity
 */
object StartRouter {

    /** Shu qadamda ochilishi kerak bo'lgan ekran. */
    fun next(ctx: Context): Class<*> = when {
        !Config.hasUserConsent(ctx) -> ConsentActivity::class.java
        Config.isFirstRun(ctx) -> OnboardingActivity::class.java
        needsTgRegistration(ctx) -> TgRegisterActivity::class.java
        !Config.isInitialScanDone(ctx) -> InitialScanActivity::class.java
        !Config.isProtectionAcked(ctx) -> ProtectionStatusActivity::class.java
        !ProtectionStatusActivity.allCriticalPermissionsGranted(ctx) ->
            ProtectionStatusActivity::class.java
        else -> DashboardNewActivity::class.java
    }

    /**
     * Berilgan ekrandan KEYINGI qadam — o'sha ekranning o'zini qaytarmaydi (aks holda
     * ekran o'zini qayta ochib cheksiz siklga tushardi).
     */
    fun after(ctx: Context, current: Class<*>): Class<*> {
        val n = next(ctx)
        if (n != current) return n
        // Shlagbaum hali "yopiq" ko'rinsa ham (masalan bayroq yozilishi kechikdi) —
        // pastdagi qadamga o'tamiz, foydalanuvchi ekranda qamalib qolmasin.
        return when {
            !Config.isInitialScanDone(ctx) && current != InitialScanActivity::class.java ->
                InitialScanActivity::class.java
            current != ProtectionStatusActivity::class.java &&
                (!Config.isProtectionAcked(ctx) ||
                    !ProtectionStatusActivity.allCriticalPermissionsGranted(ctx)) ->
                ProtectionStatusActivity::class.java
            else -> DashboardNewActivity::class.java
        }
    }

    /**
     * Telegram shlagbaumi kerakmi.
     *
     * Bulut sozlanmagan build'da (fork, CLOUD_BASE_URL bo'sh) shlagbaum YO'Q — aks holda
     * ilova umuman ochilmasdi: token oladigan server yo'q. Rozilik ham shart, chunki
     * ekran shaxsiy ma'lumot (ism + telefon) so'raydi.
     */
    fun needsTgRegistration(ctx: Context): Boolean =
        !Config.isTgRegistered(ctx) &&
            Config.hasUserConsent(ctx) &&
            CloudTelemetry.isCloudConfigured()
}
