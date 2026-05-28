package com.kiberqalqon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты blacklist цифровых подписей. Здесь зашиты SHA-256 двух реально
 * проанализированных вирусов из репо — проверяем что они детектятся.
 */
class MaliciousCertsTest {

    @Test
    fun knownTaklifnomaVirus_isDetected() {
        val fp = "5db8a5668648061fc388a43df97863a1be3cc9b20764cf5b752b5357a976c307"
        assertEquals("Uzbek-dropper.taklifnoma", MaliciousCerts.maliciousFamily(fp))
    }

    @Test
    fun knownVudgiVirus_isDetected() {
        val fp = "954ee710c4419d1be570a9874e5460650c014f9897cc454b3da02bc80c8ab42d"
        assertEquals("Uzbek-dropper.vudgi", MaliciousCerts.maliciousFamily(fp))
    }

    @Test
    fun caseInsensitive_uppercaseAlsoMatches() {
        // Принимаем и uppercase — это частая ошибка в строках из keytool.
        val fp = "5DB8A5668648061FC388A43DF97863A1BE3CC9B20764CF5B752B5357A976C307"
        assertEquals("Uzbek-dropper.taklifnoma", MaliciousCerts.maliciousFamily(fp))
    }

    @Test
    fun unknownFingerprint_returnsNull() {
        val randomFp = "0000000000000000000000000000000000000000000000000000000000000000"
        assertNull(MaliciousCerts.maliciousFamily(randomFp))
    }

    @Test
    fun blankInput_returnsNull() {
        assertNull(MaliciousCerts.maliciousFamily(""))
        assertNull(MaliciousCerts.maliciousFamily(null))
    }
}
