package com.uzguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whitelist официальных приложений, которые часто триггерят false positive.
 *
 * Telegram/WhatsApp требуют REQUEST_INSTALL_PACKAGES + INTERNET → выглядят подозрительно,
 * но это легитимный софт. Проверяем по комбинации (package + sha256(cert)).
 *
 * ПОЧЕМУ ПАРА, А НЕ ТОЛЬКО ПАКЕТ:
 *   Малварь может объявить себя package="org.telegram.messenger" — Android этого не запрещает,
 *   пока подпись отличается от установленной. Но в APK-файле (offline scan) пакет — это просто
 *   строка. Без проверки cert-fingerprint whitelist по package превращается в backdoor.
 *
 * ТРИ ИСТОЧНИКА доверенных (package, cert) пар:
 *   1) [ENTRIES] — статически зашитые (нужны РЕАЛЬНЫЕ fingerprints; пусто по умолчанию).
 *   2) [dynamicSelf] — сам UzGuard (вычисляется в рантайме, [registerSelf]).
 *   3) [dynamicVendors] — сертификаты доверенных вендоров, РЕАЛЬНО установленных на
 *      устройстве ИЗ Google Play ([captureInstalledTrusted]). Без выдуманных хэшей.
 *
 * ВАЖНО: пустой fingerprint = "пока не верифицирован" = whitelist не сработает.
 * Никогда не оставляй "" или "TODO" в проде — лучше удалить запись.
 */
object TrustedSignatures {

    /** Пара (package, sha256 cert) -> читаемое имя для логов и UI. */
    private val ENTRIES: Map<Pair<String, String>, String> = mapOf(
        // Сам UzGuard — fingerprint вычисляется в рантайме (см. registerSelf()).
        // Здесь только остальные.

        // ----- НИЖЕ FINGERPRINTS НУЖНО ЗАПОЛНИТЬ ВРУЧНУЮ -----
        // Скрипт: python scripts/extract_cert_fingerprint.py <apk>
        // Пока пусто — whitelist по этим пакетам опирается на dynamicVendors (рантайм-пин
        // с Google Play). Это безопасное поведение по умолчанию (никаких выдуманных хэшей).

        // ("org.telegram.messenger" to "<sha256>")     to "Telegram",
        // ("org.telegram.messenger.web" to "<sha256>") to "Telegram (web)",
        // ("com.whatsapp" to "<sha256>")               to "WhatsApp",
        // ("com.android.vending" to "<sha256>")        to "Google Play Store",
        // ("uz.click.evo" to "<sha256>")               to "Click",
        // ("uz.dida.payme" to "<sha256>")              to "Payme",
        // ("uz.uzcard.uzcard" to "<sha256>")           to "Uzcard",
    )

    /**
     * Динамически вычисляемые доверенные fingerprints — сам UzGuard.
     * Заполняется при старте App.kt через [registerSelf].
     */
    private val dynamicSelf = mutableMapOf<Pair<String, String>, String>()

    /**
     * Сертификаты доверенных вендоров (AppReputation.TRUSTED_EXACT), реально установленных
     * на устройстве из Google Play. Заполняется в фоне через [captureInstalledTrusted].
     * ConcurrentHashMap — пишется из фонового потока, читается сканером.
     */
    private val dynamicVendors = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()

    fun registerSelf(packageName: String, sha256: String) {
        if (sha256.isNotBlank()) {
            dynamicSelf[packageName to sha256] = "UzGuard (self)"
        }
    }

    /**
     * Закрепляет (pin) сертификаты доверенных вендоров, РЕАЛЬНО установленных на устройстве
     * из Google Play. Тогда offline-скан APK с такой парой (package, cert) сразу даёт SAFE —
     * без device-проверки AppReputation. Никаких выдуманных fingerprint'ов: всё берётся из
     * настоящего установленного приложения. Снижение риска: пиним ТОЛЬКО приложения, чей
     * источник установки — Google Play (com.android.vending). Вызывать в фоне.
     */
    fun captureInstalledTrusted(context: Context) {
        val pm = context.packageManager
        for (pkg in AppReputation.exactTrustedPackages()) {
            try {
                if (!isFromTrustedStore(pm, pkg)) continue
                val fp = CertUtil.installedFingerprintSha256(context, pkg) ?: continue
                if (fp.isNotBlank()) dynamicVendors[pkg to fp] = pkg
            } catch (_: Throwable) {
                // не установлено / не видно (package visibility) — пропускаем, это безопасно.
            }
        }
    }

    private fun isFromTrustedStore(pm: PackageManager, pkg: String): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer == "com.android.vending"   // Google Play Store
    } catch (_: Throwable) {
        false
    }

    /** Вернёт имя доверенного приложения, если (package, fingerprint) в whitelist. */
    fun trustedName(packageName: String?, sha256: String?): String? {
        if (packageName.isNullOrBlank() || sha256.isNullOrBlank()) return null
        val key = packageName to sha256
        return ENTRIES[key] ?: dynamicSelf[key] ?: dynamicVendors[key]
    }
}
