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
        // eTLD+1 ko'p darajali suffiks (com.uz / co.uz) — bank baza to'g'ri tan olinadi.
        assertTrue("click.com.uz haqiqiy bank domeni", LinkScanner.isLegitBankDomain("click.com.uz"))
        assertTrue("kapitalbank.co.uz haqiqiy bank domeni", LinkScanner.isLegitBankDomain("kapitalbank.co.uz"))
    }

    // ════════════════════════════════════════════════════════════════════════════════
    //  RED-TEAM KORPUSI (2026-06-11) — 77 holat. Har biri [LinkScanner.analyze] verdiktini
    //  pinlaydi. UZ/RU assert xabarlari. Toifalar bo'yicha guruhlangan.
    // ════════════════════════════════════════════════════════════════════════════════

    private fun assertVerdict(expected: Verdict, url: String, msgUz: String) {
        val r = LinkScanner.analyze(url)
        assertEquals("$msgUz | URL=$url | flags=${r.reasons}", expected, r.verdict)
    }

    // ─────────── 1) LEET / RAQAM / ASCII-CONFUSABLE ───────────
    // 1) Leet/raqam-almashtirish (cl1ck→click va h.k.) endi de-leet fold orqali HARD.
    // RU: подстановка цифр/символов вместо букв в бренде банка → опасно.

    @Test fun leet_cl1ck_isDanger() =
        assertVerdict(Verdict.DANGER, "https://cl1ck.uz", "cl1ck → click (i→1) — XAVFLI")

    @Test fun leet_c1ick_isDanger() =
        assertVerdict(Verdict.DANGER, "https://c1ick.uz", "c1ick → click (l→1) — XAVFLI")

    @Test fun leet_uzcand_isDanger() =
        assertVerdict(Verdict.DANGER, "https://uzcand.uz", "uzcand → uzcard (r→n) — XAVFLI")

    @Test fun leet_hum0_isDanger() =
        assertVerdict(Verdict.DANGER, "https://hum0.uz", "hum0 → humo (o→0, qisqa brend) — XAVFLI")

    @Test fun leet_0son_isDanger() =
        assertVerdict(Verdict.DANGER, "https://0son.uz", "0son → oson (O→0) — XAVFLI")

    @Test fun leet_clik_isDanger() =
        assertVerdict(Verdict.DANGER, "https://clik.uz", "clik → click (tushib qolgan harf) — XAVFLI")

    @Test fun leet_paYme_hyphenInside_isDanger() =
        assertVerdict(Verdict.DANGER, "https://pa-yme.uz", "pa-yme → payme (ichki defis) — XAVFLI")

    @Test fun leet_paye_isDanger() =
        assertVerdict(Verdict.DANGER, "https://paye.uz", "paye → payme (tushib qolgan harf) — XAVFLI")

    @Test fun leet_rnobiuz_isDanger() =
        assertVerdict(Verdict.DANGER, "https://rnobiuz.uz", "rnobiuz → mobiuz (rn→m) — XAVFLI")

    @Test fun leet_infinbantk_isDanger() =
        assertVerdict(Verdict.DANGER, "https://infinbantk.uz", "infinbantk → infinbank — XAVFLI")

    @Test fun leet_paym3_isDanger() =
        assertVerdict(Verdict.DANGER, "https://paym3.uz", "paym3 → payme (e→3) — XAVFLI")

    @Test fun leet_payme0_suffixDigit_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme0.uz", "payme0 (suffiks raqam) — XAVFLI")

    @Test fun leet_uzcard1_suffixDigit_isDanger() =
        assertVerdict(Verdict.DANGER, "https://uzcard1.uz", "uzcard1 (suffiks raqam) — XAVFLI")

    @Test fun leet_g00glePay_isDanger() =
        assertVerdict(Verdict.DANGER, "https://g00gle-pay.uz", "g00gle-pay → googlepay — XAVFLI")

    @Test fun leet_kapita1bank_isDanger() =
        assertVerdict(Verdict.DANGER, "https://kapita1bank.uz", "kapita1bank → kapitalbank — XAVFLI")

    @Test fun leet_uzcardUz_hyphenSuffix_isDanger() =
        assertVerdict(Verdict.DANGER, "https://uzcard-uz.uz", "uzcard-uz (chegara) — XAVFLI")

    // ─────────── 2) HOMOGLYPH / ARALASH SKRIPT ───────────
    // Aralash skript (Latin+Kirill) → HARD; sof bitta-skriptli IDN → hujum emas.
    // RU: смешанный шрифт в одной метке — атака; односкриптовый IDN — нет.

    @Test fun homoglyph_rayme_mixed_isDanger() =
        assertVerdict(Verdict.DANGER, "https://раyme.uz/login", "раyme (kirill+lotin) — XAVFLI")

    @Test fun homoglyph_klickCyr_isDanger() =
        assertVerdict(Verdict.DANGER, "http://клиcк.uz/tasdiq", "клиcк (kirill+lotin) — XAVFLI")

    @Test fun homoglyph_punycodeMixed_isDanger() =
        assertVerdict(Verdict.DANGER, "https://xn--pyme-9na.uz/", "xn--pyme-9na (dekod → aralash) — XAVFLI")

    @Test fun homoglyph_benignIdnPunycode_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://xn--80ak6aa92e.com/", "benign IDN xn-- — kamida SHUBHALI")

    @Test fun homoglyph_legitCyrillicIdn_isSafe() =
        assertVerdict(Verdict.SAFE, "https://киберқалқон.уз/", "haqiqiy kirill .уз domeni — XAVFSIZ")

    @Test fun homoglyph_c1ickPay_isDanger() =
        assertVerdict(Verdict.DANGER, "https://c1ick.uz/pay", "c1ick (ASCII leet) — XAVFLI")

    @Test fun homoglyph_c1ickRoot_isDanger() =
        assertVerdict(Verdict.DANGER, "https://c1ick.uz/", "c1ick (kalit so'zsiz ham) — XAVFLI")

    @Test fun homoglyph_klickLatin_isDanger() =
        assertVerdict(Verdict.DANGER, "https://klick.uz/", "klick (lotin transliteratsiya) — XAVFLI")

    @Test fun homoglyph_realPaymeUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://payme.uz/", "haqiqiy payme.uz — XAVFSIZ")

    @Test fun homoglyph_sberbankCyr_isDanger() =
        assertVerdict(Verdict.DANGER, "https://ѕberbank.uz/", "ѕberbank (kirill ѕ) — XAVFLI")

    @Test fun homoglyph_uzcardCyr_isDanger() =
        assertVerdict(Verdict.DANGER, "https://uzcаrd.uz/verify", "uzcаrd (kirill а) — XAVFLI")

    @Test fun homoglyph_realPaymeCom_isSafe() =
        assertVerdict(Verdict.SAFE, "https://payme.com/", "haqiqiy payme.com — XAVFSIZ")

    @Test fun homoglyph_fullwidthPayme_isDanger() =
        assertVerdict(Verdict.DANGER, "https://ｐａｙｍｅ.uz/", "fullwidth ｐａｙｍｅ → payme — XAVFLI")

    @Test fun homoglyph_atTrickRealHost_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme.uz@click.evil.tk/", "@ ortidagi click.evil.tk — XAVFLI")

    @Test fun homoglyph_mixedSubdomainOnRealBank_isDanger() =
        assertVerdict(Verdict.DANGER, "https://раyмe.click.uz/", "aralash-skript subdomen click.uz ustida — XAVFLI")

    // ─────────── 3) HOST-TUZILMA & URL TRIK ───────────

    @Test fun host_decimalIp_isDanger() =
        assertVerdict(Verdict.DANGER, "http://2130706433/login.php", "o'nlik dword IP (127.0.0.1) — XAVFLI")

    @Test fun host_hexDottedIp_isDanger() =
        assertVerdict(Verdict.DANGER, "http://0x7f.0x0.0x0.0x1/payme", "oltilik nuqtali IP — XAVFLI")

    @Test fun host_hexDwordIp_isDanger() =
        assertVerdict(Verdict.DANGER, "http://0x7f000001/", "oltilik dword IP — XAVFLI")

    @Test fun host_openRedirectNext_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://payme.uz/?next=http://kapitalbank-secure.tk/login", "ochiq yo'naltirish ?next= — SHUBHALI")

    @Test fun host_openRedirectGoogleQ_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://google.com/url?q=http://payme.login-verify.tk", "redirektor ?q= — SHUBHALI")

    @Test fun host_concatPaymeuz_isDanger() =
        assertVerdict(Verdict.DANGER, "https://paymeuz.com/confirm", "paymeuz (birikma) — XAVFLI")

    @Test fun host_concatUzcardpay_isDanger() =
        assertVerdict(Verdict.DANGER, "http://uzcardpay.com/karta/confirm", "uzcardpay (birikma) — XAVFLI")

    @Test fun host_atKapitalbankSubdomain_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme.uz@kapitalbank-login.tk/auth", "@ ortida kapitalbank yorlig'i — XAVFLI")

    @Test fun host_atEvilHostWithBrandUserinfo_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme.uz@evil-host.com/login", "userinfo'da payme brendi @ — XAVFLI")

    @Test fun host_clickComUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://click.com.uz/login", "click.com.uz (haqiqiy eTLD) — XAVFSIZ")

    @Test fun host_kapitalbankCoUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://kapitalbank.co.uz/", "kapitalbank.co.uz (haqiqiy eTLD) — XAVFSIZ")

    @Test fun host_govUzAsSubdomain_isDanger() =
        assertVerdict(Verdict.DANGER, "https://soliq.gov.uz.account-verify.tk/login", "gov.uz chet-suffiksli domen subdomenida — XAVFLI")

    @Test fun host_telegramBotLureHttp_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "http://t.me/payme_uz_support_bot", "t.me bot lure (http) — SHUBHALI")

    @Test fun host_paymeSubdomainPunycode_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme.uz.xn--80a.tk/login", "payme subdomen + xn-- chet domen — XAVFLI")

    @Test fun host_percentEncodedDot_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme%2Euz.evil.tk/", "payme%2Euz (%-kodlangan nuqta) — XAVFLI")

    // ─────────── 4) DROPPER-YETKAZISH & UZ-SCAM ───────────

    @Test fun dropper_doubleExtMp4Apk_http_isDanger() =
        assertVerdict(Verdict.DANGER, "http://share.tg/VIDEO.20.01.2026.mp4.apk", "ikki-kengaytma .mp4.apk — XAVFLI")

    @Test fun dropper_doubleExtHttps_isDanger() =
        assertVerdict(Verdict.DANGER, "https://files-uz.com/video.mp4.apk", ".mp4.apk HTTPS'da ham — XAVFLI")

    @Test fun dropper_telegramImpersonationApk_isDanger() =
        assertVerdict(Verdict.DANGER, "https://t-me.co/file/toy.apk", "t-me.co + .apk — XAVFLI")

    @Test fun dropper_fakeDriveBankApk_isDanger() =
        assertVerdict(Verdict.DANGER, "https://drive.evil.tk/download/bank.apk", "soxta drive + bank.apk — XAVFLI")

    @Test fun dropper_cdnClickUpdateApk_isDanger() =
        assertVerdict(Verdict.DANGER, "https://cdn-uzfiles.com/update/Click_yangilash.apk", "Click yangilash .apk — XAVFLI")

    @Test fun dropper_ipHostApk_isDanger() =
        assertVerdict(Verdict.DANGER, "http://192.168.1.50/toy_video.apk", "IP host + .apk — XAVFLI")

    @Test fun dropper_c2InjectPath_isDanger() =
        assertVerdict(Verdict.DANGER, "https://config-srv8.net/api/inject", "ma'lum C2 /api/inject — XAVFLI")

    @Test fun dropper_uploadSmsPath_isDanger() =
        assertVerdict(Verdict.DANGER, "https://app-update7.com/api/upload_sms", "ma'lum exfil /api/upload_sms — XAVFLI")

    @Test fun scam_skidkaYutuqSovga_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://skidka-yutuq.uz/sovga-olish", "UZ scam lure (skidka/yutuq/sovga) — SHUBHALI")

    @Test fun scam_pulOtishKartaTekshirish_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://pul-otish.uz/karta-tekshirish", "pul-otish/tekshirish lure — SHUBHALI")

    @Test fun scam_telegramPremium_isSuspicious() =
        assertVerdict(Verdict.SUSPICIOUS, "https://telegram-premium.com/get", "telegram-premium taqlid — SHUBHALI")

    @Test fun dropper_paymeBonusHost_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme-bonus.click/login", "payme-bonus.click host typosquat — XAVFLI")

    @Test fun control_legitTelegramInvite_isSafe() =
        assertVerdict(Verdict.SAFE, "https://t.me/joinchat/AAAAExampleHash", "haqiqiy t.me taklif — XAVFSIZ")

    @Test fun control_legitClickPayments_isSafe() =
        assertVerdict(Verdict.SAFE, "https://my.click.uz/payments/checkout", "haqiqiy click.uz to'lov sahifasi — XAVFSIZ")

    // ─────────── 5) NOTO'G'RI-POZITIV KORPUSI (legit → XAVFSIZ) ───────────

    @Test fun fp_clickUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://click.uz", "click.uz — XAVFSIZ")

    @Test fun fp_myClickUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://my.click.uz", "my.click.uz — XAVFSIZ")

    @Test fun fp_deepKapitalbankSubdomain_isSafe() =
        assertVerdict(Verdict.SAFE, "https://login.secure.kapitalbank.uz", "chuqur bank subdomeni — XAVFSIZ")

    @Test fun fp_uzumbankUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://uzumbank.uz", "uzumbank.uz — XAVFSIZ")

    @Test fun fp_soliqUz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://soliq.uz", "soliq.uz (soliq portali) — XAVFSIZ")

    @Test fun fp_tMeDurov_isSafe() =
        assertVerdict(Verdict.SAFE, "https://t.me/durov", "t.me/durov — XAVFSIZ")

    @Test fun fp_githubLogin_isSafe() =
        assertVerdict(Verdict.SAFE, "https://github.com/login", "github.com/login — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_accountGoogle_isSafe() =
        assertVerdict(Verdict.SAFE, "https://account.google.com", "account.google.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_paymentsStripe_isSafe() =
        assertVerdict(Verdict.SAFE, "https://payments.stripe.com", "payments.stripe.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_clickhouse_isSafe() =
        assertVerdict(Verdict.SAFE, "https://clickhouse.com", "clickhouse.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_clickup_isSafe() =
        assertVerdict(Verdict.SAFE, "https://clickup.com", "clickup.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_transfermarkt_isSafe() =
        assertVerdict(Verdict.SAFE, "https://www.transfermarkt.com", "transfermarkt.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_paypal_isSafe() =
        assertVerdict(Verdict.SAFE, "https://paypal.com", "paypal.com — XAVFSIZ (FP tuzatildi)")

    @Test fun fp_mobileTwitter_isSafe() =
        assertVerdict(Verdict.SAFE, "https://mobile.twitter.com", "mobile.twitter.com — XAVFSIZ (mobiuz emas)")

    @Test fun fp_osaka_isSafe() =
        assertVerdict(Verdict.SAFE, "https://osaka.com", "osaka.com (asaka emas) — XAVFSIZ")

    @Test fun fp_google_isSafe() =
        assertVerdict(Verdict.SAFE, "https://google.com", "google.com — XAVFSIZ")

    @Test fun fp_dalerXyz_isSafe() =
        assertVerdict(Verdict.SAFE, "https://daler.xyz", "daler.xyz (shaxsiy blog, yolg'iz arzon TLD) — XAVFSIZ")

    // ─── 2-bosqich red-team: edit-byudjet "cliff" + Unicode-nuqta obxodlari yopildi ───
    // Brend 2+ ko’rinmas/ekzotik belgi bilan to’ldirilib fold edit-byudjeti portlatilardi; va
    // alternativ Unicode nuqtalar host’ni bitta yorliqqa aylantirardi. Endi ikkalasi ham yopilgan.
    // (Maxsus belgilar \u-escape bilan — manbada ko’rinmas bayt yo’q.)

    @Test fun rt2_zeroWidthDoublePayme_isDanger() =
        // payme + 2 ta zero-width space (U+200B): ilgari fold edit-2 → XAVFSIZ. Endi ko’rinmas
        // belgilar fold’da tashlanadi → "payme" → AYNAN moslik → XAVFLI.
        assertVerdict(Verdict.DANGER, "https://pa\u200B\u200Byme.uz/", "Ikki zero-width payme taqlidi — XAVFLI")

    @Test fun rt2_combiningDoublePayme_isDanger() =
        assertVerdict(Verdict.DANGER, "https://payme\u0301\u0301.uz/", "Birikuvchi diakritikli payme — XAVFLI")

    @Test fun rt2_romanNumeralClick_isDanger() =
        // U+217D (Number-Letter, harf EMAS) ikki marta. Ilgari fold edit-2 → XAVFSIZ.
        // Endi ekzotik non-ASCII + bank tokeniga masofa ≤2 → XAVFLI.
        assertVerdict(Verdict.DANGER, "https://\u217Dli\u217Dk.uz/", "Roman-numeral click taqlidi — XAVFLI")

    @Test fun rt2_ideographicDotBrandSubdomain_isDanger() =
        // U+3002 ideografik nuqta → ".": click。account-verify.tk → click.account-verify.tk
        // → brend subdomenda, begona registrable → XAVFLI.
        assertVerdict(Verdict.DANGER, "https://click\u3002account-verify.tk/", "Ideografik nuqta + brend subdomen — XAVFLI")

    @Test fun rt2_fullwidthDotRealPayme_isNotDanger() {
        // payme．uz (fullwidth nuqta U+FF0E) IDNA bo’yicha haqiqiy payme.uz ga aylanadi — XAVFLI EMAS.
        val r = LinkScanner.analyze("https://payme\uFF0Euz")
        org.junit.Assert.assertNotEquals(
            "Fullwidth-nuqtali haqiqiy payme.uz — XAVFLI bo’lmasligi kerak",
            Verdict.DANGER, r.verdict
        )
    }
}
