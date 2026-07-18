package com.uzguard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * APK skan natijasini fayl yo'li + (mtime, size) bo'yicha keshlash.
 *
 * **Nima muammoni hal qiladi:** ApkScanner.scan() har gal SHA-256 hash hisoblaydi
 * (50 MB APK uchun ~500ms), ZIP entries iteratsiya qiladi va native libs'ni tahlil
 * qiladi. Bir xil fayl uchun bu ish takrorlanadi: list refresh, har bir tap, har bir
 * fon worker chaqirig'i.
 *
 * **Kesh mantiqi:** Agar fayl yo'li, oxirgi o'zgartirish vaqti va hajmi avvalgi
 * skan bilan bir xil bo'lsa — fayl o'zgarmagan, qayta skan kerak emas. Kesh hit
 * bo'lganda telemetry/history/statistika YANGI yozuv qo'shilmaydi (allaqachon
 * birinchi skanda yozilgan), bu Telegram chatni spam'lashning oldini oladi.
 *
 * **Cheklash:** maksimum 500 ta yozuv, eng eskisi avtomatik o'chiriladi.
 *
 * **Invalidatsiya:** mtime yoki size o'zgarsa — kesh miss. Apk yangilanganda
 * yoki almashtirilganda Android fayl tizimi mtime'ni yangilaydi.
 */
object ScanCache {

    private const val PREFS = "uzguard_scan_cache"
    // v2 (2026-05): verdict-mantiqi qayta kalibrlandi (false-positive tuzatildi).
    // Versiya nomi o'zgartirildi → eski (noto'g'ri DANGER) keshlangan natijalar
    // tashlanadi. Aks holda allaqachon skanlangan legit ilovalar (Chrome, GMS...)
    // mtime/size o'zgarmagani uchun eski "Zararli" verdict bilan qolib ketardi.
    private const val KEY_ENTRIES = "entries_v2"
    private const val MAX_ENTRIES = 500

    data class Entry(
        val pathHash: String,   // SHA-256 path → JSON kalit qisqarishi uchun
        val path: String,
        val mtime: Long,
        val size: Long,
        val verdict: String,
        val reason: String,
        val details: List<String>,
        val dangerousPermissions: List<String>,
        val malwareSignatures: List<String>,
        val stamp: String,     // ta'riflar (versionCode + bazа yangilanish vaqti) muhri
        val cachedAt: Long
    )

    /**
     * Ta'riflar (definitions) muhri: ilova versiyasi + bazа oxirgi yangilanish vaqti.
     * Yangi qora ro'yxat/ta'riflar kelganda muhr o'zgaradi → eski kesh AVTOMATIK
     * bekor bo'ladi. Aks holda avval SAFE deb keshlangan, keyin qora ro'yxatga
     * tushgan zararli APK keshdan SAFE bo'lib qolaverardi (#5 false-safe).
     */
    private fun currentStamp(ctx: Context): String =
        "${BuildConfig.VERSION_CODE}:${Config.lastDatabaseUpdate(ctx)}"

    /**
     * PERF (qizish — 2026-06-18, qurilmada o'lchangan): avval get()/put() HAR chaqiruvda
     * butun JSON'ni (500 yozuvgacha, har biri massivlar bilan) qayta parse qilardi. Skan
     * keshi HIT bo'lganda ham — ya'ni hech narsa qayta skanlanmaganda — shu takror parse
     * protsessorni band qilib, telefonni isitardi ("hammasi tekshirilgach ham qizardi"
     * simptomi shu edi). Endi yozuvlar XOTIRADA (pathHash → Entry) bir marta yuklanadi:
     * get() — O(1) map lookup, disk parse YO'Q. Diskka yozish faqat put()'da (yangi yoki
     * o'zgargan fayl skanlanganda) bo'ladi. Bitta jarayon — SharedPreferences bilan
     * sinxron qoladi; ScanCache best-effort (miss bo'lsa baribir qayta skan — false-SAFE yo'q).
     */
    @Volatile private var mem: LinkedHashMap<String, Entry>? = null

    private fun ensureLoaded(ctx: Context): LinkedHashMap<String, Entry> {
        mem?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        for (e in loadAll(ctx)) map[e.pathHash] = e
        mem = map
        return map
    }

    /** Faylga mos kesh yozuvi bo'lsa va mtime+size mos kelsa — ScanResult qaytar. */
    fun get(ctx: Context, apkPath: String): ScanResult? = synchronized(this) {
        val file = File(apkPath)
        if (!file.exists()) return null
        val mtime = file.lastModified()
        val size = file.length()
        val hash = pathHash(apkPath)

        val entry = ensureLoaded(ctx)[hash] ?: return null
        if (entry.mtime != mtime || entry.size != size) return null
        // Ta'riflar o'zgargan bo'lsa — keshni ishonchsiz deb bekor qilamiz (qayta skan).
        if (entry.stamp != currentStamp(ctx)) return null

        val verdict = try {
            ScanResult.Verdict.valueOf(entry.verdict)
        } catch (_: Throwable) {
            return null
        }
        return ScanResult(
            verdict = verdict,
            reason = entry.reason,
            details = entry.details,
            dangerousPermissions = entry.dangerousPermissions,
            malwareSignatures = entry.malwareSignatures
        )
    }

    /** Skan natijasini saqlash. Eski yozuv almashtiriladi, eng eskisi evict. */
    fun put(ctx: Context, apkPath: String, result: ScanResult): Unit = synchronized(this) {
        val file = File(apkPath)
        if (!file.exists()) return
        val mtime = file.lastModified()
        val size = file.length()
        val hash = pathHash(apkPath)

        val now = System.currentTimeMillis()
        val newEntry = Entry(
            pathHash = hash,
            path = apkPath,
            mtime = mtime,
            size = size,
            verdict = result.verdict.name,
            reason = result.reason,
            details = result.details,
            dangerousPermissions = result.dangerousPermissions,
            malwareSignatures = result.malwareSignatures,
            stamp = currentStamp(ctx),
            cachedAt = now
        )

        val map = ensureLoaded(ctx)
        // Bir xil yo'l uchun eski yozuvni o'chir (LinkedHashMap tartibi yangilansin → eng yangi oxirda).
        map.remove(hash)
        map[hash] = newEntry
        // Eng eskilarini olib tashlaymiz (cachedAt bo'yicha) — MAX_ENTRIES dan oshsa.
        if (map.size > MAX_ENTRIES) {
            val keep = map.values.sortedByDescending { it.cachedAt }.take(MAX_ENTRIES)
            map.clear()
            for (e in keep) map[e.pathHash] = e
        }
        saveAll(ctx, map.values.toList())
    }

    /** Barcha keshni tozalash — masalan Settings → "Keshni tozalash" tugmasi uchun. */
    fun clear(ctx: Context): Unit = synchronized(this) {
        mem = LinkedHashMap()
        prefs(ctx).edit { remove(KEY_ENTRIES) }
    }

    private fun pathHash(apkPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(apkPath.toByteArray(Charsets.UTF_8))
        // Birinchi 8 bayt yetarli — kollizyon ehtimoli 500 yozuv ichida amalda nol.
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun loadAll(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Entry(
                            pathHash = o.optString("h"),
                            path = o.optString("p"),
                            mtime = o.optLong("m", 0L),
                            size = o.optLong("s", 0L),
                            verdict = o.optString("v"),
                            reason = o.optString("r"),
                            details = jsonArrToList(o.optJSONArray("d")),
                            dangerousPermissions = jsonArrToList(o.optJSONArray("dp")),
                            malwareSignatures = jsonArrToList(o.optJSONArray("ms")),
                            stamp = o.optString("st"),
                            cachedAt = o.optLong("c", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("h", e.pathHash)
                put("p", e.path)
                put("m", e.mtime)
                put("s", e.size)
                put("v", e.verdict)
                put("r", e.reason)
                put("d", JSONArray(e.details))
                put("dp", JSONArray(e.dangerousPermissions))
                put("ms", JSONArray(e.malwareSignatures))
                put("st", e.stamp)
                put("c", e.cachedAt)
            })
        }
        prefs(ctx).edit { putString(KEY_ENTRIES, arr.toString()) }
    }

    private fun jsonArrToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) add(arr.optString(i, ""))
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
