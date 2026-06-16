package com.uzguard

/**
 * O'zbekiston bank / fintech / to'lov ilovalarining yagona ro'yxati.
 *
 * Bu ro'yxat uchta mavjud manbadan birlashtirilgan (union):
 *   - [AppReputation.TRUSTED_EXACT]        — imzo bilan tasdiqlanadigan ishonchli paketlar.
 *   - [IconImpersonationDetector] PROTECTED_PACKAGES — ikonka taqlidi bazasi (Click/Payme/...).
 *   - [FilenameHeuristic] KNOWN_BRANDS      — fayl nomi typosquat brendlari.
 *
 * Iste'molchilar (B1 [LinkScanner] typosquat tekshiruvi, B4 [BankAppAudit] soxta-bank skani)
 * shu yagona manbadan foydalanadi — har joyda alohida ro'yxat yuritmaslik uchun.
 *
 * Maydonlar:
 *   - [Bank.pkg]   — haqiqiy (kanonik) paket nomi (Play Store'dagi).
 *   - [Bank.brand] — typosquat masofasi uchun BITTA kichik harfli token, probelsiz
 *                    (masalan "payme", "kapitalbank"). Bir brendga bir token.
 *   - [Bank.label] — odam o'qiy oladigan ko'rinish ("Payme", "Kapitalbank").
 *
 * DIQQAT — brend to'qnashuvi: bizning OWN ilovamiz "UzGuard" deb ataladi va uning
 * brend tokeni "anorbank" bilan (Anorbank fintech ilovasi) yaqin. Lekin bu yerda Anorbank
 * uchun token "anorbank" bo'lib qoladi (substring "anor" emas!). Iste'molchilar O'ZIMIZNING
 * paketni (context.packageName + ".debug") ALBATTA chiqarib tashlashi va BUTUN token bilan
 * solishtirishi shart (substring "anor" bilan emas) — aks holda o'zimiznikini soxta deb belgilaymiz.
 */
object KnownBanks {

    /** Bitta bank/fintech/to'lov ilovasi. */
    data class Bank(
        val pkg: String,
        val brand: String,
        val label: String,
    )

    /**
     * Birlashtirilgan ro'yxat. Har bir brend bitta haqiqiy paketga bog'langan.
     * (To'lov/fintech hamyonlari ham kiritilgan: Payme, Click, Uzcard, Apelsin, Oson, Paynet, Google Pay.)
     */
    val ALL: List<Bank> = listOf(
        // --- To'lov / hamyon (fintech) ---
        Bank("uz.dida.payme", "payme", "Payme"),
        Bank("uz.click.evo", "click", "Click"),
        Bank("uz.uzcard.uzcard", "uzcard", "Uzcard"),
        Bank("com.oson.app", "oson", "OSON"),
        Bank("com.paynet.android", "paynet", "Paynet"),
        Bank("uz.ums.tenge", "apelsin", "Apelsin"),                 // Apelsin (TBC/UMS hamyoni)
        Bank("com.google.android.apps.nbu.paisa.user", "googlepay", "Google Pay"),

        // --- Banklar ---
        Bank("uz.kapitalbank.android", "kapitalbank", "Kapitalbank"),
        Bank("uz.uzum.bank", "uzumbank", "Uzum Bank"),
        Bank("uz.tbcbank.mobile", "tbcbank", "TBC Bank"),
        Bank("uz.hamkorbank.mobile", "hamkorbank", "Hamkorbank"),
        Bank("uz.agrobank.mobile", "agrobank", "Agrobank"),
        Bank("com.ipakyulibank.mobile", "ipakyulibank", "Ipak Yo'li Bank"),  // haqiqiy paket com.* (AppReputation tuzatgan); brend tokeni paketga mos
        Bank("uz.infinbank.mobile", "infinbank", "InfinBank"),
        Bank("uz.davrbank.mobile", "davrbank", "Davrbank"),
        Bank("uz.anorbank.mobile", "anorbank", "Anorbank"),        // DIQQAT: brend "anorbank" — bizning "UzGuard" emas!
        Bank("uz.dida.smartbank", "smartbank", "Smart Bank"),
        Bank("uz.asaka.mobile", "asaka", "Asaka Bank"),
        Bank("uz.qishloqqurilishbank.mobile", "qishloqqurilishbank", "Qishloq Qurilish Bank"),
        Bank("uz.humo.mobile", "humo", "Humo"),

        // --- Telekom hamyonlari (bank/to'lov funksiyasi bor) ---
        Bank("uz.mobiuz.android", "mobiuz", "Mobiuz"),
        Bank("uz.beeline.odp", "beeline", "Beeline"),
        Bank("uz.ums.mobile", "ums", "UMS"),
    )

    private val BY_PACKAGE: Map<String, Bank> = ALL.associateBy { it.pkg.lowercase() }

    private val BRAND_TOKENS: Set<String> = ALL.map { it.brand }.toSet()

    /** Aniq paket nomi bo'yicha bank yozuvi (null-safe, kichik harf), aks holda null. */
    fun byPackage(pkg: String?): Bank? {
        if (pkg.isNullOrBlank()) return null
        return BY_PACKAGE[pkg.lowercase()]
    }

    /** Barcha brend tokenlari — typosquat (Levenshtein) masofasini tekshirish uchun. */
    fun brandTokens(): Set<String> = BRAND_TOKENS
}
