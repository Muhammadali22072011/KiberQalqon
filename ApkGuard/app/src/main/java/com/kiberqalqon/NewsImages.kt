package com.uzguard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * E'lon rasmlari uchun minimal yuklagich: OkHttp + BitmapFactory + xotira-kesh.
 * Glide/Coil ataylab YO'Q — bitta karusel uchun kutubxona ortiqcha.
 *
 * Xavfsizlik chegaralari:
 *   • faqat https.
 *   • CHEGARALANGAN o'qish ([readCapped]): javob baytma-bayt ≤ 4 MB gacha o'qiladi va
 *     oshib ketsa DARHOL uziladi — chunked/Content-Length'siz javob butun heap'ni yutib
 *     OOM qilmasin (NewsClient.readCapped bilan bir xil usul; body.bytes() ATAYLAB emas).
 *   • callTimeout (umumiy) — sekin cheksiz oqim abadiy osib qolmasin.
 *   • dekodlashda 1080 px gacha downsample.
 * Xatoda null — chaqiruvchi rasmni shunchaki yashiradi. Bloklaydi — IO thread'dan chaqirilsin.
 *
 * Manfiy kesh: muvaffaqiyatsiz URL [FAIL_TTL_MS] davomida qayta urilmaydi — RecyclerView/
 * ViewPager har qayta-bind'da o'sha buzuq/oflayn havolani qayta yuklab, trafik/batareyani
 * sarflamasligi uchun (muvaffaqiyatli bitmap'lar [cache]'da).
 */
object NewsImages {

    private const val TAG = "NewsImages"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_DIM = 1080
    private const val FAIL_TTL_MS = 5L * 60 * 1000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Umumiy chegara: hatto sekin-sekin oqitilayotgan cheksiz javob ham 30s da uziladi.
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ~12 MB gacha bitmap keshi (8 ta to'liq karta rasmi atrofida) — protsess ichida.
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Oxirgi muvaffaqiyatsiz urinish vaqti (epoch ms) — qisqa muddatli backoff.
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun load(url: String): Bitmap? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        cache.get(url)?.let { return it }
        val now = System.currentTimeMillis()
        failedAt[url]?.let { if (now - it in 0..FAIL_TTL_MS) return null }
        return try {
            val req = Request.Builder().url(url).get().build()
            val bytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return fail(url)
                val body = resp.body ?: return fail(url)
                // Content-Length bo'lsa erta rad etamiz; bo'lmasa (chunked) o'qish baribir
                // [MAX_BYTES]'da uziladi — buffer hech qachon cheksiz o'smaydi.
                val len = body.contentLength()
                if (len > MAX_BYTES) return fail(url)
                readCapped(body.byteStream(), MAX_BYTES) ?: return fail(url)
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return fail(url)
            var sample = 1
            while (opts.outWidth / sample > MAX_DIM || opts.outHeight / sample > MAX_DIM) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return fail(url)
            cache.put(url, bmp)
            failedAt.remove(url)
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "image load failed: $url", e)
            fail(url)
        }
    }

    private fun fail(url: String): Bitmap? {
        failedAt[url] = System.currentTimeMillis()
        return null
    }

    /**
     * Oqimdan [cap] baytgacha o'qiydi; oshib ketsa null (juda katta/bomb). Heap'da
     * hech qachon [cap]+16 KB dan ko'p saqlanmaydi — body.bytes()'dan farqli ravishda
     * cheksiz javob OOM qilolmaydi (NewsClient.readCapped bilan bir xil).
     */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
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
}
