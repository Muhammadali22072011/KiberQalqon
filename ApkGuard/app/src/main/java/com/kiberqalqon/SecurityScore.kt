package com.uzguard

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings

/**
 * ====== XAVFSIZLIK BALLI (0–100) ======
 *
 * Qurilma xavfsizlik holatini bitta tushunarli raqamga jamlaydi va foydalanuvchini
 * "100 gacha yetkaz" deb rag'batlantiradi. Har bir belgi — real himoya omili:
 *   • Ekran qulfi o'rnatilganmi (o'g'irlansa kirib bo'lmasin)     — 25 ball
 *   • Fon himoyasi yoqilganmi (24/7 skan)                         — 20 ball
 *   • Xavfli o'rnatilgan ilova yo'qmi                             — 20 ball
 *   • Masofaviy-boshqaruv ilovasi yo'qmi (firibgarlik vektori)    — 15 ball
 *   • VPN/C2 filtri yoqilganmi                                    — 10 ball
 *   • USB nosozliklarni tuzatish (ADB) o'chiqmi                   — 10 ball
 *
 * SOF yadro: [evaluate] — [Posture]'dan ball + tuzatilishi kerak bo'lgan muammolar
 * ro'yxatini hisoblaydi (Android API'siz, unit-test bilan qoplangan). [capture] —
 * runtime holatni yig'adi.
 */
object SecurityScore {

    /** Qurilma xavfsizlik holati (o'lchangan signallar). */
    data class Posture(
        val screenLockSet: Boolean,
        val backgroundProtection: Boolean,
        val dangerousAppCount: Int,
        val remoteAccessCount: Int,
        val vpnFilter: Boolean,
        val usbDebugging: Boolean,
    )

    /** Tuzatilishi kerak bo'lgan bitta muammo (foydalanuvchiga ko'rsatiladi). */
    data class Issue(val id: String, val title: String, val advice: String, val weight: Int)

    /** Yakuniy natija: ball (0–100) + ochiq muammolar (eng og'iri birinchi). */
    data class Result(val score: Int, val issues: List<Issue>)

    private const val W_LOCK = 25
    private const val W_BACKGROUND = 20
    private const val W_NO_DANGER = 20
    private const val W_NO_REMOTE = 15
    private const val W_VPN = 10
    private const val W_NO_ADB = 10

    /**
     * SOF funksiya: holatdan ball va muammolar ro'yxatini hisoblaydi.
     * Ball = bajarilgan omillar og'irliklari yig'indisi (barchasi yaxshi = 100).
     */
    fun evaluate(p: Posture): Result {
        var score = 0
        val issues = ArrayList<Issue>()

        if (p.screenLockSet) score += W_LOCK else issues.add(
            Issue(
                "lock", "Ekran qulfi o'rnatilmagan",
                "PIN, parol yoki barmoq izi qo'ying — telefon o'g'irlansa hech kim kira olmasin.",
                W_LOCK
            )
        )

        if (p.backgroundProtection) score += W_BACKGROUND else issues.add(
            Issue(
                "background", "Fon himoyasi o'chiq",
                "24/7 avtomatik himoyani yoqing — yangi fayllar va o'rnatishlar doim tekshiriladi.",
                W_BACKGROUND
            )
        )

        if (p.dangerousAppCount == 0) score += W_NO_DANGER else issues.add(
            Issue(
                "danger", "Xavfli ilova o'rnatilgan (${p.dangerousAppCount})",
                "Skaner topgan xavfli ilovalarni o'chiring — bu eng muhim qadam.",
                W_NO_DANGER
            )
        )

        if (p.remoteAccessCount == 0) score += W_NO_REMOTE else issues.add(
            Issue(
                "remote", "Masofaviy boshqaruv ilovasi bor (${p.remoteAccessCount})",
                "AnyDesk/TeamViewer kabi ilovalarni faqat o'zingiz ishlatmasangiz o'chiring — " +
                    "firibgarlar shu orqali telefoningizni boshqaradi.",
                W_NO_REMOTE
            )
        )

        if (p.vpnFilter) score += W_VPN else issues.add(
            Issue(
                "vpn", "Havola/C2 filtri o'chiq",
                "VPN filtrini yoqing — zararli domenlar va C2 serverlar bloklanadi.",
                W_VPN
            )
        )

        if (!p.usbDebugging) score += W_NO_ADB else issues.add(
            Issue(
                "adb", "USB nosozliklarni tuzatish yoqilgan",
                "Developer sozlamalaridan USB debugging'ni o'chiring — bu xavfsizlik teshigi.",
                W_NO_ADB
            )
        )

        return Result(score.coerceIn(0, 100), issues.sortedByDescending { it.weight })
    }

    /** Ball rangi/darajasi uchun band (DashboardNewActivity poroglariga mos). */
    fun band(score: Int): String = when {
        score >= 80 -> "safe"
        score >= 50 -> "warn"
        else -> "danger"
    }

    /** Runtime: qurilmaning joriy xavfsizlik holatini yig'adi. */
    fun capture(ctx: Context): Posture {
        val screenLock = try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isDeviceSecure ?: false
        } catch (_: Throwable) { false }

        val dangerCount = try {
            // Kunlik re-scan (InstalledAppsRescanWorker) yozgan oxirgi verdiktlar.
            val prefs = ctx.getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
            prefs.all.count { (k, v) -> k.startsWith("verdict_") && v == "DANGER" }
        } catch (_: Throwable) { 0 }

        val remoteCount = try {
            RemoteAccessDetector.installed(ctx).size
        } catch (_: Throwable) { 0 }

        val adbOn = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Throwable) { false }

        return Posture(
            screenLockSet = screenLock,
            backgroundProtection = Config.isBackgroundEnabled(ctx),
            dangerousAppCount = dangerCount,
            remoteAccessCount = remoteCount,
            vpnFilter = Config.isVpnFilterEnabled(ctx),
            usbDebugging = adbOn,
        )
    }
}
