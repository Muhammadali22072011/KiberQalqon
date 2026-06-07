package com.kiberqalqon

import android.content.Context

/**
 * Reputatsiya qatlami — ma'lum, qonuniy ishlab chiqaruvchilar va keng tarqalgan
 * ilovalar ro'yxati. Bu heuristic false-positive'larni to'xtatish uchun.
 *
 * MUAMMO: Chrome, Gmail, Google Play, Instagram, Telegram, Kapitalbank kabi ASLI
 * ilovalar ham native kutubxonalarga ega (dlopen/JNI_OnLoad), ham 3+ "xavfli"
 * ruxsat so'raydi (kamera, mikrofon, lokatsiya, kontaktlar). Heuristic skaner
 * ularning HAMMASINI "Zararli dastur" deb belgilab qo'yardi. Bu skanerni
 * foydasiz qiladi — har bir tizim ilovasi "virus" bo'lib chiqadi.
 *
 * YECHIM: agar paket nomi ma'lum qonuniy ishlab chiqaruvchiga tegishli bo'lsa VA
 * QAT'IY (hard) zararli signal topilmagan bo'lsa (yashirin APK/DEX dropper, ikonka
 * impersonatsiyasi, qora ro'yxatdagi hash/imzo/paket, ZIP-shifrlash, brand
 * impersonatsiyasi) — verdictni SAFE ga tushiramiz.
 *
 * XAVFSIZLIK: bu "package-only whitelist backdoor" emas, chunki:
 *   1) Qora ro'yxatdagi hash/imzo/paket — bu ro'yxatdan OLDIN tekshiriladi va
 *      darhol DANGER beradi (ApkScanner.scan boshidagi early-return'lar).
 *   2) Dropper (yashirin payload), ikonka taqlidi, ZIP-shifrlash, brand taqlidi —
 *      bular reputatsiyadan QAT'IY NAZAR DANGER bo'lib qoladi (verdict `when`
 *      ichida reputatsiya tekshiruvidan oldin turadi).
 * Ya'ni soxta "com.android.chrome" ichida haqiqiy zararli yuk bo'lsa — baribir
 * ushlanadi. Faqat "ko'p ruxsat + native lib" kabi YUMSHOQ signallar bostiriladi.
 */
object AppReputation {

    /**
     * Ishonchli ishlab chiqaruvchi prefikslari. Zararli dasturlar deyarli hech
     * qachon aynan shu namespace'larda chiqmaydi; chiqsa ham yuqoridagi qat'iy
     * signallar ushlaydi.
     */
    private val TRUSTED_PREFIXES = listOf(
        "com.google.",
        "com.android.",          // AOSP + Google tizim ilovalari (vending, chrome, gms...)
        "com.samsung.",
        "com.sec.",              // Samsung (galereya, soat, push...)
        "com.microsoft.",
        "com.facebook.",
        "com.instagram.",
        "com.whatsapp",
        "org.telegram.",
        "androidx.",
        "com.qualcomm.",
        "com.mediatek.",
        "com.qti.",
        // Boshqa OEM tizim namespace'lari
        "com.lge.",
        "com.huawei.",
        "com.hihonor.",
        "com.miui.",
        "com.xiaomi.",
        "com.coloros.",
        "com.oppo.",
        "com.vivo.",
        "com.oneplus.",
        "com.motorola.",
        "com.sonyericsson.",
        "com.sonymobile.",
        "com.asus.",
        "com.transsion.",
    )

    /**
     * Aniq (exact) ishonchli paketlar — keng tarqalgan uchinchi-tomon ilovalari
     * va O'zbekiston bank/fintech ilovalari (ular SMS/CALL ruxsatlari uchun
     * tez-tez xato belgilanardi).
     */
    private val TRUSTED_EXACT = setOf(
        // Mashhur uchinchi-tomon
        "ch.protonvpn.android",
        "com.isaiasmatewos.texpand",
        "com.viber.voip",
        "com.twitter.android",
        "com.zhiliaoapp.musically",          // TikTok
        "com.spotify.music",
        "com.discord",
        "org.thoughtcrime.securesms",         // Signal
        "com.snapchat.android",
        "com.linkedin.android",
        "com.pinterest",
        "com.skype.raider",
        "com.opera.browser",
        "com.opera.mini.native",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.yandex.browser",
        "ru.yandex.searchplugin",
        "com.adobe.reader",
        "com.dropbox.android",
        "ru.zdevs.zarchiver",                // ZArchiver — mashhur arxivator (APK o'rnata oladi → dropper-combo
                                             // bo'yicha SUSPICIOUS belgilanardi; o'zi toza. Hard-signallar baribir
                                             // ishlaydi: trojanlangan nusxa yashirin payload bilan DANGER bo'ladi).
        // O'zbekiston bank / fintech / telekom
        "uz.kapitalbank.android",            // Kapitalbank
        "uz.click.evo",                      // Click
        "uz.dida.payme",                     // Payme
        "uz.uzcard.uzcard",                  // Uzcard
        "uz.uzum.bank",                      // Uzum Bank
        "uz.tbcbank.mobile",                 // TBC
        "uz.hamkorbank.mobile",              // Hamkorbank
        "uz.agrobank.mobile",                // Agrobank
        "uz.ipakyulibank.mobile",            // Ipak Yo'li
        "uz.infinbank.mobile",               // InfinBank
        "uz.davrbank.mobile",                // Davrbank
        "uz.beeline.odp",                    // Beeline
        "uz.beeline.selfservice",
        "uz.mobiuz.android",                 // Mobiuz
        "uz.ucell.selfcare",                 // Ucell
        "uz.ums.mobile",                     // UMS
        "uz.dunyo.mobile",
        "uz.soliq.mygov",                    // Soliq / MyGov
        "uz.yt.dyhcm",                       // YuzAbo / e-gov
        "uz.aab.online",
        "com.oson.app",                      // OSON
        "com.paynet.android",                // Paynet
    )

    /**
     * Ma'lum ishonchli ilova bo'lsa — uning paket nomini qaytaradi, aks holda null.
     * ApkScanner buni verdict'ni SAFE ga tushirish uchun ishlatadi (faqat qat'iy
     * signallar yo'qligida).
     */
    fun trustedName(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val p = pkg.lowercase()
        if (p in TRUSTED_EXACT) return pkg
        for (prefix in TRUSTED_PREFIXES) {
            if (p.startsWith(prefix)) return pkg
        }
        return null
    }

    /** Tezkor mantiqiy yordamchi. */
    fun isTrusted(pkg: String?): Boolean = trustedName(pkg) != null

    /**
     * Aniq ishonchli paketlar ro'yxati — [TrustedSignatures.captureInstalledTrusted]
     * ish vaqtida qurilmadagi shu ilovalarning sertifikatini "pin" qilish uchun ishlatadi.
     */
    internal fun exactTrustedPackages(): Set<String> = TRUSTED_EXACT

    /**
     * Imzo bilan tasdiqlangan reputatsiya natijasi.
     *
     * Faqat paket nomiga ([trustedName]) tayanish XAVFLI: har qanday APK o'zini
     * "com.android.chrome" yoki "uz.kapitalbank.android" deb e'lon qila oladi.
     * Shuning uchun verdictni SAFE ga tushirishdan oldin APK imzosi qurilmada
     * o'rnatilgan SHU NOMDAGI ilovaning imzosi bilan solishtiriladi.
     */
    enum class Reputation {
        /** Paket nomi ishonchli ro'yxatda yo'q — reputatsiya qo'llanmaydi. */
        UNKNOWN,
        /** Ishonchli brend + o'rnatilgan nusxa bilan imzo MOS → haqiqiy ilova → SAFE. */
        VERIFIED,
        /** Ishonchli brend nomi, lekin o'rnatilgan nusxaning imzosi BOSHQACHA → qalbaki ilova → DANGER. */
        SIGNATURE_MISMATCH,
        /** Ishonchli brend, lekin qurilmada o'rnatilmagan / imzoni o'qib bo'lmadi → tasdiqlab bo'lmaydi. */
        UNVERIFIED,
    }

    /**
     * Imzo bilan tasdiqlangan reputatsiya. ApkScanner buni verdict uchun ishlatadi:
     *  - [Reputation.VERIFIED] → SAFE (faqat qat'iy zararli signallar yo'qligida).
     *  - [Reputation.SIGNATURE_MISMATCH] → DANGER (ishonchli brend nomi + soxta imzo = qalbaki ilova).
     *  - [Reputation.UNKNOWN] / [Reputation.UNVERIFIED] → reputatsiya verdictga ta'sir qilmaydi,
     *    boshqa heuristic'lar (score) hal qiladi.
     *
     * @param apkCertSha256 skanlanayotgan APK imzosining SHA-256 (CertUtil.fingerprintSha256).
     */
    fun evaluate(context: Context, pkg: String?, apkCertSha256: String?): Reputation {
        if (trustedName(pkg) == null) return Reputation.UNKNOWN
        // pkg bu yerda null emas (trustedName null bo'lmagan paketni qaytaradi).
        val installedCert = CertUtil.installedFingerprintSha256(context, pkg!!)
            ?: return Reputation.UNVERIFIED          // brend ma'lum, lekin o'rnatilmagan/ko'rinmaydi
        if (apkCertSha256.isNullOrBlank()) return Reputation.UNVERIFIED  // APK imzosi o'qilmadi — SAFE bermaymiz
        return if (apkCertSha256.equals(installedCert, ignoreCase = true)) {
            Reputation.VERIFIED
        } else {
            Reputation.SIGNATURE_MISMATCH
        }
    }
}
