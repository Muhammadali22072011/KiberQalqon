package com.kiberqalqon

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * KiberQalqon Cloud telemetriyasi — markaziy monitoring paneli/xaritasi uchun.
 *
 * Bu CommunityReportClient (Telegram) dan ALOHIDA modul. Farqi:
 *   - CommunityReportClient: faqat DANGER/SUSPICIOUS ni dev Telegram'iga MATN qilib yuboradi.
 *   - CloudTelemetry: Vercel + Supabase backendiga STRUKTURALI JSON yuboradi, shunda panel:
 *       • xaritada qurilma nuqtasini ko'rsatadi (IP'dan shahar darajasidagi geo, GPS EMAS),
 *       • "jami skan" / "topilgan virus" statistikasini chizadi (shuning uchun SAFE ham yuboriladi),
 *       • jonli tahdid oqimini to'ldiradi.
 *
 * STRICT shartlar (bittasi bajarilmasa — HECH NARSA yuborilmaydi):
 *   1. Config.hasUserConsent           — ToS+Privacy qabul qilingan
 *   2. Config.hasCommunityShareConsent — "Jamoatchilik xavfsizligi" opt-in (ConsentActivity 3-galochka)
 *   3. BuildConfig.CLOUD_BASE_URL va CLOUD_DEVICE_SECRET to'ldirilgan (forklar uchun no-op)
 *
 * Maxfiylik (aniq, yashirin maydonsiz):
 *   - device_token — TASODIFIY anonim ID (UUID), qurilmada saqlanadi.
 *     IMEI / seriya / MAC / telefon raqami / IP / GPS — HECH QACHON yuborilmaydi.
 *   - Geo — server tomonda Vercel IP sarlavhalaridan (shahar darajasi). Qurilma koordinata yubormaydi.
 *   - Yuboriladi: qurilma modeli, Android versiyasi, ilova versiyasi va skan natijasi
 *     (apk_hash, paket nomi, yorliq, verdict, risk ball, sabablar, xavfli ruxsatlar).
 *   - APK faylning O'ZI yuborilmaydi. Boshqa ilovalar ro'yxati yuborilmaydi.
 */
object CloudTelemetry {

    private const val TAG = "CloudTelemetry"
    private const val PREFS = "kiberqalqon_cloud"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_LAST_REGISTER_TS = "last_register_ts"
    private const val KEY_LAST_REGISTER_VER = "last_register_ver"

    // Register'ni har app start'da emas, 12 soatda bir marta (yoki ilova versiyasi
    // o'zgarsa) yuboramiz — 15 daqiqalik WorkManager wakeup'larida spam bo'lmasin.
    private const val REGISTER_INTERVAL_MS = 12L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

    // ---- Public API -------------------------------------------------------

    /**
     * Qurilmani ro'yxatdan o'tkazadi (App.onCreate'da chaqiriladi). Shu tufayli
     * qurilma hali biror APK skan qilmagan bo'lsa ham xaritada nuqta sifatida
     * paydo bo'ladi. 12 soatda bir marta throttle qilinadi.
     */
    fun registerDevice(ctx: Context) {
        if (!enabled(ctx)) return
        val base = baseUrl() ?: return
        val secret = deviceSecret() ?: return

        val sp = prefs(ctx)
        val now = System.currentTimeMillis()
        val lastTs = sp.getLong(KEY_LAST_REGISTER_TS, 0L)
        val lastVer = sp.getInt(KEY_LAST_REGISTER_VER, -1)
        val verChanged = lastVer != BuildConfig.VERSION_CODE
        if (!verChanged && now - lastTs in 0 until REGISTER_INTERVAL_MS) return

        val token = deviceToken(ctx)
        val body = JSONObject().apply {
            put("device_token", token)
            put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_ver", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("app_ver", "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})")
        }
        scope.launch {
            val ok = postJson("$base/api/device/register", secret, body)
            if (ok) {
                sp.edit()
                    .putLong(KEY_LAST_REGISTER_TS, now)
                    .putInt(KEY_LAST_REGISTER_VER, BuildConfig.VERSION_CODE)
                    .apply()
            }
        }
    }

    /**
     * Skan natijasini cloudga yuboradi. finalizeResult ichidan HAR BIR skan uchun
     * chaqiriladi (SAFE ham!) — panel "jami skan"ni va xaritadagi yashil nuqtalarni
     * shundan biladi. Tarmoq ishi fonda (IO), skan oqimini bloklamaydi.
     */
    fun uploadScan(ctx: Context, apkPath: String, result: ScanResult) {
        if (!enabled(ctx)) return
        val base = baseUrl() ?: return
        val secret = deviceSecret() ?: return

        scope.launch {
            try {
                val file = File(apkPath)
                // Hash yo'q bo'lsa backend (apk_hash regex) rad etadi — bekorga yubormaymiz.
                val hash = sha256OfFile(file) ?: return@launch
                val token = deviceToken(ctx)
                val pkg = inferPackage(ctx, apkPath)
                val label = inferLabel(ctx, apkPath) ?: file.name

                val reasons = JSONArray().apply {
                    if (result.reason.isNotBlank()) put(result.reason.take(300))
                    result.malwareSignatures.take(8).forEach { put(it) }
                }
                val perms = JSONArray().apply {
                    result.dangerousPermissions.take(20).forEach { put(it) }
                }

                val body = JSONObject().apply {
                    put("device_token", token)
                    put("apk_hash", hash)
                    put("package_name", pkg ?: JSONObject.NULL)
                    put("app_label", label)
                    put("apk_size", file.length())
                    put("verdict", verdictKey(result.verdict))
                    put("risk_score", riskScore(result))
                    put("reasons", reasons)
                    put("perms", perms)
                }
                postJson("$base/api/scan/upload", secret, body)
            } catch (e: Throwable) {
                Log.w(TAG, "uploadScan failed", e)
            }
        }
    }

    // ---- Gating -----------------------------------------------------------

    private fun enabled(ctx: Context): Boolean =
        Config.hasUserConsent(ctx) &&
        Config.hasCommunityShareConsent(ctx) &&
        baseUrl() != null && deviceSecret() != null

    private fun baseUrl(): String? {
        val u = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (u.isBlank()) return null
        // Faqat HTTPS — qurilma kaliti (x-device-secret) tarmoqda ochiq ketmasin.
        if (!u.startsWith("https://")) return null
        return u
    }

    private fun deviceSecret(): String? {
        val s = BuildConfig.CLOUD_DEVICE_SECRET.trim()
        return if (s.isBlank()) null else s
    }

    // ---- Helpers ----------------------------------------------------------

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Anonim qurilma tokeni — bir marta yaratiladi, keyin doimiy saqlanadi. */
    private fun deviceToken(ctx: Context): String {
        val sp = prefs(ctx)
        sp.getString(KEY_DEVICE_TOKEN, null)?.let { if (it.length >= 16) return it }
        // 64 belgili hex — backend >= 16 talab qiladi, bu yetarlicha keng zaxira.
        val token = (UUID.randomUUID().toString() + UUID.randomUUID().toString())
            .replace("-", "")
        sp.edit().putString(KEY_DEVICE_TOKEN, token).apply()
        return token
    }

    private fun verdictKey(v: ScanResult.Verdict): String = when (v) {
        ScanResult.Verdict.DANGER -> "danger"
        ScanResult.Verdict.SUSPICIOUS -> "suspicious"
        ScanResult.Verdict.SAFE -> "safe"
    }

    /**
     * ScanResult'da raqamli ball yo'q — verdict + signal soni asosida hisoblaymiz.
     * Backend rang chegaralari: >=62 danger (qizil), >=32 suspicious (sariq), qolgani safe (yashil).
     * Shuning uchun DANGER 62+ ichida, SUSPICIOUS 32..61, SAFE 0..31 oralig'ida qoladi —
     * panel xaritasidagi nuqta rangi verdict bilan zid bo'lmaydi.
     */
    private fun riskScore(r: ScanResult): Int {
        val base = when (r.verdict) {
            ScanResult.Verdict.DANGER -> 70
            ScanResult.Verdict.SUSPICIOUS -> 38
            ScanResult.Verdict.SAFE -> 4
        }
        val bonus = r.malwareSignatures.size * 8 + r.dangerousPermissions.size * 3
        val cap = when (r.verdict) {
            ScanResult.Verdict.DANGER -> 100
            ScanResult.Verdict.SUSPICIOUS -> 61  // danger zonasiga sakramasin
            ScanResult.Verdict.SAFE -> 31         // suspicious zonasiga chiqmasin
        }
        return (base + bonus).coerceIn(0, cap)
    }

    private fun postJson(url: String, secret: String, body: JSONObject): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("x-device-secret", secret)
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST $url -> ${resp.code}: ${resp.body?.string()?.take(180)}")
                }
                resp.isSuccessful
            }
        } catch (e: Throwable) {
            Log.w(TAG, "postJson failed: $url", e)
            false
        }
    }

    /** SHA-256 fayl — streaming (xotiraga to'liq yuklamaymiz). */
    private fun sha256OfFile(f: File): String? {
        if (!f.exists() || !f.canRead()) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "sha256 failed", e); null
        }
    }

    /** APK paket nomini ochmasdan o'qish. */
    private fun inferPackage(ctx: Context, apkPath: String): String? = try {
        ctx.packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName
    } catch (_: Throwable) { null }

    /** Arxiv APK ichidagi ilova yorlig'i (ko'rinadigan nom). Topilmasa null. */
    private fun inferLabel(ctx: Context, apkPath: String): String? = try {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apkPath, 0)
        info?.applicationInfo?.let { ai ->
            // Arxiv APK'da label o'qish uchun source yo'llari qo'lda o'rnatilishi shart.
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            pm.getApplicationLabel(ai).toString()
        }
    } catch (_: Throwable) { null }
}
