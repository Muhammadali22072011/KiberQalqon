package com.kiberqalqon

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
 * КАК ДОБАВИТЬ НОВУЮ ЗАПИСЬ:
 *   1) Установи официальное приложение (с Google Play или с сайта банка).
 *   2) Запусти scripts/extract_cert_fingerprint.py path/to/official.apk
 *   3) Скопируй package + sha256 в [ENTRIES] ниже.
 *
 * ВАЖНО: пустой fingerprint = "пока не верифицирован" = whitelist не сработает.
 * Никогда не оставляй "" или "TODO" в проде — лучше удалить запись.
 */
object TrustedSignatures {

    /** Пара (sha256 cert) -> читаемое имя для логов и UI. */
    private val ENTRIES: Map<Pair<String, String>, String> = mapOf(
        // Сам KiberQalqon — fingerprint вычисляется в рантайме (см. selfFingerprint()).
        // Здесь только остальные.

        // ----- НИЖЕ FINGERPRINTS НУЖНО ЗАПОЛНИТЬ ВРУЧНУЮ -----
        // Скрипт: python scripts/extract_cert_fingerprint.py <apk>
        // Пока пусто — whitelist по этим пакетам НЕ сработает (это безопасное поведение по умолчанию).

        // ("org.telegram.messenger" to "<sha256>")     to "Telegram",
        // ("org.telegram.messenger.web" to "<sha256>") to "Telegram (web)",
        // ("com.whatsapp" to "<sha256>")               to "WhatsApp",
        // ("com.android.vending" to "<sha256>")        to "Google Play Store",
        // ("uz.click.evo" to "<sha256>")               to "Click",
        // ("uz.dida.payme" to "<sha256>")              to "Payme",
        // ("uz.uzcard.uzcard" to "<sha256>")           to "Uzcard",
    )

    /**
     * Динамически вычисляемые доверенные fingerprints — сам KiberQalqon.
     * Заполняется при старте App.kt через [registerSelf].
     */
    private val dynamicSelf = mutableMapOf<Pair<String, String>, String>()

    fun registerSelf(packageName: String, sha256: String) {
        if (sha256.isNotBlank()) {
            dynamicSelf[packageName to sha256] = "KiberQalqon (self)"
        }
    }

    /** Вернёт имя доверенного приложения, если (package, fingerprint) в whitelist. */
    fun trustedName(packageName: String?, sha256: String?): String? {
        if (packageName.isNullOrBlank() || sha256.isNullOrBlank()) return null
        val key = packageName to sha256
        return ENTRIES[key] ?: dynamicSelf[key]
    }
}
