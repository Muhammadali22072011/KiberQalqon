package com.uzguard

import java.security.MessageDigest

/**
 * Shield — runtime-deobfuskatsiya strok.
 *
 * Maqsad: `strings uzguard.apk | grep` IOC'larni (zararli APK xeshlari,
 * paketlar, sertifikatlar) va anti-tamper markerlarini (frida, magisk, su...)
 * KO'RSATMASIN. DEX ichida faqat hex-"axlat" yotadi — u oddiy SHA-256 xeshlardan
 * farq qilmaydi. Haqiqiy qiymat faqat xotirada ochiladi.
 *
 * Shifr: keystream-XOR. keystream = SHA-256(KEY || counterBE) 32-baytli bloklar.
 * Bu maxfiy kalitni himoyalovchi kriptografiya EMAS (kalit baribir APK ichida) —
 * uning vazifasi statik tahlilni yengish va reverse narxini oshirish.
 *
 * Generator (bayt-ma-bayt mos kelishi SHART): scripts/shield_encode.py
 *
 * Toza JVM (android.* ishlatilmaydi) — shuning uchun oddiy JUnit bilan
 * tekshiriladi (ShieldTest). minSdk 24'da android.util.Base64 muammosi yo'q,
 * chunki bu yerda faqat hex ishlatiladi.
 */
internal object Shield {

    // Kalit runtime'da bo'laklardan yig'iladi — DEX ichida bitta ravshan
    // "KEY=..." literali bo'lmasin. Bo'laklar ma'nosiz build-shovqiniga o'xshaydi.
    private const val P1 = "qz7"
    private const val P2 = "4fx9"
    private const val P3 = "2k"

    private val KEY: ByteArray by lazy {
        sha256((P1 + P2 + P3).toByteArray(Charsets.UTF_8))
    }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    /** keystream = SHA-256(KEY || counter_big_endian) bloklari, n baytgacha. */
    private fun keystream(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        var counter = 0
        while (off < n) {
            val ctr = byteArrayOf(
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte()
            )
            val block = sha256(KEY + ctr)
            val take = minOf(block.size, n - off)
            System.arraycopy(block, 0, out, off, take)
            off += take
            counter++
        }
        return out
    }

    private fun xorHex(hex: String): ByteArray {
        val data = hexToBytes(hex)
        val ks = keystream(data.size)
        return ByteArray(data.size) { (data[it].toInt() xor ks[it].toInt()).toByte() }
    }

    /** hex-literalni stringga ochish. scripts/shield_encode.py bilan mos. */
    fun dec(hex: String): String = String(xorHex(hex), Charsets.UTF_8)

    /** stringni hex'ga shifrlash. Faqat test/generatsiya uchun (prodda kerak emas). */
    fun enc(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        val ks = keystream(data.size)
        val out = ByteArray(data.size) { (data[it].toInt() xor ks[it].toInt()).toByte() }
        return toHex(out)
    }

    /**
     * Ro'yxatni ochish. Har qanday xato → bo'sh ro'yxat (fail-open): chaqiruvchi
     * `.any { ... }` qiladi, bo'sh ro'yxat hech narsani topmaydi — ya'ni noto'g'ri
     * pozitiv (begunoh foydalanuvchini bloklash) BO'LMAYDI. Antivirusda eng yomon
     * xato — soxta DANGER, shuning uchun shu yo'nalishda fail qilamiz.
     */
    fun decList(vararg hexes: String): List<String> =
        try { hexes.map { dec(it) } } catch (_: Throwable) { emptyList() }

    /** Map ochish: kalit→qiymat juftliklari. Xato → bo'sh map (fail-open). */
    fun decMap(vararg pairs: Pair<String, String>): Map<String, String> =
        try {
            HashMap<String, String>(pairs.size * 2).apply {
                for ((k, v) in pairs) put(dec(k), dec(v))
            }
        } catch (_: Throwable) { emptyMap() }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val n = clean.length / 2
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            out[i] = (((hi shl 4) or lo) and 0xFF).toByte()
            i++
        }
        return out
    }

    private val HEXCH = "0123456789abcdef".toCharArray()
    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEXCH[(x.toInt() ushr 4) and 0x0F])
            sb.append(HEXCH[x.toInt() and 0x0F])
        }
        return sb.toString()
    }
}
