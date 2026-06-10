package com.kiberqalqon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Yangiliklar / e'lonlar lentasi — bosh ekranda ko'rsatiladi.
 *
 * Egasi panelda e'lon yozadi (rasm havolasi ham qo'shsa bo'ladi), ilova esa
 * shu lentani cloud'dan o'qib bosh ekranga chiqaradi. Faqat O'QISH:
 *   GET $base/api/news  → { ok:true, news:[ {id,title,body,level,image_url,pinned,created_at} ] }
 *
 * x-device-secret bilan ochiladi (server tomonda GET shu sirni qabul qiladi);
 * ADMIN_SECRET bu yerda ISHLATILMAYDI — ilova faqat ko'radi, yoza olmaydi.
 *
 * Cloud sozlanmagan bo'lsa (CLOUD_BASE_URL bo'sh) — NotConfigured, lenta yashiriladi.
 */
object NewsClient {

    private const val TAG = "NewsClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class NewsItem(
        val id: String,
        val title: String,
        val body: String,
        val level: String,
        val imageUrl: String,
        val pinned: Boolean,
        val createdAt: String,
    )

    sealed class Result {
        data class Success(val items: List<NewsItem>) : Result()
        /** Cloud build'da sozlanmagan — lenta o'chiq. */
        object NotConfigured : Result()
        /** Tarmoq/server xatosi (ulanib bo'lmadi yoki endpoint yo'q). */
        data class NetworkError(val reason: String) : Result()
    }

    /** Lentani yuklaydi. onResult main thread'da chaqiriladi. */
    fun fetch(onResult: (Result) -> Unit) {
        val base = baseUrl()
        val secret = deviceSecret()
        if (base == null || secret == null) {
            runMain { onResult(Result.NotConfigured) }
            return
        }

        scope.launch {
            val result = try {
                val req = Request.Builder()
                    .url("$base/api/news")
                    .header("x-device-secret", secret)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    parse(resp.code, resp.body?.string().orEmpty())
                }
            } catch (e: Throwable) {
                Log.w(TAG, "news fetch failed", e)
                Result.NetworkError(e.javaClass.simpleName)
            }
            runMain { onResult(result) }
        }
    }

    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024  // 5MB — decompression-bomb chegarasi
    private const val MAX_IMAGE_DIM = 1024                // namuna olishdan keyingi maksimal o'lcham

    /**
     * E'lon rasmini yuklaydi (ixtiyoriy). Tashqi havola — auth sarlavhasi yo'q.
     * FAQAT https; hajmi cheklangan (5MB) va namuna bilan dekodlanadi (OOM/bomb himoyasi).
     * Xato/yo'q bo'lsa null qaytadi (rasm ko'rsatilmaydi).
     */
    fun loadImage(url: String, onResult: (Bitmap?) -> Unit) {
        val u = url.trim()
        if (!u.startsWith("https://")) {
            runMain { onResult(null) }
            return
        }
        scope.launch {
            val bmp = try {
                val req = Request.Builder().url(u).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val declared = resp.body?.contentLength() ?: -1L
                        if (declared > MAX_IMAGE_BYTES) null
                        else resp.body?.byteStream()?.use { readCapped(it, MAX_IMAGE_BYTES) }?.let { decodeBounded(it) }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "image load failed", e)
                null
            }
            runMain { onResult(bmp) }
        }
    }

    /** Oqimdan [cap] baytgacha o'qiydi; oshib ketsa null (juda katta/bomb). */
    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Avval o'lchamni o'qiydi (piksel ajratmasdan), so'ng inSampleSize bilan kichraytirib dekodlaydi. */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (probe.outWidth / sample > MAX_IMAGE_DIM || probe.outHeight / sample > MAX_IMAGE_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun parse(httpCode: Int, text: String): Result {
        val json = try { JSONObject(text) } catch (_: Throwable) { null }
            ?: return if (httpCode == 404) {
                Result.NetworkError("endpoint yo'q (404) — backend hali deploy qilinmagan")
            } else {
                Result.NetworkError("HTTP $httpCode")
            }
        if (!json.optBoolean("ok", false)) {
            return Result.NetworkError(json.optString("error", "xato"))
        }
        val arr = json.optJSONArray("news") ?: return Result.Success(emptyList())
        val out = ArrayList<NewsItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "").trim()
            if (title.isEmpty()) continue
            out.add(
                NewsItem(
                    id = o.optString("id", ""),
                    title = title,
                    body = if (o.isNull("body")) "" else o.optString("body", "").trim(),
                    level = o.optString("level", "info"),
                    imageUrl = if (o.isNull("image_url")) "" else o.optString("image_url", "").trim(),
                    pinned = o.optBoolean("pinned", false),
                    createdAt = o.optString("created_at", ""),
                )
            )
        }
        return Result.Success(out)
    }

    // ---- Konfiguratsiya (CloudTelemetry bilan bir xil BuildConfig) ------

    private fun baseUrl(): String? {
        val u = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (u.isBlank() || !u.startsWith("https://")) return null
        return u
    }

    private fun deviceSecret(): String? {
        val s = Secrets.cloudDeviceSecret().trim()
        return if (s.isBlank()) null else s
    }

    private fun runMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
