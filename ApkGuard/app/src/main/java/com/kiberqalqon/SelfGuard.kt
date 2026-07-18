package com.uzguard

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Защита от самоуничтожения: UzGuard НИКОГДА не должен сканировать/удалять собственный APK.
 *
 * Проверки (любая срабатывает — это наш APK):
 *  1) Совпадение пакета (com.uzguard или com.uzguard.debug)
 *  2) Файл расположен в /data/app/com.uzguard... (системная папка установленного APK)
 *  3) Подпись APK совпадает с подписью установленного UzGuard (release/debug keystore)
 *  4) Имя файла начинается с "uzguard" ИЛИ "apkguard" — fallback,
 *     срабатывает только если PackageManager не смог распарсить APK
 *     (corrupted, scoped storage, etc.). Без сигнатурной проверки сюда
 *     полагаться нельзя, но в комбинации с (3) безопасно.
 *
 * Используется в:
 *  - ApkScanner.scan()      — возвращаем SAFE без сканирования
 *  - ApkScanner.findApkFiles() — отфильтровываем из списка
 *  - FileDeleter.delete()   — отказываемся удалять, чтобы юзер не убил защитника случайно
 */
object SelfGuard {

    private const val TAG = "SelfGuard"
    // com.apkguard* — eski paket nomi (uzguard'ga qayta nomlashdan oldin). Eski
    // build'lar ham "o'ziniki" deb tan olinadi (OWN_FILENAME_PREFIXES'da "apkguard" allaqachon bor).
    private val OWN_PACKAGES = setOf(
        "com.uzguard", "com.uzguard.debug",
        "com.apkguard", "com.apkguard.debug",
    )
    private val OWN_FILENAME_PREFIXES = listOf("uzguard", "apkguard")

    // Self-signature kesh: PackageManager chaqiruvi sekin (~10ms per call), o'z
    // imzomiz hech qachon o'zgarmaydi. Birinchi chaqirikda hisoblab, qoldirib turamiz.
    @Volatile private var cachedSelfSig: String? = null
    @Volatile private var selfSigComputed: Boolean = false

    private fun selfSig(context: Context): String? {
        if (selfSigComputed) return cachedSelfSig
        synchronized(this) {
            if (selfSigComputed) return cachedSelfSig
            cachedSelfSig = try { CertUtil.selfFingerprintSha256(context) } catch (_: Throwable) { null }
            selfSigComputed = true
            return cachedSelfSig
        }
    }

    /** True, если этот файл — наш собственный установленный APK или сборочный артефакт. */
    fun isOwnApk(context: Context, apkPath: String): Boolean {
        try {
            // 1) Системные пути установленного приложения.
            //    Android хранит APK как /data/app/<package>-<hash>/base.apk, а на
            //    Android 10+ — /data/app/~~rand~~/<package>-<hash>/base.apk.
            //
            //    MUHIM (kritik bug-fix): avval bu yerda `path.contains("/$pkg/")`
            //    ham bor edi. Bu HALOKATLI false-positive berardi: ShareReceiver
            //    har bir kelgan APK'ni o'z cache'iga ko'chiradi
            //    (/data/data/com.uzguard.debug/cache/shared/... yoki
            //     /sdcard/Android/data/com.uzguard.debug/cache/...). Bu yo'lda
            //    ham "/com.uzguard.debug/" bor → HAR QANDAY skanlangan virus
            //    "o'zimiznikidir" deb SAFE qaytarilardi (ZipEncryption/Dropper
            //    tekshiruvlari umuman ishga tushmasdan). Endi faqat HAQIQIY
            //    o'rnatilgan joy — /data/app/ ostidagi APK — "o'ziniki" deb
            //    hisoblanadi.
            //
            //    HAVFSIZLIK (HIGH bug-fix): avval `path.contains("/data/app/")`
            //    ishlatilardi — bu ANCHOR'lanmagan substring. Hujumchi
            //    /storage/emulated/0/Download/data/app/com.uzguard/evil.apk
            //    yaratsa, unda ham "/data/app/" va "/com.uzguard/" bor edi →
            //    APK skanlashdan CHETLATILARDI (va o'chirishdan ham). Endi
            //    HAQIQIY fayl tizimi ildiziga anchor qilamiz: canonicalPath
            //    ROSA "/data/app/" bilan boshlanishi shart (app u yerga
            //    yozolmaydi), keyingina paket segmentini tekshiramiz.
            //    O'zimizning APK boshqa joyda bo'lsa ham (2) paket va (4) imzo
            //    tekshiruvlari baribir tutadi.
            val canonical = try { File(apkPath).canonicalPath } catch (_: Exception) { apkPath }
                .replace('\\', '/')
            val canonLower = canonical.lowercase()
            if (canonLower.startsWith("/data/app/")) {
                for (pkg in OWN_PACKAGES) {
                    if (canonLower.contains("/$pkg-") || canonLower.contains("/$pkg/")) {
                        Log.d(TAG, "Self APK detected by install path: $apkPath")
                        return true
                    }
                }
            }

            // 2) Через PackageManager: читаем package из заголовка APK и сравниваем.
            val pm = context.packageManager
            val info = try { pm.getPackageArchiveInfo(apkPath, 0) } catch (_: Throwable) { null }
            val pkgName = info?.packageName
            if (pkgName != null && pkgName in OWN_PACKAGES) {
                Log.d(TAG, "Self APK detected by package: $pkgName")
                return true
            }

            // 3) Сравнение с собственным запущенным процессом.
            //    Если файл — это applicationInfo.sourceDir самого приложения, это мы.
            val ourSourceDir = context.applicationInfo.sourceDir
            if (ourSourceDir != null) {
                val ours = try { File(ourSourceDir).canonicalPath } catch (_: Exception) { ourSourceDir }
                val candidate = try { File(apkPath).canonicalPath } catch (_: Exception) { apkPath }
                if (ours.equals(candidate, ignoreCase = false)) {
                    Log.d(TAG, "Self APK detected by sourceDir match")
                    return true
                }
            }

            // 4) Imzo solishtirish. Bu eng ishonchli usul — debug build keystore va release
            //    keystore o'zining unikal imzosi bor; mos tushgan APK garantiya bilan
            //    bizniki. PackageName tekshiruvi muvaffaqiyatsiz bo'lganda ham ishlaydi.
            val ourSig = selfSig(context)
            if (ourSig != null) {
                val theirSig = try { CertUtil.fingerprintSha256(context, apkPath) } catch (_: Throwable) { null }
                if (theirSig != null && theirSig.equals(ourSig, ignoreCase = true)) {
                    Log.d(TAG, "Self APK detected by signature match")
                    return true
                }
            }

            // 5) Filename heuristic — OLIB TASHLANDI "o'ziniki=SAFE" sifatida (#6 false-safe).
            //    Avval: info==null va nom "uzguard"/"apkguard" bilan boshlansa true qaytarardi.
            //    Lekin bu HUJUMCHI NAZORATIDAGI nom bilan boshqariladigan ягона shart edi:
            //    "uzguard_update.apk" deb nomlangan va PackageManager parse qila olmaydigan
            //    qilib yasalgan dropper SAFE bo'lib, umuman skanlanmasdan o'tib ketardi.
            //    Bizning HAQIQIY APK valid ZIP — uni PM doim parse qiladi (info != null) va
            //    paket nomi (2) tutadi; demak bu fallback faqat "parse failed" holatda, ya'ni
            //    aynan hujumchi yasagan buzuq faylda ishlardi. Endi bunday faylni SAFE deb
            //    o'tkazmaymiz — uni odatdagidek skanlaymiz (zararli bo'lsa SUSPICIOUS/DANGER).
            //
            //    Eslatma: o'zimizning haqiqiy build artefakti (com.uzguard[.debug]) baribir
            //    (2) paket / (3) sourceDir / (4) imzo orqali tanaladi.
        } catch (e: Exception) {
            Log.w(TAG, "isOwnApk check failed", e)
            // При сомнении возвращаем false — лучше просканировать лишний раз,
            // чем пропустить чужой вирус.
        }
        return false
    }
}
