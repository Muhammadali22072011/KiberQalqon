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
 * Bulutdan yangilanadigan qora ro'yxat (fayl SHA-256 + package name). Markaz (panel)
 * yangi troyanni aniqlaganda, qurilmalar uni ilovani YANGILAMASDAN bloklaydi —
 * blacklist tarmoq orqali yetkaziladi va [ThreatDb]'ga qo'shiladi.
 *
 * ====== ANTIVIRUS OLTIN QOIDASI ======
 * Feed faqat DETEKTSIYANI KUCHAYTIRA oladi (ro'yxatga hash/paket QO'SHADI), hech qachon
 * zaiflashtira olmaydi. Imzo (HMAC-SHA256, CONFIG_SIGNING_SECRET — RemoteConfig bilan bir xil
 * kalit) MAJBURIY: imzosiz/buzilgan/eski-versiyali javob rad etiladi va assets bazasi
 * (malicious_hashes.txt) o'zgarishsiz ishlayveradi. Hech qachon throw qilmaydi.
 *
 * Skan ISSIQ yo'lida tarmoq YO'Q:
 *   • [loadCached] — keshlangan (avval tekshirilgan) feed'ni ThreatDb'ga qo'shadi (tez, App.onCreate).
 *   • [refresh]    — fonda (App.onCreate appScope / Worker) bir marta tarmoqdan yangilaydi.
 *
 * Sertifikatlar bu yerda YO'Q: bulut `threats` jadvalida sertifikat ustuni yo'q, shuning
 * uchun cert blacklist mijozda assets/malicious_certs.txt orqali qoladi.
 */
object CloudBlacklist {

    private const val TAG = "CloudBlacklist"
    private const val PREFS = "uzguard_cloud_bl"
    private const val KEY_V = "cbl_v"      // hash/paket feed versiyasi (threatMaxSeen)
    private const val KEY_DV = "cbl_dv"    // domen feed versiyasi (domainMaxSeen) — MUSTAQIL rollback-guard
    private const val KEY_PAYLOAD = "cbl_payload"
    private const val KEY_FETCHED_AT = "cbl_fetched_at"
    private const val KEY_ATTEMPTED_AT = "cbl_attempted_at"  // oxirgi URINISH (muvaffaqiyatdan qat'i nazar) — backoff

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Keshlangan (avval tekshirilgan) feed'ni ThreatDb'ga qo'shadi. Tarmoq YO'Q. */
    fun loadCached(ctx: Context) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val envelope = sp.getString(KEY_PAYLOAD, null) ?: return
            val signingKey = Secrets.configSigningSecret().trim()
            // Imzo kaliti yo'q bo'lsa — keshga ishonmaymiz (assets bazasi qoladi).
            if (signingKey.isBlank()) return
            // CC-03: keshlangan feed ham QAYTA imzo bo'yicha tekshiriladi (HMAC). Aks holda
            // rooted/backup-restore orqali prefs'ga yozib qo'yilgan soxta payload (masalan benign
            // paketni "zararli" qilib) shartsiz merge bo'lardi. Endi imzosiz kesh rad etiladi.
            val payload = verifyAndDecode(envelope, signingKey) ?: return
            // Keshlangan konvert — oxirgi qabul qilingan (imzo tekshirilgan) feed; ikkala manbani ham
            // (hash/paket + domen) qo'llaymiz (ThreatDb merge faqat QO'SHADI, hech qachon zaiflashtirmaydi).
            mergeIntoThreatDb(payload, applyThreats = true, applyDomains = true)
            // YARA-lite qoida paketlari + known-good (RuleStore) — MUSTAQIL rv/gv rollback-guard'lar
            // RuleStore ichida (uzguard_rules prefs). Keshlangan konvert allaqachon imzo bo'yicha
            // qayta tekshirilgan; RuleStore faqat versiya guard'ini qo'llaydi. Fail-soft.
            RuleStore.applyRules(ctx, payload.optJSONArray("rules"), payload.optInt("rv", 0))
            RuleStore.applyGood(ctx, payload.optJSONArray("good"), payload.optInt("gv", 0))
        } catch (e: Throwable) {
            Log.w(TAG, "loadCached failed", e)
        }
    }

    /**
     * Davriy yangilash (GuardWorker, har 15 daqiqada chaqiriladi): oxirgi MUVAFFAQIYATLI
     * yuklab olishdan beri kamida [minAgeMs] o'tgan bo'lsagina tarmoqqa chiqadi — server
     * 5 daqiqa keshlaydi, har 15 daqiqada urishning ma'nosi yo'q. App.onCreate'dagi
     * to'g'ridan-to'g'ri [refresh] throttlesiz qoladi (sovuq start kamdan-kam bo'ladi).
     */
    fun refreshIfStale(ctx: Context, minAgeMs: Long = 30L * 60 * 1000) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Throttle URINISH bo'yicha, MUVAFFAQIYAT bo'yicha emas: refresh() FETCHED_AT'ni faqat
        // to'liq muvaffaqiyatda yozadi, shuning uchun server o'chiq/imzo xato/versiya regress
        // bo'lsa edi har 15 daqiqada (har Worker) qayta tarmoqqa chiqaverardi. Endi urinish
        // vaqtini OLDIN belgilaymiz — muvaffaqiyatsiz bo'lsa ham keyingi urinish ≥ minAgeMs'dan keyin.
        val lastAttempt = sp.getLong(KEY_ATTEMPTED_AT, 0L)
        // Soat orqaga surilgan bo'lsa (now < lastAttempt) throttle abadiy qolib ketmasin.
        if (now in lastAttempt..(lastAttempt + minAgeMs)) return
        sp.edit().putLong(KEY_ATTEMPTED_AT, now).apply()
        refresh(ctx)
    }

    /**
     * Fonda tarmoqdan yangilaydi (IO thread shart — bloklaydi). Hammasi fail-safe:
     * muvaffaqiyatsizlikda kesh tegmaydi, assets bazasi ishlayveradi.
     */
    fun refresh(ctx: Context) {
        try {
            val base = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
            if (base.isBlank() || !base.startsWith("https://")) return
            val deviceSecret = Secrets.cloudDeviceSecret().trim()
            if (deviceSecret.isBlank()) return
            val signingKey = Secrets.configSigningSecret().trim()
            // Imzo kaliti yo'q bo'lsa — imzolanmagan feed'ga ishonmaymiz → assets'da qolamiz.
            if (signingKey.isBlank()) return

            val req = Request.Builder()
                .url("$base/api/threats?feed=1")
                .header("x-device-secret", deviceSecret)
                .get()
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body?.string().orEmpty()
            }
            val root = try { JSONObject(body) } catch (_: Throwable) { return }
            if (!root.optBoolean("ok", false)) return
            val envelope = root.optString("feed", "")
            val payload = verifyAndDecode(envelope, signingKey) ?: return

            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val remoteV = payload.optInt("v", -1)
            if (remoteV < 0) return
            // Domen versiyasi alohida (eski feed'da bo'lmasligi mumkin — 0 = neytral, regress emas).
            val remoteDv = payload.optInt("dv", 0)

            // MUSTAQIL rollback-guard'lar: hash/paket (v) va domen (dv) ALOHIDA baholanadi. Bir
            // jadvalning vaqtinchalik nosozligi (server domainMaxSeen=0 berishi) endi BUTUN konvertni
            // emas, faqat o'sha manbani bloklaydi. Aks holda yangi hash/paket yangilanishi domen
            // o'qish xatosi tufayli rad etilardi.
            val applyThreats = remoteV >= sp.getInt(KEY_V, -1)
            val applyDomains = remoteDv >= sp.getInt(KEY_DV, -1)
            if (!applyThreats && !applyDomains) {
                Log.w(TAG, "feed versiyasi eski — rad etildi")
                return
            }
            // CC-03: imzolangan KONVERTni saqlaymiz (dekodlangan JSON emas) — loadCached uni
            // qayta tekshira oladi (prefs'ga soxta yozuvga qarshi). Faqat qo'llaniladigan manba
            // versiyasini oshiramiz — qo'llanmaganini regress qildirmaymiz.
            val ed = sp.edit()
                .putString(KEY_PAYLOAD, envelope)
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            if (applyThreats) ed.putInt(KEY_V, remoteV)
            if (applyDomains) ed.putInt(KEY_DV, remoteDv)
            ed.apply()
            // Faqat HAQIQATAN yangi hash/paket/domen qo'shilganda skan keshini bekor qilamiz.
            // Aks holda (xuddi shu feed har sovuq startda qayta merge bo'ladi) kesh
            // har ochilishda yo'qolib, foydasiz bo'lib qolardi. Yangi tahdid kelganda esa
            // avval SAFE keshlangan (endi qora ro'yxatdagi) fayl qayta skan qilinadi (#1 false-safe).
            if (mergeIntoThreatDb(payload, applyThreats, applyDomains)) {
                Config.markDatabaseUpdated(ctx)
            }
            // YARA-lite qoida paketlari (rules) + known-good (good) — hash/paket/domen feed'idan
            // MUSTAQIL. rv/gv rollback-guard'lar RuleStore ichida (uzguard_rules prefs) — CloudBlacklist'ning
            // KEY_V/KEY_DV idiomasi kabi (remote >= stored → qo'llanadi). 0 = neytral (qoida yo'q).
            // Fail-soft: RuleStore hech qachon throw qilmaydi. Rules FAQAT detektsiyani kuchaytiradi;
            // good esa ApkScanner'da DOWNGRADE-ONLY (soft signal → SAFE, hard DANGER'ga tegmaydi).
            RuleStore.applyRules(ctx, payload.optJSONArray("rules"), payload.optInt("rv", 0))
            RuleStore.applyGood(ctx, payload.optJSONArray("good"), payload.optInt("gv", 0))
            Log.i(TAG, "cloud blacklist qo'llandi (v=$remoteV, dv=$remoteDv, threats=$applyThreats, domains=$applyDomains)")
        } catch (e: Throwable) {
            Log.w(TAG, "refresh failed (assets bazasi saqlanadi)", e)
        }
    }

    /**
     * @param applyThreats hash/paket manbasini qo'llash (v rollback-guard'idan o'tgan bo'lsa).
     * @param applyDomains domen manbasini qo'llash (dv rollback-guard'idan o'tgan bo'lsa).
     * @return true — agar ThreatDb'ga yangi/o'zgargan yozuv qo'shilgan bo'lsa (kesh bekor qilinishi kerak).
     */
    private fun mergeIntoThreatDb(payload: JSONObject, applyThreats: Boolean, applyDomains: Boolean): Boolean {
        val hashes = HashMap<String, String>()
        val packages = HashMap<String, String>()
        val domains = HashMap<String, String>()
        if (applyThreats) payload.optJSONArray("hashes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = o.optString("h", "").trim().lowercase()
                if (h.length == 64) hashes[h] = o.optString("f", "Cloud.feed").ifBlank { "Cloud.feed" }
            }
        }
        if (applyThreats) payload.optJSONArray("packages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p", "").trim().lowercase()
                // O'zimizni HECH QACHON bloklamaymiz (zaharlangan feed himoyasi — CLOUD-01).
                if (p.isNotEmpty() && !isSelfPackage(p)) {
                    packages[p] = o.optString("f", "Cloud.feed").ifBlank { "Cloud.feed" }
                }
            }
        }
        // B1 — domen feed'i: {"domains":[{"d":host,"f":family}]} (URL/link checker uchun).
        // d — kichik harf host (trailing nuqtasiz ThreatDb'da normallashtiriladi); default oila "Cloud.feed".
        // O'zimizning domen yo'q (skip qiladigan narsa yo'q). Fail-soft: massiv yo'q bo'lsa o'tkazib yuboramiz.
        if (applyDomains) payload.optJSONArray("domains")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val d = o.optString("d", "").trim().lowercase().trimEnd('.')
                if (d.isNotEmpty() && d.contains('.')) {
                    domains[d] = o.optString("f", "Cloud.feed").ifBlank { "Cloud.feed" }
                }
            }
        }
        var changed = false
        if (hashes.isNotEmpty() || packages.isNotEmpty()) {
            // OR-in: hash/paket VA domen o'zgarishi — ikkalasi ham markDatabaseUpdated'ni yoqishi kerak.
            if (ThreatDb.mergeCloud(hashes, packages)) changed = true
        }
        if (domains.isNotEmpty()) {
            if (ThreatDb.mergeCloudDomains(domains)) changed = true
        }
        return changed
    }

    /** Bulut paket-feed'i o'zimizni yoki tizim paketini bloklab qo'ymasligi uchun istisno. */
    private fun isSelfPackage(pkg: String): Boolean =
        pkg == "com.uzguard" || pkg == "com.uzguard.debug" || pkg.startsWith("com.apkguard")

    /** "payloadB64.sigB64" envelopni HMAC-SHA256 bilan tekshiradi, payload JSON'ini qaytaradi. */
    private fun verifyAndDecode(envelope: String, signingKey: String): JSONObject? {
        val dot = envelope.indexOf('.')
        if (dot <= 0 || dot >= envelope.length - 1) return null
        val payloadB64 = envelope.substring(0, dot)
        val sigB64 = envelope.substring(dot + 1)
        val expected = hmac(signingKey, payloadB64) ?: return null
        if (!constantTimeEquals(expected, sigB64)) {
            Log.w(TAG, "feed imzosi noto'g'ri — rad etildi")
            return null
        }
        return try {
            JSONObject(String(b64dec(payloadB64), Charsets.UTF_8))
        } catch (_: Throwable) { null }
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
