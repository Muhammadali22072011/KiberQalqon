package com.uzguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * ====== WI-FI STRAJ (ochiq tarmoq ogohlantirgichi) ======
 *
 * Ochiq (parolsiz) jamoat Wi-Fi (kafe, bozor, metro) — MITM hujum uchun ideal:
 * boshqa mijoz trafikni ushlab, soxta sahifa ko'rsatishi yoki bank sessiyasini
 * o'g'irlashi mumkin. UzGuard bunday tarmoqqa ulanishni aniqlab, foydalanuvchini
 * bir marta (har SSID uchun) ogohlantiradi: "Ochiq Wi-Fi — bankka kirmang".
 *
 * SOF yadro: [isOpenCapabilities] — scanResult.capabilities satridan shifrlashsiz
 * tarmoqni aniqlaydi (unit-test bilan qoplangan). Runtime qismi (Android API) —
 * [openSsidOrNull], u ProtectionService'dagi NetworkCallback'dan chaqiriladi.
 */
object WifiGuard {

    /** API 31+ WifiInfo.SECURITY_TYPE_OPEN — reflectionsiz konstanta (yangi qurilmalar). */
    private const val SECURITY_TYPE_OPEN = 0    // WifiInfo.SECURITY_TYPE_OPEN
    private const val SECURITY_TYPE_UNKNOWN = -1 // WifiInfo.SECURITY_TYPE_UNKNOWN

    /**
     * SOF funksiya: scanResult.capabilities satri shifrlashsiz (ochiq) tarmoqni
     * ko'rsatadimi. Har qanday shifrlash markeri (WPA/RSN/WEP/PSK/EAP/SAE) bo'lsa —
     * ochiq EMAS. OWE (Enhanced Open) — parolsiz, lekin SHIFRLANGAN → ochiq deb
     * hisoblamaymiz (bexatar). Faqat hech qanday shifrlash markeri bo'lmasa — ochiq.
     */
    fun isOpenCapabilities(capabilities: String?): Boolean {
        if (capabilities.isNullOrBlank()) return false
        val c = capabilities.uppercase()
        val securedMarkers = listOf("WPA", "RSN", "WEP", "PSK", "EAP", "SAE", "OWE")
        if (securedMarkers.any { c.contains(it) }) return false
        // Odatda ochiq tarmoq: "[ESS]" yoki "[ESS][WPS]" (WPS shifrlash emas — sozlash usuli).
        return true
    }

    /** API 31+ xavfsizlik-turi kodi ochiq tarmoqnimi (WifiInfo.getCurrentSecurityType). */
    fun isOpenSecurityType(type: Int): Boolean = type == SECURITY_TYPE_OPEN

    /** SSID atrofidagi qo'shtirnoqlarni tozalaydi ("MyWifi" → MyWifi). */
    fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().trim('"')
        // Android ulanmagan/ruxsatsiz holatda bu maxsus qiymatni qaytaradi.
        if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
        return s
    }

    /**
     * Joriy ulangan Wi-Fi OCHIQ (parolsiz) bo'lsa uning SSID'ini qaytaradi, aks holda null.
     * Aniqlab bo'lmasa (ruxsat yo'q / ma'lumot yo'q) — null (yolg'on ogohlantirmaymiz).
     *
     * @param caps NetworkCallback'dan kelgan joriy tarmoq imkoniyatlari (API 31+ da
     *             transportInfo orqali xavfsizlik-turi olinadi). Eski qurilmalarda
     *             WifiManager + scanResults bilan zaxira aniqlash.
     */
    fun openSsidOrNull(ctx: Context, caps: NetworkCapabilities?): String? {
        return try {
            // Wi-Fi transport'i emasmi — umuman tekshirmaymiz.
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            // API 31+ : eng ishonchli yo'l — transportInfo'dagi WifiInfo.currentSecurityType.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps != null) {
                val info = caps.transportInfo as? WifiInfo
                if (info != null) {
                    val type = try { info.currentSecurityType } catch (_: Throwable) { SECURITY_TYPE_UNKNOWN }
                    if (!isOpenSecurityType(type)) return null
                    // Tarmoq OCHIQ ekani aniq, lekin SSID joylashuv ruxsatisiz
                    // "<unknown ssid>" bo'lishi mumkin — jim qolmaymiz, umumiy nom
                    // bilan ogohlantiramiz (xavf haqiqiy, nomi shart emas).
                    return cleanSsid(info.ssid) ?: GENERIC_OPEN
                }
            }

            // Eski qurilmalar (yoki transportInfo yo'q) — WifiManager + scanResults zaxira.
            openSsidLegacy(ctx)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Zaxira (API < 31): joriy ulangan SSID/BSSID'ni scanResults ichidan topib,
     * uning capabilities satrini [isOpenCapabilities] bilan tekshiradi. scanResults
     * ACCESS_FINE_LOCATION + joylashuv xizmati yoqilishini talab qiladi — bo'lmasa
     * ro'yxat bo'sh bo'lib, null qaytamiz (false-positive bermaymiz).
     */
    private fun openSsidLegacy(ctx: Context): String? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val conn = wm.connectionInfo ?: return null
        val curSsid = cleanSsid(conn.ssid) ?: return null
        @Suppress("DEPRECATION")
        val curBssid = conn.bssid
        val results = try {
            @Suppress("DEPRECATION") wm.scanResults
        } catch (_: Throwable) {
            null
        } ?: return null
        // Avval BSSID (aniqroq), keyin SSID bo'yicha moslik.
        val match = results.firstOrNull { it.BSSID == curBssid }
            ?: results.firstOrNull { cleanSsid(it.SSID) == curSsid }
            ?: return null
        return if (isOpenCapabilities(match.capabilities)) curSsid else null
    }

    /**
     * NetworkCallback'dan chaqiriladi. Joriy Wi-Fi ochiq bo'lsa — SSID bo'yicha
     * dedublangan (har tarmoq bir marta) ogohlantirish bildirishnomasini ko'rsatadi.
     */
    fun onWifiCapabilities(ctx: Context, caps: NetworkCapabilities?) {
        if (!Config.isWifiGuardEnabled(ctx)) return
        val ssid = openSsidOrNull(ctx, caps) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ssid == GENERIC_OPEN) {
            // Nomi o'qib bo'lmagan ochiq tarmoqlar: bitta abadiy bayroq o'rniga
            // 24 soatlik dedup — har yangi kun/tarmoqda yana ogohlantiradi.
            val last = prefs.getLong(KEY_GENERIC_TS, 0L)
            val now = System.currentTimeMillis()
            if (now - last < GENERIC_DEDUP_MS) return
            prefs.edit().putLong(KEY_GENERIC_TS, now).apply()
        } else {
            val key = "warned_$ssid"
            if (prefs.getBoolean(key, false)) return
            prefs.edit().putBoolean(key, true).apply()
        }
        try {
            // Sentinel GENERIC_OPEN — ichki qiymat; ko'rsatishda joriy til resursi olinadi
            // (aks holda RU bildirishnomasida o'zbekcha "ochiq Wi-Fi" aralashib qolardi).
            val displaySsid = if (ssid == GENERIC_OPEN)
                ctx.getString(R.string.kq4_wifi_open_generic) else ssid
            NotificationHelper.showOpenWifiNotification(ctx, displaySsid)
        } catch (_: Throwable) {}
    }

    /** SSID o'qilmaganda ogohlantirishda ko'rsatiladigan umumiy nom. */
    const val GENERIC_OPEN = "ochiq Wi-Fi"
    private const val KEY_GENERIC_TS = "warned_generic_ts"
    private const val GENERIC_DEDUP_MS = 24L * 60 * 60 * 1000

    private const val PREFS = "uzguard_wifi"
}
