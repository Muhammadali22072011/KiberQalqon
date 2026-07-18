package com.uzguard

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Imzolangan masofaviy konfiguratsiya — verdikt CHEGARALARI (thresholds) va skoring
 * minimumlarini bulutdan oladi. Maqsad: yuklab olingan APK ichida "qanday ball DANGER
 * beradi" degan ANIQ raqamlar TURMASIN — buzg'unchi chegaralarni bilib, obxod yasay olmasin.
 *
 * ====== ANTIVIRUS OLTIN QOIDASI (eng muhim) ======
 * Har qanday xato yo'li (oflayn, imzo noto'g'ri, kalit yo'q, parse xatosi, eski versiya)
 * → BAKED (ichki) standartlarga qaytadi. Server faqat DETEKTSIYANI KUCHAYTIRA oladi
 * (chegarani PASAYTIRISH = ko'proq aniqlash), hech qachon ZAIFLASHTIRA olmaydi:
 *   - chegaralar:        effective = min(remote, baked)   // pastroq = ko'proq DANGER
 *   - strongComboMin:    effective = min(remote, baked)   // pastroq = ko'proq kuchli-combo
 *   - randomPkg minlar:  effective = min(remote, baked)
 * Shu sabab SOXTA (forge qilingan) config ham faqat ko'proq aniqlashga olib keladi —
 * hujumchi uchun foyda yo'q. Imzo (HMAC) + TLS-pinning — qo'shimcha himoya qatlamlari.
 *
 * Skan ISSIQ yo'lida TARMOQ YO'Q: get() faqat keshlangan (allaqachon clamp qilingan)
 * qiymatlarni o'qiydi. refresh() fonda (App.onCreate / WorkManager) bir marta chaqiriladi.
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"
    private const val PREFS = "uzguard_remote_config"
    private const val KEY_V = "rc_v"
    private const val KEY_FETCHED_AT = "rc_fetched_at"
    // Davriy (GuardWorker) yangilash uchun oxirgi URINISH vaqti + eskirish oynasi.
    private const val KEY_LAST_REFRESH_ATTEMPT = "rc_last_refresh_attempt"
    private const val REFRESH_STALE_MS = 6L * 60 * 60 * 1000  // 6 soat

    // ====== BAKED (ichki) standartlar — ApkScanner'dagi joriy qiymatlar bilan AYNAN bir xil ======
    // O'zgartirsangiz, ApkScanner verdikt mantig'i bilan mos bo'lishini tekshiring.
    private const val BAKED_HIGH_DANGER = 55
    private const val BAKED_HIGH_SUSP = 28
    private const val BAKED_MED_DANGER = 85
    private const val BAKED_MED_SUSP = 40
    private const val BAKED_LOW_DANGER = 120
    private const val BAKED_LOW_SUSP = 60
    private const val BAKED_STRONG_COMBO_MIN = 90
    private const val BAKED_RANDOM_FILENAME_MIN = 40
    private const val BAKED_RANDOM_PERMS_MIN = 3

    // ====== PASTKI POL (floor) — masofaviy config faqat KUCHAYTIRA oladi, lekin NOLGA
    // tushira olmaydi. Aks holda imzolangan (server xatosi yoki kalit o'g'irlangan) config
    // chegarani 0 qilib, HAR BIR skan APK'ni DANGER deb belgilashi mumkin edi (totalScore
    // doim >= 0). Bu — ommaviy false-positive DoS. Shu sabab har bir maydonga kichik musbat
    // pol qo'yamiz: chegara hech qachon 0 bo'lmaydi, minlar hech qachon 0 bo'lmaydi.
    private const val FLOOR_THRESHOLD = 10   // danger/suspicious chegaralari uchun
    private const val FLOOR_STRONG_COMBO = 10
    private const val FLOOR_RANDOM_FILENAME = 5
    private const val FLOOR_RANDOM_PERMS = 1

    /** Pref kaliti bo'yicha pastki pol. Noma'lum kalit → 1 (hech qachon 0). */
    private fun floorFor(key: String): Int = when (key) {
        "rc_high_danger", "rc_high_susp",
        "rc_med_danger", "rc_med_susp",
        "rc_low_danger", "rc_low_susp" -> FLOOR_THRESHOLD
        "rc_strong_combo_min" -> FLOOR_STRONG_COMBO
        "rc_random_filename_min" -> FLOOR_RANDOM_FILENAME
        "rc_random_perms_min" -> FLOOR_RANDOM_PERMS
        else -> 1
    }

    /** Keshdan o'qishda ham polni qayta qo'llaymiz: minOf(baked, maxOf(kesh, pol)). */
    private fun clampRead(sp: android.content.SharedPreferences, key: String, bakedVal: Int): Int =
        minOf(bakedVal, maxOf(sp.getInt(key, bakedVal), floorFor(key)))

    /** Skan vaqtida o'qiladigan amaldagi (clamp qilingan) qiymatlar. */
    data class Effective(
        val highDanger: Int, val highSusp: Int,
        val medDanger: Int, val medSusp: Int,
        val lowDanger: Int, val lowSusp: Int,
        val strongComboMin: Int,
        val randomPkgFilenameMin: Int,
        val randomPkgDangerousPermsMin: Int,
    ) {
        fun dangerThreshold(sensitivity: String): Int = when (sensitivity) {
            "high" -> highDanger; "low" -> lowDanger; else -> medDanger
        }
        fun suspiciousThreshold(sensitivity: String): Int = when (sensitivity) {
            "high" -> highSusp; "low" -> lowSusp; else -> medSusp
        }
    }

    private val baked = Effective(
        BAKED_HIGH_DANGER, BAKED_HIGH_SUSP, BAKED_MED_DANGER, BAKED_MED_SUSP,
        BAKED_LOW_DANGER, BAKED_LOW_SUSP, BAKED_STRONG_COMBO_MIN,
        BAKED_RANDOM_FILENAME_MIN, BAKED_RANDOM_PERMS_MIN
    )

    /** Baked standartlar (test/diagnostika uchun ham). */
    fun baked(): Effective = baked

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Skan vaqtida chaqiriladi — FAQAT keshdan o'qiydi (tarmoq YO'Q). Har bir maydon
     * uchun keshlangan (clamp qilingan) qiymat bo'lmasa, baked'ga qaytadi. Hech qachon
     * tashlamaydi (xato → baked).
     */
    fun get(ctx: Context): Effective = try {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // O'QISHDA ham qayta clamp = min(kesh, BAKED): keshlangan qiymat keyingi build'da
        // pasaytirilgan baked'dan KATTA (zaif) bo'lib qolmasin. min faqat detekt tomon
        // siljitadi — golden qoidaga to'liq mos, downside YO'Q.
        // O'QISHDA: minOf(baked, maxOf(kesh, pol)) — pol tufayli chegara/min hech qachon
        // 0 (yoki juda past) bo'lib qolmaydi, hatto kesh buzilgan bo'lsa ham.
        Effective(
            highDanger = clampRead(sp, "rc_high_danger", baked.highDanger),
            highSusp = clampRead(sp, "rc_high_susp", baked.highSusp),
            medDanger = clampRead(sp, "rc_med_danger", baked.medDanger),
            medSusp = clampRead(sp, "rc_med_susp", baked.medSusp),
            lowDanger = clampRead(sp, "rc_low_danger", baked.lowDanger),
            lowSusp = clampRead(sp, "rc_low_susp", baked.lowSusp),
            strongComboMin = clampRead(sp, "rc_strong_combo_min", baked.strongComboMin),
            randomPkgFilenameMin = clampRead(sp, "rc_random_filename_min", baked.randomPkgFilenameMin),
            randomPkgDangerousPermsMin = clampRead(sp, "rc_random_perms_min", baked.randomPkgDangerousPermsMin),
        )
    } catch (_: Throwable) { baked }

    /**
     * Fonda config'ni yangilaydi (App.onCreate / WorkManager'dan). Tarmoq ishi —
     * IO thread'da chaqirilishi shart (chaqiruvchi coroutine/Worker ichida). Bloklaydi.
     * Hamma narsa fail-safe: muvaffaqiyatsizlikda kesh tegmaydi → baked yoki oldingi clamp.
     */
    /**
     * Davriy (GuardWorker) yo'li uchun: oxirgi urinishdan 6 soat o'tgan bo'lsagina tarmoqdan
     * yangilaydi. Sabab: [refresh] faqat App.onCreate'da chaqirilardi — jarayon foreground
     * service tufayli kunlab tirik qolsa sovuq start bo'lmaydi va yangi config (jumladan
     * SelfUpdate "update" bloki) HECH QACHON yetib kelmasdi. Attempt-throttle: urinish vaqti
     * xatoda ham yoziladi (oflaynda har 30 daqiqada tarmoqqa uravermaslik uchun).
     */
    fun refreshIfStale(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = sp.getLong(KEY_LAST_REFRESH_ATTEMPT, 0L)
            val now = System.currentTimeMillis()
            if (last in 1..now && now - last < REFRESH_STALE_MS) return
            sp.edit().putLong(KEY_LAST_REFRESH_ATTEMPT, now).apply()
            refresh(ctx)
        } catch (_: Throwable) { /* fail-safe: keyingi oynada qayta uriniladi */ }
    }

    fun refresh(ctx: Context) {
        try {
            val base = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
            if (base.isBlank() || !base.startsWith("https://")) return
            val deviceSecret = Secrets.cloudDeviceSecret().trim()
            if (deviceSecret.isBlank()) return
            val signingKey = Secrets.configSigningSecret().trim()
            // Imzo kaliti yo'q bo'lsa — masofaviy config'ga ishonib bo'lmaydi → baked'da qolamiz.
            if (signingKey.isBlank()) return

            val req = Request.Builder()
                .url("$base/api/config")
                .header("x-device-secret", deviceSecret)
                .get()
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body?.string().orEmpty()
            }
            val root = try { JSONObject(body) } catch (_: Throwable) { return }
            if (!root.optBoolean("ok", false)) return
            val envelope = root.optString("config", "")
            val payloadJson = verifyAndDecode(envelope, signingKey) ?: return

            apply(ctx, payloadJson)
        } catch (e: Throwable) {
            Log.w(TAG, "refresh failed (baked saqlanadi)", e)
        }
    }

    /** "payloadB64.sigB64" envelopni HMAC-SHA256 bilan tekshiradi, payload JSON'ini qaytaradi. */
    private fun verifyAndDecode(envelope: String, signingKey: String): JSONObject? {
        val dot = envelope.indexOf('.')
        if (dot <= 0 || dot >= envelope.length - 1) return null
        val payloadB64 = envelope.substring(0, dot)
        val sigB64 = envelope.substring(dot + 1)
        val expected = hmac(signingKey, payloadB64) ?: return null
        // Doimiy-vaqtli solishtirish (timing leak'ni kamaytirish).
        if (!constantTimeEquals(expected, sigB64)) {
            Log.w(TAG, "config imzosi noto'g'ri — rad etildi")
            return null
        }
        return try {
            val json = String(b64dec(payloadB64), Charsets.UTF_8)
            JSONObject(json)
        } catch (_: Throwable) { null }
    }

    private fun apply(ctx: Context, payload: JSONObject) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remoteV = payload.optInt("v", -1)
        if (remoteV < 0) return
        // Rollback guard: eski versiyani qabul qilmaymiz.
        val storedV = sp.getInt(KEY_V, -1)
        if (remoteV < storedV) {
            Log.w(TAG, "config versiyasi eski ($remoteV < $storedV) — rad etildi")
            return
        }

        val th = payload.optJSONObject("thresholds")
        val ed = sp.edit()
        // clamp = min(remote, baked): masofaviy faqat PASAYTIRA oladi (ko'proq aniqlash).
        th?.optJSONObject("high")?.let {
            putClampedMin(ed, "rc_high_danger", it.optInt("danger", baked.highDanger), baked.highDanger)
            putClampedMin(ed, "rc_high_susp", it.optInt("suspicious", baked.highSusp), baked.highSusp)
        }
        th?.optJSONObject("medium")?.let {
            putClampedMin(ed, "rc_med_danger", it.optInt("danger", baked.medDanger), baked.medDanger)
            putClampedMin(ed, "rc_med_susp", it.optInt("suspicious", baked.medSusp), baked.medSusp)
        }
        th?.optJSONObject("low")?.let {
            putClampedMin(ed, "rc_low_danger", it.optInt("danger", baked.lowDanger), baked.lowDanger)
            putClampedMin(ed, "rc_low_susp", it.optInt("suspicious", baked.lowSusp), baked.lowSusp)
        }
        if (payload.has("strongComboMin"))
            putClampedMin(ed, "rc_strong_combo_min", payload.optInt("strongComboMin", baked.strongComboMin), baked.strongComboMin)
        if (payload.has("randomPkgFilenameMin"))
            putClampedMin(ed, "rc_random_filename_min", payload.optInt("randomPkgFilenameMin", baked.randomPkgFilenameMin), baked.randomPkgFilenameMin)
        if (payload.has("randomPkgDangerousPermsMin"))
            putClampedMin(ed, "rc_random_perms_min", payload.optInt("randomPkgDangerousPermsMin", baked.randomPkgDangerousPermsMin), baked.randomPkgDangerousPermsMin)

        // Ixtiyoriy o'z-o'zini yangilash bloki: {"update": {"versionCode": N, "apkUrl": "https://…",
        // "apkSha256": "…"}}. Butun payload HMAC bilan imzolangan + rollback-guard — bu metadata
        // ishonchli. Qo'shimcha himoya baribir [SelfUpdate]'da: yuklangan APK SHA-256 va IMZO
        // SERTIFIKATI o'zimiznikiga mos kelmasa O'RNATILMAYDI. Maydonlar yo'q bo'lsa — hech narsa.
        val up = payload.optJSONObject("update")
        if (up != null) {
            val upVc = up.optInt("versionCode", -1)
            val upUrl = up.optString("apkUrl", "")
            val upSha = up.optString("apkSha256", "")
            if (upVc > 0 && upUrl.startsWith("https://") && upSha.length >= 32) {
                ed.putInt("rc_up_vc", upVc)
                ed.putString("rc_up_url", upUrl)
                ed.putString("rc_up_sha", upSha.lowercase())
            }
        }

        ed.putInt(KEY_V, remoteV)
        ed.putLong(KEY_FETCHED_AT, System.currentTimeMillis())
        ed.apply()
        Log.i(TAG, "remote config qo'llandi (v=$remoteV, clamp=min)")
    }

    /** O'z-o'zini yangilash ma'lumoti (imzolangan config'dan keshlangan). Yo'q bo'lsa null. */
    fun updateInfo(ctx: Context): SelfUpdate.Info? = try {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vc = sp.getInt("rc_up_vc", -1)
        val url = sp.getString("rc_up_url", null)
        val sha = sp.getString("rc_up_sha", null)
        if (vc > 0 && !url.isNullOrBlank() && !sha.isNullOrBlank()) {
            SelfUpdate.Info(vc, url, sha)
        } else null
    } catch (_: Throwable) { null }

    // clamp: faqat baked'dan KICHIK (yoki teng) qiymatlar saqlanadi — ko'proq aniqlash tomon.
    // Manfiy/aqlsiz qiymatlardan ham himoya: 0 dan kichik bo'lsa baked.
    // PASTKI POL: masofaviy config chegarani nolga (yoki polдан pastga) tushira olmaydi —
    // aks holda dangerThreshold=0 barcha APK'ni DANGER qilib, ommaviy false-positive DoS
    // yasardi. safe = minOf(baked, maxOf(remote, pol)).
    private fun putClampedMin(ed: android.content.SharedPreferences.Editor, key: String, remote: Int, baked: Int) {
        val safe = if (remote < 0) baked else minOf(baked, maxOf(remote, floorFor(key)))
        ed.putInt(key, safe)
    }

    private fun hmac(key: String, msg: String): String? = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        Base64.encodeToString(
            mac.doFinal(msg.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    } catch (_: Throwable) { null }

    private fun b64dec(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ba.size != bb.size) return false
        var r = 0
        for (i in ba.indices) r = r or (ba[i].toInt() xor bb[i].toInt())
        return r == 0
    }
}
