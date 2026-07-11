package com.uzguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ====== O'Z-O'ZINI YANGILASH (self-update) ======
 *
 * Ilova hali Play Store'da emas (sideload) — yangilanish ham o'zimizdan keladi.
 * Manba: IMZOLANGAN RemoteConfig'dagi ixtiyoriy "update" bloki (versionCode + apkUrl +
 * apkSha256). Oqim: App.onCreate fonda config yangilangach [checkAndNotify] —
 * yangi versiya bo'lsa BIR MARTA bildirishnoma → bosilsa [SelfUpdateActivity] →
 * tasdiq → fonda yuklab olish → tekshiruv → tizim o'rnatuvchisi.
 *
 * ====== XAVFSIZLIK — 3 qavat (soxta yangilanish o'rnatib bo'lmaydi) ======
 * 1. Metadata HMAC-imzolangan config'dan (rollback-guard bilan) — URL/hash'ni MITM
 *    almashtira olmaydi.
 * 2. Yuklangan faylning SHA-256'i imzolangan qiymatga AYNAN mos kelishi shart.
 * 3. Yuklangan APK'ning IMZO SERTIFIKATI o'rnatilgan UzGuard'nikiga AYNAN mos
 *    kelishi shart ([CertUtil]) — hatto config kaliti o'g'irlansa ham, buzg'unchi
 *    bizning keystore'siz yangilanish bera olmaydi. (Android'ning o'zi ham boshqa
 *    imzoli update'ni rad etadi — bu undan OLDINGI qatlam.)
 * Har qanday mos kelmaslik → fayl o'chiriladi, o'rnatish so'ralmaydi.
 *
 * O'rnatishning o'zi baribir tizim oynasi orqali (Android sideload'da jim o'rnatishga
 * ruxsat bermaydi) — foydalanuvchidan BIR tap.
 */
object SelfUpdate {

    private const val TAG = "SelfUpdate"
    private const val CHANNEL_ID = "kq_self_update"
    private const val NOTIF_ID = 7781
    private const val NOTIF_INSTALL_ID = 7782
    private const val PREFS = "uzguard_self_update"
    private const val KEY_NOTIFIED_VC = "notified_vc"

    /** Imzolangan config'dan kelgan yangilanish metadata'si. */
    data class Info(val versionCode: Int, val url: String, val sha256: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Yangi versiya bormi — bo'lsa BIR MARTA (har versionCode uchun) bildirishnoma.
     * App.onCreate fonida RemoteConfig.refresh'dan KEYIN chaqiriladi. Hech qachon throw qilmaydi.
     */
    fun checkAndNotify(ctx: Context) {
        try {
            val info = RemoteConfig.updateInfo(ctx) ?: return
            if (info.versionCode <= BuildConfig.VERSION_CODE) return
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getInt(KEY_NOTIFIED_VC, -1) == info.versionCode) return  // allaqachon aytilgan

            // Bildirishnoma ruxsati YO'Q bo'lsa (Android 13+ POST_NOTIFICATIONS hali berilmagan):
            // KO'RSATMAYMIZ va "aytilgan" bayrog'ini ham QO'YMAYMIZ — keyingi sovuq startda
            // (ruxsat berilgach) qayta urinamiz. Aks holda bildirishnoma jim yo'qolib, foydalanuvchi
            // yangi versiyani umuman ko'rmasdi (faqat undan ham keyingi versiyada ko'rinardi).
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Yangilanish", NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, SelfUpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.kq4_update_notif_title))
                .setContentText(ctx.getString(R.string.kq4_update_notif_body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
            sp.edit().putInt(KEY_NOTIFIED_VC, info.versionCode).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "checkAndNotify failed", e)
        }
    }

    /**
     * Yuklab oladi, 2 qavat tekshiradi (SHA-256 + imzo-cert), so'ng tizim o'rnatuvchisini
     * ochadi. BLOKLAYDI — fon thread'da chaqiring. Natija: null = OK, aks holda xato kaliti
     * (string resursi).
     */
    fun downloadVerifyInstall(ctx: Context, info: Info): Int? {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, "shared")   // FileProvider cache-path "shared/" bilan mos
        val out = File(dir, "kq-update-${info.versionCode}.apk")
        try {
            dir.mkdirs()
            // 1) Yuklab olish (avvalgi chala fayl ustiga).
            val req = Request.Builder().url(info.url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return R.string.kq4_update_err_download
                val body = resp.body ?: return R.string.kq4_update_err_download
                out.outputStream().use { os -> body.byteStream().copyTo(os) }
            }

            // 2) SHA-256 imzolangan qiymatga mosligi.
            val sha = try { CertUtil.apkFileSha256(out.absolutePath) } catch (_: Throwable) { null }
            if (sha == null || !sha.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                Log.w(TAG, "SHA-256 mos emas — yangilanish rad etildi")
                return R.string.kq4_update_err_verify
            }

            // 3) Imzo sertifikati O'ZIMIZNIKI bo'lishi shart.
            val selfFp = try { CertUtil.selfFingerprintSha256(app) } catch (_: Throwable) { null }
            val apkFp = try { CertUtil.fingerprintSha256(app, out.absolutePath) } catch (_: Throwable) { null }
            if (selfFp.isNullOrBlank() || apkFp.isNullOrBlank() || !apkFp.equals(selfFp, ignoreCase = true)) {
                out.delete()
                Log.w(TAG, "imzo sertifikati mos emas — yangilanish rad etildi")
                return R.string.kq4_update_err_verify
            }

            // 4) Tizim o'rnatuvchisi (AutoScanActivity'dagi tekshirilgan oqim bilan bir xil).
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", out)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            // MUHIM: bu metod fon thread'da (SelfUpdateActivity allaqachon finish() qilingan)
            // ishlaydi. Android 10+ (API 29+) da fon jarayonidan startActivity JIM tashlanadi —
            // istisno OTILMAYDI, shuning uchun to'g'ridan-to'g'ri chaqirsak o'rnatish oynasi
            // umuman chiqmaydi-yu, biz null (=OK) qaytarardik va foydalanuvchi yangilandim deb
            // o'ylardi. Buning o'rniga o'rnatishni FOYDALANUVCHI BOSADIGAN bildirishnoma orqali
            // beramiz: tap foreground kontekstdan keladi, BAL cheklovi tegmaydi. Ishonchli yo'l
            // bo'lmasa (bildirishnoma o'chirilgan) — haqiqiy xato qaytaramiz.
            if (!offerInstall(app, intent)) {
                Log.w(TAG, "o'rnatish oynasini ochib bo'lmadi (fon + bildirishnoma yo'q)")
                return R.string.kq4_update_err_download
            }
            return null
        } catch (e: Throwable) {
            Log.w(TAG, "downloadVerifyInstall failed", e)
            try { out.delete() } catch (_: Throwable) {}
            return R.string.kq4_update_err_download
        }
    }

    /**
     * O'rnatish oynasini ISHONCHLI ochadi. Avval to'g'ridan-to'g'ri urinamiz (agar biror
     * foreground activity qolgan bo'lsa ishlaydi), so'ng — asosiy yo'l — foydalanuvchi
     * bosadigan bildirishnoma qo'yamiz: uning tap'i foreground kontekstdan keladi va
     * Android 10+ fon-launch cheklovidan qutuladi. Ishonchli yo'l o'rnatilsa true, aks
     * holda (bildirishnoma o'chirilgan — jim yo'qolish xavfi) false qaytaradi.
     */
    private fun offerInstall(app: Context, intent: Intent): Boolean {
        // Best-effort to'g'ridan-to'g'ri (foreground bo'lsa darhol ochiladi).
        try { app.startActivity(intent) } catch (_: Throwable) {}

        // Ishonchli yo'l: tap-to-install bildirishnoma. Bu foreground'dan ochiladi.
        return try {
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Yangilanish", NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val pi = PendingIntent.getActivity(
                app, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(app.getString(R.string.kq4_update_notif_title))
                .setContentText(app.getString(R.string.kq4_update_notif_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_INSTALL_ID, n)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "offerInstall notification failed", e)
            false
        }
    }
}
