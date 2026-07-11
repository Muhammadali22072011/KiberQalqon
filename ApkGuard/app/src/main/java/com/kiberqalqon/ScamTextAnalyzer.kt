package com.uzguard

/**
 * ====== "BU XABAR FIRIBGARLIKMI?" — MATN TAHLILGICHI ======
 *
 * SMS / Telegram / boshqa xabar matnini OFFLINE evristika bilan baholaydi. Tarmoq YO'Q —
 * sof funksiya (java.lang.* JVM'da bor), shuning uchun unit testlardan to'g'ridan-to'g'ri
 * chaqirsa bo'ladi. uz-latin / uz-cyrillic / ru tillarini qamrab oladi.
 *
 * ====== HALOL RAMKA ======
 * Bu — YAKUNIY HUKM EMAS, balki SIGNALLAR. Hech qachon qat'iy "firibgar" demaymiz; faqat
 * xavf belgilarini sanab, foydalanuvchini ehtiyotkorlikка chaqiramiz. Ball KONSERVATIV —
 * "xavfli"dan ko'ra "shubhali"ni afzal ko'ramiz. "xavfli" faqat ikki aniq holatda:
 *   (a) xabardagi havola [LinkScanner] tomonidan DANGER deb baholansa;
 *   (b) maxfiy ma'lumot (kod/parol/karta) so'rovi + kuchli tuzoq (yutuq/jarima) YOKI pul
 *       o'tkazish YOKI shoshiltirish bilan BIRGA kelsa.
 *
 * Kalit so'z ro'yxatlari QASDDAN qisqa (izohlangan) — soxta-musbatni kamaytirish uchun.
 */
object ScamTextAnalyzer {

    /**
     * Tahlil natijasi.
     * @param signals  o'zbekcha xavf-belgisi izohlari (bo'sh bo'lsa — belgi topilmadi).
     * @param riskLevel "xavfsiz" / "shubhali" / "xavfli" — SIGNAL darajasi, yakuniy hukm emas.
     */
    data class Result(val signals: List<String>, val riskLevel: String)

    // ── Kalit so'z guruhlari (kichik harfda; apostrof ' ga normallashtiriladi) ──

    /** Yutuq / sovg'a / aksiya tuzog'i — odamni "tekin pul" bilan aldash. */
    private val PRIZE = listOf(
        "yutuq", "yutdingiz", "yutib oldingiz", "sovg'a", "sovga", "sovrin", "mukofot",
        "aksiya", "bonus", "tabriklaymiz", "muborak bo'lsin",
        // uz-cyrillic
        "ютуқ", "ютдингиз", "совға", "мукофот", "акция", "бонус", "табриклаймиз",
        // ru
        "приз", "выиграл", "выигрыш", "выиграли", "подарок", "поздравля", "акция", "бонус"
    )

    /** Jarima / soliq / politsiya / sud nomidan qo'rqitish. */
    private val FINE = listOf(
        "jarima", "soliq", "politsiya", "sud ", "ijro byurosi",
        // uz-cyrillic
        "жарима", "солиқ", "полиция", "суд",
        // ru
        "штраф", "налог", "налоговая", "полиция", "суд", "пристав", "задолженност"
    )

    /** Maxfiy ma'lumot so'rovi — karta/kod/parol/CVV/PIN/OTP. */
    private val CREDENTIAL = listOf(
        "karta raqami", "karta raqam", "kod", "parol", "sms kod", "sms-kod", "cvv", "pin kod",
        "tasdiqlash kodi", "bir martalik kod",
        // uz-cyrillic
        "карта рақами", "парол", "код", "тасдиқлаш коди",
        // ru
        "номер карты", "код из смс", "код из sms", "пароль", "cvv", "пин", "otp",
        "одноразовый код", "код подтверждения", "смс-код", "смс код"
    )

    /** Shoshiltirish — o'ylab ko'rishga vaqt bermaslik. */
    private val URGENCY = listOf(
        "shoshiling", "darhol", "zudlik bilan", "tezda", "24 soat ichida", "hoziroq",
        // uz-cyrillic
        "шошилинг", "дарҳол", "зудлик билан", "24 соат ичида",
        // ru
        "срочно", "немедленно", "в течение 24", "поторопит", "сейчас же", "прямо сейчас"
    )

    /** Pul o'tkazish so'rovi. */
    private val MONEY = listOf(
        "pul o'tkazing", "pul otkazing", "pul o'tkazish", "o'tkazma qiling", "pul yuboring",
        // uz-cyrillic
        "пул ўтказинг", "пул юборинг", "ўтказма",
        // ru
        "переведите", "перевод денег", "отправьте деньги", "пополните", "оплатите"
    )

    // URL topish — brauzer-ko'rinishli token yoki keng tarqalgan TLD bilan tugagan host.
    private val URL_REGEX = Regex(
        "(?i)((?:https?://|www\\.)[^\\s]+" +
            "|[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?\\.(?:uz|com|ru|net|org|info|top|xyz|tk|ml|link|app|me|co|biz|online|site|click)(?:/[^\\s]*)?)"
    )

    fun analyze(text: String): Result {
        return try {
            analyzeInner(text)
        } catch (_: Throwable) {
            // Tahlil xatosi — false-"xavfsiz" bermaymiz.
            Result(listOf("Xabarni to'liq tahlil qilib bo'lmadi — ehtiyot bo'ling."), "shubhali")
        }
    }

    private fun analyzeInner(text: String): Result {
        val raw = text.trim()
        if (raw.isEmpty()) return Result(emptyList(), "xavfsiz")

        // Apostrof variantlari va harflarni normallashtiramiz (o'/o'/oʻ/o` → o').
        val norm = raw.lowercase()
            .replace('`', '\'')
            .replace('ʻ', '\'')  // ʻ modifier letter turned comma
            .replace('‘', '\'')  // '
            .replace('’', '\'')  // '

        val signals = ArrayList<String>()
        var credentialAsk = false
        var strongLure = false
        var money = false
        var urgency = false
        var weakCount = 0  // "shubhali" chegarasi uchun zaif signallar soni

        if (anyWord(norm, PRIZE)) {
            signals.add("Yutuq / sovg'a / aksiya va'dasi — firibgarlar odamni aldash uchun ishlatadi.")
            strongLure = true; weakCount++
        }
        if (anyWord(norm, FINE)) {
            signals.add("Jarima / soliq / politsiya nomidan qo'rqitish — rasmiy idoralar xabarda kod so'ramaydi.")
            strongLure = true; weakCount++
        }
        if (anyWord(norm, CREDENTIAL)) {
            signals.add("Karta raqami, SMS-kod yoki parol so'ralmoqda — buni HECH KIMGA bermang!")
            credentialAsk = true; weakCount++
        }
        if (anyWord(norm, URGENCY)) {
            signals.add("Shoshiltirish ('darhol', '24 soat ichida') — o'ylashga vaqt bermaslik hiylasi.")
            urgency = true; weakCount++
        }
        if (anyWord(norm, MONEY)) {
            signals.add("Pul o'tkazish so'ralmoqda — notanish odamga pul yubormang.")
            money = true; weakCount++
        }

        // Bank nomi tilga olingan bo'lsa — FAQAT maxfiy ma'lumot so'rovi bilan birga signal
        // (bank nomi yolg'iz o'zi xavf emas). Rasmiy bank hech qachon kod/parol so'ramaydi.
        val bank = mentionedBank(norm)
        if (bank != null && credentialAsk) {
            signals.add("«$bank» nomidan yozilgan-u, maxfiy ma'lumot so'ralmoqda — rasmiy bank bunday qilmaydi.")
            weakCount++
        }

        // Xabardagi havolalarni mavjud LinkScanner bilan baholaymiz (offline). Eng ko'pi 5 ta.
        var urlDanger = false
        var urlSuspicious = false
        val seenHosts = HashSet<String>()
        for (m in URL_REGEX.findAll(raw).take(5)) {
            val candidate = m.value.trimEnd('.', ',', ')', ']', '}', '!', '?', ';', '"', '\'')
            val lr = try { LinkScanner.analyze(candidate) } catch (_: Throwable) { null } ?: continue
            val host = lr.host ?: candidate
            when (lr.verdict) {
                ScanResult.Verdict.DANGER -> {
                    urlDanger = true
                    if (seenHosts.add(host)) {
                        signals.add("Xabardagi havola XAVFLI ($host): ${flagText(lr.reasons.firstOrNull())} — ochmang.")
                    }
                }
                ScanResult.Verdict.SUSPICIOUS -> {
                    urlSuspicious = true
                    if (seenHosts.add(host)) {
                        signals.add("Xabardagi havola shubhali ($host): ${flagText(lr.reasons.firstOrNull())} — ehtiyot bo'ling.")
                        weakCount++
                    }
                }
                ScanResult.Verdict.SAFE -> { /* belgi qo'shmaymiz */ }
            }
        }

        // ── Darajani KONSERVATIV hisoblaymiz ──
        val riskLevel = when {
            urlDanger -> "xavfli"
            credentialAsk && (strongLure || money || urgency) -> "xavfli"
            weakCount >= 1 || urlSuspicious -> "shubhali"
            else -> "xavfsiz"
        }

        return Result(signals, riskLevel)
    }

    /** Ro'yxatdagi biror kalit so'z matnda so'z-chegarasi bilan uchraydimi. */
    private fun anyWord(text: String, keywords: List<String>): Boolean =
        keywords.any { hasWord(text, it) }

    /**
     * `kw` matnda so'z sifatida (chegaralari harf/raqam emas) uchraydimi. Unicode-ogoh
     * (kirill uchun ham ishlaydi), Java `\b` (faqat ASCII) muammosini chetlab o'tadi.
     */
    private fun hasWord(text: String, kw: String): Boolean {
        if (kw.isEmpty()) return false
        var from = 0
        while (true) {
            val idx = text.indexOf(kw, from)
            if (idx < 0) return false
            val before = if (idx == 0) ' ' else text[idx - 1]
            val after = if (idx + kw.length >= text.length) ' ' else text[idx + kw.length]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = idx + 1
        }
    }

    /** Matnda biror bank brend tokeni (>=4 harf) so'z sifatida uchrasa — o'sha bank nomi, aks holda null. */
    private fun mentionedBank(text: String): String? {
        for (token in KnownBanks.brandTokens()) {
            if (token.length < 4) continue
            if (hasWord(text, token)) {
                // Brend tokeni bo'yicha odam o'qiy oladigan nomni topamiz (masalan "payme" → "Payme").
                return KnownBanks.ALL.firstOrNull { it.brand == token }?.label ?: token
            }
        }
        return null
    }

    /** LinkScanner belgisidan qisqa o'zbekcha izoh. */
    private fun flagText(f: LinkScanner.Flag?): String = when (f) {
        LinkScanner.Flag.BLACKLISTED_DOMAIN -> "qora ro'yxatdagi zararli domen"
        LinkScanner.Flag.TYPOSQUAT_BANK -> "bank nomiga o'xshatilgan soxta domen"
        LinkScanner.Flag.PUNYCODE_HOMOGLYPH -> "harflari almashtirilgan soxta domen"
        LinkScanner.Flag.APK_DELIVERY -> "ilova (.apk) yuklashga majburlaydi"
        LinkScanner.Flag.PHISHING_PATH -> "ma'lum firibgarlik manzili"
        LinkScanner.Flag.LOOKALIKE_TLD -> "bank brendi noto'g'ri domen zonasida"
        LinkScanner.Flag.SOCIAL_IMPERSONATION -> "ijtimoiy tarmoqqa taqlid qiluvchi domen"
        LinkScanner.Flag.IP_LITERAL_HOST -> "domen o'rniga IP manzil"
        LinkScanner.Flag.AT_IN_URL -> "haqiqiy manzilni yashiruvchi '@' belgisi"
        LinkScanner.Flag.OPEN_REDIRECT -> "yashirin yo'naltirish havolasi"
        LinkScanner.Flag.EXCESSIVE_SUBDOMAINS -> "juda ko'p subdomen (soxta manzil)"
        LinkScanner.Flag.SUSPICIOUS_TLD -> "arzon/abuziv domen zonasi"
        LinkScanner.Flag.SCAM_LURE -> "firibgar tuzoq so'zlari havolada"
        LinkScanner.Flag.URL_SHORTENER -> "qisqartirilgan (yashirin) havola"
        LinkScanner.Flag.FINANCIAL_KEYWORDS -> "moliyaviy kalit so'zlar havolada"
        LinkScanner.Flag.NON_HTTPS -> "shifrlanmagan (http) havola"
        LinkScanner.Flag.BENIGN_IDN -> "noodatiy kodlangan domen"
        LinkScanner.Flag.UNPARSABLE, null -> "o'qib bo'lmaydigan / shubhali havola"
    }
}
