package com.uzguard

/**
 * ====== HAVOLA TEKSHIRGICH (LINK CHECKER) — eng qimmatli funksiya ======
 *
 * Telegram / SMS / QR'dan kelgan havolani OFFLINE evristika bilan baholaydi. Tarmoq YO'Q
 * (v1 — sof, JVM-test qilinadigan funksiya). Verdiktlar [ScanResult.Verdict]
 * lug'atidan: SAFE (Xavfsiz) / SUSPICIOUS (Shubhali) / DANGER (Xavfli).
 *
 * ====== ANTIVIRUS OLTIN QOIDASI ======
 * Hech qachon noto'g'ri "XAVFSIZ" bermaymiz. Bo'sh / o'qib bo'lmaydigan / tahlil qilib
 * bo'lmaydigan kirish → [Flag.UNPARSABLE] + SUSPICIOUS (hech qachon SAFE). Xatolik bo'lsa
 * ham SUSPICIOUS. SAFE faqat hech qanday belgi topilmaganda qaytariladi.
 *
 * Verdikt qoidalari:
 *   • Qora ro'yxatdagi domen / bank typosquat / punycode-homoglyph / APK-dropper /
 *     ma'lum C2-yo'l → DANGER.
 *   • Zaif belgilar ≥2 ta, YOKI moliyaviy kalit so'z + HTTPS emas → SUSPICIOUS.
 *   • Hech qanday belgi yo'q → SAFE.
 *
 * [analyze] SOF funksiya: Android API'lariga tegmaydi (java.lang.* JVM'da bor),
 * shuning uchun unit testlardan to'g'ridan-to'g'ri chaqirsa bo'ladi.
 *
 * ====== 2026-06-11 QATTIQLASHTIRISH (red-team korpusi) ======
 * 1. CONFUSABLE/LEET NORMALIZATSIYA: har bir yorliqni "fold" qilamiz (0→o, 1→l/i, 3→e,
 *    5/$→s, 4/@→a, 7→t, rn→m, vv→w). Fold AYNAN bank tokeni bo'lsa (lekin foldsiz emas)
 *    → HARD TYPOSQUAT_BANK (cl1ck→click, hum0→humo, paym3→payme, kapita1bank→kapitalbank).
 * 2. ARALASH SKRIPT (MIXED-SCRIPT): bitta yorliqda ham ASCII-lotin, ham ASCII bo'lmagan
 *    harf bo'lsa (yoki ≥2 skript) → HARD PUNYCODE_HOMOGLYPH (раyme, uzcаrd, ѕberbank).
 *    Sof bitta-skriptli IDN (киберқалқон.уз) — hujum EMAS. xn-- dekod qilinadi.
 * 3. BREND-SUBDOMENDA, CHET REGISTRABLE: bank tokeni yorliq sifatida (aynan yoki fold)
 *    chiqsa-yu registrable domeni o'sha bank emas (payme.login-secure.com) → HARD.
 * 4. APK_DELIVERY (HARD): yo'l '.apk' bilan tugaydi yoki ikki-kengaytma (.mp4.apk) —
 *    Ajina dropper signali. Play/store hostlari bundan mustasno.
 * 5. PHISHING_PATH: ma'lum C2/exfil yo'llari (/api/upload_sms, /api/inject ...) → HARD;
 *    UZ firibgar lure tokenlari (skidka, yutuq, sovga ...) → zaif.
 * 6. SOCIAL_BRANDS: telegram/instagram/... lookalike rasmiy bo'lmagan domenda → zaif.
 * 7. IP_LITERAL_HOST: o'nlik/oltilik dword IP (2130706433, 0x7f000001) ham aniqlanadi.
 * 8. AT_IN_URL endi HARD-ga yaqin: userinfo brend/host ko'rinishida bo'lsa HARD;
 *    redirect-parametrdagi (?url=/?next=) ikkinchi URL — zaif OPEN_REDIRECT.
 */
object LinkScanner {

    /** Bitta havola tahlili natijasi. */
    data class LinkResult(
        val verdict: ScanResult.Verdict,
        val url: String,
        val host: String?,
        val reasonKey: String,             // kq4_link_reason_* — UI hal qiladi
        val reasons: List<Flag>            // tartiblangan, eng yomoni birinchi
    )

    /** Har bir belgi kq4_link_flag_* satriga + ic4 ikonkasiga moslanadi (UI'da). */
    enum class Flag {
        BLACKLISTED_DOMAIN,   // qora ro'yxatdagi C2/fishing domen — DANGER
        TYPOSQUAT_BANK,       // bank brendiga o'xshash (fold/lookalike/chegara) — DANGER
        PUNYCODE_HOMOGLYPH,   // xn-- punycode / aralash-skript homoglyph hujumi — DANGER
        APK_DELIVERY,         // yo'l .apk bilan tugaydi / ikki-kengaytma dropper — DANGER
        PHISHING_PATH,        // ma'lum C2/exfil yo'li (/api/upload_sms ...) — DANGER
        IP_LITERAL_HOST,      // domen o'rniga IP manzil (o'nlik/oltilik dword ham)
        AT_IN_URL,            // '@' belgisi (haqiqiy hostni yashiradi) — brendli bo'lsa HARD
        EXCESSIVE_SUBDOMAINS, // juda ko'p subdomen (paypal.com.evil.tk)
        SUSPICIOUS_TLD,       // .tk .ml .xyz .top ... arzon/abuziv TLD
        OPEN_REDIRECT,        // ?url=/?next= ichida ikkinchi (chet) URL — yashirin yo'naltirish
        BENIGN_IDN,           // dekod qilib bo'lmagan / bitta-skriptli xn-- (zaif — DANGER emas)
        SOCIAL_IMPERSONATION, // telegram/instagram/... rasmiy bo'lmagan domenda taqlid
        SCAM_LURE,            // UZ firibgar lure so'zlari (skidka/yutuq/sovga ...)
        NON_HTTPS,            // http:// (shifrlanmagan)
        URL_SHORTENER,        // bit.ly / t.co ... haqiqiy manzil yashiringan
        FINANCIAL_KEYWORDS,   // bank/parol/karta/otp ... kalit so'zlari URL'da
        LOOKALIKE_TLD,        // bank brendi noto'g'ri TLD'da (payme.uz emas, payme.top)
        UNPARSABLE            // o'qib bo'lmadi / bo'sh — DOIM SUSPICIOUS
    }

    // ── Qattiq (DANGER) belgilar ──
    private val HARD_FLAGS = setOf(
        Flag.BLACKLISTED_DOMAIN, Flag.TYPOSQUAT_BANK, Flag.PUNYCODE_HOMOGLYPH,
        Flag.APK_DELIVERY, Flag.PHISHING_PATH
    )

    /**
     * Moliyaviy / maxfiy kalit so'zlar — [PhishingNotificationService] ro'yxatining NUSXASI
     * (uni tahrir qilmaymiz; bu yerda mustaqil saqlanadi). FAQAT host (subdomen) ichida yoki
     * yo'l/query ichida brend-korroboratsiya bilan ishlatiladi — butun URL bo'ylab yolg'iz
     * substring sifatida ENDI ogohlantirmaydi (github.com/login, paypal.com soxta-FP'ni
     * keltirib chiqarardi). Kichik harfda taqqoslanadi.
     */
    private val FINANCIAL_KEYWORDS = listOf(
        "kod", "код", "parol", "пароль", "password", "payme", "uzcard", "humo",
        "bank", "банк", "karta", "карта", "tasdiq", "confirm", "подтвердит",
        "click", "sms", "смс", "pin", "пин", "otp", "perevod", "перевод", "transfer",
        "login", "secure", "verify", "account", "wallet", "card", "pay"
    )

    /** Qora ro'yxatdagi URL qisqartirgichlar — haqiqiy manzil yashirinadi. */
    private val URL_SHORTENERS = setOf(
        "bit.ly", "t.co", "tinyurl.com", "goo.gl", "is.gd", "cutt.ly", "rb.gy",
        "ow.ly", "shorturl.at", "clck.ru"
    )

    /** Arzon / suiiste'mol qilinadigan TLD'lar (host TLD'si shu bo'lsa — zaif belgi). */
    private val SUSPICIOUS_TLDS = setOf(
        "tk", "ml", "ga", "cf", "gq", "top", "xyz", "click", "zip", "mov",
        "work", "rest", "country", "kim", "loan", "icu", "buzz", "cyou", "sbs",
        "monster", "quest", "lol", "gdn", "su"
    )

    /**
     * Banklarning HAQIQIY TLD'lari — agar host bank brendiga o'xshasa-yu, lekin shu
     * "ishonchli" TLD'larda bo'lmasa, LOOKALIKE_TLD belgisini beradi (payme.top, click.ru).
     */
    private val BANK_OK_TLDS = setOf("uz", "com", "ru")

    /**
     * Ko'p darajali ommaviy suffikslar (eTLD) — registrable domen (eTLD+1) to'g'ri
     * hisoblanishi uchun. click.com.uz / kapitalbank.co.uz HAQIQIY domenlar, ularni
     * soxta deb belgilamaslik kerak. Faqat eng keng tarqalganlari.
     */
    private val MULTI_PART_SUFFIXES = setOf(
        "com.uz", "co.uz", "org.uz", "net.uz", "gov.uz", "mil.uz", "ac.uz", "edu.uz",
        "co.ru", "com.ru", "co.uk", "org.uk", "gov.uk", "com.tr", "co.jp"
    )

    /**
     * SOFT (tahrir-masofa) yaqinlik DANGER'ga ko'tarilMAYDIGAN registrable bazalar —
     * HAQIQIY so'z/nom bank tokeniga tasodifan yaqin (osaka↔asaka, asana↔asaka,
     * mobile↔mobiuz). Bu allowlist faqat aniq ma'lum legit lookalike'larni himoyalaydi;
     * noma'lum yorliqlar uchun SOFT → HARD (antivirus oltin qoidasi: bank typosquat'ni
     * o'tkazib yuborgandan ko'ra, soxta-bankni ortiqcha belgilagan afzal).
     */
    private val SOFT_ALLOWLIST = setOf(
        "osaka", "asana", "mobile", "alaska", "osasuna"
    )

    /**
     * Mashhur ijtimoiy / messenjer brendlari (KnownBanks'ni ifloslantirmaslik uchun bu yerda).
     * Bu tokenga aynan/fold teng yorliq RASMIY domenda bo'lmasa — taqlid (zaif belgi).
     */
    private val SOCIAL_BRANDS = setOf(
        "telegram", "instagram", "youtube", "facebook", "whatsapp", "tiktok"
    )

    /** Ijtimoiy brendlarning RASMIY registrable domenlari (taqlid emas). */
    private val SOCIAL_OK_DOMAINS = setOf(
        "telegram.org", "telegram.me", "t.me", "telegram.dog",
        "instagram.com", "youtube.com", "youtu.be", "facebook.com", "fb.com",
        "whatsapp.com", "wa.me", "tiktok.com"
    )

    /** Ilova do'konlari — bu yerdagi .apk havolasi normal (APK_DELIVERY belgisi qo'yilmaydi). */
    private val STORE_HOSTS = setOf(
        "play.google.com", "f-droid.org", "apkpure.com", "apkmirror.com",
        "galaxy.store", "appgallery.huawei.com", "store.google.com"
    )

    /**
     * Ma'lum zararli C2/exfil yo'llari (case-study Ajina.Banker forensikasi) — HARD.
     * Path (kichik harf) shu satrni o'z ichiga olsa. Faqat ANIQ zararli endpointlar
     * (/admin/banks kabi ko'p ma'noli yo'llar bu yerda EMAS — ular zaif belgi qoladi).
     */
    private val MALWARE_PATHS = listOf(
        "/api/upload_sms", "/api/uploadsms", "/api/inject", "/api/gettask",
        "/api/get_task", "/api/poll", "/gate.php", "/c2/", "/panel/gate"
    )

    /**
     * Brend tokendan keyin/oldin kelganda typosquat-ni bekor qilmaydigan umumiy "to'ldiruvchi"
     * so'zlar (paymeuz, uzcardpay, clickapp). Brend + filler birikmasi → HARD.
     */
    private val BRAND_FILLERS = setOf(
        "uz", "app", "online", "pay", "secure", "login", "official",
        "support", "account", "update", "apk", "mobile", "wallet"
    )

    /** UZ firibgar "lure" so'zlari (sovrin/chegirma scam) — zaif SCAM_LURE signali. */
    private val SCAM_LURE_TOKENS = listOf(
        "skidka", "chegirma", "yutuq", "yutdingiz", "sovga", "sovg", "sovrin",
        "bonus", "mukofot", "aksiya", "tekin", "tekshir", "tasdiqla",
        "pul-otish", "pulotish", "pul_otish", "muborak", "tabrik"
    )

    /**
     * Havolani tahlil qiladi. SOF funksiya — hech qachon throw qilmaydi.
     * @param rawUrl foydalanuvchi yopishtirgan / QR'dan kelgan xom matn.
     */
    fun analyze(rawUrl: String): LinkResult {
        // Bo'sh / o'qib bo'lmaydigan → SUSPICIOUS (hech qachon SAFE).
        val raw = rawUrl.trim()
        if (raw.isEmpty()) {
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, rawUrl, null,
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }

        return try {
            analyzeInner(raw)
        } catch (e: Throwable) {
            // Tahlil xatosi — false-safe verdict bermaymiz: SUSPICIOUS.
            LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, null,
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }
    }

    private fun analyzeInner(raw: String): LinkResult {
        // Unicode "nuqta" variantlarini ASCII '.' ga normallashtiramiz — IDNA/brauzerlar aynan
        // shunday qiladi (click。evil.tk / payme．uz haqiqiy hostga aylanadi). Aks holda butun host
        // bitta yorliq bo'lib UNPARSABLE chiqardi va brend-subdomen tekshiruvi ishlamasdi.
        val work = raw.replace('。', '.').replace('．', '.')
            .replace('｡', '.').replace('․', '.')
        // Sxema bo'lmasa, http:// taxmin qilamiz (lekin NON_HTTPS belgisi qo'yiladi).
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(work)
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://").find(work)
        val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
        // https?:// dan boshqa sxema (ftp, javascript, data, file...) — o'qib bo'lmaydi.
        if (hasScheme && scheme != "http" && scheme != "https") {
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, null,
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }

        val normalized = if (hasScheme) work else "http://$work"
        val isHttps = normalized.startsWith("https://", ignoreCase = true)

        // Host'ni qo'lda ajratamiz (java.net.URI ba'zi xom kirishlarda throw qiladi).
        val afterScheme = normalized.substringAfter("://", "")
        if (afterScheme.isBlank()) {
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, null,
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }
        // '@' bo'lsa — haqiqiy host '@' dan keyin (userinfo@host). Bu yashirish hiylasi.
        val authorityRaw = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hasAt = authorityRaw.contains('@')
        val userinfo = if (hasAt) authorityRaw.substringBeforeLast('@') else ""
        val hostPort = if (hasAt) authorityRaw.substringAfterLast('@') else authorityRaw
        // IPv6 emas deb hisoblaymiz (kam uchraydi); port'ni olib tashlaymiz.
        // Host'ni %-dekod qilamiz (payme%2Euz.evil.tk hiylasini ochish uchun).
        val rawHost = percentDecode(hostPort.substringBefore(':'))
            .trim().trimEnd('.').lowercase()

        // Yo'l + query (kichik harf) — APK/phishing-path/lure/redirect tahlili uchun.
        val pathAndQuery = run {
            val afterHost = afterScheme.substringAfter('/', "")
            if (afterHost.isEmpty()) "" else "/$afterHost"
        }.lowercase()
        val lowerUrl = normalized.lowercase()

        if (rawHost.isEmpty() || (!rawHost.contains('.') && !isIpLiteral(rawHost))) {
            // Host yo'q yoki nuqtasiz (localhost-simon) → ishonchsiz.
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, rawHost.ifEmpty { null },
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }

        val flags = LinkedHashSet<Flag>()  // tartibni saqlaymiz, keyin worst-first saralaymiz

        // 1) IP-literal host (domen o'rniga raqamli manzil — o'nlik/oltilik dword ham).
        val isIp = isIpLiteral(rawHost)
        val isObfuscatedIp = isIp && isObfuscatedIpLiteral(rawHost)
        if (isIp) flags.add(Flag.IP_LITERAL_HOST)

        // 2) Punycode / homoglyph. Aralash-skript (Latin+nonASCII) yoki fold→brend → HARD;
        //    bitta-skriptli noma'lum xn-- → zaif BENIGN_IDN; sof bitta-skriptli Unicode → belgi yo'q.
        if (!isIp) when (homoglyphKind(rawHost)) {
            HomoglyphKind.HARD -> flags.add(Flag.PUNYCODE_HOMOGLYPH)
            HomoglyphKind.BENIGN_IDN -> flags.add(Flag.BENIGN_IDN)
            HomoglyphKind.NONE -> { /* sof bitta-skriptli — hujum emas */ }
        }

        // 3) Qora ro'yxat (curated C2 + bulut feed). Suffiks moslik MaliciousDomains ichida.
        val blacklisted = !isIp && MaliciousDomains.maliciousFamily(rawHost) != null
        if (blacklisted) flags.add(Flag.BLACKLISTED_DOMAIN)

        // HAQIQIY bank domeni: registrable domen (eTLD+1) bank tokeniga AYNAN teng + ishonchli
        // TLD (.uz/.com/.ru) + qora ro'yxatda/punycode'da emas. Bunda "click"/"payme" kabi brend
        // SO'ZINING o'zi noto'g'ri ogohlantirish bermasligi uchun zaif belgilarni bostiramiz.
        val isKnownGoodBank = !isIp && !blacklisted &&
            !flags.contains(Flag.PUNYCODE_HOMOGLYPH) && isLegitBankDomain(rawHost)

        // 4) Bank typosquat. IP'da bekor. Uch kuch:
        //    • HARD lookalike: yorliq (yoki fold/dehyphen) bank tokeni, lekin registrable bank.* emas
        //      (payme.evil.tk, cl1ck.uz, pa-yme.uz, g00gle-pay.uz, gov.uz chet-suffiksda).
        //    • SOFT tahrir-masofa: noma'lum yorliq → HARD; allowlist (osaka/asana/mobile) →
        //      faqat zaif signal bilan tasdiqlansa.
        //    • NONE — taqlid yo'q.
        if (!isIp && !isKnownGoodBank) {
            when (looksLikeBankKind(rawHost)) {
                BankMatch.HARD -> flags.add(Flag.TYPOSQUAT_BANK)
                BankMatch.SOFT -> {
                    val tldNow = effectiveTld(rawHost)
                    val corroborated = tldNow in SUSPICIOUS_TLDS || !isHttps ||
                        FINANCIAL_KEYWORDS.any { lowerUrl.contains(it) }
                    if (corroborated) flags.add(Flag.TYPOSQUAT_BANK)
                }
                BankMatch.NONE -> { /* taqlid yo'q */ }
            }
        }

        // 5) '@' yashirish hiylasi. Userinfo brend/host ko'rinishida bo'lsa (payme.uz@evil) —
        //    bu aniq ishonch-yashirish → TYPOSQUAT_BANK (HARD). Aks holda zaif AT_IN_URL.
        if (hasAt) {
            flags.add(Flag.AT_IN_URL)
            if (userinfoLooksDeceptive(userinfo)) flags.add(Flag.TYPOSQUAT_BANK)
        }

        // 6) Juda ko'p subdomen (paypal.com.evil.tk) — IP'da emas. Haqiqiy bank domenida bekor.
        if (!isIp && !isKnownGoodBank && labelCount(rawHost) >= 4) flags.add(Flag.EXCESSIVE_SUBDOMAINS)

        // 7) Shubhali TLD (eTLD oxirgi qismi).
        val tld = if (!isIp) effectiveTld(rawHost) else ""
        if (!isIp && tld in SUSPICIOUS_TLDS) flags.add(Flag.SUSPICIOUS_TLD)

        // 8) URL qisqartirgich (registrable domen). Haqiqiy bank domenida bekor.
        val registrable = if (!isIp) registrableDomain(rawHost) else ""
        if (!isIp && !isKnownGoodBank && registrable in URL_SHORTENERS) flags.add(Flag.URL_SHORTENER)

        // 9) Bank brendi noto'g'ri TLD'da (lookalike-tld). Faqat brend topilsa-yu typosquat yo'q bo'lsa.
        if (!isIp && !flags.contains(Flag.TYPOSQUAT_BANK) && !flags.contains(Flag.BLACKLISTED_DOMAIN)) {
            if (bankBrandInLabelsButWrongTld(rawHost, tld)) flags.add(Flag.LOOKALIKE_TLD)
        }

        // 10) APK-dropper: yo'l .apk bilan tugaydi yoki ikki-kengaytma (.mp4.apk). Store hostlari mustasno.
        if (!blacklisted && rawHost !in STORE_HOSTS && isApkDelivery(pathAndQuery)) {
            flags.add(Flag.APK_DELIVERY)
        }

        // 11) Ma'lum C2/exfil yo'li (HARD).
        if (MALWARE_PATHS.any { pathAndQuery.contains(it) }) flags.add(Flag.PHISHING_PATH)

        // 12) Ijtimoiy/messenjer brend taqlidi (rasmiy domenda emas).
        if (!isIp && socialImpersonation(rawHost)) flags.add(Flag.SOCIAL_IMPERSONATION)

        // 13) Yashirin yo'naltirish: ?url=/?next=/redirect= ichida ikkinchi (chet) URL.
        if (hasEmbeddedRedirectUrl(normalized)) flags.add(Flag.OPEN_REDIRECT)

        // 14) UZ firibgar lure so'zlari (host/path). Haqiqiy bank domenida bekor.
        if (!isKnownGoodBank && SCAM_LURE_TOKENS.any { rawHost.contains(it) || pathAndQuery.contains(it) }) {
            flags.add(Flag.SCAM_LURE)
        }

        // 15) Moliyaviy kalit so'zlar — ENDI butun-URL substring EMAS (github.com/login,
        //     account.google.com, paypal.com soxta-FP beradi edi). Faqat: yo'l/query ichida
        //     moliyaviy kalit so'z bo'lib, host'da BREND signali (typosquat/lookalike/social/
        //     punycode/blacklist) ham bor. Haqiqiy bank domenida bekor.
        if (!isKnownGoodBank && financialKeywordSignal(pathAndQuery, flags)) {
            flags.add(Flag.FINANCIAL_KEYWORDS)
        }

        // 16) HTTPS emas.
        if (!isHttps) flags.add(Flag.NON_HTTPS)

        // ── Verdiktni hisoblaymiz ──
        var verdict = decideVerdict(flags)
        // Obfuskatsiya qilingan IP (dword/hex/octal: 2130706433, 0x7f000001) — bu raqamli host
        // BILA TURIB yashirish, qasddan evaziya → DANGER (oddiy nuqtali IP esa zaif qoladi).
        if (isObfuscatedIp && verdict != ScanResult.Verdict.DANGER) {
            verdict = ScanResult.Verdict.DANGER
        }
        val ordered = orderFlags(flags)
        val reasonKey = reasonKeyFor(verdict, ordered)

        return LinkResult(verdict, raw, rawHost, reasonKey, ordered)
    }

    // ───────────────────────── Verdikt mantig'i ─────────────────────────

    /** Belgilar to'plamidan yakuniy verdikt. [analyze] uchun ajratilgan (test qilinadi). */
    internal fun decideVerdict(flags: Set<Flag>): ScanResult.Verdict {
        if (flags.isEmpty()) return ScanResult.Verdict.SAFE
        // Qattiq belgi (qora ro'yxat / typosquat / punycode / apk / phishing-path) → DANGER.
        if (flags.any { it in HARD_FLAGS }) return ScanResult.Verdict.DANGER
        // UNPARSABLE — DOIM SUSPICIOUS (yuqorida UNPARSABLE bilan qaytariladi, lekin himoya uchun).
        if (flags.contains(Flag.UNPARSABLE)) return ScanResult.Verdict.SUSPICIOUS
        // Moliyaviy kalit so'z + HTTPS emas → SUSPICIOUS.
        if (flags.contains(Flag.FINANCIAL_KEYWORDS) && flags.contains(Flag.NON_HTTPS)) {
            return ScanResult.Verdict.SUSPICIOUS
        }
        // Bitta zaif belgi qoldi-yu, u FAQAT arzon TLD bo'lsa (brend/moliyaviy signalsiz) —
        // shaxsiy .xyz/.top blog ham bo'lishi mumkin → SAFE (false-DANGER emas, oltin qoida
        // SAFE'ni faqat HARD/UNPARSABLE'da taqiqlaydi; bu yerda hech qanday HARD yo'q).
        if (flags.size == 1 && flags.contains(Flag.SUSPICIOUS_TLD)) return ScanResult.Verdict.SAFE
        // Bitta BENIGN_IDN (dekod bo'lmagan / bitta-skriptli xn--) — yolg'iz SHUBHALI.
        // Ikki yoki undan ko'p zaif belgi → SUSPICIOUS.
        if (flags.size >= 2) return ScanResult.Verdict.SUSPICIOUS
        // Bitta zaif belgi qoldi (masalan faqat http://). Antivirus oltin qoidasi: aniq
        // tozalanmagan har qanday belgi (TLD'dan tashqari) SAFE bermaydi → SUSPICIOUS.
        return ScanResult.Verdict.SUSPICIOUS
    }

    /** Belgilarni eng yomonidan eng yengiligacha tartiblaydi (UI ro'yxati uchun). */
    private fun orderFlags(flags: Set<Flag>): List<Flag> {
        val priority = listOf(
            Flag.BLACKLISTED_DOMAIN, Flag.TYPOSQUAT_BANK, Flag.PUNYCODE_HOMOGLYPH,
            Flag.APK_DELIVERY, Flag.PHISHING_PATH, Flag.LOOKALIKE_TLD,
            Flag.SOCIAL_IMPERSONATION, Flag.IP_LITERAL_HOST, Flag.AT_IN_URL,
            Flag.OPEN_REDIRECT, Flag.EXCESSIVE_SUBDOMAINS, Flag.SUSPICIOUS_TLD,
            Flag.SCAM_LURE, Flag.BENIGN_IDN, Flag.URL_SHORTENER,
            Flag.FINANCIAL_KEYWORDS, Flag.NON_HTTPS, Flag.UNPARSABLE
        )
        return priority.filter { it in flags }
    }

    /** Banner ostidagi qisqa sabab kaliti. */
    private fun reasonKeyFor(verdict: ScanResult.Verdict, ordered: List<Flag>): String {
        if (ordered.contains(Flag.UNPARSABLE)) return "kq4_link_reason_unparsable"
        return when (verdict) {
            ScanResult.Verdict.DANGER -> "kq4_link_reason_danger"
            ScanResult.Verdict.SUSPICIOUS -> "kq4_link_reason_suspicious"
            ScanResult.Verdict.SAFE -> "kq4_link_reason_safe"
        }
    }

    // ───────────────────────── Evristika yordamchilari ─────────────────────────

    /** %xx ketma-ketliklarni dekod qiladi (faqat ASCII; xato bo'lsa o'zini qaytaradi). */
    internal fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        return try {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 3; continue
                    }
                }
                sb.append(c); i++
            }
            sb.toString()
        } catch (e: Throwable) {
            s
        }
    }

    /**
     * IP-literal host: nuqtali IPv4, oltilik nuqtali (0x7f.0x0.0x0.0x1), o'nlik dword
     * (2130706433), oltilik dword (0x7f000001), yoki IPv6 (ko'p ':').
     */
    internal fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        // IPv6 (ikki nuqta ko'p) yoki bracket bilan.
        if (host.startsWith("[") || host.count { it == ':' } >= 2) return true
        val parts = host.split('.')
        // Nuqtali to'rt oktet: o'nlik 0..255 YOKI oltilik (0x..) YOKI sakkizlik.
        if (parts.size == 4 && parts.all { octetOrNull(it) != null }) return true
        // Nuqtasiz dword: o'nlik (≥ katta son) yoki oltilik (0x...).
        if (!host.contains('.')) {
            if (host.startsWith("0x") && host.length > 2 &&
                host.substring(2).all { it.isDigit() || it in 'a'..'f' }
            ) return true
            // Sof o'nlik dword (≥2^24 — IP sifatida ishlatiladigan katta son, masalan 2130706433).
            val dec = host.toLongOrNull()
            if (dec != null && dec in 16777216L..4294967295L) return true
        }
        return false
    }

    /**
     * IP obfuskatsiya qilinganmi (nuqtasiz dword 2130706433 / oltilik 0x7f000001 / oltilik
     * yoki sakkizlik oktetlar 0x7f.0x0...). Bunday yashirin shakl qasddan evaziya → DANGER.
     * Oddiy nuqtali o'nlik IPv4 (192.168.1.50) obfuskatsiya EMAS (zaif belgi qoladi).
     */
    internal fun isObfuscatedIpLiteral(host: String): Boolean {
        if (!isIpLiteral(host)) return false
        if (host.startsWith("[")) return false
        if (!host.contains('.')) return true  // dword (o'nlik yoki 0x...)
        val parts = host.split('.')
        if (parts.size == 4) {
            for (p in parts) {
                if (p.startsWith("0x")) return true
                if (p.length > 1 && p[0] == '0') return true  // sakkizlik
            }
        }
        return false
    }

    /** Bitta IPv4 oktet: o'nlik 0..255, oltilik (0x00..0xff), yoki sakkizlik. */
    private fun octetOrNull(s: String): Int? {
        if (s.isEmpty()) return null
        return when {
            s.startsWith("0x") -> s.substring(2).toIntOrNull(16)?.takeIf { it in 0..255 }
            s.length > 1 && s[0] == '0' -> s.toIntOrNull(8)?.takeIf { it in 0..255 }
            else -> s.toIntOrNull()?.takeIf { it in 0..255 }
        }
    }

    /** Homoglyph baholash kuchi. */
    internal enum class HomoglyphKind { HARD, BENIGN_IDN, NONE }

    /**
     * Punycode / homoglyph hujumini KUCH bilan baholaydi:
     *   • HARD — aralash-skript yorliq (bir yorliqda ASCII-lotin + ASCII bo'lmagan harf,
     *     yoki ≥2 alohida skript: раyme, uzcаrd, ѕberbank, клиcк), YOKI dekod/normallashtirilgan
     *     yorliq bank tokeniga teng/yaqin (ｐａｙｍｅ → payme).
     *   • BENIGN_IDN — xn-- yoki ASCII bo'lmagan harf bor, lekin bitta skriptli va brendga
     *     o'xshamaydi (xn--80ak6aa92e.com). Dekod qilib bo'lmasa ham — false-safe bermaslik
     *     uchun kamida BENIGN_IDN (yolg'iz → SHUBHALI).
     *   • NONE — sof ASCII (bu yo'lda emas) yoki sof bitta-skriptli Unicode bo'lib registrable
     *     TLD ham o'sha skriptda (киберқалқон.уз — haqiqiy kirill domeni) → belgi yo'q.
     */
    internal fun homoglyphKind(host: String): HomoglyphKind {
        var worst = HomoglyphKind.NONE
        for (rawLabel in host.split('.')) {
            if (rawLabel.isEmpty()) continue
            val isPuny = rawLabel.startsWith("xn--")
            val decoded = if (isPuny) decodePunycode(rawLabel.substring(4)) else null
            if (isPuny && decoded == null) {
                // xn-- dekod bo'lmadi — false-safe bermaymiz (kamida BENIGN_IDN).
                if (worst == HomoglyphKind.NONE) worst = HomoglyphKind.BENIGN_IDN
                continue
            }
            val label = decoded ?: rawLabel
            if (label.none { it.code > 127 }) continue  // sof ASCII yorliq — bu yerda emas

            // Aralash-skript? (ASCII-lotin harf + ASCII bo'lmagan harf, yoki ≥2 skript)
            if (isMixedScript(label)) return HomoglyphKind.HARD

            // Bitta-skriptli non-ASCII — brendga o'xshaydimi? (fold → bank tokeni / edit-1)
            if (foldMatchesBank(confusableFold(label))) return HomoglyphKind.HARD

            // Ekzotik ASCII bo'lmagan belgi (Number-Letter U+217D 'ⅽ', belgi/symbol) brendni
            // "to'ldirib" edit-byudjetni portlatish uchun ishlatiladi (ⅽliⅽk → 2 ta belgi, fold
            // edit-2). HAQIQIY IDN yorliqlari HARFLARDAN iborat (Lo/Ll), shuning uchun harf
            // BO'LMAGAN ASCII bo'lmagan belgi bo'lsa-yu, fold bank tokeniga masofa ≤2 bo'lsa —
            // qasddan taqlid → HARD. (киберқалқон.уз — sof harfli, bu yerga tushmaydi.)
            if (label.any { it.code > 127 && !it.isLetter() } &&
                foldWithinBankDistance(confusableFold(label), 2)
            ) {
                return HomoglyphKind.HARD
            }

            // Xom (xn--siz) bitta-skriptli Unicode (киберқалқон.уз) — HAQIQIY IDN, hujum EMAS.
            // Faqat xn-- punycode noma'lum bo'lsa — shubhali kodlash → BENIGN_IDN (yolg'iz SHUBHALI).
            if (isPuny && worst == HomoglyphKind.NONE) worst = HomoglyphKind.BENIGN_IDN
        }
        return worst
    }

    /** Yorliqda HAM ASCII-lotin harf, HAM ASCII bo'lmagan harf bormi (aralash-skript hujumi). */
    private fun isMixedScript(label: String): Boolean {
        var asciiLatin = false
        var nonAscii = false
        for (ch in label) {
            if (!ch.isLetter()) continue
            if (ch.code < 128) asciiLatin = true else nonAscii = true
            if (asciiLatin && nonAscii) return true
        }
        return false
    }

    /**
     * Confusable + leet "fold": ASCII bo'lmagan kirill/yunon homoglyflarni lotinga, fullwidth/
     * aksentli harflarni soddaga, raqam/symbol leetni harfga moslaydi. Maqsad: ｐａｙｍｅ/раyme/
     * cl1ck → bank tokeniga teng bo'lishi.
     */
    internal fun confusableFold(label: String): String {
        // Avval multigraf (rn→m, vv→w), keyin har bir belgi.
        val pre = label.lowercase().replace("rn", "m").replace("vv", "w")
        val sb = StringBuilder(pre.length)
        for (ch in pre) {
            val code = ch.code
            // Ko'rinmas/format/birikuvchi belgilarni TASHLAB yuboramiz (zero-width ZWSP/ZWNJ/ZWJ,
            // BOM, soft-hyphen, birikuvchi diakritiklar). Ular hostda qonuniy emas — faqat fold
            // masofasini sun'iy oshirib brend-taqlidini yashirish uchun qo'yiladi (pa<ZWSP><ZWSP>yme).
            if (code == 0x200B || code == 0x200C || code == 0x200D || code == 0xFEFF || code == 0x00AD) continue
            val type = Character.getType(code)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.FORMAT.toInt()) continue
            val mapped = CONFUSABLE_MAP[ch]
            if (mapped != null) {
                sb.append(mapped); continue
            }
            if (code < 128) {
                sb.append(ch); continue
            }
            // ASCII bo'lmagan, xaritada yo'q — fullwidth/aksent bo'lsa ASCII'ga keltiramiz.
            val ascii = foldNonAsciiToAscii(ch)
            sb.append(ascii ?: ch)
        }
        return sb.toString()
    }

    /** Fullwidth (U+FF01..FF5E) → ASCII; mumkin bo'lmasa null. */
    private fun foldNonAsciiToAscii(ch: Char): Char? {
        val code = ch.code
        // Fullwidth ASCII variantlari.
        if (code in 0xFF01..0xFF5E) return (code - 0xFEE0).toChar()
        return null
    }

    /** Leet + kirill/yunon homoglyph xaritasi (kichik harf). */
    private val CONFUSABLE_MAP: Map<Char, Char> = buildMap {
        // Leet raqam/symbol → harf.
        put('0', 'o'); put('1', 'i'); put('3', 'e'); put('5', 's'); put('$', 's')
        put('4', 'a'); put('@', 'a'); put('7', 't'); put('8', 'b'); put('9', 'g')
        // 'l' ni 'i' ga normallashtiramiz (1/l/i bir-biriga o'xshaydi) — token ham fold bo'ladi.
        put('l', 'i')
        // Kirill homoglyflar → lotin.
        put('а', 'a'); put('о', 'o'); put('р', 'p'); put('с', 'c'); put('е', 'e')
        put('х', 'x'); put('у', 'y'); put('к', 'k'); put('м', 'm'); put('н', 'h')
        put('в', 'b'); put('т', 't'); put('ѕ', 's'); put('і', 'i'); put('ј', 'j')
        put('ԛ', 'q'); put('ӏ', 'i'); put('ё', 'e'); put('қ', 'q'); put('ҳ', 'h')
        // Yunon homoglyflar → lotin.
        put('ο', 'o'); put('α', 'a'); put('ρ', 'p'); put('υ', 'u'); put('ι', 'i')
        put('ν', 'v'); put('ε', 'e'); put('τ', 't'); put('κ', 'k')
    }

    /** Fold qilingan satr biror bank tokeniga (fold qilingan) AYNAN teng yoki edit-1 yaqinmi. */
    private fun foldMatchesBank(folded: String): Boolean {
        if (folded.length < 4) return false
        for (token in KnownBanks.brandTokens()) {
            if (token.length < 4) continue
            val ft = confusableFold(token)
            if (folded == ft) return true
            // Bitta-skriptli IDN edit-1 (kichik ehtiyot — faqat 5+ tokenda).
            if (token.length >= 5 && levenshtein(folded, ft) == 1) return true
        }
        return false
    }

    /**
     * Fold qilingan satr biror bank tokeniga (≥5 harfli) Levenshtein masofasi `max` ichidami.
     * FAQAT ekzotik ASCII bo'lmagan belgili yorliqlar uchun ishlatiladi (yuqoridagi gate bilan) —
     * shu sabab kengroq (≤2) byudjet yolg'on-musbat bermaydi: qonuniy host bunday belgi saqlamaydi.
     */
    private fun foldWithinBankDistance(folded: String, max: Int): Boolean {
        if (folded.length < 4) return false
        for (token in KnownBanks.brandTokens()) {
            if (token.length < 5) continue
            if (levenshtein(folded, confusableFold(token)) <= max) return true
        }
        return false
    }

    /**
     * Punycode (RFC 3492) dekoderi — sof Kotlin (java.net'siz). Faqat ASCII bo'lmagan kod
     * nuqtalarni qaytaradi. Xato/cheksiz kirishda null (chaqiruvchi BENIGN_IDN deb oladi).
     */
    internal fun decodePunycode(input: String): String? {
        return try {
            val base = 36; val tmin = 1; val tmax = 26; val skew = 38; val damp = 700
            val initialBias = 72; val initialN = 128
            val output = ArrayList<Int>()
            var idx = input.lastIndexOf('-')
            if (idx > 0) {
                for (i in 0 until idx) {
                    val c = input[i]
                    if (c.code >= 128) return null
                    output.add(c.code)
                }
            } else idx = -1
            var i = 0; var n = initialN; var bias = initialBias
            var inPos = idx + 1
            while (inPos < input.length) {
                val oldi = i; var w = 1; var k = base
                while (true) {
                    if (inPos >= input.length) return null
                    val c = input[inPos++]
                    val digit = when (c) {
                        in 'a'..'z' -> c - 'a'
                        in 'A'..'Z' -> c - 'A'
                        in '0'..'9' -> c - '0' + 26
                        else -> return null
                    }
                    if (digit > (Int.MAX_VALUE - i) / w) return null
                    i += digit * w
                    val t = when {
                        k <= bias -> tmin
                        k >= bias + tmax -> tmax
                        else -> k - bias
                    }
                    if (digit < t) break
                    if (w > Int.MAX_VALUE / (base - t)) return null
                    w *= (base - t)
                    k += base
                }
                val outLen = output.size + 1
                bias = adaptBias(i - oldi, outLen, oldi == 0, damp, base, tmin, tmax, skew)
                if (i / outLen > Int.MAX_VALUE - n) return null
                n += i / outLen
                i %= outLen
                if (n < 128) return null  // ASCII kod nuqta — noto'g'ri punycode
                output.add(i, n)
                i++
                if (output.size > 128) return null  // himoya: juda uzun
            }
            val sb = StringBuilder()
            for (cp in output) sb.appendCodePoint(cp)
            sb.toString()
        } catch (e: Throwable) {
            null
        }
    }

    private fun adaptBias(
        delta0: Int, numPoints: Int, firstTime: Boolean,
        damp: Int, base: Int, tmin: Int, tmax: Int, skew: Int
    ): Int {
        var delta = if (firstTime) delta0 / damp else delta0 / 2
        delta += delta / numPoints
        var k = 0
        while (delta > (base - tmin) * tmax / 2) {
            delta /= (base - tmin); k += base
        }
        return k + (base - tmin + 1) * delta / (delta + skew)
    }

    /** Domendagi yorliqlar (nuqta bilan ajratilgan qismlar) soni. */
    private fun labelCount(host: String): Int = host.split('.').count { it.isNotEmpty() }

    /**
     * Registrable domen (eTLD+1). Ko'p darajali suffikslarni (com.uz, co.uz, ...) hisobga oladi:
     * click.com.uz → "click.com.uz" (regBase=click), my.click.uz → "click.uz" (regBase=click).
     */
    internal fun registrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return host
        // Oxirgi ikki yorliq ko'p-darajali suffiks bo'lsa (com.uz) — eTLD+1 = oxirgi uchtasi.
        val lastTwo = parts.takeLast(2).joinToString(".")
        if (lastTwo in MULTI_PART_SUFFIXES && parts.size >= 3) {
            return parts.takeLast(3).joinToString(".")
        }
        return lastTwo
    }

    /** Registrable bazasi (eTLD+1 ning eng chap yorlig'i): click.com.uz → "click". */
    private fun registrableBase(host: String): String {
        val reg = registrableDomain(host)
        return reg.split('.').firstOrNull { it.isNotEmpty() } ?: reg
    }

    /** Samarali (eng yuqori darajali) TLD: click.com.uz → "uz", payme.top → "top". */
    internal fun effectiveTld(host: String): String =
        host.substringAfterLast('.', host)

    /** Bank-taqlid moslik kuchi: HARD (aniq qasddan) / SOFT (faqat tahrir-masofa) / NONE. */
    internal enum class BankMatch { HARD, SOFT, NONE }

    /**
     * Host bank brendiga o'xshashligini KUCH bilan baholaydi:
     *   • HARD — aniq qasddan taqlid: (a) yorliq bank tokeniga AYNAN yoki FOLD (cl1ck→click)
     *     teng, lekin registrable bank.* emas (payme.evil.tk, cl1ck.uz); (b) yorliq tokendan
     *     boshlanadi/tugaydi-yu chegara HARF EMAS (payme24, payme-uz); (c) defis/pastki chiziq
     *     olib tashlangan birikma (pa-yme→payme, g00gle-pay→googlepay) bank tokeni; (d) ishonchli
     *     "gov.uz" marker chet-suffiksli domen subdomenida (soliq.gov.uz.evil.tk).
     *   • SOFT — faqat tahrir-masofa yaqinligi. Noma'lum yorliq → chaqiruvchi HARD'ga ko'taradi;
     *     SOFT_ALLOWLIST (osaka/asana/mobile) → faqat tasdiq bilan.
     *   • NONE — taqlid yo'q.
     *
     * O'ZIMIZNING brendimiz ("anor") bank tokeni "anorbank" bilan to'qnashmasligi uchun BUTUN
     * token taqqoslanadi (substring "anor" emas).
     */
    internal fun looksLikeBankKind(host: String): BankMatch {
        val tokens = KnownBanks.brandTokens()
        val regBase = registrableBase(host)
        // Haqiqiy registrable bazasi bank tokeni bo'lsa (click.uz, click.com.uz) — typosquat EMAS.
        if (regBase in tokens) return BankMatch.NONE

        val dotSegs = host.split('.').filter { it.isNotEmpty() }
        // "gov.uz" ishonchli marker subdomenda, lekin samarali TLD uz emas → HARD impersonatsiya.
        if (effectiveTld(host) != "uz") {
            for (k in 0 until dotSegs.size - 1) {
                if (dotSegs[k] == "gov" && dotSegs[k + 1] == "uz") return BankMatch.HARD
            }
        }

        // Har bir nuqta-segment uchun: o'zi + defis/pastki-chiziq olib tashlangan birikma.
        val candidates = LinkedHashSet<String>()
        for (seg in dotSegs) {
            candidates.add(seg)
            val deh = seg.replace("-", "").replace("_", "")
            if (deh != seg) candidates.add(deh)
        }
        // Defis-bo'lingan yorliqlar ham (payme, uz <- payme-uz).
        for (sub in host.split('.', '-', '_')) {
            if (sub.isNotEmpty()) candidates.add(sub)
        }

        var soft = false
        for (label in candidates) {
            if (label.length < 4) continue
            val folded = confusableFold(label)
            for (token in tokens) {
                if (token.length < 4) continue
                val ft = confusableFold(token)
                // (a) AYNAN yoki FOLD teng, registrable bank.* emas → HARD.
                if (label == token || folded == ft) {
                    if (regBase != token) return BankMatch.HARD
                    continue
                }
                // (b) Chegara-lookalike (HARD): tokendan boshlanadi/tugaydi-yu qo'shni HARF EMAS.
                if (label.length > token.length) {
                    if (label.startsWith(token) && !label[token.length].isLetter()) return BankMatch.HARD
                    if (label.endsWith(token) && !label[label.length - token.length - 1].isLetter()) return BankMatch.HARD
                    // Token + umumiy to'ldiruvchi so'z birikmasi (paymeuz, uzcardpay, clickapp).
                    if (label.startsWith(token) && label.substring(token.length) in BRAND_FILLERS) return BankMatch.HARD
                    if (label.endsWith(token) && label.substring(0, label.length - token.length) in BRAND_FILLERS) return BankMatch.HARD
                }
                // Tahrir-masofa yaqinligi (SOFT) — fold ustida, byudjet token uzunligiga proporsional.
                val budget = if (token.length >= 8) 2 else 1
                val d = levenshtein(folded, ft)
                if (d in 1..budget) soft = true
            }
        }
        if (soft) {
            // Noma'lum yorliq SOFT → HARD (klick, uzcand, paye, clik). Faqat ma'lum legit
            // so'z (osaka/asana/mobile) registrable bazasi bo'lsa — SOFT (tasdiq talab).
            return if (regBase in SOFT_ALLOWLIST) BankMatch.SOFT else BankMatch.HARD
        }
        return BankMatch.NONE
    }

    /**
     * Host HAQIQIY bank domenimi: registrable bazasi bank brend tokeniga AYNAN teng VA
     * samarali TLD ishonchli (.uz/.com/.ru) — click.uz, click.com.uz, kapitalbank.co.uz,
     * my.click.uz. Soxta TLD (click.top) o'tmaydi.
     */
    internal fun isLegitBankDomain(host: String): Boolean {
        val base = registrableBase(host)
        val tld = effectiveTld(host)
        return base in KnownBanks.brandTokens() && tld in BANK_OK_TLDS
    }

    /**
     * Host yorliqlaridan biri bank brendiga aynan teng, lekin samarali TLD ishonchsiz
     * (.uz/.com/.ru emas) → lookalike-tld (payme.top, click.xyz).
     */
    private fun bankBrandInLabelsButWrongTld(host: String, tld: String): Boolean {
        val regBase = registrableBase(host)
        return regBase in KnownBanks.brandTokens() && tld !in BANK_OK_TLDS
    }

    /**
     * Userinfo (foydalanuvchi qismi) BANK/SOCIAL brendini o'z ichiga oladimi (payme.uz@evil).
     * Bu aniq ishonch-yashirish hiylasi → HARD. Oddiy "google.com@..." (brend emas) — faqat
     * zaif AT_IN_URL bo'lib qoladi (false-DANGER bermaslik uchun).
     */
    private fun userinfoLooksDeceptive(userinfo: String): Boolean {
        if (userinfo.isEmpty()) return false
        val u = percentDecode(userinfo).lowercase()
        val tokens = KnownBanks.brandTokens() + SOCIAL_BRANDS
        val foldedTokens = tokens.map { confusableFold(it) }.toSet()
        val labels = u.split('.', '-', '_', '/').filter { it.isNotEmpty() }
        return labels.any { it in tokens || confusableFold(it) in foldedTokens }
    }

    /** Yo'l/query .apk dropper'ni ko'rsatadimi (oxiri .apk yoki ikki-kengaytma .x.apk). */
    internal fun isApkDelivery(pathAndQuery: String): Boolean {
        if (pathAndQuery.isEmpty()) return false
        // Faqat yo'l qismi (query'dan oldin) — fayl nomi.
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        // .apk bilan tugaydimi (har qanday joyda — query'da ham bo'lishi mumkin).
        if (path.endsWith(".apk")) return true
        // Ikki-kengaytma yoki query ichida .apk (download?file=x.apk).
        if (Regex("\\.[a-z0-9]{1,5}\\.apk(\\b|$)").containsMatchIn(path)) return true
        if (pathAndQuery.contains(".apk")) return true
        return false
    }

    /** Ijtimoiy brend taqlidi: host fold ma'lum social brendga teng/yaqin, lekin rasmiy domen emas. */
    private fun socialImpersonation(host: String): Boolean {
        val reg = registrableDomain(host)
        if (reg in SOCIAL_OK_DOMAINS || host in SOCIAL_OK_DOMAINS) return false
        val labels = host.split('.', '-', '_').filter { it.isNotEmpty() }
        for (label in labels) {
            if (label.length < 4) continue
            val f = confusableFold(label)
            for (brand in SOCIAL_BRANDS) {
                val fb = confusableFold(brand)
                if (f == fb) return true
                if (brand.length >= 6 && levenshtein(f, fb) == 1) return true
                // boshlanish/birikma (telegram-premium, t-me): "telegram" / "t.me" lookalike.
            }
        }
        // "t-me" / "t_me" t.me taqlidi.
        if (Regex("(^|\\.)t[-_]me(\\.|$)").containsMatchIn(host) && reg != "t.me") return true
        // telegram-* / telegramm host (lekin telegram.org rasmiy emas).
        if (host.contains("telegram") && reg !in SOCIAL_OK_DOMAINS) return true
        return false
    }

    /** ?url=/?next=/redirect=/return=/u=/q= ichida ikkinchi (boshqa hostli) URL bormi. */
    internal fun hasEmbeddedRedirectUrl(url: String): Boolean {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return false
        val lower = q.lowercase()
        // http(s):// yoki // bilan boshlangan ichki URL parametri.
        val m = Regex("(?:[?&](?:url|next|redirect|return|u|q|dest|target|goto|continue)=)([^&#]+)", RegexOption.IGNORE_CASE)
            .findAll("?$q")
        for (match in m) {
            val v = percentDecode(match.groupValues[1])
            val lv = v.lowercase()
            if (lv.startsWith("http://") || lv.startsWith("https://") || lv.startsWith("//")) return true
        }
        // Umumiy: query ichida ikkinchi http(s):// (boshqa host).
        val idx = lower.indexOf("http://").let { if (it >= 0) it else lower.indexOf("https://") }
        if (idx >= 0) return true
        return false
    }

    /**
     * Moliyaviy kalit so'z signali — substring-spam EMAS. Faqat host'da BREND signali
     * (typosquat / lookalike-tld / social / punycode / blacklist) BO'LSA VA yo'l/query'da
     * moliyaviy kalit so'z bo'lsa. Shunday qilib github.com/login, account.google.com,
     * paypal.com (brend signalisiz) ENDI ogohlantirMAYDI.
     */
    private fun financialKeywordSignal(pathAndQuery: String, flags: Set<Flag>): Boolean {
        val brandSignal = flags.any {
            it == Flag.TYPOSQUAT_BANK || it == Flag.LOOKALIKE_TLD ||
                it == Flag.SOCIAL_IMPERSONATION || it == Flag.PUNYCODE_HOMOGLYPH ||
                it == Flag.BLACKLISTED_DOMAIN
        }
        return brandSignal && FINANCIAL_KEYWORDS.any { pathAndQuery.contains(it) }
    }

    /** Levenshtein masofasi (ikki satr orasidagi minimal tahrir soni). */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
