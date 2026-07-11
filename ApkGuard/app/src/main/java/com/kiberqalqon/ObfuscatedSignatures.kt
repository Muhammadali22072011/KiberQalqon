package com.uzguard

import java.security.MessageDigest

/**
 * Зашифрованные сигнатуры — чтобы `strings uzguard.apk | grep` не показал
 * список malware-доменов и индикаторов. Виды защиты:
 *
 * 1) [MALICIOUS_TOKEN_HASHES] — SHA-256 хэши конкретных индикаторов
 *    (домены, бот-имена, пути). Из APK невозможно понять что именно мы ищем.
 *
 * 2) [XOR_ENCRYPTED_SIGNATURES] — длинные строки в Base64 + XOR. `strings`
 *    выдаст Base64-мусор, который не похож на сигнатуры малвари.
 *
 * 3) Generic-словарь ("trojan", "phishing") УДАЛЁН — слишком много false-positive
 *    на чужих антивирусных либах. Современное обнаружение основано на:
 *    конкретные IoC + permission combos + DEX patterns + native imports.
 *
 * Текст для скана:
 *   - извлекаем токены (URL, host, package-like, path)
 *   - хэшируем каждый
 *   - проверяем в [MALICIOUS_TOKEN_HASHES]
 *
 * Добавление новой сигнатуры:
 *   val h = ObfuscatedSignatures.hash("dashapp-v2.org")  // → "a1b2c3..."
 *   → вставить в MALICIOUS_TOKEN_HASHES с family-меткой.
 */
object ObfuscatedSignatures {

    private const val XOR_KEY: Byte = 0x5A

    /**
     * Map: первые 16 hex SHA-256(token_lowercase) → family label.
     * Token проверяется как exact match после нормализации (lowercase, trim).
     *
     * ВАЖНО (безопасность): ключи — это УЖЕ ВЫЧИСЛЕННЫЕ hash()-значения, НЕ вызовы
     * hash("plaintext"). Раньше здесь стояло hash("elrxzx.com") и т.п. — но аргумент
     * вычислялся в рантайме, поэтому ОТКРЫТЫЙ домен/ключ попадал в dex как литерал
     * (`strings` его видел). Теперь в dex только хэш; открытый текст — в комментарии.
     * Сгенерировать новую запись: python scripts/obf_precompute.py
     */
    private val MALICIOUS_TOKEN_HASHES: Map<String, String> = mapOf(
        // ── Original public list ──
        "27D0C9A87D82BE38" to "dashapp",              // hash("dashapp-v2.org")
        "68A4E4B60AFBAED0" to "uzbekchill",           // hash("uzbekchill.com")
        "8A14F13D280C2BE0" to "taklif_bot",           // hash("taklifnomatoy_robot")
        "3CF06CB580D29C29" to "bot_internal",         // hash("BotSigner")
        "7B74C0D6B496570D" to "bot_internal",         // hash("BotOrg")
        "01C1E695D65C54DA" to "googleadst_dropper",   // hash("googleadst")

        // ── Ajina.Banker (README §10 — Markaziy Osiyo bank troyani) ──
        "0703AD242EBE35AA" to "Ajina.Banker.C2",      // hash("elrxzx.com") — C2 domeni
        // XOR/AES kalitlari DEX'dan ajratilgan (README §3.6):
        "BAAAD646B4778F88" to "Ajina.Banker.key",     // hash("JYTAs0m31lxvwkQE42Y10Ktm")
        "955A67BFA36ACCD3" to "Ajina.Banker.key",     // hash("3183701586F97GhYNSURErMM")

        // ── RoundRift dropper (README §10 — native libdan, KeyManager classi) ──
        "6E5195C8A998CD1D" to "RoundRift.dropper",    // hash("ydbllnjd.com")
        "FF523F1F5CD70BF6" to "RoundRift.C2",         // hash("ilovekkksfm.com")
        "97B0A49C3F969CC0" to "RoundRift.key",        // hash("sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin")
        // Native symbol from RoundRift KeyManager — appears in .so strings table:
        "6C15C44E0E05578E" to "RoundRift.native_sym", // hash("_zl6enckey")
        // Sahifa endpointi — fishing overlay'ni yuklaydi:
        "D6150650B1737562" to "RoundRift.endpoint",   // hash("/video/dropper.html")

        // ── Generic Markaziy Osiyo banker indicators ──
        // Klassik fishing path'lari paid-grade malware kit'larida:
        "E0F2417107ABA140" to "banker.sms_exfil",     // hash("/api/upload_sms")
        "54FED55FB5096A6C" to "banker.overlay_inject",// hash("/api/inject")
        "E9EE565416387AA1" to "banker.admin_panel"    // hash("/admin/banks")
    )

    /**
     * Сигнатуры в XOR+Base64. Используются для full-text search'а внутри ZIP entries:
     * мы декодируем их в memory один раз и ищем substring как раньше.
     * `strings` команда покажет только Base64-мусор.
     *
     * Чтобы сгенерировать новую запись:
     *   ObfuscatedSignatures.encodeForCode("scary-domain.tld")
     */
    private val XOR_ENCRYPTED_SIGNATURES: List<Pair<String, String>> = listOf(
        // (encoded, family_label) — УЖЕ ЗАКОДИРОВАННЫЕ строки (XOR 0x5A + Base64),
        // НЕ encode("plaintext"). Раньше тут стоял encode("frida-server") и аргумент
        // кодировался в рантайме → "frida-server" попадал в dex открытым текстом.
        // Теперь в dex только base64-мусор; открытый текст — в комментарии.
        // Сгенерировать новую запись: python scripts/obf_precompute.py
        // "/commends" — misspelled "commands", spetsifichno dlya etogo bot-semeystva.
        "dTk1Nzc/ND4p" to "bot_endpoint",                        // encode("/commends")
        "MD8uKTEz" to "jetski_family",                           // encode("jetski")

        // Anti-analysis check'lar (README §3.6 Ajina.Banker uchun klassik):
        "PCgzPjt3KT8oLD8o" to "anti.frida",                      // encode("frida-server")
        "PCgzPjt1PTs+PT8u" to "anti.frida",                      // encode("frida/gadget")
        "OTU3dC41KjA1MjQtL3Q3Oz0zKTE=" to "anti.magisk",         // encode("com.topjohnwu.magisk")
        "dT47Ljt1NjU5OzZ1LjcqdTwoMz47dyk/KCw/KA==" to "anti.frida", // encode("/data/local/tmp/frida-server")
        // VpnService aktivligini tekshiradi — fishing overlay'da "Disable VPN" so'raydi:
        "OzQ+KDUzPnQ0Py50DCo0CT8oLDM5Pw==" to "anti.vpn",        // encode("android.net.VpnService")

        // Banking trojan overlay infrastructure markerlari:
        "DTM0PjUtFzs0Oz0/KHQWOyM1Ly4KOyg7Nyk=" to "overlay.windowmgr",  // encode("WindowManager.LayoutParams")
        "DgMKHwUbCgoWExkbDhMVFAUVDB8IFhsD" to "overlay.type",    // encode("TYPE_APPLICATION_OVERLAY")
        // Accessibility orqali ekrandagi matnni o'qib OTP'ni topish:
        "Gzk5PykpMzgzNjMuIx8sPzQudA4DCh8FDBMfDQUOHwIOBRkSGxQdHx4=" to "overlay.text_grab" // encode("AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED")
    )

    /**
     * "YUMSHOQ" family teglari — bular YAKKA O'ZI DANGER BERMAYDI (obfuscatedSignature ni
     * yoqmaydi). Sabab (2026-07-09 panelda kuzatilgan ommaviy false-positive):
     * bu teglar generik Android API/xulq identifikatorlari bo'lib, MILLIONLAB HALOL ilovada
     * uchraydi:
     *   • overlay.windowmgr / overlay.type — har qanday overlay ishlatadigan ilovada
     *     (WindowManager.LayoutParams / TYPE_APPLICATION_OVERLAY) bor.
     *   • overlay.text_grab — accessibility ishlatadigan ilovalarda.
     *   • anti.magisk / anti.vpn / anti.frida — bank/o'yin/DRM ilovalari root/frida/VPN'ni
     *     QONUNIY tekshiradi (Facebook, Instagram, ELSA... shu tufayli "virus" bo'lib qolardi).
     *   • jetski_family — 6 belgili substring, DEX'ni ISO-8859-1 o'qiganda tasodifan mos keladi.
     * Ular baribir SCORE'ga hissa qo'shishi va detali sifatida ko'rsatilishi mumkin, lekin
     * yakuniy DANGER faqat QAT'IY IoC (domen/kalit/bot-nomi hash yoki bot-endpoint) bilan chiqadi.
     * Sideload malware baribir hard-IoC / dropper / permission-combo / random-pkg bilan ushlanadi.
     */
    val SOFT_FAMILIES: Set<String> = setOf(
        "overlay.windowmgr", "overlay.type", "overlay.text_grab",
        "anti.magisk", "anti.vpn", "anti.frida",
        "jetski_family",
    )

    /**
     * Family tegi QAT'IY (yakka o'zi DANGER beradigan) IoC'mi? Faqat malware'ga XOS bo'lgan
     * indikatorlar (aniq C2 domen/kalit hash'lari, bot-nomlar, "/commends" bot-endpoint kabi)
     * hard hisoblanadi; [SOFT_FAMILIES] generik API markerlaridir.
     */
    fun isHardFamily(family: String): Boolean = family !in SOFT_FAMILIES

    /**
     * "GENERIK BANKER" REST-yo'l teglari — bular texnik jihatdan HARD (SOFT_FAMILIES'da emas),
     * lekin ularning IoC'i shunchaki umumiy REST endpoint YO'LI (path) bo'lib, halol ilovada
     * ham (yoki UzGuard'ning O'ZIDA — uning DEX'ida LinkScanner.MALWARE_PATHS bu satrlarni
     * saqlaydi) uchrashi mumkin:
     *   • banker.overlay_inject = "/api/inject"
     *   • banker.sms_exfil      = "/api/upload_sms"
     *   • banker.admin_panel    = "/admin/banks"
     * Shu sabab (2026-07-11 ommaviy false-positive + o'z-o'zini DANGER qilish) bu uchtasi
     * YAKKA O'ZI TIER-1 DANGER bermasin — ApkScanner ularni faqat banker korroboratori bilan
     * (strongCombo / dropped .so / random-pkg / yashirin APK|DEX|ELF) yoqadi. Aniq malware'ga
     * XOS IoC'lar (C2 domen/kalit hash, bot-nomlar, "/commends" bot-endpoint) bu to'plamda EMAS
     * → ular baribir yakka o'zi TIER-1 chiqadi. LinkScanner (alohida URL-skan yo'li) bu yo'llarni
     * o'zgarishsiz HARD saqlaydi.
     */
    val GENERIC_BANKER_PATHS: Set<String> = setOf(
        "banker.overlay_inject", "banker.sms_exfil", "banker.admin_panel",
    )

    @Volatile private var decryptedCache: List<Pair<String, String>>? = null

    // PERF: matchTokenHashes() bitta DEX uchun o'n minglab token'ni hash qiladi. Avval HAR token
    // uchun MessageDigest.getInstance("SHA-256") (yangi obyekt) + "%02X".format() (String.format)
    // chaqirilardi → katta CPU/GC bosimi. Endi thread-local digest qayta ishlatiladi (skanlar
    // bir vaqtda ishlaydi — shuning uchun ThreadLocal, oddiy maydon emas) va hex lookup ishlatiladi.
    // Natija BAYT-AYNAN o'sha (katta harf, 16 hex) — ShieldTest/ObfuscatedSignaturesTest invariantlari saqlanadi.
    private val sha256Local = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** SHA-256 → first 16 hex chars (uppercase). */
    fun hash(s: String): String = hashLower(s.lowercase())

    /**
     * hash() ning ichki yo'li: argument ALLAQACHON lowercase bo'lishi SHART.
     * matchTokenHashes() har tokenni bir marta lowercase qiladi, shuning uchun bu yerda
     * qayta lowercase QILMAYMIZ — har token uchun ortiqcha String allokatsiyasini
     * (va CPU/GC bosimini) tejaymiz. Natija hash(s) bilan bayt-aynan bir xil.
     */
    private fun hashLower(lower: String): String {
        val md = sha256Local.get()!!
        md.reset()
        val digest = md.digest(lower.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4])
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Текстовая токен-сверка: вычленяем "значащие" токены (8+ chars без пробелов),
     * хэшируем, проверяем. Только exact match — никаких regex/word-boundary,
     * чтобы случайный кусок шифра не дал false positive.
     *
     * PERF (qizish — 2026-06-18, qurilmada o'lchangan): bitta DEX matni o'n minglab token
     * beradi, lekin ularning aksariyati TAKRORIY (bir xil tip/metod/string nomlari). Avval
     * HAR uchrash uchun SHA-256 hisoblanardi → ApkScanner.scan profilida eng issiq joy shu edi
     * (telefon qizib, protsessor 3+ yadroda band bo'lardi). Endi har UNIKAL token FAQAT BIR
     * marta hash qilinadi (seen to'plami) va lowercase ham bir marta (hashLower qayta
     * lowercase qilmaydi). Aniqlash BAYT-AYNAN o'sha: token to'plami va exact-match o'zgarmaydi.
     */
    fun matchTokenHashes(text: String): List<String> {
        val found = mutableSetOf<String>()
        val seen = HashSet<String>(2048)
        // Кандидаты: alnum + дефис/подчёркивание/точка/слэш, длина 4..128 (длинный run
        // режется на куски по 128, как это делал жадный {4,128}; хвост <4 отбрасывается).
        // PERF (qizish — 2026-07-09, qurilmada am profile bilan o'lchangan): avval bu yerda
        // Regex.findAll ishlatilardi. Har chaqiruvda ICU MatcherNative.setInput BUTUN matnni
        // (DEX uchun 8MB gacha) native buferga NUSXALAB olardi, va bu har entry uchun
        // takrorlanardi (APK'da 260 tagacha entry) — skan profilining ~70% shu edi.
        // Qo'lda yozilgan belgi-sinf skaneri nusxasiz ishlaydi; token to'plami BAYT-AYNAN o'sha.
        forEachToken(text) { tok ->
            if (seen.add(tok)) {   // bu token allaqachon hash qilingan — qayta hisoblamaymiz
                MALICIOUS_TOKEN_HASHES[hashLower(tok)]?.let { family -> found.add(family) }
            }
        }
        return found.toList()
    }

    /** Har topilgan token (allaqachon lowercase) uchun [action] chaqiriladi. */
    private inline fun forEachToken(text: String, action: (String) -> Unit) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isTokenChar(text[i])) { i++; continue }
            var j = i + 1
            while (j < n && j - i < 128 && isTokenChar(text[j])) j++
            if (j - i >= 4) action(text.substring(i, j).lowercase())
            i = j
        }
    }

    private fun isTokenChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '.' || c == '_' || c == '/' || c == '-'

    /** Faqat test uchun: tokenizator chiqishini regex-referens bilan solishtirish imkoni. */
    @androidx.annotation.VisibleForTesting
    internal fun tokensOf(text: String): List<String> {
        val out = ArrayList<String>()
        forEachToken(text) { out.add(it) }
        return out
    }

    /** Декодированные XOR-сигнатуры с лейблами. Декодируется один раз, кэшируется. */
    fun decryptedSignatures(): List<Pair<String, String>> {
        decryptedCache?.let { return it }
        val list = XOR_ENCRYPTED_SIGNATURES.map { (enc, label) -> decode(enc) to label }
        decryptedCache = list
        return list
    }

    // PERF (qizish — 2026-07-09, qurilmada am profile bilan o'lchangan): avval har sig uchun
    // text.contains(sig, ignoreCase=true) chaqirilardi — bu HAR BELGI uchun
    // Character.toUpperCase/toLowerCase qiladi va 8MB matnni 10+ marta aylanib chiqadi
    // (profilda regionMatches+to*Case eng issiq joy edi). Endi matn BIR marta lowercase
    // qilinadi, sig'lar oldindan lowercase keshlanadi → oddiy (case-siz) indexOf.
    // Sig'lar ASCII — amaliy detektsiya o'zgarmaydi (farq faqat ekzotik Unicode
    // case-fold burchaklarida: masalan Kelvin U+212A, dotted İ — DEX IoC uchun ahamiyatsiz).
    @Volatile private var loweredSigCache: List<Pair<String, String>>? = null

    private fun loweredSignatures(): List<Pair<String, String>> {
        loweredSigCache?.let { return it }
        val list = decryptedSignatures().map { (sig, label) -> sig.lowercase() to label }
        loweredSigCache = list
        return list
    }

    /** Поиск декодированных сигнатур в строке. Возвращает найденные family-метки. */
    fun matchDecrypted(text: String): List<String> {
        val found = mutableSetOf<String>()
        val lowered = text.lowercase()
        for ((sigLower, label) in loweredSignatures()) {
            if (lowered.contains(sigLower)) {
                found.add(label)
            }
        }
        return found.toList()
    }

    /** Encode helper — не используется в продакшене, для генерации констант. */
    @androidx.annotation.VisibleForTesting
    internal fun encode(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(bytes.size) { (bytes[it].toInt() xor XOR_KEY.toInt()).toByte() }
        return android.util.Base64.encodeToString(xored, android.util.Base64.NO_WRAP)
    }

    @androidx.annotation.VisibleForTesting
    internal fun decode(enc: String): String {
        val xored = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val bytes = ByteArray(xored.size) { (xored[it].toInt() xor XOR_KEY.toInt()).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    /** Удобная функция для генератора: показывает hash + encode для новой сигнатуры. */
    @androidx.annotation.VisibleForTesting
    internal fun encodeForCode(plain: String): String {
        return "hash=${hash(plain)}, encoded=${encode(plain)}"
    }
}
