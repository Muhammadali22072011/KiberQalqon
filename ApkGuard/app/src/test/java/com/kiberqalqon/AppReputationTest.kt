package com.kiberqalqon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppReputation] — qonuniy ishlab chiqaruvchilar ro'yxati heuristic
 * false-positive'larni to'xtatish uchun.
 *
 * Asosiy holatlar foydalanuvchining haqiqiy loglaridan olingan — o'sha yerda
 * Chrome, Google Play, GMS, Instagram, ProtonVPN, Samsung tizim ilovalari
 * barchasi xato "Zararli 🚫" deb belgilangan edi.
 */
class AppReputationTest {

    @Test
    fun trusts_googleAndAndroidSystemPackages() {
        // Foydalanuvchi logidagi paketlar — endi ishonchli bo'lishi kerak.
        for (pkg in listOf(
            "com.android.vending",          // Google Play Store
            "com.android.chrome",
            "com.google.android.gms",
            "com.google.android.gm",        // Gmail
            "com.google.android.tts",
            "com.google.android.apps.maps",
            "com.google.android.apps.bard",
            "com.google.android.marvin.talkback",
            "com.google.android.partnersetup",
        )) {
            assertNotNull("$pkg ishonchli bo'lishi kerak", AppReputation.trustedName(pkg))
            assertTrue(AppReputation.isTrusted(pkg))
        }
    }

    @Test
    fun trusts_samsungSystemPackages() {
        for (pkg in listOf(
            "com.samsung.android.timezone.data",
            "com.samsung.android.bixby.service",
            "com.samsung.android.mobileservice",
            "com.samsung.SMT",
            "com.sec.android.gallery3d",
            "com.sec.android.app.clockpackage",
            "com.sec.spp.push",
            "com.sec.android.app.myfiles",
        )) {
            assertNotNull("$pkg ishonchli bo'lishi kerak", AppReputation.trustedName(pkg))
        }
    }

    @Test
    fun trusts_microsoftAndPopularThirdParty() {
        for (pkg in listOf(
            "com.microsoft.office.excel",
            "com.microsoft.office.word",
            "com.microsoft.office.powerpoint",
            "com.microsoft.skydrive",       // OneDrive
            "com.instagram.android",
            "org.telegram.messenger.web",
            "ch.protonvpn.android",
            "com.isaiasmatewos.texpand",
            "com.whatsapp",
        )) {
            assertNotNull("$pkg ishonchli bo'lishi kerak", AppReputation.trustedName(pkg))
        }
    }

    @Test
    fun trusts_uzbekBanks() {
        for (pkg in listOf(
            "uz.kapitalbank.android",
            "uz.click.evo",
            "uz.dida.payme",
        )) {
            assertNotNull("$pkg ishonchli bo'lishi kerak", AppReputation.trustedName(pkg))
        }
    }

    @Test
    fun doesNotTrust_knownMalwarePackages() {
        // Ajina.Banker / droplar — hech qachon ishonchli emas.
        for (pkg in listOf(
            "com.lzthzvxte.xazoalzxhr",
            "com.oktgkst.rrcpkge",
            "pyw.kzxwc",
            "uzbekchill.com",
            "ydbllnjd.com",
        )) {
            assertNull("$pkg ishonchli BO'LMASLIGI kerak", AppReputation.trustedName(pkg))
            assertFalse(AppReputation.isTrusted(pkg))
        }
    }

    @Test
    fun doesNotTrust_lookalikeOrUnknown() {
        // "com.google" prefiksiga o'xshatib qo'yilgan, lekin aslida boshqa paket.
        assertNull(AppReputation.trustedName("com.googlexevil.payload"))
        assertNull(AppReputation.trustedName("org.telegramx.fake"))
        assertNull(AppReputation.trustedName("net.random.app"))
        assertNull(AppReputation.trustedName(null))
        assertNull(AppReputation.trustedName(""))
    }

    @Test
    fun knownGoodPackagesAreNotFlaggedAsRandom() {
        // Reputatsiyadan tashqari: legit paketlar "tasodifiy" heuristic'iga ham
        // tushmasligi kerak (ikki himoya qatlami mustaqil ishlasin).
        for (pkg in listOf(
            "com.android.chrome",
            "com.google.android.gms",
            "com.instagram.android",
            "org.telegram.messenger.web",
        )) {
            assertFalse(
                "$pkg tasodifiy paket deb belgilanmasligi kerak",
                ApkScanner.looksRandomPackageName(pkg)
            )
        }
    }
}
