package com.kiberqalqon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * SOXTA BANK ILOVASI auditi — qurilmaga O'RNATILGAN ilovalar ichidan haqiqiy bank/fintech
 * brendiga taqlid qiluvchi (label yoki paket nomi o'xshash, lekin haqiqiy bank paketi EMAS)
 * ilovalarni topadi.
 *
 * Bank trojanlari ("Ajina.Banker" turi) ko'pincha o'zini "Click", "Payme", "Kapitalbank" deb
 * ko'rsatadi — bir xil nom, bir xil ikonka — lekin paketi begona ("com.evil.clickpay") va
 * Play Store'dan emas, imzosi ham haqiqiy bankniki emas. Bu skaner shu nomuvofiqliklarni
 * to'playdi.
 *
 * Tasdiqlash signallari (har biri [Reason]):
 *   - [Reason.LABEL_LOOKS_LIKE_BANK] — label/paket brendi bank brendiga o'xshaydi, lekin paket begona.
 *   - [Reason.ICON_IMPERSONATION]    — ikonkasi haqiqiy bank ikonkasiga deyarli teng (aHash).
 *   - [Reason.NOT_PLAY_INSTALL]      — Play Store'dan emas (sideload / noma'lum manba).
 *   - [Reason.CERT_NOT_OFFICIAL]     — haqiqiy bank o'rnatilgan bo'lsa, imzo SHA-256 unga to'g'ri kelmaydi.
 *   - [Reason.PACKAGE_TYPOSQUAT]     — paket nomi bank brendiga juda yaqin, lekin haqiqiy paket emas.
 *
 * MUHIM QOIDA — false-positive emas:
 *   - Haqiqiy bank o'z paketi ostida + Play'dan o'rnatilgan → HECH QACHON soxta emas (reasons bo'sh).
 *   - O'ZIMIZNING ilova (com.kiberqalqon[.debug]) — "Anor Qalqon" vs "anorbank" to'qnashuvi —
 *     ALBATTA chiqarib tashlanadi.
 *   - Brendlar BUTUN token bilan solishtiriladi (substring "anor" emas).
 *
 * [object] singleton; har bir public kirish nuqtasi xatoni yutadi va hech qachon tashqariga
 * exception tashlamaydi.
 */
object BankAppAudit {

    private const val TAG = "BankAppAudit"

    /** Typosquat masofa chegarasi va minimal token uzunligi (qisqa tokenlarda Levenshtein juda shovqinli). */
    private const val MAX_DISTANCE = 2
    private const val MIN_TOKEN_LEN = 5

    /** Topilgan bitta (potensial) soxta bank ilovasi. */
    data class Finding(
        val pkg: String,
        val label: String,
        val impersonates: KnownBanks.Bank?,   // qaysi bankka taqlid qilmoqda (null = umumiy)
        val reasons: List<Reason>,
        val fromPlay: Boolean,
    ) {
        /**
         * SOXTA deb hisoblash uchun LABEL_LOOKS_LIKE_BANK'dan TASHQARI kamida bitta tasdiqlovchi
         * dalil shart (ikonka taqlidi / paket typosquat / Play'dan emas / imzo noto'g'ri).
         * Faqat nom o'xshashligi YETARLI EMAS — aks holda har bir legal fintech yoki bankning
         * ikkilamchi ilovasi (boshqa paket, lekin rasmiy imzo) noto'g'ri soxta deb belgilanardi.
         */
        val isFake: Boolean get() = reasons.any { it != Reason.LABEL_LOOKS_LIKE_BANK }
    }

    enum class Reason {
        LABEL_LOOKS_LIKE_BANK,
        ICON_IMPERSONATION,
        NOT_PLAY_INSTALL,
        CERT_NOT_OFFICIAL,
        PACKAGE_TYPOSQUAT,
    }

    /**
     * Qurilmadagi NOsistema ilovalarni tekshiradi va soxta bank ilovalarini qaytaradi
     * (eng xavfli birinchi). O'zimizning paketni + ".debug" ni chiqarib tashlaydi.
     * Hech qachon exception tashlamaydi.
     *
     * QAYTISH: muvaffaqiyatli skan → topilgan ro'yxat (bo'sh = haqiqatan toza); skan UMUMAN
     * BAJARILMASA (ilovalar ro'yxati o'qilmadi / kutilmagan xato) → null. ANTIVIRUS OLTIN QOIDASI:
     * xato bo'sh ro'yxatdan farqlanishi shart — aks holda xatoda yashil "toza" (false-SAFE) chiqardik.
     */
    fun scan(context: Context): List<Finding>? {
        return try {
            val pm = context.packageManager
            val ownPkgs = setOf(context.packageName, "${context.packageName}.debug")
            val apps = try { pm.getInstalledApplications(0) } catch (_: Throwable) { return null }

            val out = ArrayList<Finding>()
            for (app in apps) {
                val pkg = app.packageName
                if (pkg in ownPkgs) continue
                if (isSystem(app)) continue

                val label = labelOf(pm, app)
                // 1) Brend-o'xshashlik: label yoki paket biror bank brendiga o'xshaydimi?
                val bank = looksLikeBank(label, pkg) ?: continue

                // 2) Haqiqiy bank o'z paketi ostida → soxta emas, o'tkazib yuboramiz.
                if (pkg.equals(bank.pkg, ignoreCase = true)) continue

                // Bu yerga kelsak: brendga o'xshaydi, lekin paket begona = nomzod.
                val fromPlay = fromPlay(pm, pkg)

                // 2b) Bir noshirning LEGAL ikkilamchi ilovasi: Play'dan o'rnatilgan VA imzosi
                //     haqiqiy bankning rasmiy imzosiga to'g'ri kelsa — HECH QACHON soxta emas
                //     (masalan "Kapitalbank Biznes" boshqa paket ostida, lekin o'sha rasmiy imzo).
                if (fromPlay && certMatchesOfficial(context, pkg, bank)) continue

                val reasons = ArrayList<Reason>()
                reasons.add(Reason.LABEL_LOOKS_LIKE_BANK)

                // 3) Paket typosquat (brend tokeni paketda, lekin haqiqiy paket emas).
                if (isPackageTyposquat(pkg, bank)) reasons.add(Reason.PACKAGE_TYPOSQUAT)

                // 4) Ikonka taqlidi — haqiqiy bank ikonkasiga deyarli teng bo'lsa.
                if (hasIconImpersonation(context, app, pkg)) reasons.add(Reason.ICON_IMPERSONATION)

                // 5) Play Store'dan emas → ishonchsiz manba.
                if (!fromPlay) reasons.add(Reason.NOT_PLAY_INSTALL)

                // 6) Imzo haqiqiy bankniki emas (haqiqiy bank ham o'rnatilgan bo'lsagina solishtiramiz).
                if (certNotOfficial(context, pkg, bank)) reasons.add(Reason.CERT_NOT_OFFICIAL)

                out.add(Finding(pkg, label, bank, reasons, fromPlay))
            }
            out.sortedByDescending { it.reasons.size }
        } catch (e: Throwable) {
            // Skan umuman bajarilmadi — null (xato) qaytaramiz, BO'SH ro'yxat (toza) EMAS.
            Log.w(TAG, "scan failed", e)
            null
        }
    }

    // ───────────────────── tekshiriladigan testlanadigan yadro ─────────────────────

    /**
     * Label yoki paket nomidagi tokenlardan biri biror bank brendiga
     * (butun token tengligi YOKI Levenshtein ≤ 2, token uzunligi ≥ 5) mos kelsa,
     * o'sha [KnownBanks.Bank] qaytadi; aks holda null.
     *
     * PURE — PackageManager kerak emas, JVM'da testlanadi. Paketning haqiqiy bank ekanligini
     * BU YERDA TEKSHIRMAYDI — chaqiruvchi `pkg == bank.pkg` ni alohida hisobga oladi.
     */
    internal fun looksLikeBank(label: String?, pkg: String?): KnownBanks.Bank? {
        // Birinchi navbatda: paket aniq bir bankka teng bo'lsa, o'sha bank.
        KnownBanks.byPackage(pkg)?.let { return it }

        val tokens = HashSet<String>()
        tokens += tokenize(label)
        tokens += tokenize(pkg)
        if (tokens.isEmpty()) return null

        var best: KnownBanks.Bank? = null
        var bestDist = Int.MAX_VALUE
        for (bank in KnownBanks.ALL) {
            val brand = bank.brand
            // Qisqa brendlar (ums/humo/oson, <5 harf) label/paket TOKEN-tengligi orqali shovqinli
            // mos keladi ("oson" = "oson hayot", o'zbekcha keng so'z). Ular faqat AYNAN PAKET
            // bo'yicha (yuqorida byPackage) aniqlanadi — bu yerda token solishtirishdan chiqariladi.
            if (brand.length < MIN_TOKEN_LEN) continue
            for (t in tokens) {
                if (t.isEmpty()) continue
                // Butun token tengligi — har doim eng kuchli moslik.
                if (t == brand) return bank
                // Levenshtein faqat ikkala token ham yetarlicha uzun bo'lsa (qisqa so'zlar shovqinli).
                if (t.length >= MIN_TOKEN_LEN && brand.length >= MIN_TOKEN_LEN) {
                    val d = levenshtein(t, brand, MAX_DISTANCE)
                    if (d in 1..MAX_DISTANCE && d < bestDist) {
                        best = bank
                        bestDist = d
                    }
                }
            }
        }
        return best
    }

    /**
     * Paket nomi typosquat: brend tokeni paket bo'laklaridan birida bor (yoki yaqin),
     * lekin paket haqiqiy bank paketi EMAS. (looksLikeBank label'dan ham mos kelishi mumkin,
     * shuning uchun paket-typosquat'ni alohida hisoblaymiz.)
     */
    private fun isPackageTyposquat(pkg: String?, bank: KnownBanks.Bank): Boolean {
        val tokens = tokenize(pkg)
        if (tokens.isEmpty()) return false
        val brand = bank.brand
        // Qisqa brend (ums/humo/oson) — token-tengligi shovqinli, paket-typosquat hisoblamaymiz.
        if (brand.length < MIN_TOKEN_LEN) return false
        for (t in tokens) {
            if (t == brand) return true
            if (t.length >= MIN_TOKEN_LEN && brand.length >= MIN_TOKEN_LEN) {
                val d = levenshtein(t, brand, MAX_DISTANCE)
                if (d in 1..MAX_DISTANCE) return true
            }
        }
        return false
    }

    // ───────────────────── Android signallari (PackageManager) ─────────────────────

    private fun hasIconImpersonation(context: Context, app: ApplicationInfo, pkg: String): Boolean = try {
        // O'rnatilgan ilovaning sourceDir'i o'qiladigan APK yo'li — InstalledAppsRescanWorker shu naqshdan foydalanadi.
        val src = app.sourceDir
        if (src.isNullOrBlank()) false
        else IconImpersonationDetector.detect(context, src, pkg) != null
    } catch (_: Throwable) { false }

    /**
     * Imzo tekshiruvi: haqiqiy bank ham qurilmaga o'rnatilgan bo'lsa, nomzodning imzo
     * SHA-256'si haqiqiy bankning yaroqli imzolari (rotatsiya tarixi bilan) ichida bo'lishi kerak.
     * Bo'lmasa → CERT_NOT_OFFICIAL.
     *
     * Agar haqiqiy bank o'rnatilmagan bo'lsa, solishtirishga narsa yo'q → bu sababni QO'YMAYMIZ
     * (boshqa signallar baribir soxta deb belgilaydi). False-safe emas: bu faqat qo'shimcha signal.
     */
    /**
     * Nomzodning imzosi haqiqiy bankning rasmiy imzosiga AYNAN to'g'ri keladimi (bir noshir).
     * Faqat haqiqiy bank o'rnatilgan VA ikkala imzo o'qilgan holatdagina true — aks holda false
     * (ya'ni "bilmaymiz" → suppress qilmaymiz). Bir noshir + Play → legal ikkilamchi ilova.
     */
    private fun certMatchesOfficial(context: Context, candidatePkg: String, bank: KnownBanks.Bank): Boolean = try {
        val official = CertUtil.installedSigningFingerprints(context, bank.pkg)
        if (official.isEmpty()) {
            false
        } else {
            val candidate = CertUtil.installedSigningFingerprints(context, candidatePkg)
            candidate.isNotEmpty() && candidate.any { c -> official.any { it.equals(c, ignoreCase = true) } }
        }
    } catch (_: Throwable) {
        false
    }

    private fun certNotOfficial(context: Context, candidatePkg: String, bank: KnownBanks.Bank): Boolean = try {
        val official = CertUtil.installedSigningFingerprints(context, bank.pkg)
        if (official.isEmpty()) {
            false // haqiqiy bank o'rnatilmagan / imzo o'qib bo'lmadi → solishtirmaymiz
        } else {
            val candidate = CertUtil.installedSigningFingerprints(context, candidatePkg)
            if (candidate.isEmpty()) true // imzo o'qib bo'lmadi, lekin bank o'rnatilgan → shubhali
            else candidate.none { c -> official.any { it.equals(c, ignoreCase = true) } }
        }
    } catch (_: Throwable) {
        false
    }

    private fun isSystem(app: ApplicationInfo): Boolean =
        (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun labelOf(pm: PackageManager, app: ApplicationInfo): String =
        try { pm.getApplicationLabel(app).toString() } catch (_: Throwable) { app.packageName }

    private fun fromPlay(pm: PackageManager, pkg: String): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer == "com.android.vending"
    } catch (_: Throwable) { false }

    // ───────────────────── matn yordamchilari ─────────────────────

    /** Label yoki paketni faqat harf/raqamli kichik tokenlarga ajratadi. */
    private fun tokenize(s: String?): Set<String> {
        if (s.isNullOrBlank()) return emptySet()
        return s.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Levenshtein masofasi, [max]'dan oshsa erta to'xtaydi (band kengligi optimizatsiyasi).
     * [max]'dan katta bo'lsa max+1 qaytaradi (aniq qiymat kerak emas).
     */
    private fun levenshtein(a: String, b: String, max: Int): Int {
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > max) return max + 1
        if (la == 0) return lb
        if (lb == 0) return la

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // o'chirish
                    curr[j - 1] + 1,    // qo'shish
                    prev[j - 1] + cost  // almashtirish
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1 // butun qator chegaradan oshdi — erta chiqish
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }
}
