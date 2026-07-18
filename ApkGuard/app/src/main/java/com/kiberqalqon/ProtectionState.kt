package com.uzguard

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * ====== HIMOYA HOLATI YIG'GICHI (cloud "batareya") ======
 *
 * Qurilmaning himoya "sog'lig'ini" bitta ixcham JSON obyektga jamlaydi — markaziy
 * panel har bir qurilma yonida "himoya batareyasi" (nechta qatlam yoqilgan) ko'rsata
 * olishi uchun. CloudTelemetry.registerDevice register body'siga `protections` kaliti
 * ostida qo'shadi (integrator ulaydi).
 *
 * MUHIM: har bir belgi ALOHIDA try/catch ichida — bittasini aniqlab bo'lmasa,
 * o'sha kalit TUSHIRIB QOLDIRILADI (taxmin qilinmaydi). Bulut yo'q kalitlarga bardosh
 * beradi. Maxfiylik: hech qanday shaxsiy ma'lumot yo'q — faqat yoqilgan/o'chiq bayroqlar.
 *
 * Mavjud tekshiruvlar QAYTA ISHLATILADI (ProtectionStatusActivity / SecurityScore /
 * InstallProtectionGuide / VersionCompat / LinkForwarder) — bu yerda hech narsa qayta
 * ixtiro qilinmaydi.
 */
object ProtectionState {

    /**
     * Himoya holatini yig'adi. Qaytadigan kalitlar (barchasi boolean, aks holda ko'rsatilgan):
     *   svc      — ProtectionService (fon himoyasi) ishlayaptimi
     *   a11y     — o'rnatish qalqoni (InstallShield / accessibility) yoqilganmi
     *   notif    — bildirishnoma-eshituvchi (notification listener) ruxsati berilganmi
     *   postN    — POST_NOTIFICATIONS ruxsati (Android 13+; 13'gacha doim true)
     *   linkH    — biz standart havola ochuvchimizmi
     *   apkH     — biz standart APK/content ochuvchimizmi
     *   vpn      — VPN/C2 filtri yoqilganmi
     *   batt     — batareya optimizatsiyasidan chiqarilganmi
     *   scanAgeH — (INT) oxirgi to'liq skandan beri o'tgan soatlar (noma'lum bo'lsa tushiriladi)
     */
    fun collect(ctx: Context): JSONObject {
        val o = JSONObject()

        // svc — ProtectionService foreground xizmati tirikmi (o'z xizmatimizni ko'rish mumkin).
        try {
            o.put("svc", isProtectionServiceRunning(ctx))
        } catch (_: Throwable) { /* omit */ }

        // a11y — jonli o'rnatish qalqoni (accessibility) yoqilganmi. ProtectionStatusActivity /
        // InstallProtectionGuide bilan bir xil tekshiruv.
        try {
            o.put("a11y", InstallProtectionGuide.isShieldServiceEnabled(ctx))
        } catch (_: Throwable) { /* omit */ }

        // notif — bildirishnoma-eshituvchi (NotificationAccessWatcher bilan bir xil manba).
        try {
            o.put("notif", isNotificationListenerEnabled(ctx))
        } catch (_: Throwable) { /* omit */ }

        // postN — POST_NOTIFICATIONS (Android 13+). VersionCompat 13'gacha true qaytaradi.
        try {
            o.put("postN", VersionCompat.hasNotificationPermission(ctx))
        } catch (_: Throwable) { /* omit */ }

        // linkH — standart havola ochuvchimizmi (LinkForwarder bilan bir xil).
        try {
            o.put("linkH", LinkForwarder.isDefaultLinkHandler(ctx))
        } catch (_: Throwable) { /* omit */ }

        // apkH — standart APK/content ochuvchimizmi (InstallProtectionGuide bilan bir xil).
        try {
            o.put("apkH", InstallProtectionGuide.isDefaultApkHandler(ctx))
        } catch (_: Throwable) { /* omit */ }

        // vpn — VPN/C2 filtri yoqilganmi (Config bayrog'i).
        try {
            o.put("vpn", Config.isVpnFilterEnabled(ctx))
        } catch (_: Throwable) { /* omit */ }

        // batt — batareya optimizatsiyasidan istisno (Doze whitelist).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null) o.put("batt", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            } else {
                o.put("batt", true)
            }
        } catch (_: Throwable) { /* omit */ }

        // scanAgeH — oxirgi skan yozuvidan (ScanHistory eng yangi yozuvi) beri o'tgan soatlar.
        // Yozuv umuman yo'q bo'lsa — kalitni tushiramiz (taxmin qilmaymiz).
        try {
            val lastTs = ScanHistory.all(ctx).firstOrNull()?.timestamp ?: 0L
            if (lastTs > 0L) {
                val hours = ((System.currentTimeMillis() - lastTs) / 3_600_000L).toInt()
                o.put("scanAgeH", hours.coerceAtLeast(0))
            }
        } catch (_: Throwable) { /* omit */ }

        return o
    }

    /**
     * ProtectionService (o'z foreground xizmatimiz) ishlayaptimi. getRunningServices Android O+
     * da BOSHQA ilovalar xizmatlarini bermaydi, lekin O'Z ilovamiznikini baribir qaytaradi —
     * shu sabab bu yerda ishonchli.
     */
    private fun isProtectionServiceRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(Int.MAX_VALUE) ?: return false
        val target = ProtectionService::class.java.name
        return services.any { it.service.className == target }
    }

    /**
     * Bizning paketimiz bildirishnoma-eshituvchilar (enabled_notification_listeners) ro'yxatida
     * bormi. NotificationAccessWatcher.readEnabledListeners bilan bir xil manba (ochiq Secure sozlama,
     * maxsus ruxsatsiz o'qiladi).
     */
    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        if (flat.isBlank()) return false
        val pkg = ctx.packageName
        return flat.split(':').any { it.isNotBlank() && it.substringBefore('/') == pkg }
    }
}
