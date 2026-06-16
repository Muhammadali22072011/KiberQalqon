package com.uzguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BankAppAudit] — soxta bank ilovasi auditining PURE yadrosi (`looksLikeBank`).
 *
 * PackageManager kerak emas: faqat label + paket nomidan brend-o'xshashlikni tekshiramiz.
 * To'liq `scan(context)` qaror mantig'i:
 *   - haqiqiy bank o'z paketi ostida → looksLikeBank bankni qaytaradi, AMMO pkg == bank.pkg →
 *     scan uni soxta DEB BELGILAMAYDI (bu yerda pkg tengligini tekshiramiz);
 *   - begona paket bank brendiga o'xshasa (pkg != bank.pkg) → nomzod = soxta;
 *   - o'zimizning ilova "UzGuard" — "anorbank" bilan to'qnashmaydi (butun token solishtirish).
 */
class BankAppAuditTest {

    private val OWN_PKG = "com.uzguard"

    // ─── Haqiqiy banklar: brend topiladi, lekin paket = haqiqiy → soxta EMAS ───

    @Test
    fun realClick_matchesItsOwnBank_andIsNotFake() {
        val bank = BankAppAudit.looksLikeBank("Click", "uz.click.evo")
        assertNotNull("Click haqiqiy bank sifatida topilishi kerak", bank)
        // pkg == bank.pkg → scan uni soxta deb belgilamaydi.
        assertEquals(
            "Haqiqiy Click o\'z paketi ostida bo\'lgani uchun soxta emas",
            "uz.click.evo", bank!!.pkg
        )
    }

    @Test
    fun realPayme_isMatchedToPaymeBank_byPackage() {
        val bank = BankAppAudit.looksLikeBank("Payme", "uz.dida.payme")
        assertNotNull(bank)
        assertEquals("uz.dida.payme", bank!!.pkg)
        assertEquals("payme", bank.brand)
    }

    @Test
    fun realKapitalbank_isNotFake() {
        val bank = BankAppAudit.looksLikeBank("Kapitalbank", "uz.kapitalbank.android")
        assertNotNull(bank)
        assertEquals("uz.kapitalbank.android", bank!!.pkg)
    }

    // ─── Soxta nomzodlar: brend o'xshaydi, lekin paket BEGONA → flag ───

    @Test
    fun evilClickPay_isFlaggedAsClickImpersonator() {
        // com.evil.clickpay — paketda "click" tokeni bor, label "Click" → Click brendiga mos.
        val bank = BankAppAudit.looksLikeBank("Click", "com.evil.clickpay")
        assertNotNull("com.evil.clickpay Click brendiga mos kelishi kerak", bank)
        assertEquals("click", bank!!.brand)
        // Eng muhimi: bu BEGONA paket, haqiqiy Click paketi emas → scan uni soxta deb belgilaydi.
        org.junit.Assert.assertNotEquals(
            "Begona paket haqiqiy Click paketiga teng bo\'lmasligi kerak",
            "com.evil.clickpay", bank.pkg
        )
    }

    @Test
    fun typosquatPackage_kapitalbonk_isFlagged() {
        // "kapitalbonk" — "kapitalbank"dan Levenshtein 1 (a→o) → typosquat.
        val bank = BankAppAudit.looksLikeBank("Bank", "uz.kapitalbonk.app")
        assertNotNull("kapitalbonk typosquat sifatida aniqlanishi kerak", bank)
        assertEquals("kapitalbank", bank!!.brand)
    }

    @Test
    fun fakePayme_labelLookalike_isFlagged() {
        // Label "Payme Pro", begona paket — "payme" tokeni mos.
        val bank = BankAppAudit.looksLikeBank("Payme Pro", "com.fake.wallet")
        assertNotNull(bank)
        assertEquals("payme", bank!!.brand)
    }

    // ─── O'ZIMIZNING ilova: "UzGuard" — hech qaysi bankka taqlid deb belgilanmasligi kerak ───

    @Test
    fun ownApp_uzguard_doesNotMatchUzcard() {
        // Rebrand'dan keyin "uzguard" tokeni "uzcard" brendiga Levenshtein 2 (= MAX_DISTANCE) —
        // ya'ni token solishtirish o'zimizni "uzcard"ka taqlid deb belgilab qo'yardi.
        // looksLikeBank o'z paketimizni (debug ham) ANIQ chiqarib tashlaydi → doim null.
        assertNull(
            "O\'zimizning \"UzGuard\" hech qachon bankka taqlid deb belgilanmasligi kerak",
            BankAppAudit.looksLikeBank("UzGuard", OWN_PKG)
        )
        assertNull(BankAppAudit.looksLikeBank("UzGuard", "$OWN_PKG.debug"))
    }

    @Test
    fun realAnorbank_isMatchedToAnorbank() {
        // Haqiqiy Anorbank — paket bo'yicha to'g'ri topiladi (substring chalkashligi yo'q).
        val bank = BankAppAudit.looksLikeBank("Anorbank", "uz.anorbank.mobile")
        assertNotNull(bank)
        assertEquals("anorbank", bank!!.brand)
        assertEquals("uz.anorbank.mobile", bank.pkg)
    }

    // ─── Aloqasi yo'q ilovalar → null (soxta nomzod ham emas) ───

    @Test
    fun unrelatedApp_returnsNull() {
        assertNull(BankAppAudit.looksLikeBank("Telegram", "org.telegram.messenger"))
        assertNull(BankAppAudit.looksLikeBank("Калькулятор", "com.example.calculator"))
        assertNull(BankAppAudit.looksLikeBank("WhatsApp", "com.whatsapp"))
    }

    @Test
    fun blankInputs_returnNull() {
        assertNull(BankAppAudit.looksLikeBank(null, null))
        assertNull(BankAppAudit.looksLikeBank("", ""))
        assertNull(BankAppAudit.looksLikeBank("   ", "   "))
    }

    @Test
    fun shortBenignToken_doesNotFalseMatch() {
        // "oson" (4 harf) — qisqa token; tasodifiy yaqin so'z Levenshtein'ga tushmasligi kerak.
        // "json" "oson"ga 1 masofa, lekin ikkalasi ham 5 dan qisqa → mos kelmaydi.
        assertNull(BankAppAudit.looksLikeBank("JSON Viewer", "com.dev.jsonviewer"))
    }

    @Test
    fun shortBrandToken_standaloneEquality_doesNotMatch() {
        // Qisqa brendlar (oson/ums/humo) endi label/paket TOKEN-tengligi orqali MOS KELMAYDI —
        // faqat aynan paket bo'yicha. "oson" o'zbekcha "oson(lik)" so'zi — begona ilovada bo'lishi mumkin.
        assertNull(
            "Begona 'oson' standalone tokeni soxta bank deb belgilanmasligi kerak",
            BankAppAudit.looksLikeBank("Oson Hayot", "com.example.oson")
        )
        assertNull(
            "Begona 'ums' tokeni soxta bank deb belgilanmasligi kerak",
            BankAppAudit.looksLikeBank("UMS Player", "com.example.ums")
        )
        assertNull(
            "Begona standalone 'humo' tokeni soxta bank deb belgilanmasligi kerak",
            BankAppAudit.looksLikeBank("Humo Art", "com.example.humo")
        )
    }

    @Test
    fun shortBrand_realPackage_stillRecognized() {
        // Lekin haqiqiy qisqa-brend paketlari AYNAN PAKET bo'yicha hamon topiladi (byPackage).
        assertNotNull(BankAppAudit.looksLikeBank("OSON", "com.oson.app"))
        assertNotNull(BankAppAudit.looksLikeBank("UMS", "uz.ums.mobile"))
        assertNotNull(BankAppAudit.looksLikeBank("Humo", "uz.humo.mobile"))
    }
}
