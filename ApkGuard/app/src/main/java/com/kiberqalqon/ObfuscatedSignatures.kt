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
    fun hash(s: String): String {
        val md = sha256Local.get()!!
        md.reset()
        val digest = md.digest(s.lowercase().toByteArray(Charsets.UTF_8))
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
     */
    fun matchTokenHashes(text: String): List<String> {
        val found = mutableSetOf<String>()
        // Извлекаем кандидаты: alnum + дефис/подчёркивание/точка/слэш, длина 4+
        val tokenRegex = Regex("[A-Za-z0-9._/\\-]{4,128}")
        for (m in tokenRegex.findAll(text)) {
            val tok = m.value.lowercase()
            val h = hash(tok)
            MALICIOUS_TOKEN_HASHES[h]?.let { family ->
                if (found.add(family)) {
                    // first match per family — достаточно
                }
            }
        }
        return found.toList()
    }

    /** Декодированные XOR-сигнатуры с лейблами. Декодируется один раз, кэшируется. */
    fun decryptedSignatures(): List<Pair<String, String>> {
        decryptedCache?.let { return it }
        val list = XOR_ENCRYPTED_SIGNATURES.map { (enc, label) -> decode(enc) to label }
        decryptedCache = list
        return list
    }

    /** Поиск декодированных сигнатур в строке. Возвращает найденные family-метки. */
    fun matchDecrypted(text: String): List<String> {
        val found = mutableSetOf<String>()
        for ((sig, label) in decryptedSignatures()) {
            if (text.contains(sig, ignoreCase = true)) {
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
