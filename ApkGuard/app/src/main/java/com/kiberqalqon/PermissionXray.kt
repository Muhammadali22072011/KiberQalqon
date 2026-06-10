package com.kiberqalqon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * «Ruxsat rentgeni» (Permission X-Ray) — O'RNATILGAN ilovalarni ruxsat-KOMBINATSIYASI
 * bo'yicha xavf darajasiga ko'ra tartiblaydi.
 *
 * Miqdor emas, KOMBINATSIYA muhim: zamonaviy banker-troyan atigi 2-3 ruxsat so'raydi,
 * lekin aynan halokatli juftlikda (SMS+Accessibility, overlay+a11y+net). [PermissionCombos]
 * shu kombo'larni balllaydi — bu X-ray har bir ilova uchun o'sha ballni hisoblaydi.
 *
 * #16 KO'PRIK: BIND_ACCESSIBILITY_SERVICE / BIND_DEVICE_ADMIN / BIND_NOTIFICATION_LISTENER_SERVICE
 * <uses-permission> emas, <service android:permission> da e'lon qilinadi — shuning uchun
 * requestedPermissions'da YO'Q. ApkScanner.kt:890 dagi ko'prikni shu yerda GET_SERVICES orqali
 * takrorlaymiz, aks holda eng kuchli combo'lar (OTP-grabber=90, Full banker=100) hech qachon ishlamasdi.
 *
 * Bu REVYU-vositasi: o'zi hech narsani o'chirmaydi, hech qachon istisno tashlamaydi.
 */
object PermissionXray {

    private const val TAG = "PermissionXray"

    private const val P = "android.permission."
    private const val BIND_A11Y = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val BIND_ADMIN = "android.permission.BIND_DEVICE_ADMIN"
    private const val BIND_NOTIF = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

    /**
     * #16 ko'prik orqali qo'shiladigan SERVICE-ruxsatlari — PermissionsDetailActivity.classify
     * ularni bilmaydi (NORMAL qaytaradi). Bularni X-ray detalida DOIM KRITIK ko'rsatamiz, aks holda
     * ball WARN/DANGER bo'lsa-da "kritik ruxsat yo'q" deb chiqib, ziddiyat va o'lik string'lar paydo bo'lardi.
     */
    private val BRIDGED_CRIT_PERMS = setOf(BIND_A11Y, BIND_ADMIN, BIND_NOTIF)

    data class AppRisk(
        val pkg: String,
        val label: String,
        val fromPlay: Boolean,
        val score: Int,
        val combos: List<String>,        // PermissionCombos.Match labellari (worst-first emas — score totalga ta'sir qiladi)
        val critPerms: List<String>,     // PermissionsDetailActivity.classify → CRIT (to'liq permission nomlari)
        val warnPerms: List<String>      // PermissionsDetailActivity.classify → WARN (to'liq permission nomlari)
    )

    /**
     * O'rnatilgan ilovalarni ruxsat-xavfi bo'yicha kamayuvchi tartibda qaytaradi.
     * O'z paketimiz + ".debug" + sof tizim ilovalari (yangilangan tizim ilovasidan tashqari) chiqarib tashlanadi.
     */
    fun scan(context: Context): List<AppRisk> {
      return try {
        val pm = context.packageManager
        val ownPkgs = setOf(context.packageName, "${context.packageName}.debug")

        val apps = try {
            pm.getInstalledApplications(0)
        } catch (e: Throwable) {
            Log.w(TAG, "getInstalledApplications failed", e)
            return emptyList()
        }

        val out = ArrayList<AppRisk>()
        for (app in apps) {
            try {
                val pkg = app.packageName
                if (pkg in ownPkgs) continue
                if (isPureSystem(app)) continue   // yangilangan tizim ilovalari (FLAG_UPDATED_SYSTEM_APP) qoladi

                val perms = requestedPermsWithBridge(pm, pkg)
                if (perms.isEmpty()) continue

                val (score, combos) = scoreOf(perms)
                // Ball 0 bo'lsa — hech qanday xavfli kombo yo'q, ro'yxatga qo'shmaymiz (shovqin kamaytirish).
                if (score <= 0) continue

                val crit = ArrayList<String>()
                val warn = ArrayList<String>()
                classifyPerms(perms, crit, warn)

                out.add(
                    AppRisk(
                        pkg = pkg,
                        label = labelOf(pm, app),
                        fromPlay = fromPlay(pm, pkg),
                        score = score,
                        combos = combos,
                        critPerms = crit,
                        warnPerms = warn
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "per-app x-ray failed", e)
            }
        }
        out.sortedByDescending { it.score }
      } catch (e: Throwable) {
        Log.w(TAG, "scan failed", e)
        emptyList()
      }
    }

    /**
     * SOF skorlash — testlanadigan yadro (Android'siz Set<String> beriladi).
     * [PermissionCombos] ni o'raydi va ENG-03 device-admin istisnosini qo'llaydi:
     * faqat-BIND_DEVICE_ADMIN talab qiladigan Ransomware-kombo (legit MDM/Find-My-Device)
     * yagona zararli signal bo'lsa, uni ballga qo'shmaymiz.
     *
     * @return Pair(totalScore, comboLabels) — combo yorliqlari ball bo'yicha kamayuvchi tartibda.
     */
    internal fun scoreOf(perms: Set<String>): Pair<Int, List<String>> = try {
        val matches = PermissionCombos.evaluate(perms)

        // ENG-03: BIND_DEVICE_ADMIN'ning O'ZINI talab qiladigan kombo'larni chiqarib tashlaymiz
        // (mirror ApkScanner.kt:912 comboScoreIndependentOfDeviceAdmin).
        val effective = matches.filter { m ->
            val req = m.combo.required
            !(req.size == 1 && req.contains(BIND_ADMIN))
        }

        val score = PermissionCombos.totalScore(effective)
        val labels = effective
            .sortedByDescending { it.combo.score }
            .map { it.combo.label }
        score to labels
    } catch (e: Throwable) {
        Log.w(TAG, "scoreOf failed", e)
        0 to emptyList()
    }

    /**
     * requestedPermissions + #16 ko'prik: 3 ta service-permission'ni GET_SERVICES orqali qo'shamiz.
     */
    private fun requestedPermsWithBridge(pm: PackageManager, pkg: String): Set<String> {
        val perms = LinkedHashSet<String>()
        try {
            val reqs = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
            if (reqs != null) perms.addAll(reqs)
        } catch (_: Throwable) { /* davom etamiz — service ko'prigi baribir ishlaydi */ }

        // #16 KO'PRIK: <service android:permission="..."> dan bind-ruxsatlarni chiqaramiz.
        try {
            val services = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
            if (services != null) {
                for (svc in services) {
                    when (svc.permission) {
                        BIND_A11Y -> perms.add(BIND_A11Y)
                        BIND_NOTIF -> perms.add(BIND_NOTIF)
                    }
                }
            }
        } catch (_: Throwable) { /* muhim emas */ }

        // BIND_DEVICE_ADMIN <receiver android:permission> da bo'ladi — GET_RECEIVERS orqali.
        try {
            val receivers = pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS).receivers
            if (receivers != null) {
                for (rcv in receivers) {
                    if (rcv.permission == BIND_ADMIN) perms.add(BIND_ADMIN)
                }
            }
        } catch (_: Throwable) { /* muhim emas */ }

        return perms
    }

    /** Ruxsatlarni PermissionsDetailActivity.classify orqali CRIT / WARN ga ajratamiz. */
    private fun classifyPerms(perms: Set<String>, crit: MutableList<String>, warn: MutableList<String>) {
        for (perm in perms) {
            try {
                // #16 ko'prik bilan qo'shilgan SERVICE-ruxsatlari (PermissionsDetailActivity ularni
                // bilmaydi → NORMAL'ga tushardi va detal ekrandan tushib qolardi, ball WARN/DANGER
                // bo'lsa-da). Ularni shu yerda KRITIK deb belgilaymiz — aks holda "kritik ruxsat yo'q"
                // degan ziddiyatli xabar chiqardi va kq4_xray_perm_notif satri o'lik kod bo'lib qolardi.
                if (perm in BRIDGED_CRIT_PERMS) {
                    crit.add(perm)
                    continue
                }
                when (PermissionsDetailActivity.classify(perm)) {
                    PermissionsDetailActivity.Sev.CRIT -> crit.add(perm)
                    PermissionsDetailActivity.Sev.WARN -> warn.add(perm)
                    PermissionsDetailActivity.Sev.NORMAL -> { /* ko'rsatmaymiz */ }
                }
            } catch (_: Throwable) { /* shubhali ruxsatni e'tiborsiz qoldiramiz */ }
        }
    }

    /** Sof tizim ilovasi (yangilangan tizim ilovasi EMAS). */
    private fun isPureSystem(app: ApplicationInfo): Boolean {
        val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updated = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return system && !updated
    }

    private fun labelOf(pm: PackageManager, app: ApplicationInfo): String =
        try { pm.getApplicationLabel(app).toString() } catch (_: Throwable) { app.packageName }

    /** Install-manba Play Market'mi (qolgan repo idiomi bilan bir xil). */
    private fun fromPlay(pm: PackageManager, pkg: String): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer == "com.android.vending"
    } catch (_: Throwable) { false }
}
