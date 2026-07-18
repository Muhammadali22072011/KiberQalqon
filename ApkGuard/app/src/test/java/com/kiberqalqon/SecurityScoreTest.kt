package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecurityScore] — qurilma xavfsizlik holatidan 0–100 ball. SOF yadro [evaluate].
 */
class SecurityScoreTest {

    private val perfect = SecurityScore.Posture(
        screenLockSet = true,
        backgroundProtection = true,
        dangerousAppCount = 0,
        remoteAccessCount = 0,
        vpnFilter = true,
        usbDebugging = false,
    )

    @Test
    fun perfectPosture_is100_noIssues() {
        val r = SecurityScore.evaluate(perfect)
        assertEquals(100, r.score)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun worstPosture_is0_allIssues() {
        val r = SecurityScore.evaluate(
            SecurityScore.Posture(
                screenLockSet = false,
                backgroundProtection = false,
                dangerousAppCount = 3,
                remoteAccessCount = 1,
                vpnFilter = false,
                usbDebugging = true,
            )
        )
        assertEquals(0, r.score)
        assertEquals(6, r.issues.size)
        // Eng og'iri (25 ballli ekran qulfi) birinchi.
        assertEquals("lock", r.issues.first().id)
    }

    @Test
    fun missingScreenLock_subtracts25() {
        val r = SecurityScore.evaluate(perfect.copy(screenLockSet = false))
        assertEquals(75, r.score)
        assertEquals(1, r.issues.size)
        assertEquals("lock", r.issues[0].id)
    }

    @Test
    fun dangerousApp_subtracts20_andReportsCount() {
        val r = SecurityScore.evaluate(perfect.copy(dangerousAppCount = 2))
        assertEquals(80, r.score)
        assertTrue(r.issues.any { it.id == "danger" && it.title.contains("2") })
    }

    @Test
    fun band_thresholds() {
        assertEquals("safe", SecurityScore.band(80))
        assertEquals("warn", SecurityScore.band(50))
        assertEquals("danger", SecurityScore.band(49))
    }
}
