package com.uzguard

import android.content.Context

/**
 * ====== MASOFAVIY BOSHQARUV DETEKTORI (remote-access / ekran-ulashish) ======
 *
 * O'zbekistonda eng ko'p uchraydigan firibgarlik sxemasi: qurbonni telefon orqali
 * "bank xodimi" niqobida qo'ng'iroq qilib, AnyDesk yoki TeamViewer o'rnatishga
 * ko'ndiradi ("kartangizni himoyalash uchun"), so'ng ekranni ko'rib / boshqarib
 * bank ilovasiga kirib pulni o'tkazadi yoki SMS-kodni o'qiydi.
 *
 * Bu ilovalarning O'ZI virus EMAS (AnyDesk/TeamViewer qonuniy dasturlar) — shuning
 * uchun ular DANGER deb belgilanmaydi. Lekin oddiy foydalanuvchi telefonida bunday
 * ilova o'rnatilgan bo'lishi = yuqori xavf signali, ayniqsa bank ilovasi bilan birga.
 * UzGuard bularni topib, foydalanuvchini OGOHLANTIRADI ("agar buni bank xodimi
 * so'ragan bo'lsa — bu firibgarlik").
 *
 * SOF yadro: [matchPackages] — o'rnatilgan paketlar to'plamidan xavflilarni ajratadi
 * (unit-test bilan qoplangan, Android API'siz).
 */
object RemoteAccessDetector {

    /** Topilgan masofaviy-boshqaruv ilovasi (paket + ko'rinadigan brend nomi). */
    data class RemoteApp(val pkg: String, val brand: String)

    /**
     * Ma'lum masofaviy boshqaruv / ekran-ulashish ilovalari (paket → brend).
     * Firibgarlar UZ'da eng ko'p AnyDesk va TeamViewer'dan foydalanadi; qolganlari
     * ham bir xil imkoniyat (ekranni ko'rish + boshqarish) berganligi uchun kiritilgan.
     */
    val KNOWN: Map<String, String> = linkedMapOf(
        // Eng ko'p ishlatiladigan (UZ firibgarlik) —
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.teamviewer.teamviewer.market.mobile" to "TeamViewer",
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.host.market" to "TeamViewer Host",
        // RustDesk (ochiq kodli, so'nggi yillarda firibgarlikda ham) —
        "com.carriez.flutter_hbb" to "RustDesk",
        // Boshqa keng tarqalgan remote/screen-share —
        "com.sand.airdroid" to "AirDroid",
        "com.sand.airmirror" to "AirMirror",
        "com.apowersoft.mirror" to "ApowerMirror",
        "com.splashtop.remote.pad.v2" to "Splashtop",
        "com.splashtop.remote.stb" to "Splashtop SOS",
        "com.google.chromeremotedesktop" to "Chrome Remote Desktop",
        "com.microsoft.rdc.androidx" to "Microsoft Remote Desktop",
        "com.microsoft.rdc.android" to "Microsoft Remote Desktop",
        "com.realvnc.viewer.android" to "VNC Viewer",
        "com.aweray.remote" to "AweSun",
        "com.zoho.assist" to "Zoho Assist",
        "com.zoho.assist.customer" to "Zoho Assist Customer",
        "com.iperius.remote" to "Iperius Remote",
        "com.islonline.isllight" to "ISL Light",
        "com.Relmtech.Remote" to "Unified Remote",
        "com.Relmtech.RemotePaid" to "Unified Remote",
    )

    /**
     * SOF funksiya: o'rnatilgan paketlar to'plamidan ma'lum masofaviy-boshqaruv
     * ilovalarini ajratadi. Tartib [KNOWN] tartibida (eng xavflisi birinchi).
     */
    fun matchPackages(installed: Set<String>): List<RemoteApp> =
        KNOWN.entries
            .filter { it.key in installed }
            .map { RemoteApp(it.key, it.value) }

    /**
     * Qurilmada haqiqatan o'rnatilgan masofaviy-boshqaruv ilovalarini qaytaradi.
     * O'zimizni (UzGuard) hech qachon o'z ichiga olmaydi. Xatolikda bo'sh ro'yxat.
     */
    fun installed(ctx: Context): List<RemoteApp> {
        return try {
            val pm = ctx.packageManager
            KNOWN.entries.mapNotNull { (pkg, brand) ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    RemoteApp(pkg, brand)
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
