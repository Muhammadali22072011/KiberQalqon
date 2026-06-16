package com.uzguard

/**
 * Tarmoq sirlari (cloud qurilma kaliti, dev Telegram bot token/chat id) APK ichida
 * OCHIQ yotmasligi uchun [Shield] (keystream-XOR) shifrida BuildConfig'da saqlanadi.
 * `strings uzguard.apk | grep` sirni KO'RSATMAYDI — DEX'da faqat hex-"axlat".
 *
 * Build vaqtida app/build.gradle.kts local.properties'dagi qiymatni Shield bilan
 * shifrlab `*_ENC` BuildConfig maydoniga yozadi (algoritm bayt-ma-bayt [Shield] va
 * scripts/shield_encode.py bilan mos — ShieldTest bu mosligni ushlab turadi).
 *
 * Bo'sh sir → Shield.dec("") = "" → mavjud `isBlank()` gating saqlanadi (sir
 * sozlanmagan fork'lar avvalgidek no-op bo'ladi, crash bermaydi).
 *
 * MUHIM: bu maxfiy kalitni himoyalovchi kriptografiya EMAS — Shield kaliti baribir
 * APK ichida. Vazifasi: statik tahlilni (jadx/strings) yengib o'tib sirni qo'lga
 * kiritishni qiyinlashtirish va reverse narxini oshirish. Sirning O'G'IRLANISHIga
 * qarshi asl himoya — serverdagi per-device token + HMAC imzo + nonce + rate-limit
 * (cloud/lib/devauth.ts), shu sabab o'g'irlangan statik kalitning foydasi cheklangan.
 */
internal object Secrets {

    /** Cloud yozish kaliti (x-device-secret). Sozlanmagan bo'lsa "". */
    fun cloudDeviceSecret(): String = dec(BuildConfig.CLOUD_DEVICE_SECRET_ENC)

    /** Dev Telegram bot tokeni (community sharing). Sozlanmagan bo'lsa "". */
    fun tgBotToken(): String = dec(BuildConfig.DEV_TG_BOT_TOKEN_ENC)

    /** Dev Telegram chat id. Sozlanmagan bo'lsa "". */
    fun tgChatId(): String = dec(BuildConfig.DEV_TG_CHAT_ID_ENC)

    /** Imzolangan remote-config (RemoteConfig) HMAC kaliti. Sozlanmagan bo'lsa "". */
    fun configSigningSecret(): String = dec(BuildConfig.CONFIG_SIGNING_SECRET_ENC)

    private fun dec(encHex: String): String =
        if (encHex.isBlank()) "" else try { Shield.dec(encHex) } catch (_: Throwable) { "" }
}
