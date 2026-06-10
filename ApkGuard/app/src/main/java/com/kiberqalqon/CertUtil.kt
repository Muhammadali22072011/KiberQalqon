package com.kiberqalqon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Извлечение и сравнение SHA-256 от X.509-сертификата подписи APK.
 *
 * На API 28+ есть apkContentsSigners (правильный путь после v2/v3 schemes).
 * На старых — fallback на deprecated signatures.
 */
object CertUtil {

    /** SHA-256 от первого подписи APK (lowercase hex, без двоеточий). null если не удалось прочитать. */
    fun fingerprintSha256(context: Context, apkPath: String): String? = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo? = pm.getPackageArchiveInfo(apkPath, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info?.signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    /** SHA-256 от подписи самого KiberQalqon, для авто-whitelist. */
    fun selfFingerprintSha256(context: Context): String? = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * SHA-256 ot podpisi UZHE USTANOVLENNOGO paketa po imeni (lowercase hex).
     * null esli paket ne ustanovlen / ne viden (NameNotFoundException) ili podpis'
     * ne udalos' prochitat'.
     *
     * Ispol'zuetsya [AppReputation] dlya proverki: sovpadaet li podpis' skaniruemogo
     * APK s podpis'yu real'no ustanovlennogo prilozheniya s tem zhe imenem paketa.
     * Imya paketa legko poddelat' (lyuboy APK mozhet ob'yavit' sebya "com.android.chrome"),
     * podpis' — net. Bez etoy proverki feyk pod brendom prohodil kak SAFE.
     */
    fun installedFingerprintSha256(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(pkg, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * SHA-256 ot soderzhimogo fayla APK (lowercase hex). Eto te-zhe hashes, kotoryye
     * shlyutsya v community-report ("hash=..."). Ispol'zuetsya dlya proverki na
     * blacklist iz [MaliciousHashes]. null esli ne smogli prochitat' fayl.
     *
     * Vazhno: my ne mappiruem ves' fayl v pamyat' — mozhet byt' 50+ MB.
     * Chitanie v bufer po 64 KB hvataet dlya bystrogo hash.
     */
    fun apkFileSha256(apkPath: String): String? = try {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            null
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            sha256HexBytes(digest.digest())
        }
    } catch (_: Exception) {
        null
    }

    /**
     * ENG-04: o'rnatilgan paketning BARCHA yaroqli imzo SHA-256'lari — joriy imzo(lar) +
     * KALIT ROTATSIYA TARIXI (signingCertificateHistory). Google Play App Signing kalitni
     * rotatsiya qilganда (yoki ilova Play App Signing'ga o'tganда) yangi APK BOSHQA kalit bilan
     * imzolanadi; faqat firstOrNull()'ni solishtirish legit yangilanishni "soxta imzo" (DANGER)
     * deb belgilardi — айнан репода ташвиш bo'lgan O'zbek gov/bank ilovalarида. Tarixни ham
     * qo'shsak, rotatsiya zanjiridagi har qanday imzo VERIFIED bo'ladi. Soxta ilova (zanjirда
     * yo'q begona kalit) baribir MISMATCH → DANGER bo'lib qoladi.
     */
    fun installedSigningFingerprints(context: Context, pkg: String): Set<String> = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            val sigs = buildList {
                si?.apkContentsSigners?.let { addAll(it.toList()) }
                // hasMultipleSigners() bo'lsa tarix mavjud emas; aks holda rotatsiya zanjiri.
                if (si != null && !si.hasMultipleSigners()) {
                    si.signingCertificateHistory?.let { addAll(it.toList()) }
                }
            }
            sigs.mapNotNull { it?.toByteArray()?.let { b -> sha256Hex(b) } }.toSet()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            (info.signatures ?: emptyArray()).mapNotNull { it?.toByteArray()?.let { b -> sha256Hex(b) } }.toSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return sha256HexBytes(digest)
    }

    private fun sha256HexBytes(digest: ByteArray): String {
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
