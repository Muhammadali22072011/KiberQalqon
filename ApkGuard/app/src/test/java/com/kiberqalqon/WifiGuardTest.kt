package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WifiGuard] — ochiq (parolsiz) tarmoq aniqlash. SOF yadro [isOpenCapabilities]
 * scanResult.capabilities satridan shifrlashsiz tarmoqni ajratadi.
 */
class WifiGuardTest {

    @Test
    fun open_network_capabilities() {
        assertTrue(WifiGuard.isOpenCapabilities("[ESS]"))
        assertTrue(WifiGuard.isOpenCapabilities("[ESS][WPS]"))  // WPS shifrlash emas
    }

    @Test
    fun secured_networks_not_open() {
        assertFalse(WifiGuard.isOpenCapabilities("[WPA2-PSK-CCMP][ESS]"))
        assertFalse(WifiGuard.isOpenCapabilities("[WPA-PSK-TKIP][ESS]"))
        assertFalse(WifiGuard.isOpenCapabilities("[WPA3-SAE][ESS]"))
        assertFalse(WifiGuard.isOpenCapabilities("[WEP][ESS]"))
        assertFalse(WifiGuard.isOpenCapabilities("[RSN-EAP-CCMP][ESS]"))
        // OWE (Enhanced Open) — parolsiz lekin shifrlangan → ochiq deb hisoblamaymiz.
        assertFalse(WifiGuard.isOpenCapabilities("[OWE][ESS]"))
    }

    @Test
    fun blank_capabilities_not_open() {
        assertFalse(WifiGuard.isOpenCapabilities(null))
        assertFalse(WifiGuard.isOpenCapabilities(""))
    }

    @Test
    fun securityType_open() {
        assertTrue(WifiGuard.isOpenSecurityType(0))   // SECURITY_TYPE_OPEN
        assertFalse(WifiGuard.isOpenSecurityType(2))  // SECURITY_TYPE_PSK
        assertFalse(WifiGuard.isOpenSecurityType(-1)) // UNKNOWN
    }

    @Test
    fun ssid_cleaning() {
        assertEquals("MyWifi", WifiGuard.cleanSsid("\"MyWifi\""))
        assertEquals("Cafe", WifiGuard.cleanSsid("Cafe"))
        assertNull(WifiGuard.cleanSsid("<unknown ssid>"))
        assertNull(WifiGuard.cleanSsid(null))
        assertNull(WifiGuard.cleanSsid(""))
    }
}
