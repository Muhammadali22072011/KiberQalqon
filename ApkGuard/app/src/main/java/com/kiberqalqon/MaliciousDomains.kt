package com.kiberqalqon

import android.util.Log

/**
 * Zararli domen qora ro'yxati — qo'lda kiritilgan curated IOC C2 domenlari + bulut feed
 * ([ThreatDb.domainFamily]) fallback. [MaliciousHashes] / [MaliciousPackages] bilan AYNAN
 * bir xil uslubda: avval curated ([ENTRIES]) tekshiriladi, topilmasa bulut feed'ga tushadi.
 *
 * CURATED ro'yxati case-study (Ajina.Banker / RoundRift) C2 domenlari — bu [VpnFilterService]
 * dagi `C2_DOMAINS` to'plamining oynasi (bir xil IOC'lar). Suffiks moslik: `host == it`
 * yoki `host.endsWith(".$it")` (subdomenlar ham bloklanadi).
 *
 * Hech qachon throw qilmaydi — xato bo'lsa `null` qaytaradi (link checker uni SHUBHALI
 * sifatida talqin qiladi, hech qachon "topilmadi → xavfsiz" demaydi).
 */
object MaliciousDomains {

    private const val TAG = "MaliciousDomains"

    /**
     * Case-study IOC C2 domenlari (host → oila). [VpnFilterService.C2_DOMAINS] bilan bir xil +
     * tahlilda ko'rilgan qo'shimcha domenlar (ObfuscatedSignatures'dagi token hash'lari bilan
     * mos: dashapp-v2.org, uzbekchill.com, elrxzx.com, ydbllnjd.com, ilovekkksfm.com).
     */
    private val CURATED: Map<String, String> = mapOf(
        "elrxzx.com" to "C2.caseStudy",
        "ydbllnjd.com" to "C2.caseStudy",
        "ilovekkksfm.com" to "C2.caseStudy",
        "dashapp-v2.org" to "C2.caseStudy",
        "uzbekchill.com" to "C2.caseStudy",
    )

    /**
     * Host (domen) zararli bo'lsa — oila nomi, aks holda null.
     *
     * Tartib: curated CURATED ro'yxati (suffiks moslik bilan) → topilmasa
     * [ThreatDb.domainFamily] (bulut feed). Null-safe; host kichik harf + trailing nuqtasiz
     * normallashtiriladi. Hech qachon throw qilmaydi.
     */
    fun maliciousFamily(host: String?): String? {
        return try {
            if (host.isNullOrBlank()) return null
            val h = host.lowercase().trimEnd('.')
            if (h.isEmpty()) return null
            // Curated: aniq yoki subdomen moslik (host == it || host.endsWith(".$it")).
            for ((dom, fam) in CURATED) {
                if (h == dom || h.endsWith(".$dom")) return fam
            }
            // Bulut feed (CloudBlacklist → ThreatDb): faqat aniq host moslik.
            ThreatDb.domainFamily(h)
        } catch (e: Throwable) {
            Log.w(TAG, "maliciousFamily lookup failed", e)
            null
        }
    }
}
