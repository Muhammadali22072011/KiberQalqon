package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PermissionXray.scoreOf — sof skorlash yadrosi (Android'siz, PermissionCombos ustida).
 * Skorlash mantig'ini o'zgartirsang, bu testlar yo'nalishni ushlab turadi.
 */
class PermissionXrayTest {

    private val P = "android.permission."

    private fun perms(vararg short: String): Set<String> = short.map { "$P$it" }.toSet()

    @Test
    fun fullBanker_highScore() {
        // SYSTEM_ALERT_WINDOW + BIND_ACCESSIBILITY_SERVICE + INTERNET = Full banker (100).
        // Bu bilan boshqa kombo'lar ham yonib, jami juda yuqori bo'ladi.
        val (score, labels) = PermissionXray.scoreOf(
            perms("SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE", "INTERNET")
        )
        assertTrue("full-banker yuqori bo'lishi kerak, lekin $score", score >= 100)
        assertTrue("Full banker kombosi ko'rinishi kerak", labels.any { it.contains("Full banker") })
    }

    @Test
    fun otpGrabber_highScore() {
        // READ_SMS + BIND_ACCESSIBILITY_SERVICE = OTP-grabber (90).
        val (score, _) = PermissionXray.scoreOf(perms("READ_SMS", "BIND_ACCESSIBILITY_SERVICE"))
        assertTrue("OTP-grabber >= 60 (xavfli chegara), lekin $score", score >= 60)
    }

    @Test
    fun benignApp_lowScore() {
        // Oddiy ilova: internet + tarmoq holati + xotira — hech qanday zararli kombo yo'q.
        val (score, labels) = PermissionXray.scoreOf(
            perms("INTERNET", "ACCESS_NETWORK_STATE", "READ_EXTERNAL_STORAGE", "ACCESS_WIFI_STATE")
        )
        assertEquals("benign 0 bo'lishi kerak", 0, score)
        assertTrue("benign'da kombo bo'lmasligi kerak", labels.isEmpty())
    }

    @Test
    fun benignCameraApp_belowWarnThreshold() {
        // Video-chat ilovasi: kamera+mikrofon+geo+internet — qayta kalibrlangan past-ball kombo (15).
        val (score, _) = PermissionXray.scoreOf(
            perms("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "INTERNET")
        )
        assertTrue("legit kamera-ilova warn chegarasidan (25) past bo'lishi kerak, lekin $score", score < 25)
    }

    @Test
    fun deviceAdminOnly_excludedByEng03() {
        // Faqat BIND_DEVICE_ADMIN (legit MDM / Find-My-Device) — Ransomware-kombo
        // ENG-03 bo'yicha chiqarib tashlanadi, ball 0 bo'lib qoladi.
        val (score, labels) = PermissionXray.scoreOf(perms("BIND_DEVICE_ADMIN"))
        assertEquals("faqat-device-admin ENG-03 bilan chiqariladi", 0, score)
        assertTrue("Ransomware-kombo labellarda bo'lmasligi kerak", labels.isEmpty())
    }

    @Test
    fun deviceAdminPlusMaliciousCombo_counted() {
        // Device-admin + MUSTAQIL zararli kombo (OTP-grabber) — Ransomware'ning O'ZI emas,
        // shuning uchun mustaqil kombo ball bo'yicha hisoblanadi.
        val (score, _) = PermissionXray.scoreOf(
            perms("BIND_DEVICE_ADMIN", "READ_SMS", "BIND_ACCESSIBILITY_SERVICE")
        )
        assertTrue("device-admin + OTP-grabber yuqori bo'lishi kerak, lekin $score", score >= 90)
    }

    @Test
    fun emptyPerms_zeroScore() {
        val (score, labels) = PermissionXray.scoreOf(emptySet())
        assertEquals(0, score)
        assertTrue(labels.isEmpty())
    }
}
