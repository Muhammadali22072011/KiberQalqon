package com.uzguard

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Bulut e'lonlari (panel "Yangiliklar" sahifasi → /api/news) — dashboard karuseli va
 * to'liq ro'yxat ([NewsActivity]) shu yerdan o'qiydi.
 *
 * Faqat O'QISH: e'lonlar ko'rinishga ta'sir qiladi, detektsiya/verdiktga EMAS — shuning
 * uchun threat-feed'dagi HMAC imzo shart emas (transport TLS + sertifikat pin bilan
 * himoyalangan, kontent egasi/admin tomonidan paneldan joylanadi).
 *
 * Skan ISSIQ yo'lida tarmoq YO'Q — hammasi fail-safe:
 *   • [loadCached] — keshdan (SharedPreferences) o'qiydi, tarmoqsiz, hech qachon throw qilmaydi.
 *   • [refresh]    — fonda tarmoqdan yangilaydi (IO thread shart), xatoda kesh tegmaydi.
 */
object NewsStore {

    private const val TAG = "NewsStore"
    private const val PREFS = "uzguard_news"
    private const val KEY_PAYLOAD = "news_json"
    private const val KEY_FETCHED_AT = "news_fetched_at"

    /**
     * Tarmoqqa qayta chiqishlar orasidagi minimal interval (dashboard har ochilganda urmaslik uchun).
     * 2 daq: [NewsNotifier] (~3 daqiqalik tekshiruv, ProtectionService loop'idan) yangi e'lonni
     * "deyarli real-vaqt"da olishi uchun bundan kichik bo'lishi shart. Yengil HTTPS GET — batareyaga
     * sezilarsiz; rasm faqat YANGI e'lon bo'lganda yuklanadi.
     */
    private const val MIN_REFRESH_INTERVAL_MS = 2L * 60 * 1000

    data class Item(
        val id: String,
        val title: String,
        val body: String,
        val level: String,      // info | warning | critical (server LEVELS bilan bir xil)
        val imageUrl: String?,  // faqat https — boshqasi parse'da tashlanadi
        val pinned: Boolean,
        val createdAt: Long,    // epoch millis (parse bo'lmasa 0)
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Keshlangan e'lonlar (server tartibida: qadalgan → yangi → eski). Tarmoq YO'Q. */
    fun loadCached(ctx: Context): List<Item> = try {
        val raw = prefs(ctx).getString(KEY_PAYLOAD, null)
        if (raw.isNullOrBlank()) emptyList() else parseItems(JSONArray(raw))
    } catch (e: Throwable) {
        Log.w(TAG, "loadCached failed", e)
        emptyList()
    }

    /**
     * Tarmoqdan yangilaydi (bloklaydi — IO thread'dan chaqirilsin). Muvaffaqiyatda yangi
     * ro'yxatni qaytaradi va keshni yangilaydi; throttle/xato/oflaynda null (kesh tegmaydi).
     */
    fun refresh(ctx: Context): List<Item>? {
        try {
            val sp = prefs(ctx)
            val last = sp.getLong(KEY_FETCHED_AT, 0L)
            val now = System.currentTimeMillis()
            // Soat orqaga surilgan bo'lsa (now < last) throttle abadiy qolmasin.
            if (now in last..(last + MIN_REFRESH_INTERVAL_MS)) return null

            val base = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
            if (base.isBlank() || !base.startsWith("https://")) return null
            val deviceSecret = Secrets.cloudDeviceSecret().trim()
            if (deviceSecret.isBlank()) return null

            // Guruhga yo'naltirilgan e'lonlar: qurilma o'z guruh kodini yuboradi (?g=), server
            // global + SHU guruh e'lonlarini qaytaradi (bir maktabga tegishli ogohlantirish butun
            // viloyatni spamlamaydi). Kod yo'q bo'lsa — faqat global (avvalgidek).
            val gc = CloudTelemetry.savedGroupCode(ctx)
                ?.uppercase()?.filter { it in 'A'..'Z' || it in '2'..'9' }?.take(16).orEmpty()
            val url = if (gc.isNotEmpty()) "$base/api/news?g=$gc" else "$base/api/news"

            val req = Request.Builder()
                .url(url)
                .header("x-device-secret", deviceSecret)
                .get()
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
            val root = try { JSONObject(body) } catch (_: Throwable) { return null }
            if (!root.optBoolean("ok", false)) return null
            val arr = root.optJSONArray("news") ?: return null
            val items = parseItems(arr)

            sp.edit()
                .putString(KEY_PAYLOAD, arr.toString())
                .putLong(KEY_FETCHED_AT, now)
                .apply()
            Log.i(TAG, "yangiliklar yangilandi (${items.size} ta)")
            return items
        } catch (e: Throwable) {
            Log.w(TAG, "refresh failed (kesh saqlanadi)", e)
            return null
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseItems(arr: JSONArray): List<Item> {
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Himoya chegaralari: server bo'sh/buzilgan bo'lsa ham ulkan matn keshda yotib,
            // har ochilishda TextView'ni qotirib qo'ymasin. Sarlavha 300, matn 8000 belgigacha.
            val title = o.optString("title", "").trim().take(300)
            if (title.isEmpty()) continue
            // Rasm faqat https bo'lsa — server ham shuni filtrlaydi, mijozda takror tekshiramiz.
            val img = o.optString("image_url", "").trim()
                .takeIf { it.startsWith("https://", ignoreCase = true) }
            out += Item(
                id = o.optString("id", ""),
                title = title,
                body = o.optString("body", "").trim().take(8000),
                level = when (val l = o.optString("level", "info")) {
                    "warning", "critical" -> l
                    else -> "info"
                },
                imageUrl = img,
                pinned = o.optBoolean("pinned", false),
                createdAt = parseIsoTime(o.optString("created_at", "")),
            )
        }
        return out
    }

    /** Supabase ISO vaqti ("2026-06-12T08:30:00.123+00:00") → epoch millis; xatoda 0. */
    private fun parseIsoTime(s: String): Long {
        if (s.length < 19) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(s.substring(0, 19))?.time ?: 0L
        } catch (_: Throwable) { 0L }
    }
}
