package com.uzguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * Notification helper.
 *
 * Pochemu 4 kanala: na Android 8+ sound/vibration nastroyki kanala IMMUTABLE posle
 * pervogo sozdaniya — polzovatel' menyaet ih tol'ko vruchnuyu cherez sistemnye
 * sozlamalar. Chtoby nastroyki UzGuard'a (switchSound / switchVibration) real'no
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

    // Yangilik/e'lon bildirishnomalari uchun ALOHIDA kanal — xavfsizlik alertlaridan
    // ajralib tursin (IMPORTANCE_DEFAULT: tovush bor, lekin lock ekran ustida o'zi
    // ochilmaydi). Foydalanuvchi faqat shu kanalni o'chirib, tahdid alertlarini
    // qoldira oladi.
    private const val CH_NEWS = "uzguard_news"

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

        // Yangiliklar — past bosim (DEFAULT): tovush bor, heads-up majburiy emas. Tahdid
        // alertlaridan alohida — foydalanuvchi shu kanalni o'chirsa, viruslar haqida
        // bildirishnomalar baribir keladi.
        mgr.createNotificationChannel(
            NotificationChannel(CH_NEWS, context.getString(R.string.kq4_news_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.kq4_news_channel_desc)
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
            // Android 14+ (API 34): FSI ruxsati bo'lmasa tizim oynani o'zi ochmaydi —
            // ruxsat holatini aniq uzatamiz; ruxsatsiz ham PRIORITY_MAX heads-up keladi.
            .setFullScreenIntent(pendingIntent, VersionCompat.canUseFullScreenIntent(context))
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
            .setContentTitle(context.getString(R.string.kq4_notif_quar_title))
            .setContentText(context.getString(R.string.kq4_notif_quar_text, fileName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.kq4_notif_quar_big, fileName, reason.take(200))
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            // Lock ekran ustida o'zi ochilishi uchun. MUHIM: Android 14+ (API 34) FSI ruxsatini
            // oddiy ilovalardan oldi — canUseFullScreenIntent=false bo'lsa tizim buni oddiy
            // heads-up'ga tushiradi (oyna o'zi ochilmaydi), shuning uchun ruxsat bor-yo'qligini
            // ANIQ uzatamiz (PRIORITY_MAX + CATEGORY_ALARM tufayli baribir baland heads-up keladi).
            .setFullScreenIntent(pi, VersionCompat.canUseFullScreenIntent(context))
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
            // tizim avtomatik ravishda uninstall dialogini ochadi (FSI ruxsati bo'lsa;
            // Android 14+ da ruxsatsiz — oddiy heads-up + "O'chirish" tugmasi).
            .setFullScreenIntent(pi, VersionCompat.canUseFullScreenIntent(context))
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
     * Foydalanuvchi qurilmasi (Xiaomi/MIUI/Huawei va h.k.) UzGuard jarayonini
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

    /**
     * Haftalik xulosa bildirishnomasi — WeeklyReportWorker tomonidan ~20:00 da, haftada
     * bir marta yuboriladi. Tahdid emas, ma'lumot xarakteridagi xabar (welcome bilan bir xil
     * shablon): PRIORITY_DEFAULT, full-screen intent yo'q. Tap → ScanHistoryActivity
     * (statistika ekrani), u yerda haftalik grafik va tahdid tarixi ko'rinadi.
     */
    @Suppress("NAME_SHADOWING")
    fun showWeeklyReportNotification(
        context: Context,
        scanned: Int,
        blocked: Int,
        quarantined: Int
    ) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val openIntent = Intent(context, ScanHistoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 7004, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.kq4_weekly_notif_title))
            .setContentText(context.getString(R.string.kq4_weekly_notif_text, scanned, blocked))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.kq4_weekly_notif_big, scanned, blocked, quarantined)
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(7004, builder.build())
    }

    /**
     * SD-02: SecurityGuard ilovani to'xtatishdan OLDIN sababни узбекча tushuntiradi. Avval
     * jim killProcess bo'lardi — root/kastom-proshivkali legit foydalanuvchi (O'zbek bozorida ko'p)
     * ilova "sababsiz yo'qolib" ketganini ko'rardi (sindi deb o'ylaydi). Bildirishnoma тизим
     * jarayoniga binder orqali kill'dan oldin topshiriladi, shu sabab ko'rinadi.
     *
     * @param reason SecurityGuard.CheckResult.reason (tekshiruv nomi: signature/tamper/root/...).
     */
    @Suppress("NAME_SHADOWING")
    fun showSecurityBlockNotification(context: Context, reason: String?) {
        try {
            val context = LocaleHelper.apply(context)
            createChannels(context)
            // Перепакетлаш/имзо мос эмас = қатъий бузилиш; қолганлари = муҳит (root/эмулятор).
            val tamper = reason == "signature" || reason == "tamper"
            val title = context.getString(R.string.kq4_notif_secblock_title)
            val text = if (tamper) {
                context.getString(R.string.kq4_notif_secblock_tamper)
            } else {
                context.getString(R.string.kq4_notif_secblock_env)
            }
            val builder = NotificationCompat.Builder(context, channelFor(context))
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            applyLegacyPrefs(context, builder)
            NotificationManagerCompat.from(context).notify(7009, builder.build())
        } catch (_: Throwable) {
            // Bildirishnoma bermasa ham kill davom etadi — bu best-effort.
        }
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
            .setContentTitle(context.getString(R.string.kq4_notif_a11y_title))
            .setContentText(context.getString(R.string.kq4_notif_a11y_text, appLabel))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.kq4_notif_a11y_big, appLabel, pkg)
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(piOpen)
            .setFullScreenIntent(piOpen, VersionCompat.canUseFullScreenIntent(context))
            .addAction(R.drawable.ic_shield, context.getString(R.string.kq4_notif_a11y_action), piOpen)
            .addAction(R.drawable.ic_trash, context.getString(R.string.uninstall_app), piUninstall)
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
            .setContentTitle(context.getString(R.string.kq4_notif_notifacc_title))
            .setContentText(context.getString(R.string.kq4_notif_notifacc_text, appLabel))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.kq4_notif_notifacc_big, appLabel, pkg)
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(piOpen)
            .setFullScreenIntent(piOpen, VersionCompat.canUseFullScreenIntent(context))
            .addAction(R.drawable.ic_shield, context.getString(R.string.kq4_notif_notifacc_action), piOpen)
            .addAction(R.drawable.ic_trash, context.getString(R.string.uninstall_app), piUninstall)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(("notif_$pkg").hashCode() and 0x7FFFFFFF, builder.build())
    }

    /**
     * Panel joylagan yangi e'lon (yangilik) bildirishnomasi — [NewsNotifier] fonда
     * aniqlaganda chiqaradi. Tahdid EMAS: alohida [CH_NEWS] kanali, past bosim, ovoz
     * foydalanuvchi sozlamalaridan emas, kanal default'idan.
     *
     * Rasm ([image]) — chaqiruvchi (IO thread) [NewsImages] orqali oldindan yuklab beradi;
     * bo'lsa BigPictureStyle (kengaytirilganda to'liq rasm), bo'lmasa BigTextStyle (matn).
     * Tap → [NewsActivity] (to'liq e'lonlar ro'yxati).
     *
     * Notification ID e'lon id'sidan keladi — bir e'lon ikki marta push qilinsa ham
     * stack'da bittagina ko'rinadi (yangilanadi, dublikat emas).
     */
    @Suppress("NAME_SHADOWING")
    fun showNewsNotification(context: Context, item: NewsStore.Item, image: Bitmap?) {
        val context = LocaleHelper.apply(context)
        createChannels(context)

        val openIntent = Intent(context, NewsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val rc = ("news_${item.id}").hashCode() and 0x7FFFFFFF
        val pi = PendingIntent.getActivity(
            context, rc, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // critical → biroz balandroq (heads-up ehtimoli), qolgani DEFAULT.
        val priority = if (item.level == "critical") NotificationCompat.PRIORITY_HIGH
        else NotificationCompat.PRIORITY_DEFAULT

        val body = item.body.ifBlank { context.getString(R.string.kq4_news_notif_default_body) }

        val builder = NotificationCompat.Builder(context, CH_NEWS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(item.title)
            .setContentText(body)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pi)

        if (image != null) {
            builder.setLargeIcon(image)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(image)
                    .bigLargeIcon(null as Bitmap?)   // kengaytirilganda large icon takrorlanmasin
                    .setBigContentTitle(item.title)
                    .setSummaryText(body.take(120))
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        NotificationManagerCompat.from(context).notify(rc, builder.build())
    }

    /**
     * Masofaviy boshqaruv ilovasi (AnyDesk/TeamViewer/...) topilganda ogohlantirish.
     * Tap → SideloadAuditActivity (u masofaviy ilovalarni ham ko'rsatadi va o'chirishga yo'l ochadi).
     * Bu ilovalarni AVTOMATIK o'chirmaymiz — ular qonuniy bo'lishi mumkin, faqat ogohlantiramiz.
     */
    @Suppress("NAME_SHADOWING")
    fun showRemoteAccessNotification(context: Context, brands: List<String>) {
        if (brands.isEmpty()) return
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val intent = Intent(context, SideloadAuditActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, "remote_access".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val names = brands.distinct().joinToString(", ")
        val body = context.getString(R.string.notif_remote_access_body, names)
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_remote_access_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(REMOTE_ACCESS_NOTIF_ID, builder.build())
    }

    /**
     * Ochiq (parolsiz) Wi-Fi tarmog'iga ulanilganda ogohlantirish (MITM xavfi).
     * Har SSID uchun bir marta ([WifiGuard] dedublaydi).
     */
    @Suppress("NAME_SHADOWING")
    fun showOpenWifiNotification(context: Context, ssid: String) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val body = context.getString(R.string.notif_open_wifi_body, ssid)
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_open_wifi_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(
            ("wifi_$ssid").hashCode() and 0x7FFFFFFF, builder.build()
        )
    }

    /**
     * Bildirishnomadagi (Telegram/SMS) havola [LinkScanner] tomonidan XAVFLI deb topilganda
     * foydalanuvchini ogohlantirish. Tap → LinkCheckActivity (to'liq sabab + tekshirish).
     */
    @Suppress("NAME_SHADOWING")
    fun showPhishingLinkNotification(context: Context, url: String, host: String?) {
        val context = LocaleHelper.apply(context)
        createChannels(context)
        val intent = LinkCheckActivity.intent(context, url).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val rc = ("phish_$url").hashCode() and 0x7FFFFFFF
        val pi = PendingIntent.getActivity(
            context, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val shown = host ?: url
        val body = context.getString(R.string.notif_phish_link_body, shown)
        val builder = NotificationCompat.Builder(context, channelFor(context))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_phish_link_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
        applyLegacyPrefs(context, builder)
        NotificationManagerCompat.from(context).notify(rc, builder.build())
    }

    private const val REMOTE_ACCESS_NOTIF_ID = 0x0F51
}
