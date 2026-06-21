package com.uzguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * O'rnatish himoyasi yordamchisi — UI (ProtectionStatusActivity / SettingsActivity /
 * MainActivity) shu yerdagi sof funksiyalarni chaqiradi:
 *
 *  • isDefaultApkHandler / promptSetDefaultApk — "proxodnaya" (UzGuard APK uchun standart).
 *  • isShieldServiceEnabled / openAccessibilitySettings — jonli qalqon (Accessibility).
 *  • unknownSourceCandidates / openUnknownSourceFor — "noma'lum manbalar"ni o'chirish.
 *  • openClearCache — Telegram'ni ochib keshni tozalashga yo'naltirish (dasturiy tozalash
 *    qurilma-egasi bo'lmasdan MUMKIN EMAS — faqat yo'naltiramiz).
 *
 * Standart ilovani majburan o'rnatib bo'lmaydi, boshqa ilovaning ruxsatini dasturiy
 * o'chirib bo'lmaydi — hammasi tizim ekraniga yo'naltirish (deep-link).
 */
object InstallProtectionGuide {

    private const val TAG = "InstallProtect"

    /** Telegram/WhatsApp/brauzer — virus eng ko'p shu manbalardan keladi. */
    private val RISKY_SOURCES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "com.whatsapp",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.yandex.browser",
    )

    // ───────────────────────── Proxodnaya (default APK handler) ─────────────────────────

    /** UzGuard hozir APK fayllar uchun standart ochuvchimi? (LinkForwarder uslubida.) */
    fun isDefaultApkHandler(ctx: Context): Boolean = try {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.uzguard.probe/x.apk"),
                "application/vnd.android.package-archive"
            )
        }
        val r = ctx.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName == ctx.packageName
    } catch (t: Throwable) {
        Log.w(TAG, "isDefaultApkHandler failed", t)
        false
    }

    /**
     * ENG QULAY YO'L: foydalanuvchini APK qidirishga majburlamaymiz — UzGuard'ning O'Z
     * APK'sini "ochish" intent'ini otamiz, shunda tizim "Qaysi ilova bilan ochish?" oynasini
     * darhol ko'rsatadi. Foydalanuvchi «UzGuard» + «Doimo»ni bossa — har bir APK uchun standart
     * bo'lib qoladi (tanlov MIME bo'yicha, faqat shu fayl uchun emas). ShareReceiver o'z
     * APK'ni tanib, skan qilmasdan "tayyor" deb ko'rsatadi.
     *
     * Standart ilovalar sozlamasida APK uchun alohida punkt YO'Q — shuning uchun aynan shu
     * "ochish" oynasi yagona qulay usul.
     */
    fun triggerSetDefaultApk(ctx: Context): Boolean = try {
        val own = java.io.File(ctx.applicationInfo.sourceDir)
        val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
        val bait = java.io.File(dir, "uzguard-setup.apk")
        own.copyTo(bait, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", bait)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(view)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "triggerSetDefaultApk failed", t)
        false
    }

    /** Zaxira: standart ilovalar sozlamasini ochadi (trigger ishlamaganda). */
    fun promptSetDefaultApk(ctx: Context) {
        if (triggerSetDefaultApk(ctx)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            safeStart(ctx, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        ) return
        safeStart(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    /** Paket nomi UzGuard'ning o'ziniki (har xil applicationId variantlari)? */
    fun isOwnPackage(pkg: String?): Boolean {
        val p = pkg ?: return false
        return p == "com.kiberqalqon" || p == "com.kiberqalqon.debug" || p == "com.uzguard"
    }

    // ───────────────────────── Jonli qalqon (Accessibility) ─────────────────────────

    /** InstallShieldService tizimda yoqilganmi (foydalanuvchi qo'lda yoqishi kerak). */
    fun isShieldServiceEnabled(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val cn = ComponentName(ctx, InstallShieldService::class.java)
        enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "isShieldServiceEnabled failed", t)
        false
    }

    fun openAccessibilitySettings(ctx: Context) {
        if (safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return
        safeStart(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    // ───────────────────────── Noma'lum manbalar ─────────────────────────

    /** Qurilmada o'rnatilgan xavfli manbalar (Telegram/WhatsApp/brauzer). */
    fun unknownSourceCandidates(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return RISKY_SOURCES.mapNotNull { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppEntry(pkg, pm.getApplicationLabel(ai).toString())
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Aniq manba ilovasi uchun "Noma'lum ilovalarni o'rnatish" toggle'ini ochadi. */
    fun openUnknownSourceFor(ctx: Context, pkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            safeStart(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg")))
        ) return
        // Pre-O yoki ekran yo'q bo'lsa — o'sha ilova sozlamalariga.
        safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    // ───────────────────────── Telegram keshini tozalash ─────────────────────────

    /** Telegram'ni (yoki uning sozlamalarini) ochadi — foydalanuvchi keshni qo'lda tozalaydi. */
    fun openClearCache(ctx: Context, pkg: String = "org.telegram.messenger") {
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                safeStart(ctx, launch)
                return
            }
        } catch (_: Throwable) { /* fall through */ }
        safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    data class AppEntry(val pkg: String, val label: String)

    private fun safeStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "safeStart failed: ${intent.action}", t)
        false
    }
}
