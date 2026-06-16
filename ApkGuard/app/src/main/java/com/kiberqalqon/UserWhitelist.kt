package com.uzguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ====== FOYDALANUVCHI OQ RO'YXATI (ishonchli ro'yxat / exclusions) ======
 *
 * Foydalanuvchi "bu fayl/ilova menga tanish, ishonaman" deb belgilagan yozuvlar.
 * Maqsad: SHUBHALI (heuristik) ogohlantirishlarni o'sha aniq fayl/ilova uchun o'chirish
 * (false-positive shovqinini kamaytirish).
 *
 * ====== TEXNIKA — nega aynan shunday (xavfsizlik) ======
 * 1. KRIPTOGRAFIK BOG'LASH, nom emas:
 *      • Fayl yozuvi = APK faylning SHA-256 hash'i. Fayl 1 bayt o'zgarsa — hash boshqa,
 *        ishonch YO'QOLADI (virus "o'sha nom bilan" kelsa ham o'tmaydi).
 *      • Ilova yozuvi = (package + imzo-sertifikat SHA-256) JUFTLIGI. Faqat paket nomi
 *        bo'yicha oq ro'yxat — backdoor: malware o'zini istalgan paket deb e'lon qila oladi
 *        ([TrustedSignatures] dagi xuddi shu qoida). Imzo mos kelmasa — ishonch YO'Q.
 * 2. FAQAT SHUBHALI BOSILADI (antivirus oltin qoidasi):
 *      • Oq ro'yxat XAVFLI (DANGER) verdiktni HECH QACHON o'zgartirmaydi. Qat'iy IOC'lar
 *        (ma'lum hash/imzo/paket, ZIP-shifrlash, dropper) [ApkScanner.scan] ichida oq
 *        ro'yxat tekshiruvidan OLDIN erta-return bilan chiqadi, hook esa qo'shimcha
 *        ravishda faqat SUSPICIOUS'da ishlaydi. Ma'lum virusni "oqlab" bo'lmaydi.
 * 3. KESH BEKOR QILINADI: ro'yxat o'zgarganda [ScanCache.clear] — eski SHUBHALI keshlangan
 *    verdiktlar darhol qayta hisoblanadi (aks holda qo'shilgan ishonch keyingi mtime
 *    o'zgarishigacha ko'rinmasdi).
 *
 * Hammasi SharedPreferences'da JSON ko'rinishida (boshqa uzguard_* lar kabi).
 */
object UserWhitelist {

    private const val PREFS = "uzguard_user_whitelist"
    private const val KEY_ENTRIES = "entries_v1"
    private const val MAX_ENTRIES = 200

    /** Bitta yozuv: type = "file" (key = sha256) yoki "app" (key = "pkg|certSha256"). */
    data class Entry(
        val type: String,
        val key: String,
        val label: String,
        val addedAt: Long,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun entries(ctx: Context): List<Entry> {
        return try {
            val raw = prefs(ctx).getString(KEY_ENTRIES, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type")
                val key = o.optString("key")
                if (type.isBlank() || key.isBlank()) return@mapNotNull null
                Entry(type, key, o.optString("label"), o.optLong("addedAt"))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Fayl (SHA-256) ishonchli ro'yxatdami. Hash null/bo'sh bo'lsa — YO'Q (fail-safe). */
    fun isWhitelistedFile(ctx: Context, sha256: String?): Boolean {
        val h = sha256?.trim()?.lowercase() ?: return false
        if (h.isEmpty()) return false
        return entries(ctx).any { it.type == TYPE_FILE && it.key == h }
    }

    /**
     * Ilova (package + imzo-sertifikat SHA-256 JUFTLIGI) ishonchli ro'yxatdami.
     * Ikkalasidan biri yo'q bo'lsa — YO'Q (nomning o'zi hech qachon yetarli emas).
     */
    fun isWhitelistedApp(ctx: Context, pkg: String?, certSha256: String?): Boolean {
        val p = pkg?.trim() ?: return false
        val c = certSha256?.trim()?.lowercase() ?: return false
        if (p.isEmpty() || c.isEmpty()) return false
        val key = "$p|$c"
        return entries(ctx).any { it.type == TYPE_APP && it.key == key }
    }

    /** Faylni (SHA-256) ro'yxatga qo'shadi. Muvaffaqiyatda true. */
    fun addFile(ctx: Context, sha256: String?, label: String): Boolean {
        val h = sha256?.trim()?.lowercase() ?: return false
        if (h.length < 32) return false   // haqiqiy sha256 emas — qo'shmaymiz
        return addEntry(ctx, Entry(TYPE_FILE, h, label, System.currentTimeMillis()))
    }

    /** Ilovani (package + cert juftligi) ro'yxatga qo'shadi. Muvaffaqiyatda true. */
    fun addApp(ctx: Context, pkg: String?, certSha256: String?, label: String): Boolean {
        val p = pkg?.trim() ?: return false
        val c = certSha256?.trim()?.lowercase() ?: return false
        if (p.isEmpty() || c.length < 32) return false
        return addEntry(ctx, Entry(TYPE_APP, "$p|$c", label, System.currentTimeMillis()))
    }

    /** Yozuvni (type+key bo'yicha) olib tashlaydi. */
    @Synchronized
    fun remove(ctx: Context, type: String, key: String) {
        val rest = entries(ctx).filterNot { it.type == type && it.key == key }
        save(ctx, rest)
        invalidateCache(ctx)
    }

    @Synchronized
    private fun addEntry(ctx: Context, e: Entry): Boolean {
        val cur = entries(ctx)
        if (cur.any { it.type == e.type && it.key == e.key }) return true  // allaqachon bor
        val next = (cur + e).takeLast(MAX_ENTRIES)
        save(ctx, next)
        invalidateCache(ctx)
        return true
    }

    private fun save(ctx: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("type", e.type)
                        .put("key", e.key)
                        .put("label", e.label)
                        .put("addedAt", e.addedAt)
                )
            }
            prefs(ctx).edit().putString(KEY_ENTRIES, arr.toString()).apply()
        } catch (_: Throwable) { /* saqlash xatosi — eski ro'yxat qoladi */ }
    }

    /** Ro'yxat o'zgardi — eski SHUBHALI keshlangan verdiktlar qayta hisoblansin. */
    private fun invalidateCache(ctx: Context) {
        try { ScanCache.clear(ctx) } catch (_: Throwable) {}
    }

    const val TYPE_FILE = "file"
    const val TYPE_APP = "app"
}
