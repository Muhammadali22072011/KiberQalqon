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
     * Birlashtirilgan ro'yxat. Bir brendga BIR YOKI BIR NECHTA paket bo'lishi mumkin
     * (asosiy + biznes/ikkilamchi ilova, yoki eski + joriy paket). Brend tokeni takrorlansa
     * [BRAND_TOKENS] Set dedublaydi; [BY_PACKAGE] har bir paketni alohida saqlaydi.
     * Paketlar 2026-07'da Play Store'da tekshirilgan (pastdagi ikkinchi blok).
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

        // ============================================================================
        // 2026-07: Play Store'da TEKSHIRILGAN haqiqiy paketlar + yangi banklar.
        // Yuqoridagi ba'zi eski paketlar Play'da 404 (taxminan yozilgan edi) — ular
        // ESKI o'rnatmalar uchun saqlanadi (VPN istisnosi/ishonch uchun zararsiz), lekin
        // JORIY haqiqiy paketlar shu yerda. Bir brendga bir nechta paket bo'lishi mumkin
        // (asosiy + biznes/ikkilamchi) — brend tokeni takrorlansa Set dedublaydi.
        // ============================================================================

        // --- To'lov / hamyon (joriy haqiqiy paketlar) ---
        Bank("air.com.ssdsoftwaresolutions.clickuz", "click", "Click"),   // uz.click.evo → 404
        Bank("uz.uzcardpay.android", "uzcard", "Uzcard Pay"),             // uz.uzcard.uzcard → 404
        Bank("mobile.uzcard.uz.uzcard", "uzcard", "Uzcard Plum"),         // 2-rasmiy Uzcard ilovasi
        Bank("com.oson", "oson", "OSON"),                                 // com.oson.app → noto'g'ri
        Bank("uz.paynet.app", "paynet", "Paynet"),                        // com.paynet.android → 404
        Bank("com.olsoft.mats.prod", "beepul", "Beepul"),                 // Beeline (UNITEL) to'lov ilovasi
        Bank("uz.octagram.paylov", "paylov", "Paylov"),
        Bank("uz.owl.multicard", "multicard", "Multicard / Rahmat"),

        // --- Banklar (joriy haqiqiy paketlar + yangi) ---
        Bank("uz.kapitalbank.kbonline", "kapitalbank", "Kapitalbank (retail)"), // uz.kapitalbank.android endi Uzum
        Bank("ge.space.app.uzbekistan", "tbcbank", "TBC Bank UZ"),        // uz.tbcbank.mobile → 404
        Bank("com.hamkorbank.mobile", "hamkorbank", "Hamkorbank"),        // uz.hamkorbank.mobile = "OLD"
        Bank("uz.agrobank.mobile.mbank", "agrobank", "Agrobank (mbank)"), // 2-rasmiy Agrobank ilovasi
        Bank("uz.xsoft.myinfin", "infinbank", "InfinBank"),              // uz.infinbank.mobile → 404
        Bank("uz.anormobile.retail", "anorbank", "Anorbank"),            // uz.anorbank.mobile → 404
        Bank("uz.smartbank", "smartbank", "Openbank UZ (ex-Smart Bank)"),// uz.dida.smartbank → noto'g'ri
        Bank("uz.asakabank.myasaka", "asaka", "Asakabank"),             // uz.asaka.mobile → yo'q
        Bank("com.qqb.quant", "brb", "BRB (ex-Qishloq Qurilish)"),       // QQB → BRB rebrand
        Bank("uz.mobiuz.mobiservice", "mobiuz", "Mobiuz"),               // uz.mobiuz.android → 404
        Bank("com.tune.milliy", "milliy", "NBU Milliy"),                 // Milliy Bank (NBU)
        Bank("uz.tune.xazna", "xazna", "Xalq banki (Xazna)"),
        Bank("uz.aloqabank.zoomrad", "zoomrad", "Aloqabank (Zoomrad)"),
        Bank("com.colvir.turon.mobile", "turonbank", "Turonbank (MyTuron)"),
        Bank("com.bss.ipotekabank.retail.lite", "ipotekabank", "Ipoteka Bank"),
        Bank("uz.fido_biznes.mobile.client.mkb_newrelease", "mkbank", "Mikrokreditbank"),
        Bank("trastpay.uz", "trastpay", "Trustbank (Trastpay)"),
        Bank("uz.tune.juicer", "alliancepay", "Asia Alliance (Alliance Pay)"),
        Bank("uz.ofbmobile.android", "ofb", "Orient Finans (OFB)"),
        Bank("com.ofss.ziraat", "ziraat", "Ziraat Bank Uzbekistan"),
        Bank("uz.tune.tenge", "tengebank", "Tenge Bank (Tenge24)"),
        Bank("com.ravnaqbank.rbkmobile", "octobank", "Octobank"),        // paket ex-Ravnaqbank
        Bank("uz.fido.universaldigital", "universalbank", "Universal Bank"),
        Bank("fido.poytaxtmobile", "poytaxtbank", "Poytaxt Bank"),
        Bank("com.uzpsb.olam", "sqb", "SQB (Joyda)"),                    // Sanoat-Qurilish Bank

        // --- Islomiy fintech / BNPL / to'lov ---
        Bank("tj.alif.mobi", "alif", "Alif"),                            // TJ+UZ bitta ilova
        Bank("com.zoodel.kz", "zood", "Zood / ZoodPay"),                 // mintaqaviy, UZ Play'da bor
        Bank("udevs.iman_invest", "imaninvest", "Iman Invest"),
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
