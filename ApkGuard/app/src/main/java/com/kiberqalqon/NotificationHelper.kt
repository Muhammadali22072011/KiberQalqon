package com.kiberqalqon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * Notification helper.
 *
 * Pochemu 4 kanala: na Android 8+ sound/vibration nastroyki kanala IMMUTABLE posle
 * pervogo sozdaniya — polzovatel' menyaet ih tol'ko vruchnuyu cherez sistemnye
 * sozlamalar. Chtoby nastroyki KiberQalqon'a (switchSound / switchVibration) real'no
 * rabotali, dlya kazhdoy kombinacii (s/v) zaranee sozdaem otdel'nyy kanal.
 * V runtime vybiraem nuzhnyy kanal po Config.isSoundEnabled / isVibrationEnabled.
 *
 * Eto standartnyy podhod dlya app-controlled notifications na sovremennom Android.
 */
object NotificationHelper {

    // Legacy: ranshe byl odin "apk_scan". Ostavlyaem ego v spiske dlya obratnoy
    // sovmestimosti (uzhe sushchestvuyushchie ustanovki — chto-by ne dvoit'sya
    // "Skan" v sistemnyh notification settings posle update), no real'nye
    // notifications teper' idut cherez 4 yavnyh varianta.
    private const val CHANNEL_LEGACY = "apk_scan"

    private const val CH_SILENT = "apk_scan_silent"
    private const val CH_SOUND = "apk_scan_sound"
    private const val CH_VIBRATE = "apk_scan_vibrate"
    private const val CH_FULL = "apk_scan_full"

    /** Backward-compat: starye vyzovy createChannel() — perenapravlyaem na createChannels. */
    fun createChannel(context: Context) = createChannels(context)

    /**
     * Sozdaem vse 4 kanala odnokratno. Vyzov idempotenten: NotificationManager
     * ignoriruet povtornoye sozdaniye s tem zhe ID, no ne menyaet uzhe sushestvuyushchie
     * channel'lar (poetomu nuzhno chetyre).
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Legacy — pust' ostaetsya, no pomechen kak default. Esli komu-to vsplyvet
        // staraya notification na etot ID — zvuk/vibraciya sistemnye po defoltu.
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_LEGACY, "APK Skanerlash", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "APK fayllar tekshiruvi"
            }
        )

        mgr.createNotificationChannel(
            NotificationChannel(CH_SILENT, "Skan (jim)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tovushsiz, tebranishsiz"
                setSound(null, null)
                enableVibration(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_SOUND, "Skan (tovush)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Faqat tovush"
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                enableVibration(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_VIBRATE, "Skan (tebranish)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Faqat tebranish"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_FULL, "Skan (tovush + tebranish)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tovush ham, tebranish ham"
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
    }

    /** Vybiraem kanal po tekushchim user-prefs. */
    private fun channelFor(context: Context): String {
        val sound = Config.isSoundEnabled(context)
        val vibe = Config.isVibrationEnabled(context)
        return when {
            sound && vibe -> CH_FULL
            sound -> CH_SOUND
            vibe -> CH_VIBRATE
            else -> CH_SILENT
        }
    }

    /**
     * Na API < 26 channel ne primenyaetsya, builder.setSound/setVibrate rabotaet napryamuyu.
     * Eta funkciya konfiguriruet builder po user-prefs dlya legacy ustroystv.
     */
    private fun applyLegacyPrefs(context: Context, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return  // channel uzhe pravil
        val sound = Config.isSoundEnabled(context)
        val vibe = Config.isVibrationEnabled(context)
        builder.setSound(
            if (sound) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null
        )
        builder.setVibrate(
            if (vibe) longArrayOf(0, 500, 250, 500) else longArrayOf(0)
        )
    }

    @Suppress("NAME_SHADOWING")
    fun showScanNotification(context: Context, apkFile: File) {
        val context = LocaleHelper.apply(context)
        createChannels(context)

        val intent = Intent(context, AutoScanActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("apk_path", apkFile.absolutePath)
            putExtra("apk_name", apkFile.name)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_dangerous_apk_title))
            .setContentText(context.getString(R.string.notif_tap_to_check, apkFile.name))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_new_apk_big, apkFile.name)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
        applyLegacyPrefs(context, builder)

        NotificationManagerCompat.from(context).notify(777, builder.build())
    }

    /**
     * Уведомление после установки опасного APK. Тапание открывает системный диалог
     * удаления — юзер одним кликом сносит вирус.
     */
    @Suppress("NAME_SHADOWING")
    fun showInstalledDangerNotification(
        context: Context,
        pkg: String,
        appLabel: String,
        result: ScanResult
    ) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, pkg.hashCode(), uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reasons = result.details.joinToString("\n• ", "• ").take(400)
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_installed_danger_title, appLabel))
            .setContentText(result.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasons))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            // Full-screen intent: lock screen ustida ko'rinadi, telefon ochilsa
            // tizim avtomatik ravishda uninstall dialogini ochadi.
            .setFullScreenIntent(pi, true)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(pkg.hashCode() and 0x7FFFFFFF, builder.build())
    }

    /**
     * Batch skan natijasida topilgan xavfli APK fayli (hali o'rnatilmagan).
     * Full-screen Activity OCHMAYDI — faqat notification ko'rinadi. Foydalanuvchi tapsa
     * AutoScanActivity ochiladi va u APK'ni o'chirish/o'rnatish tugmalarini ko'rsatadi.
     *
     * Avval MainActivity startAutoProtection forEach loop'da har bir DANGER uchun
     * startActivity(AutoScanActivity) chaqirardi — natijada AutoScanActivity ekrani
     * stack'da to'planib qolib, foydalanuvchi orqaga qaytsa boshqasi chiqib turardi
     * va dasturdan chiqib bo'lmasdi. Bu funksiya — o'rnini bosadi.
     */
    @Suppress("NAME_SHADOWING")
    fun showFoundApkNotification(
        context: Context,
        apkFile: File,
        verdict: ScanResult.Verdict,
        reason: String
    ) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val intent = Intent(context, AutoScanActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("apk_path", apkFile.absolutePath)
            putExtra("apk_name", apkFile.name)
            putExtra("apk_source", "batch_scan")
        }
        // Har fayl uchun unikal requestCode, aks holda PendingIntent'lar bir-birini almashtiradi.
        val rc = apkFile.absolutePath.hashCode() and 0x7FFFFFFF
        val pi = PendingIntent.getActivity(
            context, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (icon, title) = when (verdict) {
            ScanResult.Verdict.DANGER -> "🚫" to context.getString(R.string.notif_verdict_danger)
            ScanResult.Verdict.SUSPICIOUS -> "⚠️" to context.getString(R.string.notif_verdict_suspicious)
            ScanResult.Verdict.SAFE -> "✅" to context.getString(R.string.notif_verdict_found)
        }
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("$icon $title")
            .setContentText(apkFile.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${apkFile.name}\n\n${reason.take(200)}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            // setFullScreenIntent ATAYLAB CHAQIRILMAYDI — aks holda Activity avtomatik
            // ochilib, batch scan paytida bir nechta Activity stack'da to'planadi.
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(rc, builder.build())
    }

    /**
     * Foydalanuvchi qurilmasi (Xiaomi/MIUI/Huawei va h.k.) KiberQalqon jarayonini
     * o'ldirgan bo'lsa, qaytib kelganida bu eslatma ko'rsatiladi. Tap → SettingsActivity
     * (yoki to'g'ridan-to'g'ri OEM autostart ekrani) ochiladi.
     */
    @Suppress("NAME_SHADOWING")
    fun showKillDetectedNotification(context: Context, gapHours: Long) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val oem = OemAutostartGuide.detect()
        // Tap → SplashActivity orqali autostart guide qayta ochiladi
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_oem_guide", true)
        }
        val pi = PendingIntent.getActivity(
            context, 7002, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_kill_title))
            .setContentText(context.getString(R.string.notif_kill_text, gapHours))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(
                    R.string.notif_kill_big,
                    oem.displayName,
                    gapHours,
                    if (oem == OemAutostartGuide.Oem.XIAOMI_MIUI)
                        context.getString(R.string.notif_kill_center_miui)
                    else context.getString(R.string.notif_kill_center_default)
                )
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(7002, builder.build())
    }

    /**
     * Birinchi ochilishda chiqadigan xush kelibsiz xabari — "Himoyangiz yoqildi"
     * deb foydalanuvchiga vizual tasdiqlash. Bir martagina, doimiy emas (auto-cancel).
     */
    @Suppress("NAME_SHADOWING")
    fun showWelcomeNotification(context: Context) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val openIntent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 7001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_welcome_title))
            .setContentText(context.getString(R.string.notif_welcome_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.notif_welcome_big)
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(7001, builder.build())
    }

    @Suppress("NAME_SHADOWING")
    fun showInstalledSuspiciousNotification(
        context: Context,
        pkg: String,
        appLabel: String,
        result: ScanResult
    ) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, pkg.hashCode(), uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_installed_suspicious_title, appLabel))
            .setContentText(result.reason)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(pkg.hashCode() and 0x7FFFFFFF, builder.build())
    }
}
