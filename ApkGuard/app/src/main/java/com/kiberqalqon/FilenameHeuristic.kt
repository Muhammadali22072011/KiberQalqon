package com.uzguard

import java.io.File

/**
 * Эвристика по ИМЕНИ файла APK + сопоставление с package name и app label внутри.
 *
 * Многослойная защита от Telegram-distributed малвари в Узбекистане. Каждый слой
 * добавляет балл к общему score, плюс есть hard-DANGER сигналы (brand impersonation
 * + homoglyph attack), которые срабатывают мгновенно.
 *
 * **Слои (от простых к умным):**
 *
 *   L1. Шаблоны имён (RASMLAR (NN).apk, VIDEO.DD.MM.YYYY*.apk, toydanfotolar)
 *   L2. Двойное расширение (*.mp4.apk, *.jpg.apk)
 *   L3. Generic lure-слова: yangilanish, premium, MTS, Beeline, kupon, podarok…
 *   L4. Brand impersonation: filename = "Telegram" но package ≠ org.telegram.*
 *   L5. Brand TYPOSQUAT: "Telegrarn.apk" (rn вместо m) — Levenshtein ≤ 2
 *   L6. Homoglyph attack: смесь Cyrillic+Latin в имени (Тelegram с русской Т)
 *   L7. Label-vs-filename mismatch: filename "MyBank" но app label "Game Tools"
 *   L8. Bigram-rarity для package name segments (lzthzvxte — редкие биграммы)
 *
 * Score аккумулируется; brand impersonation/homoglyph — мгновенный hard-DANGER.
 *
 * **Реальные образцы из репо (analysis/):**
 *   RASMLAR (18).apk           → com.lzthzvxte.xazoalzxhr (Ajina.Banker)
 *   RASMLAR (8).apk            → com.oktgkst.rrcpkge (Ajina.Banker)
 *   toydanfotolar(9.jpg).apk   → com.yzsfnie.sjsztphpis (Ajina.Banker)
 *   VID_23856_21052026.apk     → com.puhfvysb.nzbftunmqq (Ajina.Banker)
 *   VIDEO.20.01.2026.mp4.apk   → ydbllnjd.com (RoundRift)
 *   TAKLIFNOMA TOY ...apk      → Uzbek-dropper.taklifnoma
 *   Video_202_089_mp8.apk      → Uzbek-dropper.vudgi
 */
object FilenameHeuristic {

    data class Findings(
        val score: Int,
        val flags: List<String>,
        /** Не null если есть «жёсткий» сигнал на DANGER без оглядки на остальное. */
        val hardDanger: HardDanger? = null,
    )

    sealed class HardDanger {
        data class BrandImpersonation(
            val brand: String,
            val expectedPackage: String,
            val actualPackage: String?,
            /** Точное совпадение, typosquat или homoglyph. */
            val matchKind: String,
        ) : HardDanger()
        data class HomoglyphScript(val sample: String) : HardDanger()
    }

    /**
     * Паттерны имён, характерные для Telegram-distributed Uzbek banker malware.
     */
    private val SUSPICIOUS_PATTERNS: List<Pair<Regex, Pair<Int, String>>> = listOf(
        // L2 — Двойное расширение: *.mp4.apk / *.jpg.apk. Telegram qayta yuklashda
        // " (2)" qo'shadi → "file.mp4 (2).apk" ham tutilishi kerak (optional suffix).
        Regex("(?i)\\.(mp4|mp3|mov|avi|jpg|jpeg|png|gif|pdf|docx?|xlsx?|zip|rar)\\s*(\\(\\d+\\))?\\.apk$")
            to (50 to "Double extension trick (masalan: file.mp4.apk / file.mp4 (2).apk)"),

        // L1 — Telegram-banker шаблоны (точные)
        Regex("(?i)^RASMLAR\\s*\\(\\d+\\)\\.apk$")
            to (40 to "Telegram-banker template: RASMLAR (NN).apk"),
        Regex("(?i)^VIDEO\\.\\d{1,2}\\.\\d{1,2}\\.\\d{4}.*\\.apk$")
            to (40 to "Telegram-banker template: VIDEO.DD.MM.YYYY"),
        Regex("(?i)^VID_\\d{4,8}_\\d{6,10}\\.apk$")
            to (40 to "Telegram-banker template: VID_NNN_DATE"),
        // "toydanfotolar", "Toydan fotolar", "To'ydan fotolar", "ToydanFotolar" — barcha
        // variantlar (probel/apostrof/camelCase). Eski regex faqat probelsiz variantni
        // tutardi → "Toydan fotolar (20) 23.05.2026.foto.apk" (papkadagi eng ko'p tur) o'tib ketardi.
        Regex("(?i)to.?ydan[\\s_]*fotolar")
            to (40 to "Uzbek lure: toydan fotolar (to'y rasmlari)"),
        // ".foto.apk" qo'shimchasi — "rasm" ko'rinishini berish uchun (Toydan fotolar ...foto.apk)
        Regex("(?i)\\.foto\\.apk$")
            to (30 to "Soxta '.foto.apk' qo'shimchasi (rasm niqobi)"),
        // "Rasmlar (NN)" / "RasmlarAlbum" — Ajina.Banker'ning klassik nomlari (rasmlar = foto).
        Regex("(?i)rasmlar\\s*album")
            to (35 to "Uzbek lure: RasmlarAlbum (foto albom niqobi)"),
        Regex("(?i)\\brasmlar\\s*\\(\\d+\\)")
            to (30 to "Telegram-banker template: Rasmlar (NN)"),
        Regex("(?i)TAKLIFNOMA")
            to (35 to "Uzbek lure: TAKLIFNOMA (taklifnoma dropper)"),
        Regex("(?i)^Video_\\d+[_-]\\d+")
            to (30 to "Telegram-distributed: Video_NNN_NNN pattern"),

        // Telegram raw message-id имена
        Regex("(?i)^\\d+_\\d{15,}\\.apk$")
            to (25 to "Telegram message-id raw name (2_5222...apk)"),
        Regex("(?i)^[A-F0-9]{16,}\\.apk$")
            to (25 to "Hex-only filename (avto-generatsiya)"),

        // Unicode-«невидимки» — визуальная маскировка. Real virus'lar HANGUL FILLER
        // (U+3164) ishlatishadi `Video_202_089_mp8ㅤㅤㅤㅤ.apk` da — `.apk` ko'rinmasligi uchun.
        // U+115F/U+1160 HANGUL CHOSEONG/JUNGSEONG FILLER, U+3164 HANGUL FILLER, U+FFA0
        // HALFWIDTH HANGUL FILLER — bularning hech biri "space" emas lekin
        // ko'rinmaydi. U+2060-U+2064 word joiner/invisible operators, U+FEFF ZWNBSP/BOM,
        // U+180B-180E MONGOLIAN variation selectors — to'liq invisible.
        Regex("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000\\u115F\\u1160\\u3164\\uFFA0\\u2060-\\u2064\\u206A-\\u206F\\uFEFF\\u180B-\\u180E\\u17B4\\u17B5]")
            to (40 to "Unicode invisible chars (`.apk` ni yashirish hiylasi)"),

        // 3+ круглые скобки или дублирование расширений
        Regex("\\(\\d+\\).*\\(\\d+\\)")
            to (15 to "Multiple numbered brackets (anti-collision rename)"),
    )

    /**
     * Generic lure-слова на uz/ru/en — реальная малварь часто их использует
     * как приманку. Сами по себе ОК, но в комбо с другими сигналами — флаг.
     */
    private val LURE_KEYWORDS: List<Pair<Regex, Pair<Int, String>>> = listOf(
        // Telecom-приманки (uz/ru)
        Regex("(?i)\\b(MTS|MTC|Beeline|Билайн|Bilayn|UCell|Uzmobile|UMS|UzbekTelecom)\\b")
            to (25 to "Telecom impersonation lure (MTS/Beeline/Ucell)"),

        // Подарки/бонусы/розыгрыши — UZ + RU + EN
        Regex("(?i)\\b(kupon|sovrin|sovgha|sovgalar|sovrinli|kupon|bonus|podarok|подарок|sovga|prize|gift|lottery|loter|lotery|sovrin)\\b")
            to (20 to "Gift/lottery lure"),

        // Обновления / updates (классическая dropper приманка)
        Regex("(?i)\\b(yangilanish|обновление|update|updater|setup|installer)\\b")
            to (15 to "Fake-update lure"),

        // Crack / Mod / Premium / Hack
        Regex("(?i)\\b(crack|cracked|mod[\\W_]?apk|premium|hack|hacked|vzlom|взлом|pro[\\W_]?version|unlocked)\\b")
            to (20 to "Crack/mod/premium lure"),

        // Adult content (часто используется как dropper приманка)
        Regex("(?i)\\b(porno|porn|sex|xxx|18\\+|porno_uz|seks|porno_video)\\b")
            to (25 to "Adult-content dropper lure"),

        // Documents / invoices (Office-style social engineering)
        Regex("(?i)\\b(invoice|receipt|chek|fatura|tilxat|shartnoma|kontrakt|kontract|hisob|hisobnoma)\\b")
            to (20 to "Document/invoice lure"),

        // Government / tax (UZ)
        Regex("(?i)\\b(soliq|tax|mygov|edo|gov_uz|soliqlar|davlat_xizmat)\\b")
            to (25 to "Government impersonation lure"),
    )

    /**
     * Известные бренды + их канонические package prefix.
     * Расширен — добавлены банки, госуслуги, и популярные UZ apps.
     */
    private val KNOWN_BRANDS: Map<String, String> = mapOf(
        // Messengers
        "telegram" to "org.telegram",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram",
        "facebook" to "com.facebook",
        "messenger" to "com.facebook.orca",
        "youtube" to "com.google.android.youtube",
        "tiktok" to "com.zhiliaoapp.musically",
        "viber" to "com.viber",
        "imo" to "com.imo",
        "signal" to "org.thoughtcrime.securesms",
        // UZ banks / payments
        "uzcard" to "uz.uzcard",
        "humo" to "uz.humo",
        "mytaxi" to "uz.mytaxi",
        "click" to "uz.click",
        "payme" to "uz.dida.payme",
        "anorbank" to "uz.anorbank",
        "tbcuz" to "uz.tbc",
        "kapitalbank" to "uz.kapitalbank",
        "asaka" to "uz.asaka",
        "agrobank" to "uz.agrobank",
        "ipakyo" to "uz.ipakyulibank",
        "qishloq" to "uz.qishloqqurilishbank",
        // Gov
        "mygov" to "uz.mygov",
        "soliq" to "uz.soliq",
        "edo" to "uz.edo",
        // Google
        "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "drive" to "com.google.android.apps.docs",
    )

    fun analyze(apkPath: String, packageName: String?, appLabel: String? = null): Findings {
        val filename = File(apkPath).name
        val flags = mutableListOf<String>()
        var score = 0
        var hard: HardDanger? = null

        // L1 + L2 — точные паттерны.
        for ((regex, scoreAndLabel) in SUSPICIOUS_PATTERNS) {
            if (regex.containsMatchIn(filename)) {
                val (s, label) = scoreAndLabel
                score += s
                flags += label
            }
        }

        // L3 — generic lure-слова.
        for ((regex, scoreAndLabel) in LURE_KEYWORDS) {
            if (regex.containsMatchIn(filename)) {
                val (s, label) = scoreAndLabel
                score += s
                flags += label
            }
        }

        // L6 — homoglyph attack (Cyrillic + Latin в одном слове = классический фишинг).
        // Например: "Тelegram.apk" где Т кириллическая (U+0422) выглядит как T.
        val mixedScript = findMixedScriptToken(filename)
        if (mixedScript != null) {
            score += 80
            flags += "Mixed Cyrillic-Latin script in filename: \"$mixedScript\" (homoglyph hujum)"
            hard = HardDanger.HomoglyphScript(mixedScript)
        }

        // L4 + L5 — brand impersonation: exact match + typosquat + qo'shilgan (camelCase).
        val rawName = stripExtension(filename)
        val lowerName = rawName.lowercase()
        // Toklenize по non-alnum:
        val tokens = Regex("[a-z][a-z0-9]{2,}").findAll(lowerName).map { it.value }.toSet()

        var brandHit: HardDanger.BrandImpersonation? = null
        for ((brand, expected) in KNOWN_BRANDS) {
            val (kind, matchedToken) = matchBrand(brand, tokens, rawName) ?: continue

            val pkgOk = packageName?.lowercase()?.startsWith(expected.lowercase()) == true
            if (!pkgOk) {
                brandHit = HardDanger.BrandImpersonation(
                    brand = brand,
                    expectedPackage = expected,
                    actualPackage = packageName,
                    matchKind = kind,
                )
                val niceKind = when (kind) {
                    "exact" -> "aniq nom"
                    "concat" -> "qo'shilgan nom (camelCase): '$matchedToken'"
                    "typosquat" -> "buzilgan nom (typosquat): '$matchedToken'"
                    else -> kind
                }
                flags += "Brand impersonation ($niceKind): \"$brand\" faylda lekin paket = ${packageName ?: "yo'q"}"
                score += 80
                break
            }
        }
        // Hard danger: prefer brand impersonation, иначе homoglyph.
        if (brandHit != null) hard = brandHit

        // L7 — App label vs filename mismatch.
        // Пользователь видит "RASMLAR (18).apk" → ожидает приложение "Фотки",
        // но открыв APK видит label = "Telegram". Это — почти 100% phishing.
        if (appLabel != null && appLabel.isNotBlank()) {
            val labelLower = appLabel.lowercase()
            val labelTokens = Regex("[a-zа-я][a-zа-я0-9]{2,}").findAll(labelLower).map { it.value }.toSet()
            // Если в label есть бренд, а в filename — нет (и наоборот), значит маскировка.
            for ((brand, _) in KNOWN_BRANDS) {
                val labelHas = labelTokens.contains(brand)
                val nameHas = tokens.contains(brand)
                if (labelHas != nameHas) {
                    score += 35
                    flags += "Label/filename mismatch: ilova \"$appLabel\" lekin fayl \"$filename\""
                    break
                }
            }
        }

        // L8 — Bigram rarity для package name segments.
        // Random consonant clusters типа "lzthzvxte" имеют редкие биграммы (lz, zt, th, zv, xt).
        // Реальные English/UZ слова такого не дают. "kzxwc" (pyw.kzxwc dropper) ham
        // qisqa (5 belgi), shuning uchun threshold 6 → 4 ga tushirildi.
        if (packageName != null) {
            val segs = packageName.split('.').filter { it.length >= 4 }
            for (seg in segs) {
                val rarity = bigramRarityScore(seg.lowercase())
                if (rarity >= 0.55) {
                    score += 20
                    flags += "Package segment '$seg' bigram-rarity = ${"%.2f".format(rarity)} (random-like)"
                    break
                }
            }
        }

        return Findings(score = score, flags = flags, hardDanger = hard)
    }

    /**
     * Поиск бренда в множестве токенов:
     *  1) exact token match
     *  2) concat (camelCase): TelegramPlus / ClickPro / PaymeUpdate — токенизатор склеивает
     *     их в один токен, поэтому ищем бренд внутри исходного имени на границе слова
     *     (#17). Граница = заглавная буква / цифра / разделитель — это отсекает FP
     *     вроде "payment" (после "payme" идёт строчная 'n').
     *  3) typosquat: любой токен в пределах Levenshtein ≤ 2 от бренда (длина ≥ 5)
     */
    private fun matchBrand(brand: String, tokens: Set<String>, rawName: String): Pair<String, String>? {
        if (brand in tokens) return "exact" to brand
        if (brand.length >= 5) {
            matchConcatenatedBrand(brand, rawName)?.let { return "concat" to it }
        }
        if (brand.length < 5) return null  // короткие бренды (imo) — не делаем typosquat (FP)
        for (tok in tokens) {
            if (tok.length !in (brand.length - 1)..(brand.length + 2)) continue
            if (tok == brand) return "exact" to tok
            val d = levenshtein(tok, brand, maxDistance = 2)
            if (d in 1..2) return "typosquat" to tok
        }
        return null
    }

    /**
     * Бренд внутри слитного имени (камелкейс), только на границе слова, чтобы не ловить
     * обычные слова. "ClickPro"→Click, "PaymeUpdate"→Payme, "WhatsAppGold"→WhatsApp;
     * "payment" / "clicker" НЕ матчатся (после бренда идёт строчная буква).
     */
    private fun matchConcatenatedBrand(brand: String, rawName: String): String? {
        val low = rawName.lowercase()
        var from = 0
        while (true) {
            val i = low.indexOf(brand, from)
            if (i < 0) return null
            val before = if (i == 0) null else rawName[i - 1]
            val afterIdx = i + brand.length
            val after = if (afterIdx >= rawName.length) null else rawName[afterIdx]
            val beforeOk = before == null || !before.isLetter() || before.isUpperCase()
            val afterOk = after == null || !after.isLetter() || after.isUpperCase() || after.isDigit()
            // Игнорируем случай "бренд = всё имя целиком" (это уже exact-токен).
            if (beforeOk && afterOk && !(before == null && after == null)) {
                return rawName.substring(i, afterIdx)
            }
            from = i + 1
        }
    }

    /**
     * Ищет в имени файла токен где смешаны Cyrillic и Latin буквы.
     * "Тelegram" с русской Т выглядит как латинская T — атакующий обходит whitelist.
     * Не флагуем если кириллицы целое слово (нормальный uz/ru текст).
     */
    private fun findMixedScriptToken(name: String): String? {
        val tokens = Regex("[\\p{L}]+").findAll(name).map { it.value }
        for (tok in tokens) {
            if (tok.length < 4) continue
            var lat = 0
            var cyr = 0
            for (c in tok) {
                when {
                    c in 'Ѐ'..'ӿ' -> cyr++  // Cyrillic block
                    c in 'A'..'Z' || c in 'a'..'z' -> lat++
                }
            }
            // Смесь — и латин, и кириллица в одном токене.
            if (lat >= 1 && cyr >= 1) return tok
        }
        return null
    }

    /** Removes .apk extension if present (case-insensitive). */
    private fun stripExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx > 0) name.substring(0, idx) else name
    }

    /**
     * Levenshtein distance with early-exit optimization: возвращает maxDistance+1
     * как только distance гарантированно ≥ maxDistance.
     */
    private fun levenshtein(a: String, b: String, maxDistance: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1
        val n = a.length
        val m = b.length
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prev = dp[0]
            dp[0] = i
            var rowMin = dp[0]
            for (j in 1..m) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev
                    else 1 + minOf(prev, dp[j - 1], dp[j])
                prev = tmp
                if (dp[j] < rowMin) rowMin = dp[j]
            }
            if (rowMin > maxDistance) return maxDistance + 1
        }
        return dp[m]
    }

    /**
     * Bigram rarity score [0..1]: средняя редкость биграмм в строке.
     * Использует фиксированный набор «очень частых английских биграмм» —
     * чем меньше частых биграмм, тем выше score (=более случайно).
     * "lzthzvxte" → ~0.7 (почти все биграммы вне частого набора).
     * "messenger" → ~0.2 (часто встречающиеся биграммы).
     */
    private fun bigramRarityScore(s: String): Double {
        if (s.length < 3) return 0.0
        var total = 0
        var rare = 0
        for (i in 0 until s.length - 1) {
            val bg = s.substring(i, i + 2)
            if (bg.length != 2 || !bg.all { it.isLetter() }) continue
            total++
            if (bg !in COMMON_BIGRAMS) rare++
        }
        if (total == 0) return 0.0
        return rare.toDouble() / total
    }

    /** Топ-100 частых биграмм английского/русского — взяты из корпусной статистики. */
    private val COMMON_BIGRAMS: Set<String> = setOf(
        // English top-50
        "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd",
        "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar",
        "st", "to", "nt", "ng", "se", "ha", "as", "ou", "io", "le",
        "ve", "co", "me", "de", "hi", "ri", "ro", "ic", "ne", "ea",
        "ra", "ce", "li", "ch", "ll", "be", "ma", "si", "om", "ur",
        // Common in software/package names
        "an", "er", "ap", "pl", "ic", "io", "on", "ol", "ne", "et",
        "pa", "ge", "il", "us", "vi", "ad", "lo", "do", "ge", "in",
        // Common in Uzbek (latin orthography)
        "uz", "ng", "ek", "ar", "im", "ic", "an", "or", "il", "al",
        // Domain TLD patterns
        "co", "om", "or", "rg", "uz", "ru", "io",
    )
}
