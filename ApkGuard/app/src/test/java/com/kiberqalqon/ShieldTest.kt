package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Shield] keystream-shifri testlari.
 *
 * Eng muhim qism — "known-answer" vektorlar: scripts/shield_encode.py CHIQARGAN
 * hex literallar shu yerda AYNAN kutilgan plaintext'ga ochilishi shart. Agar Kotlin
 * [Shield] va Python generator bayt-ma-bayt mos kelmasa (kalit/algoritm farqi),
 * bu testlar yiqiladi — ya'ni IOC bazalari jim qolib ketishidan oldin tutamiz.
 */
class ShieldTest {

    @Test
    fun roundTrip_variousStrings() {
        val samples = listOf(
            "frida", "com.topjohnwu.magisk", "/system/bin/su",
            "Ajina.Banker", "Uzbek-dropper.taklifnoma",
            "75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215",
            "", "a", "юникод-тест", "ko'p so'zli matn 123"
        )
        for (s in samples) {
            assertEquals("round-trip buzildi: '$s'", s, Shield.dec(Shield.enc(s)))
        }
    }

    /** Python generator chiqargan literal Kotlin'da to'g'ri ochiladimi (cross-check). */
    @Test
    fun knownVectors_matchPythonGenerator() {
        assertEquals("frida", Shield.dec("626c5e339a"))
        assertEquals("Ajina.Banker", Shield.dec("45745e399a71542f556b7f5b"))
        assertEquals("com.topjohnwu.magisk", Shield.dec("67715a798f3066245468745e74418a1f388ae2e5"))
        assertEquals("/data/adb", Shield.dec("2b7a56239a70772a59"))
    }

    @Test
    fun encOutput_isLowercaseHexEvenLength() {
        val hex = Shield.enc("hello world")
        assertTrue("hex bo'lishi kerak", hex.matches(Regex("[0-9a-f]+")))
        assertEquals("juft uzunlik", 0, hex.length % 2)
    }

    /** Shifrlangan IOC bazalari runtime'da to'g'ri ochilishini tekshiramiz. */
    @Test
    fun iocDatabases_decodeToExpectedFamilies() {
        // MaliciousHashes — ma'lum namuna ENTRIES'da topilsin (ThreatDb fallback'siz).
        assertEquals(
            "Ajina.Banker.lzthzvxte",
            MaliciousHashes.maliciousFamily(
                "75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215"
            )
        )
        // MaliciousPackages
        assertEquals(
            "Uzbek-dropper.taklifnoma",
            MaliciousPackages.maliciousFamily("uzbekchill.com")
        )
        // MaliciousCerts
        assertEquals(
            "Uzbek-dropper.vudgi",
            MaliciousCerts.maliciousFamily(
                "954ee710c4419d1be570a9874e5460650c014f9897cc454b3da02bc80c8ab42d"
            )
        )
        // Hammasi to'liq ochildimi — curated sanog'i kutilganidek bo'lsin.
        assertEquals(16, MaliciousHashes.curatedCount())
        assertEquals(2, MaliciousCerts.curatedCount())
    }
}
