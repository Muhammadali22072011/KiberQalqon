package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteAccessDetector] — masofaviy boshqaruv / ekran-ulashish ilovalari (firibgarlik
 * vektori). SOF yadro [matchPackages] paket to'plamidan xavflilarni ajratadi.
 */
class RemoteAccessDetectorTest {

    @Test
    fun matches_anydesk_and_teamviewer() {
        val installed = setOf(
            "com.anydesk.anydeskandroid",
            "com.teamviewer.teamviewer.market.mobile",
            "com.whatsapp",                 // aloqasiz — chiqmasin
            "uz.dida.payme",
        )
        val found = RemoteAccessDetector.matchPackages(installed)
        val brands = found.map { it.brand }
        assertTrue("AnyDesk topilishi kerak", brands.contains("AnyDesk"))
        assertTrue("TeamViewer topilishi kerak", brands.contains("TeamViewer"))
        assertEquals(2, found.size)
    }

    @Test
    fun emptyWhenNoneInstalled() {
        val installed = setOf("com.whatsapp", "org.telegram.messenger", "uz.click.evo")
        assertTrue(RemoteAccessDetector.matchPackages(installed).isEmpty())
    }

    @Test
    fun rustdesk_package_recognized() {
        val found = RemoteAccessDetector.matchPackages(setOf("com.carriez.flutter_hbb"))
        assertEquals(1, found.size)
        assertEquals("RustDesk", found[0].brand)
    }
}
