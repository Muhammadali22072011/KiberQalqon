package com.kiberqalqon

import com.kiberqalqon.ScanResult.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sof JVM-test: [LinkScanner.analyze] Android API'ga tegmaydi (java.net.* / java.util.*).
 *
 * ANTIVIRUS OLTIN QOIDASI bu yerda PINlanadi: hech qachon noto'g'ri "XAVFSIZ" — bo'sh /
 * o'qib bo'lmaydigan / shubhali kirish DOIM SUSPICIOUS yoki DANGER, hech qachon SAFE.
 *
 * Eslatma: [MaliciousDomains.maliciousFamily] curated ro'yxatni avval tekshiradi (ThreatDb
 * yuklanmagan unit-testda ham ishlaydi — CURATED Kotlin map'da), shuning uchun C2 domenlari
 * tarmoqsiz aniqlanadi.
 */
class LinkScannerTest {

    // ─────────── DANGER ───────────

    @Test
    fun typosquatBank_paymeUzCom_isDanger() {
        val r = LinkScanner.analyze("payme-uz.com")
        assertEquals("payme-uz.com bank typosquat'i — XAVFLI bo'lishi shart", Verdict.DANGER, r.verdict)
        assertTrue("TYPOSQUAT_BANK belgisi bo'lishi kerak", r.reasons.contains(LinkScanner.Flag.TYPOSQUAT_BANK))
    }

    @Test
    fun typosquatBank_clickPayNet_isDanger() {
        val r = LinkScanner.analyze("http://click-pay.net/login")
        assertEquals("click-pay.net — bank lookalike, XAVFLI", Verdict.DANGER, r.verdict)
    }

    @Test
    fun blacklistedC2_dashappV2_isDanger() {
        val r = LinkScanner.analyze("https://dashapp-v2.org/api/inject")
        assertEquals("Curated C2 domeni — XAVFLI", Verdict.DANGER, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.BLACKLISTED_DOMAIN))
    }

    @Test
    fun blacklistedC2_subdomain_isDanger() {
        // Suffiks moslik: subdomen ham bloklanadi (host.endsWith(".$it")).
        val r = LinkScanner.analyze("https://login.elrxzx.com/")
        assertEquals("C2 subdomeni ham XAVFLI", Verdict.DANGER, r.verdict)
    }

    @Test
    fun punycode_xnPrefix_isDanger() {
        // xn-- punycode (homoglyph hujumi) — masalan soxta "payme" kirill harflar bilan.
        val r = LinkScanner.analyze("https://xn--pyme-loa.com/")
        assertEquals("Punycode/homoglyph — XAVFLI", Verdict.DANGER, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.PUNYCODE_HOMOGLYPH))
    }

    @Test
    fun homoglyph_nonAsciiHost_isDanger() {
        // Kirill 'а' (U+0430) lotin 'a' o'rniga — "pаyme.uz" (а — kirill).
        val r = LinkScanner.analyze("https://pаyme.uz/")
        assertEquals("ASCII bo'lmagan harf (homoglyph) — XAVFLI", Verdict.DANGER, r.verdict)
    }

    // ─────────── SAFE ───────────

    @Test
    fun plainHttps_google_isSafe() {
        val r = LinkScanner.analyze("https://google.com")
        assertEquals("Toza HTTPS domen — XAVFSIZ", Verdict.SAFE, r.verdict)
        assertTrue("Hech qanday belgi bo'lmasligi kerak", r.reasons.isEmpty())
    }

    @Test
    fun realBank_clickUz_isSafe() {
        // Haqiqiy bank domeni: "click" brendi noto'g'ri ogohlantirmasligi kerak.
        val r = LinkScanner.analyze("https://click.uz")
        assertEquals("Haqiqiy bank domeni click.uz — XAVFSIZ", Verdict.SAFE, r.verdict)
    }

    @Test
    fun realBank_paymeUz_isSafe() {
        val r = LinkScanner.analyze("https://payme.uz/")
        assertEquals("Haqiqiy payme.uz — XAVFSIZ", Verdict.SAFE, r.verdict)
    }

    @Test
    fun realBank_subdomain_isSafe() {
        val r = LinkScanner.analyze("https://my.kapitalbank.uz/login")
        assertEquals("Haqiqiy bank subdomeni — XAVFSIZ", Verdict.SAFE, r.verdict)
    }

    @Test
    fun realBank_deepSubdomain_isNotSuspicious() {
        // login.secure.kapitalbank.uz — 4 yorliq, lekin registrable domen aynan bank tokeni +
        // ishonchli TLD → EXCESSIVE_SUBDOMAINS bostiriladi, yolg'iz zaif belgi SHUBHALI bermaydi.
        val r = LinkScanner.analyze("https://login.secure.kapitalbank.uz")
        assertEquals("Chuqur rasmiy bank subdomeni — XAVFSIZ", Verdict.SAFE, r.verdict)
    }

    // ─────────── Noto'g'ri DANGER bo'lmasligi: uzun inglizcha so'zlar (substring FP) ───────────

    @Test
    fun legitWord_clickhouse_isNotDanger() {
        // clickhouse.com — "click" tokenini O'Z ICHIGA OLADI, lekin uzun harfli so'z →
        // qattiq TYPOSQUAT_BANK / DANGER bermasligi shart (mijoz ishonchini buzadi).
        val r = LinkScanner.analyze("https://clickhouse.com")
        org.junit.Assert.assertTrue(
            "clickhouse.com hech qachon XAVFLI bo'lmasligi kerak", r.verdict != Verdict.DANGER
        )
        org.junit.Assert.assertTrue(
            "clickhouse.com TYPOSQUAT_BANK belgisini olmasligi kerak",
            !r.reasons.contains(LinkScanner.Flag.TYPOSQUAT_BANK)
        )
    }

    @Test
    fun legitWord_clickup_isNotDanger() {
        val r = LinkScanner.analyze("https://clickup.com")
        org.junit.Assert.assertTrue(
            "clickup.com XAVFLI bo'lmasligi kerak", r.verdict != Verdict.DANGER
        )
    }

    @Test
    fun legitWord_payments_isNotDanger() {
        val r = LinkScanner.analyze("https://payments.com")
        org.junit.Assert.assertTrue(
            "payments.com XAVFLI bo'lmasligi kerak", r.verdict != Verdict.DANGER
        )
    }

    @Test
    fun shortToken_editDistance_osaka_isNotDanger() {
        // osaka.com — "asaka" (5 harf) tokenidan Levenshtein 1, lekin endi qisqa tokenda byudjet 1
        // bo'lsa-da, faqat tahrir-masofasi DANGER bermasligi uchun: osaka ham, asana ham real domen.
        val r = LinkScanner.analyze("https://osaka.com")
        org.junit.Assert.assertTrue(
            "osaka.com (asaka emas) XAVFLI bo'lmasligi kerak", r.verdict != Verdict.DANGER
        )
    }

    @Test
    fun shortToken_editDistance_asana_isNotDanger() {
        val r = LinkScanner.analyze("https://asana.com")
        org.junit.Assert.assertTrue(
            "asana.com (real SaaS) XAVFLI bo'lmasligi kerak", r.verdict != Verdict.DANGER
        )
    }

    // ─────────── SUSPICIOUS (hech qachon SAFE) ───────────

    @Test
    fun blank_isSuspicious_neverSafe() {
        val r = LinkScanner.analyze("")
        assertEquals("Bo'sh kirish — SHUBHALI (HECH QACHON XAVFSIZ EMAS)", Verdict.SUSPICIOUS, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.UNPARSABLE))
    }

    @Test
    fun whitespaceOnly_isSuspicious() {
        val r = LinkScanner.analyze("    ")
        assertEquals("Faqat probel — SHUBHALI", Verdict.SUSPICIOUS, r.verdict)
    }

    @Test
    fun garbage_isSuspicious_neverSafe() {
        val r = LinkScanner.analyze("salom bu havola emas")
        assertEquals("Axlat matn — SHUBHALI (XAVFSIZ EMAS)", Verdict.SUSPICIOUS, r.verdict)
    }

    @Test
    fun nonHttpScheme_javascript_isSuspicious() {
        val r = LinkScanner.analyze("javascript:alert(1)")
        assertEquals("https?:// dan boshqa sxema — SHUBHALI", Verdict.SUSPICIOUS, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.UNPARSABLE))
    }

    @Test
    fun ipLiteralNonHttps_isSuspicious() {
        // IP-literal + HTTPS emas → ikkita zaif belgi → SHUBHALI.
        val r = LinkScanner.analyze("http://192.168.1.50/admin/banks")
        assertEquals("IP host + http + moliyaviy so'z — SHUBHALI", Verdict.SUSPICIOUS, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.IP_LITERAL_HOST))
    }

    @Test
    fun atInUrlAndExcessiveSubdomains_isSuspicious() {
        // '@' yashirish + ko'p subdomen → ko'p zaif belgi → SHUBHALI.
        val r = LinkScanner.analyze("https://google.com@a.b.c.evil.net/")
        assertEquals("'@' yashirish hiylasi — SHUBHALI", Verdict.SUSPICIOUS, r.verdict)
        assertTrue(r.reasons.contains(LinkScanner.Flag.AT_IN_URL))
    }

    @Test
    fun bankBrandWrongTld_paymeTop_isSuspicious() {
        // payme.top — brend ishonchsiz TLD'da → LOOKALIKE_TLD + SUSPICIOUS_TLD → SHUBHALI.
        val r = LinkScanner.analyze("https://payme.top/")
        assertEquals("Bank brendi soxta TLD'da — kamida SHUBHALI", Verdict.SUSPICIOUS, r.verdict)
    }

    @Test
    fun nonHttpsAlone_isNotFalseSafe() {
        // Yolg'iz http:// ham toza SAFE emas (shifrlanmagan) → SHUBHALI.
        val r = LinkScanner.analyze("http://example.com/")
        assertTrue("http yolg'iz — XAVFSIZ bo'lib qolmasligi kerak", r.verdict != Verdict.SAFE)
    }

    // ─────────── Yordamchi sof funksiyalar ───────────

    @Test
    fun levenshtein_basicDistances() {
        assertEquals(0, LinkScanner.levenshtein("payme", "payme"))
        assertEquals(1, LinkScanner.levenshtein("payme", "payne"))
        assertEquals(2, LinkScanner.levenshtein("click", "cliks"))
    }

    @Test
    fun isLegitBankDomain_recognizesRealBanks() {
        assertTrue(LinkScanner.isLegitBankDomain("click.uz"))
        assertTrue(LinkScanner.isLegitBankDomain("my.payme.uz"))
        assertTrue("payme.top haqiqiy bank emas (soxta TLD)", !LinkScanner.isLegitBankDomain("payme.top"))
    }
}
