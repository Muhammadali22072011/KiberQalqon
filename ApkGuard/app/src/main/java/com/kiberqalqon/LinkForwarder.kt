package com.uzguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * ====== HAVOLANI BRAUZERGA YO'NALTIRISH ======
 *
 * [LinkGuardActivity] havolani tekshirgach, XAVFSIZ bo'lsa uni HAQIQIY brauzerda ochishi
 * kerak — lekin O'ZIMIZGA qaytarib yubormasligi (cheksiz halqa) shart. Bu yordamchi:
 *   1) qurilmadagi brauzerlarni topadi (o'zimizdan tashqari),
 *   2) jim ochish uchun maqbul brauzerni aniqlaydi (saqlangan tanlov → tizim default'i →
 *      yagona brauzer),
 *   3) havolani aynan o'sha paketda ochadi.
 *
 * Foydalanuvchi UzGuard'ni STANDART havola ochuvchi qilib tanlagan bo'lsa, tizim
 * default'i o'zimiz bo'lib qoladi — shu sababli [silentTarget] null qaytarsa, chaqiruvchi
 * bir martalik tanlov oynasini ko'rsatib, tanlangan brauzerni [Config.setPreferredBrowser]
 * ga saqlaydi (keyingi safar jim ishlaydi).
 *
 * Brauzerlarni ko'rish uchun manifestdagi QUERY_ALL_PACKAGES yetarli (Android 11+ paket
 * ko'rinishi cheklovi shu bilan ochiq).
 */
object LinkForwarder {

    /** Bitta brauzer varianti — paket nomi + ko'rsatiladigan nomi. */
    data class BrowserOption(val pkg: String, val label: String)

    /** Brauzer borligini aniqlash uchun "namuna" http(s) intent. */
    private fun probeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

    /** Qurilmadagi barcha brauzerlar (o'zimiz bundan mustasno), takrorsiz. */
    fun browserOptions(context: Context): List<BrowserOption> {
        return try {
            val pm = context.packageManager
            pm.queryIntentActivities(probeIntent(), 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    BrowserOption(pkg, ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg })
                }
                .distinctBy { it.pkg }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Tizimning STANDART brauzeri (o'zimiz yoki resolver bo'lsa null). */
    private fun systemDefaultBrowser(context: Context): String? {
        return try {
            val ri = context.packageManager.resolveActivity(
                probeIntent(), PackageManager.MATCH_DEFAULT_ONLY
            )
            val pkg = ri?.activityInfo?.packageName
            // "android" = ResolverActivity (default tanlanmagan); o'zimiz = halqa xavfi.
            if (pkg == null || pkg == "android" || pkg == context.packageName) null else pkg
        } catch (_: Throwable) {
            null
        }
    }

    private fun isInstalledBrowser(context: Context, pkg: String): Boolean =
        browserOptions(context).any { it.pkg == pkg }

    /**
     * UzGuard HOZIR tizimning STANDART havola ochuvchisimi (http/https default handler).
     * Havola qalqoni faqat shunda har bir havolani avtomatik ushlaydi. [ProtectionStatusActivity]
     * chek-listida ✓/✗ ko'rsatish uchun ishlatiladi.
     */
    fun isDefaultLinkHandler(context: Context): Boolean {
        return try {
            val ri = context.packageManager.resolveActivity(
                probeIntent(), PackageManager.MATCH_DEFAULT_ONLY
            )
            ri?.activityInfo?.packageName == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Jim (oynasiz) ochish uchun brauzer paketi, yoki null — bunda chaqiruvchi tanlov
     * oynasini ko'rsatishi kerak. Tartib: saqlangan tanlov → tizim default'i → yagona brauzer.
     * Aniqlangan default avtomatik saqlanadi (keyingi safar tezroq).
     */
    fun silentTarget(context: Context): String? {
        val pref = Config.getPreferredBrowser(context)
        if (pref != null && isInstalledBrowser(context, pref)) return pref

        val def = systemDefaultBrowser(context)
        if (def != null) {
            Config.setPreferredBrowser(context, def)
            return def
        }

        val opts = browserOptions(context)
        if (opts.size == 1) {
            Config.setPreferredBrowser(context, opts[0].pkg)
            return opts[0].pkg
        }
        return null
    }

    /**
     * Oxirgi chora: TIZIM tanlov oynasini ochadi (paket aniqlanmaganda). [browserOptions]
     * Android 11+ paket ko'rinishi cheklovi yoki noodatiy brauzer (Telegram ichki, WebView…)
     * sababli BO'SH qaytishi mumkin — bunda «brauzer yo'q» degan noto'g'ri xulosa chiqardi,
     * Chrome o'rnatilgan bo'lsa ham. Tizim resolveri esa brauzerlarni har doim ko'radi.
     *
     * O'ZIMIZNI (LinkGuardActivity / ShareUrlReceiverActivity) tanlovdan chiqaramiz — aks
     * holda UzGuard standart havola ochuvchi bo'lsa, tanlov yana o'zimizga qaytib cheksiz
     * halqa hosil bo'lardi. EXTRA_EXCLUDE_COMPONENTS — API 24+ (minSdk 24).
     */
    fun openSystemChooser(context: Context, url: String): Boolean {
        return try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(view, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(
                            ComponentName(context, LinkGuardActivity::class.java),
                            ComponentName(context, ShareUrlReceiverActivity::class.java),
                        )
                    )
                }
            }
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Havolani aynan berilgan brauzer paketida ochadi. Muvaffaqiyatda true. */
    fun open(context: Context, url: String, pkg: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(pkg)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
