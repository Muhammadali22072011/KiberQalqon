package com.uzguard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * UzGuard Cloud telemetriyasi — markaziy monitoring paneli/xaritasi uchun.
 *
 * Bu CommunityReportClient (Telegram) dan ALOHIDA modul. Farqi:
 *   - CommunityReportClient: faqat DANGER/SUSPICIOUS ni dev Telegram'iga MATN qilib yuboradi.
 *   - CloudTelemetry: Vercel + Supabase backendiga STRUKTURALI JSON yuboradi, shunda panel:
 *       • xaritada qurilma nuqtasini ko'rsatadi (joylashuv ruxsati bo'lsa — aniq GPS;
 *         bo'lmasa — server IP'dan shahar darajasida taxminlaydi),
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
 *     IMEI / seriya / MAC / telefon raqami — HECH QACHON yuborilmaydi.
 *   - Geo — joylashuv ruxsati berilgan bo'lsa, qurilma o'z aniq koordinatasini (lat/lng)
 *     yuboradi. Ruxsat bo'lmasa koordinata yuborilmaydi va server faqat IP'dan shahar
 *     darajasida taxminlaydi. Fon joylashuvi so'ralmaydi (faqat oxirgi ma'lum nuqta).
 *   - Yuboriladi: qurilma modeli, Android versiyasi, ilova versiyasi va skan natijasi
 *     (apk_hash, paket nomi, yorliq, verdict, risk ball, sabablar, xavfli ruxsatlar).
 *   - APK faylning O'ZI — FAQAT xavfli/shubhali natijada — markaziy bulut Storage'iga
 *     yuklanadi (50 MB gacha), yangi viruslarni o'rganish va himoya signaturalari
 *     yaratish uchun. XAVFSIZ APK fayllari HECH QACHON yuklanmaydi. Boshqa ilovalar
 *     ro'yxati yuborilmaydi. (Privacy Policy 4(a)-bo'lim.)
 */
object CloudTelemetry {

    private const val TAG = "CloudTelemetry"
    private const val PREFS = "uzguard_cloud"
    private const val KEY_DEVICE_TOKEN = "device_token"
    // Per-device imzo tokeni — register javobida server beradi, yozuvlarni HMAC bilan
    // imzolash uchun. Bo'lmasa (hali register bo'lmagan / server kalitsiz) — faqat
    // x-device-secret bilan ketamiz (eski yo'l, buzilmaydi).
    private const val KEY_AUTH_TOKEN = "device_auth_token"
    private const val KEY_LAST_REGISTER_TS = "last_register_ts"
    private const val KEY_LAST_REGISTER_VER = "last_register_ver"
    // Oxirgi muvaffaqiyatli register'da yuborilgan nuqta ("lat,lng") — qurilma ko'chsa
    // 12 soatlik throttle oynasida ham qayta yuboramiz, shunda xaritadagi nuqta telefon
    // bilan birga yuradi (egasi shuni xohladi). Fon kuzatuvi YO'Q — faqat ilova ochilganda.
    private const val KEY_LAST_GEO = "last_register_geo"
    // "Sezilarli ko'chish" chegarasi (metr) — GPS shovqini yolg'on trigger bermasligi uchun.
    private const val GEO_MOVE_THRESHOLD_M = 500.0
    // Bulutga allaqachon yuklangan APK namuna hash'lari — qayta yuklamaslik uchun.
    private const val KEY_UPLOADED_SAMPLES = "uploaded_samples"
    // Qurilma qo'shilgan guruh (dashboard bejasi + GroupJoinActivity holati uchun).
    private const val KEY_GROUP_NAME = "group_name"
    private const val KEY_GROUP_COLOR = "group_color"
    // Storage'ga yuklanadigan eng katta APK (ConsentActivity 4(a) va'dasi bilan bir xil).
    private const val MAX_SAMPLE_BYTES = 50L * 1024 * 1024

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
    // APK namunasi 50 MB gacha bo'lishi mumkin — sekin mobil tarmoqda yozish uchun
    // uzunroq write timeout (kichik JSON client'i bilan bir xil emas).
    private val uploadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val APK_MEDIA = "application/vnd.android.package-archive".toMediaTypeOrNull()

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
        val withinWindow = !verChanged && now - lastTs in 0 until REGISTER_INTERVAL_MS

        val token = deviceToken(ctx)
        scope.launch {
            // Joylashuvni qaytaib yuborish strategiyasi (fon kuzatuvi YO'Q — faqat ilova ochilganda):
            //   • 12 soatlik throttle oynasida — aktiv GPS YOQMAYMIZ (batareya). Faqat OS
            //     keshidagi nuqta bilan ko'chishni tekshiramiz; qurilma >500 m ko'chmagan
            //     bo'lsa hech narsa yubormaymiz. Ko'chgan bo'lsa — xaritadagi nuqta telefon
            //     bilan birga yursin deb qayta yuboramiz.
            //   • Oyna o'tgan / versiya o'zgargan bo'lsa — to'liq fix (kerak bo'lsa bir
            //     martalik aktiv GPS) bilan odatdagi 12 soatlik "tirikman" signali.
            val fix: DeviceLocation.Fix? = if (withinWindow) {
                // #27: oxirgi ma'lum nuqtani FAQAT yetarlicha yangi bo'lsa ishlatamiz —
                // eskirgan kesh (kunlar oldingi) "joriy nuqta" sifatida yozilib qolmasin.
                val cached = DeviceLocation.lastKnownFresh(ctx, DeviceLocation.IN_WINDOW_MAX_AGE_MS)
                if (!movedSignificantly(sp, cached)) return@launch
                cached
            } else {
                DeviceLocation.currentFix(ctx)
            }

            val body = JSONObject().apply {
                put("device_token", token)
                put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_ver", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("app_ver", "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})")
            }
            if (fix != null) {
                body.put("lat", fix.lat)
                body.put("lng", fix.lng)
                fix.accuracyM?.let { body.put("loc_accuracy_m", it.toDouble()) }
            }

            val resp = postJsonForResult(ctx, "$base/api/device/register", secret, "device/register", body)
            if (resp != null) {
                // Server bergan per-device imzo tokenini saqlaymiz — keyingi yozuvlar shu
                // bilan HMAC imzolanadi. Bo'lmasa (server kalitsiz) eski yo'lda qolamiz.
                try {
                    val t = JSONObject(resp).optString("device_auth_token", "")
                    if (t.isNotBlank()) sp.edit().putString(KEY_AUTH_TOKEN, t).apply()
                } catch (_: Throwable) { /* token yo'q — muhim emas */ }
                val ed = sp.edit()
                    .putLong(KEY_LAST_REGISTER_TS, now)
                    .putInt(KEY_LAST_REGISTER_VER, BuildConfig.VERSION_CODE)
                if (fix != null) ed.putString(KEY_LAST_GEO, "${fix.lat},${fix.lng}")
                ed.apply()
            }
        }
    }

    /**
     * #3: Paneldan (EGASI) yuborilgan masofaviy buyruqlarni oladi va bajaradi.
     * At-most-once: server poll paytida buyruqni 'done' ga o'tkazadi (qayta kelmaydi →
     * masofaviy "rescan" cheksiz sikl yaratmaydi). Chaqiriladi: App.onCreate (ilova
     * ochilganda DARHOL) + HeartbeatWorker (fon, ~6 soatgacha). ALOHIDA tez-tez tsikl
     * QO'SHILMAYDI — batareya/isish regressi bo'lmasin (loyiha isish tarixiga sezgir).
     * Hozircha yagona tur: "rescan" (to'liq qayta skan → GuardWorker).
     */
    fun pollCommands(ctx: Context) {
        if (!enabled(ctx)) return
        val base = baseUrl() ?: return
        val secret = deviceSecret() ?: return
        val token = deviceToken(ctx)
        scope.launch {
            try {
                val req = Request.Builder()
                    .url("$base/api/device/poll")
                    .header("x-device-secret", secret)
                    .header("x-device-token", token)
                    .get()
                    .build()
                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    resp.body?.string().orEmpty()
                }
                val root = try { JSONObject(body) } catch (_: Throwable) { return@launch }
                if (!root.optBoolean("ok", false)) return@launch
                val arr = root.optJSONArray("commands") ?: return@launch
                var rescan = false
                for (i in 0 until arr.length()) {
                    when (arr.optJSONObject(i)?.optString("type")) {
                        "rescan" -> rescan = true
                        else -> { /* noma'lum tur — e'tiborsiz (kelajakdagi turlar) */ }
                    }
                }
                if (rescan) {
                    // Unique work — bir nechta rescan buyrug'i kelsa ham bitta skan navbatga tushadi.
                    androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
                        "remote_rescan",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        androidx.work.OneTimeWorkRequestBuilder<GuardWorker>().build(),
                    )
                    Log.i(TAG, "masofaviy qayta skan navbatga qo'yildi")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "pollCommands failed", e)
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

        val needsSample = result.verdict == ScanResult.Verdict.DANGER ||
            result.verdict == ScanResult.Verdict.SUSPICIOUS
        scope.launch {
            // #FP-2026-07-09: o'rnatilgan ilovaning O'Z base.apk'si (sourceDir) skanini bulutga
            // YUBORMAYMIZ. Bu — foydalanuvchining SHAXSIY ilovalar ro'yxati (DashboardNewActivity
            // har ochilganda 40 tagacha ilovani skanlaydi, InstalledAppsRescanWorker kunlik), "yovvoyi"
            // tahdid emas. Aks holda egasi paneli o'z telefonining har bir ilovasi uchun skan/alert bilan
            // to'lib ketardi (spike-alert ham noto'g'ri chiqardi). Haqiqiy tahdid yo'llari — yuklab olingan
            // APK fayl (GuardWorker sweep), real-time (ProtectionService), ulashilgan fayl (ShareReceiver) —
            // sourceDir EMAS, shuning uchun ular baribir yuboriladi. O'rnatilgan malware'ni
            // InstalledAppsRescanWorker Telegram orqali alohida xabar qiladi.
            if (isInstalledAppSelfScan(ctx, apkPath)) return@launch
            var snapshot: File? = null
            try {
                val original = File(apkPath)
                // #4 RACE: danger/suspicious APK GuardWorker tomonidan scan() qaytishi bilan
                // karantinga olinib O'CHIRILADI; bu (asinxron) yuklash o'shanda faylni
                // topolmasdi — namuna va ko'pincha metadata yo'qolardi. Shuning uchun namuna
                // kerak bo'lsa, tarmoq round-trip'idan OLDIN DARHOL vaqtinchalik nusxa olamiz
                // (Quarantine o'zining copyTo'sini bajarayotgan paytda asl fayl hali turibdi).
                val src = if (needsSample) (snapshotForUpload(ctx, original).also { snapshot = it } ?: original)
                          else original
                // Hash yo'q bo'lsa backend (apk_hash regex) rad etadi — bekorga yubormaymiz.
                val hash = sha256OfFile(src) ?: return@launch
                val token = deviceToken(ctx)
                val pkg = inferPackage(ctx, src.absolutePath)
                val label = inferLabel(ctx, src.absolutePath) ?: original.name

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
                    put("apk_size", src.length())
                    put("scan_duration_ms", result.durationMs)
                    put("verdict", verdictKey(result.verdict))
                    put("risk_score", riskScore(result))
                    put("reasons", reasons)
                    put("perms", perms)
                }
                putGeo(ctx, body)
                val resp = postJsonForResult(ctx, "$base/api/scan/upload", secret, "scan/upload", body)
                // Server xavfli/shubhali natija uchun imzolangan yuklash URL'i qaytarsa —
                // APK namunasini (snapshot) to'g'ridan-to'g'ri Storage'ga yuklaymiz.
                // Xavfsiz APK'lar uchun server URL bermaydi → bu yer ham hech narsa qilmaydi.
                if (resp != null && needsSample) {
                    maybeUploadSample(ctx, resp, hash, src)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "uploadScan failed", e)
            } finally {
                snapshot?.let { try { it.delete() } catch (_: Throwable) {} }
            }
        }
    }

    /**
     * Faylni cacheDir/cloud_samples'ga vaqtinchalik nusxalaydi (#4 race-fix). Faqat
     * 1..50MB oraliqdagi fayllar. Muvaffaqiyatsiz bo'lsa null (asl fayl ishlatiladi).
     */
    private fun snapshotForUpload(ctx: Context, src: File): File? {
        return try {
            if (!src.exists() || !src.canRead()) return null
            if (src.length() !in 1..MAX_SAMPLE_BYTES) return null
            val dir = File(ctx.cacheDir, "cloud_samples").apply { mkdirs() }
            val dst = File(dir, "snap_${System.nanoTime()}.tmp")
            src.copyTo(dst, overwrite = true)
            dst
        } catch (e: Throwable) {
            Log.w(TAG, "snapshotForUpload failed", e); null
        }
    }

    // ---- Guruhga qo'shilish (GroupJoinActivity) ---------------------------

    /** Guruhga qo'shilish natijasi. ok=false bo'lsa `error` sababi (code/fields/net/...). */
    data class JoinResult(val ok: Boolean, val error: String?, val groupName: String?, val groupColor: String?)

    /** Saqlangan guruh (mahalliy) — dashboard bejasi/holat uchun. Yo'q bo'lsa null. */
    data class GroupInfo(val name: String, val color: String)

    fun savedGroup(ctx: Context): GroupInfo? {
        val sp = prefs(ctx)
        val name = sp.getString(KEY_GROUP_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val color = sp.getString(KEY_GROUP_COLOR, null)?.takeIf { it.isNotBlank() } ?: "#C2143D"
        return GroupInfo(name, color)
    }

    /**
     * Qurilmani KOD bilan guruhga qo'shadi (foydalanuvchi ochiq amal — GroupJoinActivity).
     * Register'dan FARQLI: bu community-share consent'ini talab QILMAYDI (foydalanuvchi
     * o'zi ism/familiya/telefonini kiritib qo'shilishga rozi bo'ladi), faqat ToS (hasUserConsent)
     * + bulut sozlangan bo'lishi kerak. `cb` FON ipida chaqiriladi — chaqiruvchi UI'ga
     * runOnUiThread bilan o'tkazadi.
     */
    fun joinGroup(ctx: Context, code: String, first: String, last: String, phone: String, cb: (JoinResult) -> Unit) {
        val base = baseUrl()
        val secret = deviceSecret()
        if (base == null || secret == null || !Config.hasUserConsent(ctx)) {
            cb(JoinResult(false, "unconfigured", null, null)); return
        }
        val token = deviceToken(ctx)
        scope.launch {
            val result = try {
                val body = JSONObject().apply {
                    put("device_token", token)
                    put("code", code.trim().uppercase())
                    put("first", first.trim())
                    put("last", last.trim())
                    put("phone", phone.trim())
                }
                val resp = postJsonForResult(ctx, "$base/api/device/join", secret, "device/join", body)
                if (resp == null) {
                    JoinResult(false, "net", null, null)
                } else {
                    val j = JSONObject(resp)
                    if (j.optBoolean("ok", false)) {
                        val g = j.optJSONObject("group")
                        val name = g?.optString("name")?.takeIf { it.isNotBlank() } ?: code.trim().uppercase()
                        val color = g?.optString("color")?.takeIf { it.isNotBlank() } ?: "#C2143D"
                        prefs(ctx).edit()
                            .putString(KEY_GROUP_NAME, name)
                            .putString(KEY_GROUP_COLOR, color)
                            .apply()
                        JoinResult(true, null, name, color)
                    } else {
                        JoinResult(false, j.optString("error", "generic").ifBlank { "generic" }, null, null)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "joinGroup failed", e)
                JoinResult(false, "net", null, null)
            }
            cb(result)
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
        val s = Secrets.cloudDeviceSecret().trim()
        return if (s.isBlank()) null else s
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Joylashuv ruxsati berilgan bo'lsa, qurilmaning oxirgi ma'lum (kesh) koordinatasini
     * bodyga qo'shadi. Batareya/isishga sezgir: har skan yuklamasida (SAFE ham) AKTIV GPS
     * fix'i YOQILMAYDI — faqat OS keshidagi yetarlicha yangi (<=1 soat) nuqtani ishlatamiz.
     * Kesh yo'q/eski bo'lsa — hech narsa qo'shilmaydi (server IP'dan shahar darajasida
     * taxminlaydi). Xaritadagi nuqtani davriy registerDevice() yangilab turadi.
     */
    private fun putGeo(ctx: Context, body: JSONObject) {
        val fix = DeviceLocation.lastKnownFresh(ctx, DeviceLocation.IN_WINDOW_MAX_AGE_MS) ?: return
        body.put("lat", fix.lat)
        body.put("lng", fix.lng)
        fix.accuracyM?.let { body.put("loc_accuracy_m", it.toDouble()) }
    }

    /**
     * Qurilma oxirgi yuborilgan nuqtadan sezilarli (>500 m) uzoqlashganmi? Throttle
     * oynasida ham qayta register qilish kerakligini shu hal qiladi (nuqta telefon
     * bilan birga yursin). fix == null bo'lsa solishtirib bo'lmaydi → false (yubormaymiz).
     * Avval geo yuborilmagan bo'lsa → true (birinchi marta yuboramiz).
     */
    private fun movedSignificantly(sp: SharedPreferences, fix: DeviceLocation.Fix?): Boolean {
        if (fix == null) return false
        val last = sp.getString(KEY_LAST_GEO, null) ?: return true
        val i = last.indexOf(',')
        if (i <= 0) return true
        val lat = last.substring(0, i).toDoubleOrNull() ?: return true
        val lng = last.substring(i + 1).toDoubleOrNull() ?: return true
        return distanceMeters(lat, lng, fix.lat, fix.lng) > GEO_MOVE_THRESHOLD_M
    }

    /** Ikki koordinata orasidagi masofa (metr) — haversine; ko'chishni tekshirishga yetarli. */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

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

    /**
     * Yozuv so'rovi — javob MATNINI qaytaradi (muvaffaqiyatli bo'lsa). /api/scan/upload
     * "sample_upload" URL'ini, /api/device/register esa "device_auth_token" ni qaytaradi.
     * Xato/muvaffaqiyatsiz → null.
     *
     * Sarlavhalar: HAR DOIM x-device-secret (o'tish davri eski yo'li), va per-device token
     * keshlangan bo'lsa — QO'SHIMCHA HMAC imzo sarlavhalari (server dual-accept: imzoni
     * afzal ko'radi). bodyStr — AYNAN POST qilinadigan satr (imzo body-hash'i shundan).
     */
    private fun postJsonForResult(ctx: Context, url: String, secret: String, label: String, body: JSONObject): String? {
        return try {
            val bodyStr = body.toString()
            val rb = Request.Builder()
                .url(url)
                .header("x-device-secret", secret)
            addSignedHeaders(ctx, rb, label, bodyStr)
            val req = rb.post(bodyStr.toRequestBody(JSON)).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST $url -> ${resp.code}: ${text?.take(180)}")
                    null
                } else text
            }
        } catch (e: Throwable) {
            Log.w(TAG, "postJsonForResult failed: $url", e)
            null
        }
    }

    /**
     * Per-device imzo sarlavhalarini qo'shadi (token keshlangan bo'lsa). Imzo:
     *   sig = base64url(HMAC-SHA256(authToken, "<label>\n<ts>\n<nonce>\n<sha256hex(bodyStr)>"))
     * Server (devauth.ts) AYNAN shu kanonik satrni qayta hisoblab tekshiradi. Token yo'q
     * bo'lsa hech narsa qo'shilmaydi (faqat x-device-secret bilan o'tadi). Har qanday xato → jim.
     */
    private fun addSignedHeaders(ctx: Context, rb: Request.Builder, label: String, bodyStr: String) {
        try {
            val authToken = authToken(ctx) ?: return
            val deviceToken = deviceToken(ctx)
            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
            val canonical = "$label\n$ts\n$nonce\n${sha256Hex(bodyStr)}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sig = Base64.encodeToString(
                mac.doFinal(canonical.toByteArray(Charsets.UTF_8)),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            rb.header("x-device-token", deviceToken)
            rb.header("x-signature", sig)
            rb.header("x-timestamp", ts)
            rb.header("x-nonce", nonce)
        } catch (_: Throwable) { /* imzosiz — x-device-secret bilan o'tadi */ }
    }

    private fun authToken(ctx: Context): String? {
        val t = prefs(ctx).getString(KEY_AUTH_TOKEN, null)
        return if (t.isNullOrBlank()) null else t
    }

    /** SHA-256 hex (lowercase) — satr uchun (imzo body-hash'i; server 'hex' bilan mos). */
    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Server "sample_upload" URL'i bergan bo'lsa, APK faylni o'sha imzolangan
     * Storage URL'iga PUT qiladi. Faqat xavfli/shubhali natijada chaqiriladi.
     * Shartlar: fayl o'qiladigan, 1..50MB, va shu qurilmadan bu hash hali yuborilmagan.
     */
    private fun maybeUploadSample(ctx: Context, responseBody: String, hash: String, file: File) {
        try {
            val url = JSONObject(responseBody)
                .optJSONObject("sample_upload")
                ?.optString("url")
                ?.takeIf { it.isNotBlank() } ?: return

            if (!file.exists() || !file.canRead()) return
            if (file.length() !in 1..MAX_SAMPLE_BYTES) return

            val sp = prefs(ctx)
            val done = sp.getStringSet(KEY_UPLOADED_SAMPLES, emptySet()) ?: emptySet()
            if (hash in done) return  // shu qurilmadan allaqachon yuborilgan

            if (putFile(url, file)) {
                // Yangi to'plam (qaytarilgan set'ni o'zgartirmaymiz — Android talabi).
                sp.edit().putStringSet(KEY_UPLOADED_SAMPLES, done + hash).apply()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "maybeUploadSample failed", e)
        }
    }

    /** APK faylni imzolangan Storage URL'iga yuklaydi (PUT). true — muvaffaqiyatli. */
    private fun putFile(signedUrl: String, file: File): Boolean {
        return try {
            val req = Request.Builder()
                .url(signedUrl)
                .header("x-upsert", "true")  // bir xil hash qayta kelsa — qayta yoziladi
                .put(file.asRequestBody(APK_MEDIA))
                .build()
            uploadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sample PUT -> ${resp.code}: ${resp.body?.string()?.take(180)}")
                }
                resp.isSuccessful
            }
        } catch (e: Throwable) {
            Log.w(TAG, "putFile failed", e)
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

    /**
     * Skanlanayotgan fayl AYNAN o'rnatilgan biror ilovaning O'Z base.apk'si (sourceDir)mi?
     * Ha bo'lsa — bu qurilmaning shaxsiy ilovalar ro'yxati, bulut tahdid feed'iga yubormaymiz
     * (uploadScan boshidagi guard). Bitta paket-lookup — arzon (barcha paketlarni aylanmaydi).
     */
    private fun isInstalledAppSelfScan(ctx: Context, apkPath: String): Boolean {
        val pkg = inferPackage(ctx, apkPath) ?: return false
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir == apkPath
        } catch (_: Throwable) {
            false
        }
    }

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
