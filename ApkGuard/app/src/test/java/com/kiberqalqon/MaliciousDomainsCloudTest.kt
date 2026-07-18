package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bulut feed'idan kelgan domen uchun suffiks moslik: egasi paneldan "evil.com" qo'shsa,
 * "www.evil.com" va istalgan chuqur subdomen ham bloklanishi shart (foydalanuvchi xatosi:
 * www bilan ochilsa o'tib ketardi). Yakka TLD hech qachon mos kelmaydi.
 *
 * ThreatDb — protsess-yagona singleton; boshqa testlarga ta'sir qilmaslik uchun
 * faqat shu testga xos noyob domen ishlatiladi.
 */
class MaliciousDomainsCloudTest {

    private val dom = "kqtest-evil-feed-domain.com"

    @Test
    fun cloudDomainMatchesExactAndSubdomains() {
        ThreatDb.mergeCloudDomains(mapOf(dom to "Test.feed"))

        assertEquals("Test.feed", MaliciousDomains.maliciousFamily(dom))
        assertEquals("Test.feed", MaliciousDomains.maliciousFamily("www.$dom"))
        assertEquals("Test.feed", MaliciousDomains.maliciousFamily("a.b.c.$dom"))
        // Katta harf + trailing nuqta normallashtiriladi.
        assertEquals("Test.feed", MaliciousDomains.maliciousFamily("WWW.${dom.uppercase()}."))
    }

    @Test
    fun unrelatedHostsDoNotMatch() {
        ThreatDb.mergeCloudDomains(mapOf(dom to "Test.feed"))

        // O'xshash, lekin boshqa registrable domen — mos kelmasin (endsWith xatosi emas).
        assertNull(MaliciousDomains.maliciousFamily("not$dom"))
        // Yakka TLD so'ralmaydi.
        assertNull(MaliciousDomains.maliciousFamily("com"))
        assertNull(MaliciousDomains.maliciousFamily(null))
        assertNull(MaliciousDomains.maliciousFamily(""))
    }

    /**
     * Feed'ga XATO bilan butun ommaviy zona (netlify.app) kiritilsa ham, suffiks-yurish
     * uni ZONA darajasida moslab, har bir *.netlify.app saytini bloklab qo'ymasligi shart.
     * Aksincha — aniq subdomen yozuvi (evil.netlify.app) o'zini va o'z subdomenlarini bloklaydi.
     */
    @Test
    fun publicSuffixZoneEntryDoesNotBlockWholeZone() {
        ThreatDb.mergeCloudDomains(mapOf("netlify.app" to "Bad.zone"))
        // Zona o'zi yozilgan bo'lsa ham — begona sayt o'sha zonada bloklanmaydi.
        assertNull(MaliciousDomains.maliciousFamily("innocent-shop.netlify.app"))
        assertNull(MaliciousDomains.maliciousFamily("netlify.app"))
    }

    @Test
    fun specificSubdomainUnderPublicSuffixStillBlocks() {
        ThreatDb.mergeCloudDomains(mapOf("kqphish.netlify.app" to "Phish.test"))
        assertEquals("Phish.test", MaliciousDomains.maliciousFamily("kqphish.netlify.app"))
        // Aniq saytning subdomeni ham bloklanadi (suffiks yurishi shu yozuvga yetadi).
        assertEquals("Phish.test", MaliciousDomains.maliciousFamily("login.kqphish.netlify.app"))
        // Lekin yondosh boshqa sayt — yo'q.
        assertNull(MaliciousDomains.maliciousFamily("other.netlify.app"))
    }
}
