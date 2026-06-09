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
     * Fon'da DANGER fayl avtomatik karantinga olingach ko'rsatiladi.
     *
     * MUHIM: ilgari GuardWorker faylni jim karantinga olardi va foydalanuvchiga
     * HECH NARSA ko'rsatmasdi (faqat Telegram telemetriya). Natijada foydalanuvchi
     * "ilova hech narsa qilmadi" deb o'ylardi. Endi — full-screen-intent bilan
     * lock ekran ustida ham ko'rinadigan, tovushli bildirishnoma: "Virus o'chirildi".
     *
     * Bu Activity EMAS — shuning uchun MIUI keyguard bloklamaydi (POST_NOTIFICATION
     * ruxsati yetarli). Tap → Dashboard (karantin tarixini ko'rish mumkin).
     */
    @Suppress("NAME_SHADOWING")
    fun showQuarantinedNotification(
        context: Context,
        fileName: String,
        reason: String,
        originalPath: String? = null
    ) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        // Tap / full-screen-intent → "Virus topildi va o'chirildi" OYNA (ilgari Splash ochilardi).
        // already_handled: AutoScanActivity qayta skanlamaydi (fayl karantinda), natijani darhol ko'rsatadi.
        val openIntent = Intent(context, AutoScanActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("apk_name", fileName)
            if (!originalPath.isNullOrBlank()) putExtra("apk_path", originalPath)
            putExtra("already_handled", true)
            putExtra("verdict", "DANGER")
            putExtra("reason", reason)
        }
        val pi = PendingIntent.getActivity(
            context, 7100, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("🛡️ Virus o'chirildi")
            .setContentText("$fileName — avtomatik karantinga olindi")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$fileName fayli xavfli deb topildi va avtomatik o'chirildi (karantin).\n\n" +
                    "Sabab: ${reason.take(200)}\n\n" +
                    "Agar bu xato bo'lsa, 7 kun ichida tiklash mumkin."
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            // Lock ekran ustida ko'rinishi uchun (Android 12 — full-screen intent auto-grant).
            .setFullScreenIntent(pi, true)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(7100, builder.build())
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
            // Ko'rinadigan "O'chirish" tugmasi — foydalanuvchi butun bildirishnomani emas,
            // to'g'ridan-to'g'ri tugmani bosib uninstall dialogini ochadi.
            .addAction(R.drawable.ic_trash, context.getString(R.string.uninstall_app), pi)
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
        // Tap → ProtectionStatusActivity: u yerda "Avtomatik ishga tushirish (OEM)" qatori
        // bor — foydalanuvchi autostart'ni qayta yoqadi. (Ilgari Splash marafoni orqali
        // ochilardi; marafon olib tashlangach, ro'yxat ekraniga to'g'ridan-to'g'ri o'tamiz.)
        val intent = Intent(context, ProtectionStatusActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            .addAction(R.drawable.ic_trash, context.getString(R.string.uninstall_app), pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(pkg.hashCode() and 0x7FFFFFFF, builder.build())
    }

    /**
     * Yangi (system bo'lmagan) Accessibility xizmati yoqilganda QURILMADA heads-up alert.
     * Banker troyanlari aynan shu yo'l bilan ekranni o'qiydi va tugmalarni o'zi bosadi —
     * shu sabab MAX prioritet + full-screen intent. Asosiy harakat: Accessibility
     * sozlamalarini ochish (xizmatni darhol o'chirish uchun); qo'shimcha — "O'chirish".
     */
    @Suppress("NAME_SHADOWING")
    fun showAccessibilityThreatNotification(context: Context, pkg: String, appLabel: String) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val a11yIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piOpen = PendingIntent.getActivity(
            context, ("a11y_$pkg").hashCode(), a11yIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piUninstall = PendingIntent.getActivity(
            context, ("a11y_del_$pkg").hashCode(), uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Ilova ekran ustidan nazoratni oldi")
            .setContentText("$appLabel — Accessibility yoqildi. Banker troyanlari shunday qiladi.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$appLabel ($pkg) Accessibility xizmatini yoqdi — endi u ekraningizni o'qiy oladi, " +
                    "tugmalarni o'zi bosa oladi va bank ilovalari ustidan nazorat qila oladi.\n\n" +
                    "Agar buni SIZ bilib yoqmagan bo'lsangiz — darhol o'chiring (banker bo'lishi mumkin).\n" +
                    "Bosing: Accessibility sozlamalari → xizmatni o'chiring."
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(piOpen)
            .setFullScreenIntent(piOpen, true)
            .addAction(R.drawable.ic_shield, "Accessibility sozlamalari", piOpen)
            .addAction(R.drawable.ic_trash, "O'chirish", piUninstall)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(("a11y_$pkg").hashCode() and 0x7FFFFFFF, builder.build())
    }

    /**
     * Yangi (system bo'lmagan) ilova bildirishnomalarga kirish huquqini olganda heads-up alert.
     * Zamonaviy bankerlar OTP kodlarni RECEIVE_SMS'siz — bank/Telegram push'larini
     * NotificationListener orqali o'qib o'g'irlaydi. Tap → bildirishnoma kirish sozlamalari.
     */
    @Suppress("NAME_SHADOWING")
    fun showNotificationAccessThreatNotification(context: Context, pkg: String, appLabel: String) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val settingsIntent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piOpen = PendingIntent.getActivity(
            context, ("notif_$pkg").hashCode(), settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piUninstall = PendingIntent.getActivity(
            context, ("notif_del_$pkg").hashCode(), uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Ilova bildirishnomalarni o'qiy oladi")
            .setContentText("$appLabel — bildirishnomalarga kirish oldi. OTP kodlar xavf ostida.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$appLabel ($pkg) bildirishnomalarni o'qish huquqini oldi — endi u bank va " +
                    "Telegram push'laridagi bir martalik kodlarni (OTP) ko'ra oladi. Bankerlar " +
                    "SMS ruxsatisiz aynan shunday o'g'irlaydi.\n\n" +
                    "Agar buni SIZ bilib bermagan bo'lsangiz — darhol o'chiring.\n" +
                    "Bosing: bildirishnoma kirish sozlamalari → ruxsatni olib tashlang."
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(piOpen)
            .setFullScreenIntent(piOpen, true)
            .addAction(R.drawable.ic_shield, "Sozlamalar", piOpen)
            .addAction(R.drawable.ic_trash, "O'chirish", piUninstall)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(("notif_$pkg").hashCode() and 0x7FFFFFFF, builder.build())
    }
}
