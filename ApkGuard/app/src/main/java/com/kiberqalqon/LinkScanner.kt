package com.kiberqalqon

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
 *   • Qora ro'yxatdagi domen / bank typosquat / punycode-homoglyph → DANGER.
 *   • Zaif belgilar ≥2 ta, YOKI moliyaviy kalit so'z + HTTPS emas → SUSPICIOUS.
 *   • Hech qanday belgi yo'q → SAFE.
 *
 * [analyze] SOF funksiya: Android API'lariga tegmaydi (java.net.* / java.util.* JVM'da bor),
 * shuning uchun unit testlardan to'g'ridan-to'g'ri chaqirsa bo'ladi.
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
        TYPOSQUAT_BANK,       // bank brendiga o'xshash (Levenshtein ≤2 / lookalike) — DANGER
        PUNYCODE_HOMOGLYPH,   // xn-- punycode / homoglyph hujum — DANGER
        IP_LITERAL_HOST,      // domen o'rniga IP manzil
        AT_IN_URL,            // '@' belgisi (haqiqiy hostni yashiradi)
        EXCESSIVE_SUBDOMAINS, // juda ko'p subdomen (paypal.com.evil.tk)
        SUSPICIOUS_TLD,       // .tk .ml .xyz .top ... arzon/abuziv TLD
        NON_HTTPS,            // http:// (shifrlanmagan)
        URL_SHORTENER,        // bit.ly / t.co ... haqiqiy manzil yashiringan
        FINANCIAL_KEYWORDS,   // bank/parol/karta/otp ... kalit so'zlari URL'da
        LOOKALIKE_TLD,        // bank brendi noto'g'ri TLD'da (payme.uz emas, payme.top)
        UNPARSABLE            // o'qib bo'lmadi / bo'sh — DOIM SUSPICIOUS
    }

    // ── Qattiq (DANGER) belgilar ──
    private val HARD_FLAGS = setOf(
        Flag.BLACKLISTED_DOMAIN, Flag.TYPOSQUAT_BANK, Flag.PUNYCODE_HOMOGLYPH
    )

    /**
     * Moliyaviy / maxfiy kalit so'zlar — [PhishingNotificationService] ro'yxatining NUSXASI
     * (uni tahrir qilmaymiz; bu yerda mustaqil saqlanadi). URL/host ichida uchrasa, fishing
     * alomati. Kichik harfda taqqoslanadi.
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
        "work", "rest", "country", "kim", "loan"
    )

    /**
     * Banklarning HAQIQIY TLD'lari — agar host bank brendiga o'xshasa-yu, lekin shu
     * "ishonchli" TLD'larda bo'lmasa, LOOKALIKE_TLD belgisini beradi (payme.top, click.ru).
     */
    private val BANK_OK_TLDS = setOf("uz", "com", "ru")

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
        // Sxema bo'lmasa, http:// taxmin qilamiz (lekin NON_HTTPS belgisi qo'yiladi).
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(raw)
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://").find(raw)
        val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
        // https?:// dan boshqa sxema (ftp, javascript, data, file...) — o'qib bo'lmaydi.
        if (hasScheme && scheme != "http" && scheme != "https") {
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, null,
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }

        val normalized = if (hasScheme) raw else "http://$raw"
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
        val hasAt = afterScheme.substringBefore('/').contains('@')
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = if (authority.contains('@')) authority.substringAfterLast('@') else authority
        // IPv6 emas deb hisoblaymiz (kam uchraydi); port'ni olib tashlaymiz.
        val rawHost = hostPort.substringBefore(':').trim().trimEnd('.').lowercase()

        if (rawHost.isEmpty() || !rawHost.contains('.') && !isIpLiteral(rawHost)) {
            // Host yo'q yoki nuqtasiz (localhost-simon) → ishonchsiz.
            return LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, rawHost.ifEmpty { null },
                "kq4_link_reason_unparsable", listOf(Flag.UNPARSABLE)
            )
        }

        val flags = LinkedHashSet<Flag>()  // tartibni saqlaymiz, keyin worst-first saralaymiz

        // 1) IP-literal host (domen o'rniga raqamli manzil).
        val isIp = isIpLiteral(rawHost)
        if (isIp) flags.add(Flag.IP_LITERAL_HOST)

        // 2) Punycode / homoglyph (xn-- yoki ASCII bo'lmagan harf domenda).
        if (!isIp && isPunycodeOrHomoglyph(rawHost)) flags.add(Flag.PUNYCODE_HOMOGLYPH)

        // 3) Qora ro'yxat (curated C2 + bulut feed). Suffiks moslik MaliciousDomains ichida.
        val blacklisted = !isIp && MaliciousDomains.maliciousFamily(rawHost) != null
        if (blacklisted) flags.add(Flag.BLACKLISTED_DOMAIN)

        // HAQIQIY bank domeni: registrable domen bank tokeniga AYNAN teng + ishonchli TLD (.uz/.com/.ru)
        // + qora ro'yxatda/punycode'da emas. Bunda "click"/"payme" kabi brend SO'ZINING o'zi
        // moliyaviy-kalit-so'z sifatida noto'g'ri ogohlantirish bermasligi uchun zaif belgilarni
        // (moliyaviy kalit so'z / qisqartirgich) bostiramiz — aks holda click.uz SHUBHALI chiqardi.
        val isKnownGoodBank = !isIp && !blacklisted &&
            !flags.contains(Flag.PUNYCODE_HOMOGLYPH) && isLegitBankDomain(rawHost)

        // 4) Bank typosquat. IP va punycode'da bekor (alohida belgi bor). Ikki kuch:
        //    • HARD lookalike (token chegara bilan: payme-uz, click-pay, yoki payme.evil.tk) →
        //      DOIM TYPOSQUAT_BANK (DANGER) — bu aniq qasddan taqlid.
        //    • SOFT tahrir-masofa yaqinligi (osaka↔asaka) — YOLG'IZ DANGER bermaydi (osaka.com,
        //      asana.com real domenlar). Faqat zaif signal (shubhali TLD / http / moliyaviy so'z)
        //      bilan TASDIQLANSA TYPOSQUAT_BANK bo'ladi; aks holda e'tiborsiz qoldiriladi.
        if (!isIp) {
            when (looksLikeBankKind(rawHost)) {
                BankMatch.HARD -> flags.add(Flag.TYPOSQUAT_BANK)
                BankMatch.SOFT -> {
                    val tldNow = rawHost.substringAfterLast('.')
                    val lowerNow = normalized.lowercase()
                    val corroborated = tldNow in SUSPICIOUS_TLDS || !isHttps ||
                        FINANCIAL_KEYWORDS.any { lowerNow.contains(it) }
                    if (corroborated) flags.add(Flag.TYPOSQUAT_BANK)
                }
                BankMatch.NONE -> { /* taqlid yo'q */ }
            }
        }

        // 5) '@' yashirish hiylasi.
        if (hasAt) flags.add(Flag.AT_IN_URL)

        // 6) Juda ko'p subdomen (paypal.com.evil.tk) — IP'da emas. Haqiqiy bank domenida
        //    (login.secure.kapitalbank.uz) bekor — chuqur rasmiy subdomen yolg'iz zaif belgi
        //    sifatida SHUBHALI bermasligi uchun (URL_SHORTENER/FINANCIAL_KEYWORDS bilan bir xil guard).
        if (!isIp && !isKnownGoodBank && labelCount(rawHost) >= 4) flags.add(Flag.EXCESSIVE_SUBDOMAINS)

        // 7) Shubhali TLD.
        val tld = if (!isIp) rawHost.substringAfterLast('.') else ""
        if (!isIp && tld in SUSPICIOUS_TLDS) flags.add(Flag.SUSPICIOUS_TLD)

        // 8) URL qisqartirgich (registrable domen). Haqiqiy bank domenida bekor.
        val registrable = if (!isIp) registrableDomain(rawHost) else ""
        if (!isIp && !isKnownGoodBank && registrable in URL_SHORTENERS) flags.add(Flag.URL_SHORTENER)

        // 9) Bank brendi noto'g'ri TLD'da (lookalike-tld). Faqat brend topilsa-yu typosquat
        //    belgisi qo'yilmagan bo'lsa (aks holda ortiqcha bo'ladi).
        if (!isIp && !flags.contains(Flag.TYPOSQUAT_BANK) && !flags.contains(Flag.BLACKLISTED_DOMAIN)) {
            if (bankBrandInLabelsButWrongTld(rawHost, tld)) flags.add(Flag.LOOKALIKE_TLD)
        }

        // 10) Moliyaviy kalit so'zlar (butun URL bo'ylab — path/query ham). Haqiqiy bank domenida
        //     bekor (brend so'zining o'zi noto'g'ri ogohlantirmasligi uchun).
        val lowerUrl = normalized.lowercase()
        if (!isKnownGoodBank && FINANCIAL_KEYWORDS.any { lowerUrl.contains(it) }) {
            flags.add(Flag.FINANCIAL_KEYWORDS)
        }

        // 11) HTTPS emas.
        if (!isHttps) flags.add(Flag.NON_HTTPS)

        // ── Verdiktni hisoblaymiz ──
        val verdict = decideVerdict(flags)
        val ordered = orderFlags(flags)
        val reasonKey = reasonKeyFor(verdict, ordered)

        return LinkResult(verdict, raw, rawHost, reasonKey, ordered)
    }

    // ───────────────────────── Verdikt mantig'i ─────────────────────────

    /** Belgilar to'plamidan yakuniy verdikt. [analyze] uchun ajratilgan (test qilinadi). */
    internal fun decideVerdict(flags: Set<Flag>): ScanResult.Verdict {
        if (flags.isEmpty()) return ScanResult.Verdict.SAFE
        // Qattiq belgi (qora ro'yxat / typosquat / punycode) → DANGER.
        if (flags.any { it in HARD_FLAGS }) return ScanResult.Verdict.DANGER
        // UNPARSABLE — DOIM SUSPICIOUS (yuqorida UNPARSABLE bilan qaytariladi, lekin himoya uchun).
        if (flags.contains(Flag.UNPARSABLE)) return ScanResult.Verdict.SUSPICIOUS
        // Moliyaviy kalit so'z + HTTPS emas → SUSPICIOUS.
        if (flags.contains(Flag.FINANCIAL_KEYWORDS) && flags.contains(Flag.NON_HTTPS)) {
            return ScanResult.Verdict.SUSPICIOUS
        }
        // Ikki yoki undan ko'p zaif belgi → SUSPICIOUS.
        if (flags.size >= 2) return ScanResult.Verdict.SUSPICIOUS
        // Bitta zaif belgi qoldi (masalan faqat http://, yoki faqat shubhali TLD). Antivirus
        // oltin qoidasi: aniq tozalanmagan har qanday belgi SAFE bermaydi → SUSPICIOUS.
        // (http yolg'iz ham shifrlanmagan = ehtiyot SHUBHALI.)
        return ScanResult.Verdict.SUSPICIOUS
    }

    /** Belgilarni eng yomonidan eng yengiligacha tartiblaydi (UI ro'yxati uchun). */
    private fun orderFlags(flags: Set<Flag>): List<Flag> {
        val priority = listOf(
            Flag.BLACKLISTED_DOMAIN, Flag.TYPOSQUAT_BANK, Flag.PUNYCODE_HOMOGLYPH,
            Flag.LOOKALIKE_TLD, Flag.IP_LITERAL_HOST, Flag.AT_IN_URL,
            Flag.EXCESSIVE_SUBDOMAINS, Flag.SUSPICIOUS_TLD, Flag.URL_SHORTENER,
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

    /** IPv4 yoki bracketsiz IPv6'ga o'xshashmi (raqamli host). */
    internal fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        // IPv4: 4 ta 0..255 oktet.
        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }) {
            return true
        }
        // IPv6 (ikki nuqta ko'p) yoki bracket bilan.
        if (host.startsWith("[") || host.count { it == ':' } >= 2) return true
        return false
    }

    /**
     * Punycode (xn-- prefiksli yorliq) yoki ASCII bo'lmagan harf (homoglyph hujumi —
     * kirill 'а' lotin 'a' o'rniga). Ikkalasi ham brend taqlidi uchun ishlatiladi.
     */
    internal fun isPunycodeOrHomoglyph(host: String): Boolean {
        if (host.split('.').any { it.startsWith("xn--") }) return true
        // ASCII bo'lmagan harf bormi? (raqamli/IDN'siz domenlarda bo'lmasligi kerak)
        return host.any { it.code > 127 }
    }

    /** Domendagi yorliqlar (nuqta bilan ajratilgan qismlar) soni. */
    private fun labelCount(host: String): Int = host.split('.').count { it.isNotEmpty() }

    /** Registrable domen (oxirgi ikki yorliq, soddalashtirilgan — eTLD+1 emas). */
    private fun registrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    /** Bank-taqlid moslik kuchi: HARD (aniq qasddan) / SOFT (faqat tahrir-masofa) / NONE. */
    internal enum class BankMatch { HARD, SOFT, NONE }

    /**
     * Host bank brendiga o'xshashligini KUCH bilan baholaydi:
     *   • HARD — aniq qasddan taqlid (yolg'iz DANGER): (a) yorliq bank tokeniga AYNAN teng,
     *     lekin registrable domeni bank.* emas (payme.evil.tk); (b) yorliq tokendan boshlanadi
     *     yoki tugaydi-yu, chegara belgisi HARF EMAS (raqam/defis): payme24, payme-uz, click-pay.
     *   • SOFT — faqat tahrir-masofa yaqinligi (osaka↔asaka, payne↔payme). Bu YOLG'IZ DANGER
     *     bermaydi (osaka.com / asana.com / mobile-*.com real domenlar) — chaqiruvchi zaif
     *     signal bilan tasdiqlanmasa e'tiborsiz qoldiradi. 5 harfli tokenda 2 masofa string'ning
     *     ~40% farqiga yo'l qo'yardi, shuning uchun byudjet token uzunligiga proporsional.
     *   • NONE — taqlid yo'q.
     *
     * O'ZIMIZNING brendimiz ("anor"/"anorqalqon") bank tokeni "anorbank" bilan substring
     * orqali to'qnashmasligi uchun BUTUN token taqqoslanadi (substring "anor" emas).
     */
    internal fun looksLikeBankKind(host: String): BankMatch {
        val tokens = KnownBanks.brandTokens()
        val labels = host.split('.', '-', '_').filter { it.isNotEmpty() }
        // Haqiqiy registrable domen bank tokeniga AYNAN teng bo'lsa (click.uz, payme.uz) — typosquat EMAS.
        val regBase = registrableDomain(host).substringBeforeLast('.')  // "click" <- click.uz
        if (regBase in tokens) {
            // Lekin TLD ishonchsiz bo'lsa (click.tk) — bu lookalike, bu yerda emas, alohida belgida.
            return BankMatch.NONE
        }

        var soft = false
        for (label in labels) {
            if (label.length < 5) continue
            for (token in tokens) {
                if (token.length < 5) continue
                // Aynan teng — yuqorida regBase orqali tekshirildi; bu yerda yorliq ichidagi
                // qo'shimcha bilan buzilganini qidiramiz.
                if (label == token) {
                    // host = "payme.evil.tk" — "payme" yorlig'i bor lekin registrable domeni
                    // payme.* emas → aniq taqlid (HARD).
                    if (regBase != token) return BankMatch.HARD
                    continue
                }
                // Chegara-lookalike (HARD): yorliq tokendan boshlanadi/tugaydi-yu, qo'shni belgi
                // HARF EMAS (raqam/defis): payme24, payme-uz, click-pay. Uzunroq harfli so'z
                // ichidagi token (clickhouse, clickup, payments) ESA bu yerda HARD bo'lmaydi.
                if (label.length > token.length) {
                    if (label.startsWith(token) && !label[token.length].isLetter()) return BankMatch.HARD
                    if (label.endsWith(token) && !label[label.length - token.length - 1].isLetter()) return BankMatch.HARD
                }
                // Tahrir-masofa yaqinligi (SOFT) — byudjet token uzunligiga proporsional
                // (qisqa 5-7 tokenda 1, uzun ≥8 tokenda 2). Yolg'iz DANGER emas.
                val budget = if (token.length >= 8) 2 else 1
                val d = levenshtein(label, token)
                if (d in 1..budget) soft = true
            }
        }
        return if (soft) BankMatch.SOFT else BankMatch.NONE
    }

    /**
     * Host HAQIQIY bank domenimi: registrable domeni bank brend tokeniga AYNAN teng VA
     * registrable TLD ishonchli (.uz/.com/.ru) — masalan click.uz, payme.uz, kapitalbank.uz.
     * Subdomenli haqiqiy domen ham (my.click.uz) o'tadi. Soxta TLD (click.top) o'tmaydi.
     */
    internal fun isLegitBankDomain(host: String): Boolean {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val tld = parts.last()
        val base = parts[parts.size - 2]
        return base in KnownBanks.brandTokens() && tld in BANK_OK_TLDS
    }

    /**
     * Host yorliqlaridan biri bank brendiga aynan teng, lekin registrable TLD ishonchsiz
     * (.uz/.com/.ru emas) → lookalike-tld (payme.top, click.xyz). [looksLikeBankKind] aniq-teng
     * holatni typosquat deb belgilamaydi, shuning uchun bu alohida, yengilroq belgi.
     */
    private fun bankBrandInLabelsButWrongTld(host: String, tld: String): Boolean {
        val tokens = KnownBanks.brandTokens()
        val regBase = registrableDomain(host).substringBeforeLast('.')
        if (regBase in tokens && tld !in BANK_OK_TLDS) return true
        return false
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
