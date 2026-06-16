package com.uzguard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ObfuscatedSignatures precomputed literallari to'g'riligini tekshiradi.
 *
 * Endi map'lar `hash("plain")`/`encode("plain")` o'rniga OLDINDAN hisoblangan
 * literallarni saqlaydi (plaintext dex'dan olib tashlangan).
 *
 * DIQQAT: bu yerda faqat matchTokenHashes() tekshiriladi — u toza JVM'da ishlaydi
 * (hash() = java.security.MessageDigest). matchDecrypted()/decode() esa
 * android.util.Base64'ga tayanadi, u oddiy JUnit'da yo'q (RuntimeException beradi),
 * shuning uchun XOR-bloblar Python round-trip (scripts/obf_precompute.py) va
 * APK skani (scripts/shield_apk_check.py) orqali tasdiqlanadi.
 */
class ObfuscatedSignaturesTest {

    @Test
    fun tokenHash_matchesPrecomputedKey() {
        // Token runtime'da hash()'lanadi va precomputed kalitga tushishi kerak.
        // Mos kelmasa — precomputed literal noto'g'ri (Python ↔ Kotlin farqi).
        assertTrue(
            ObfuscatedSignatures.matchTokenHashes("please open uzbekchill.com now")
                .contains("uzbekchill")
        )
        assertTrue(
            ObfuscatedSignatures.matchTokenHashes("c2 host elrxzx.com beacon")
                .contains("Ajina.Banker.C2")
        )
        assertTrue(
            ObfuscatedSignatures.matchTokenHashes("dropper at ydbllnjd.com here")
                .contains("RoundRift.dropper")
        )
    }

    @Test
    fun cleanText_noTokenFalsePositive() {
        assertTrue(ObfuscatedSignatures.matchTokenHashes("ordinary harmless words here").isEmpty())
    }
}
