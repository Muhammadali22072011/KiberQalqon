package com.kiberqalqon

import android.content.Context
import android.util.Log

/**
 * Katta hajmli tahdid bazasi — `assets/` ichidagi matn fayllaridan ish vaqtida yuklanadi.
 *
 * Nega assets, Kotlin `mapOf` emas?
 *   - Minglab imzo/hash'ni manba kodga yozish kompilyatsiyani sekinlashtiradi va
 *     `<clinit>` (static init) ni shishiradi. AV'lar (va raqobatchi CyberHimoya ham)
 *     blacklistni alohida data-fayl sifatida saqlaydi va o'qiydi.
 *   - Bazani yangilash uchun faqat assets faylini almashtirish kifoya.
 *
 * Ikki bazis:
 *   - [MaliciousHashes] / [MaliciousCerts] — qo'lda tahlil qilingan, oilasi aniq
 *     o'zbek namunalari (yuqori sifat). Ular birinchi tekshiriladi.
 *   - Bu klass — keng qamrov uchun tashqi feed (fayl yoki sertifikat SHA-256).
 *
 * Fayl formati (har ikkala fayl uchun):
 *   - har qatorda bitta yozuv;
 *   - `#` bilan boshlangan qatorlar — izoh, e'tiborsiz qoldiriladi;
 *   - yozuv `sha256` yoki `sha256,oila_nomi` ko'rinishida.
 *
 * [init] App.onCreate'da bir marta chaqiriladi. Chaqirilmasa — lookup'lar shunchaki
 * `null` qaytaradi (qo'lda kiritilgan bazaga ta'sir qilmaydi), shuning uchun unit
 * testlar uchun ham xavfsiz.
 */
object ThreatDb {

    private const val TAG = "ThreatDb"
    private const val FILE_HASHES_ASSET = "malicious_hashes.txt"
    private const val CERT_HASHES_ASSET = "malicious_certs.txt"

    @Volatile private var loaded = false

    // ConcurrentHashMap: bulut feed ([CloudBlacklist]) fon thread'da mergeqilishi mumkin,
    // skan esa boshqa thread'da o'qiydi — oddiy HashMap bo'lsa bu xavfli (data race) edi.
    private val fileHashes = java.util.concurrent.ConcurrentHashMap<String, String>()   // sha256(apk fayl) -> oila
    private val certHashes = java.util.concurrent.ConcurrentHashMap<String, String>()    // sha256(sertifikat) -> oila
    private val packages = java.util.concurrent.ConcurrentHashMap<String, String>()      // package name (lowercase) -> oila (faqat bulut feed)
    private val domains = java.util.concurrent.ConcurrentHashMap<String, String>()       // host (lowercase, sans-trailing-dot) -> oila (faqat bulut feed; URL/link checker uchun)

    /** assets'dagi bazani xotiraga yuklaydi. Idempotent, thread-safe, hech qachon throw qilmaydi. */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val ctx = context.applicationContext ?: context
            loadInto(ctx, FILE_HASHES_ASSET, fileHashes, defaultFamily = "Malware.tiFeed")
            loadInto(ctx, CERT_HASHES_ASSET, certHashes, defaultFamily = "Cert.tiFeed")
            loaded = true
            Log.i(TAG, "loaded fileHashes=${fileHashes.size} certHashes=${certHashes.size}")
        }
    }

    private fun loadInto(
        context: Context,
        asset: String,
        target: MutableMap<String, String>,
        defaultFamily: String,
    ) {
        try {
            context.assets.open(asset).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val comma = line.indexOf(',')
                    val hash = (if (comma >= 0) line.substring(0, comma) else line)
                        .trim().lowercase()
                    if (!isSha256(hash)) continue
                    val family = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    target[hash] = if (family.isNotEmpty()) family else defaultFamily
                }
            }
        } catch (e: Throwable) {
            // Fayl yo'q yoki o'qib bo'lmadi — bu fatal emas, qo'lda kiritilgan baza ishlayveradi.
            Log.w(TAG, "load '$asset' failed", e)
        }
    }

    private fun isSha256(s: String): Boolean {
        if (s.length != 64) return false
        for (c in s) {
            if (c !in '0'..'9' && c !in 'a'..'f') return false
        }
        return true
    }

    /** APK fayl SHA-256'si feed'da bo'lsa — oila nomi, aks holda null. */
    fun fileHashFamily(sha256: String?): String? {
        if (sha256.isNullOrBlank()) return null
        return fileHashes[sha256.lowercase()]
    }

    /** Sertifikat SHA-256'si feed'da bo'lsa — oila nomi, aks holda null. */
    fun certFamily(sha256: String?): String? {
        if (sha256.isNullOrBlank()) return null
        return certHashes[sha256.lowercase()]
    }

    /** Paket nomi bulut feed'da bo'lsa — oila nomi, aks holda null. */
    fun packageFamily(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return packages[packageName.lowercase()]
    }

    /**
     * Host (domen) bulut feed'da bo'lsa — oila nomi, aks holda null.
     * Null-safe; kalitlar kichik harfda va trailing nuqtasiz saqlanadi
     * (`example.com.` == `example.com`). [MaliciousDomains] buni curated ro'yxatdan
     * keyin fallback sifatida chaqiradi.
     */
    fun domainFamily(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return domains[host.lowercase().trimEnd('.')]
    }

    /**
     * Bulutdan ([CloudBlacklist]) kelgan yozuvlarni qo'shadi. Qo'lda kiritilgan baza
     * ([MaliciousHashes]/[MaliciousPackages]) BIRINCHI tekshiriladi — bu feed fallback,
     * shuning uchun bulut yozuvi qo'lda kiritilganni "yenga" olmaydi. Thread-safe (ConcurrentHashMap).
     */
    fun mergeCloud(cloudHashes: Map<String, String>, cloudPackages: Map<String, String>): Boolean {
        var changed = false
        for ((h, fam) in cloudHashes) {
            val key = h.lowercase()
            // put() avvalgi qiymatni qaytaradi — yangi kalit (null) yoki boshqa oila bo'lsa, baza o'zgargan.
            if (isSha256(key) && fileHashes.put(key, fam) != fam) changed = true
        }
        for ((p, fam) in cloudPackages) {
            val key = p.lowercase()
            if (key.isNotEmpty() && packages.put(key, fam) != fam) changed = true
        }
        Log.i(TAG, "after cloud merge: fileHashes=${fileHashes.size} packages=${packages.size} changed=$changed")
        return changed
    }

    /**
     * Bulutdan ([CloudBlacklist]) kelgan domen yozuvlarini qo'shadi. Bu [mergeCloud] bilan bir xil
     * o'zgarish-aniqlash uslubida: `put()` avvalgi qiymatni qaytaradi, yangi kalit (null) yoki
     * boshqa oila bo'lsa — baza o'zgargan. Faqat haqiqiy o'zgarishda `true` qaytaradi (ScanCache'ni
     * keraksiz invalidate qilmaslik uchun). Kalitlar kichik harf + trailing nuqtasiz normallashtiriladi.
     * Thread-safe (ConcurrentHashMap), hech qachon throw qilmaydi.
     */
    fun mergeCloudDomains(cloudDomains: Map<String, String>): Boolean {
        var changed = false
        for ((d, fam) in cloudDomains) {
            val key = d.lowercase().trimEnd('.')
            if (key.isNotEmpty() && domains.put(key, fam) != fam) changed = true
        }
        Log.i(TAG, "after cloud domain merge: domains=${domains.size} changed=$changed")
        return changed
    }

    /** Diagnostika uchun: yuklangan yozuvlar soni. */
    fun fileHashCount(): Int = fileHashes.size
    fun certHashCount(): Int = certHashes.size
    fun packageCount(): Int = packages.size
    fun domainCount(): Int = domains.size
    fun isLoaded(): Boolean = loaded
}
