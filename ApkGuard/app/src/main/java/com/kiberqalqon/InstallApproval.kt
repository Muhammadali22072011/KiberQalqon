package com.uzguard

import android.content.Context
import androidx.core.content.edit

/**
 * Kichik "o'rnatish ruxsati" do'koni — [InstallShieldService] (Accessibility jonli qalqon)
 * uchun yagona haqiqat manbai. Qalqon tizimning "O'rnatasizmi?" oynasini ko'rganda faqat
 * paket NOMINI biladi (fayl yo'lini emas), shuning uchun qaror paket nomi bo'yicha olinadi:
 *
 *  • approve(pkg)   — UzGuard bu paketni TEKSHIRDI va XAVFSIZ deb topdi, foydalanuvchi
 *                     o'rnatishni o'zi tanladi. AutoScanActivity.launchInstaller() va
 *                     SelfUpdate o'rnatishdan OLDIN yozadi. TTL ichida qalqon bekor qilmaydi.
 *  • flagDanger(pkg)— UzGuard bu paketni DANGER deb topdi (ApkScanner.finalizeResult /
 *                     PackageInstallReceiver). Qalqon uni qat'iy bloklaydi (TTL'siz, uzoq).
 *
 * Hammasi SharedPreferences — boshqa ilovalar yeta olmaydi (MODE_PRIVATE). Hech qanday
 * tashqi bog'liqlik yo'q (ScanCache uslubida).
 */
object InstallApproval {

    private const val PREFS = "uzguard_install_approval"
    private const val PREFIX_OK = "ok:"      // tasdiqlangan o'rnatish (TTL)
    private const val PREFIX_BAD = "bad:"    // ma'lum DANGER paket/yorliq (uzoq TTL)
    // Global oxirgi-vaqt belgilari — qalqon paket nomini oynadan o'qiy olmaganda
    // (ko'pincha shunday) "yaqinda DANGER bo'ldi-yu, tasdiqlanmadi" qarorini O(1) oladi.
    private const val KEY_LAST_DANGER = "_last_danger_ts"
    private const val KEY_LAST_OK = "_last_ok_ts"

    // Tasdiq qisqa yashaydi: foydalanuvchi "O'rnatish" bosgach tizim oynasi ~soniyalarda
    // chiqadi. 2 daqiqa — sekin qurilma/Doze uchun ham yetarli, lekin keyin eskirib qoladi
    // (qalqon "tasdiqlanmagan"ni bloklab qoladi).
    private const val OK_TTL_MS = 2L * 60 * 1000
    // DANGER belgisi uzoq turadi — virus paketini har doim bloklash uchun.
    private const val BAD_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** UzGuard tasdiqlagan (xavfsiz) o'rnatish — qalqon bu paketni o'tkazadi. */
    fun approve(ctx: Context, pkg: String?) {
        val now = nowMs()
        prefs(ctx).edit {
            pkg?.takeIf { it.isNotBlank() }?.let { putLong(PREFIX_OK + it, now) }
            putLong(KEY_LAST_OK, now)
        }
    }

    fun isApproved(ctx: Context, pkg: String?): Boolean {
        val p = pkg?.takeIf { it.isNotBlank() } ?: return false
        val ts = prefs(ctx).getLong(PREFIX_OK + p, 0L)
        return ts > 0 && nowMs() - ts < OK_TTL_MS
    }

    /**
     * Ma'lum DANGER — qalqon uni qat'iy bloklaydi. Qalqon oynadan paket NOMINI yoki
     * faqat ilova YORLIG'INI o'qishi mumkin, shuning uchun ikkalasi ham indekslanadi.
     */
    fun flagDanger(ctx: Context, pkg: String?, label: String? = null) {
        val now = nowMs()
        prefs(ctx).edit {
            pkg?.takeIf { it.isNotBlank() }?.let { putLong(PREFIX_BAD + it, now) }
            label?.let { normalizeLabel(it) }?.takeIf { it.isNotBlank() }
                ?.let { putLong(PREFIX_BAD + "lbl:" + it, now) }
            putLong(KEY_LAST_DANGER, now)
        }
    }

    /** key = paket nomi YOKI ilova yorlig'i (qaysi biri oynadan o'qilsa). */
    fun isDanger(ctx: Context, key: String?): Boolean {
        val k = key?.takeIf { it.isNotBlank() } ?: return false
        val p = prefs(ctx)
        val byPkg = p.getLong(PREFIX_BAD + k, 0L)
        val byLbl = p.getLong(PREFIX_BAD + "lbl:" + normalizeLabel(k), 0L)
        val ts = maxOf(byPkg, byLbl)
        return ts > 0 && nowMs() - ts < BAD_TTL_MS
    }

    /** Yaqinda (windowMs ichida) biror narsa DANGER deb belgilanganmi. */
    fun hasRecentDanger(ctx: Context, windowMs: Long): Boolean {
        val ts = prefs(ctx).getLong(KEY_LAST_DANGER, 0L)
        return ts > 0 && nowMs() - ts < windowMs
    }

    /** Yaqinda (windowMs ichida) foydalanuvchi biror o'rnatishni tasdiqlaganmi. */
    fun hasRecentApproval(ctx: Context, windowMs: Long): Boolean {
        val ts = prefs(ctx).getLong(KEY_LAST_OK, 0L)
        return ts > 0 && nowMs() - ts < windowMs
    }

    private fun normalizeLabel(s: String): String =
        s.trim().lowercase().filter { !it.isWhitespace() }

    /** O'rnatish tugagach (PACKAGE_ADDED) tasdiqni olib tashlaymiz — qayta ishlatilmasin. */
    fun clearApproval(ctx: Context, pkg: String?) {
        val p = pkg?.takeIf { it.isNotBlank() } ?: return
        prefs(ctx).edit { remove(PREFIX_OK + p) }
    }

    private fun nowMs() = System.currentTimeMillis()
}
