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
     * Ommaviy suffikslar (eTLD) va ko'p-ijarali bepul hosting zonalari — suffiks yurishida
     * shu darajada HECH QACHON moslik bermaymiz. Aks holda feed'ga xato kiritilgan bitta
     * zona-yozuv (masalan "co.uz" yoki "netlify.app") butun zonani fleet bo'ylab bloklab
     * qo'yardi (har bir *.netlify.app sayti DANGER → qattiq blok). Server tomonda ham
     * (threats.ts add_domain) rad etiladi — bu mijozdagi ikkinchi himoya qatlami.
     * [LinkScanner.MULTI_PART_SUFFIXES] bilan mos + keng tarqalgan SaaS hosting zonalari.
     */
    internal val PUBLIC_SUFFIXES: Set<String> = setOf(
        "com.uz", "co.uz", "org.uz", "net.uz", "gov.uz", "mil.uz", "ac.uz", "edu.uz",
        "co.ru", "com.ru", "co.uk", "org.uk", "gov.uk", "com.tr", "co.jp",
        "github.io", "netlify.app", "vercel.app", "web.app", "firebaseapp.com",
        "blogspot.com", "telegra.ph", "pages.dev", "workers.dev", "glitch.me",
        "herokuapp.com", "repl.co", "000webhostapp.com", "weebly.com", "wixsite.com",
    )

    /** Host shu zonaning O'ZIMI (subdomeni emas) — suffiks yurishida moslik bermaymiz. */
    internal fun isPublicSuffix(host: String): Boolean = host in PUBLIC_SUFFIXES

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
            // Bulut feed (CloudBlacklist → ThreatDb): suffiks moslik — egasi paneldan
            // "evil.com" qo'shsa, "www.evil.com" / "a.b.evil.com" ham bloklanadi.
            // Yurish VpnFilterService.isBlockedC2 bilan AYNAN bir xil: yorliqni bittadan
            // tashlab boramiz, yakka TLD ("com") hech qachon so'ralmaydi. Ommaviy suffiks
            // darajasida ([PUBLIC_SUFFIXES]) ham SO'RAMAYMIZ — bitta xato zona-yozuv butun
            // zonani bloklab qo'ymasin.
            var cur = h
            while (true) {
                if (!isPublicSuffix(cur)) {
                    val fam = ThreatDb.domainFamily(cur)
                    if (fam != null) return fam
                }
                val dot = cur.indexOf('.')
                if (dot < 0 || dot == cur.lastIndexOf('.')) break
                cur = cur.substring(dot + 1)
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "maliciousFamily lookup failed", e)
            null
        }
    }
}
