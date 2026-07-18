package com.uzguard

import android.content.Context

/**
 * Har bir Android ruxsatini — oddiy, tushunarli nom (string-res) va xavf darajasiga
 * (CRITICAL / WARNING / NORMAL) bog'laydi. ScanResultActivity shu katalog yordamida
 * "Xavfli ruxsatlar" ro'yxatini odam tiliga tushunarli ko'rinishda chiqaradi.
 *
 * Bu — faqat KO'RSATISH (UI) uchun. Skaner verdikti ApkScanner ichida alohida hisoblanadi.
 */
object PermissionCatalog {

    enum class Severity { CRITICAL, WARNING, NORMAL }

    data class Info(val labelRes: Int, val severity: Severity)

    private val MAP: Map<String, Info> = mapOf(
        // ─── Mikrofon / ovoz ───
        "android.permission.RECORD_AUDIO" to Info(R.string.perm_record_audio, Severity.CRITICAL),
        "android.permission.FOREGROUND_SERVICE_MICROPHONE" to Info(R.string.perm_fg_microphone, Severity.CRITICAL),

        // ─── Joylashuv ───
        "android.permission.ACCESS_FINE_LOCATION" to Info(R.string.perm_fine_location, Severity.CRITICAL),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Info(R.string.perm_bg_location, Severity.CRITICAL),
        "android.permission.ACCESS_COARSE_LOCATION" to Info(R.string.perm_coarse_location, Severity.WARNING),

        // ─── Kamera ───
        "android.permission.CAMERA" to Info(R.string.perm_camera, Severity.WARNING),

        // ─── Kontaktlar / akkauntlar ───
        "android.permission.READ_CONTACTS" to Info(R.string.perm_read_contacts, Severity.WARNING),
        "android.permission.WRITE_CONTACTS" to Info(R.string.perm_write_contacts, Severity.WARNING),
        "android.permission.GET_ACCOUNTS" to Info(R.string.perm_get_accounts, Severity.WARNING),

        // ─── SMS / MMS ───
        "android.permission.SEND_SMS" to Info(R.string.perm_send_sms, Severity.CRITICAL),
        "android.permission.RECEIVE_SMS" to Info(R.string.perm_receive_sms, Severity.CRITICAL),
        "android.permission.READ_SMS" to Info(R.string.perm_read_sms, Severity.CRITICAL),
        "android.permission.RECEIVE_MMS" to Info(R.string.perm_receive_mms, Severity.WARNING),

        // ─── Qo'ng'iroqlar / telefon ───
        "android.permission.CALL_PHONE" to Info(R.string.perm_call_phone, Severity.CRITICAL),
        "android.permission.ANSWER_PHONE_CALLS" to Info(R.string.perm_answer_calls, Severity.CRITICAL),
        "android.permission.PROCESS_OUTGOING_CALLS" to Info(R.string.perm_outgoing_calls, Severity.CRITICAL),
        "android.permission.READ_CALL_LOG" to Info(R.string.perm_read_call_log, Severity.CRITICAL),
        "android.permission.WRITE_CALL_LOG" to Info(R.string.perm_write_call_log, Severity.CRITICAL),
        "android.permission.READ_PHONE_STATE" to Info(R.string.perm_phone_state, Severity.WARNING),
        "android.permission.READ_PHONE_NUMBERS" to Info(R.string.perm_phone_numbers, Severity.WARNING),

        // ─── Kuchli / tizimni egallovchi ───
        "android.permission.SYSTEM_ALERT_WINDOW" to Info(R.string.perm_overlay, Severity.CRITICAL),
        "android.permission.REQUEST_INSTALL_PACKAGES" to Info(R.string.perm_install_packages, Severity.CRITICAL),
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to Info(R.string.perm_accessibility, Severity.CRITICAL),
        "android.permission.BIND_DEVICE_ADMIN" to Info(R.string.perm_device_admin, Severity.CRITICAL),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to Info(R.string.perm_all_files, Severity.CRITICAL),

        // ─── Xotira / media ───
        "android.permission.READ_EXTERNAL_STORAGE" to Info(R.string.perm_read_storage, Severity.WARNING),
        "android.permission.WRITE_EXTERNAL_STORAGE" to Info(R.string.perm_write_storage, Severity.WARNING),
        "android.permission.READ_MEDIA_IMAGES" to Info(R.string.perm_media_images, Severity.WARNING),
        "android.permission.READ_MEDIA_VIDEO" to Info(R.string.perm_media_video, Severity.WARNING),
        "android.permission.READ_MEDIA_AUDIO" to Info(R.string.perm_media_audio, Severity.WARNING),

        // ─── Taqvim / sensorlar ───
        "android.permission.READ_CALENDAR" to Info(R.string.perm_read_calendar, Severity.WARNING),
        "android.permission.WRITE_CALENDAR" to Info(R.string.perm_write_calendar, Severity.WARNING),
        "android.permission.BODY_SENSORS" to Info(R.string.perm_body_sensors, Severity.WARNING),
    )

    fun info(permission: String): Info? = MAP[permission]

    fun severity(permission: String): Severity = MAP[permission]?.severity ?: Severity.NORMAL

    /** Faqat odamga ko'rsatish uchun xavfli (CRITICAL yoki WARNING) bo'lsa true. */
    fun isNotable(permission: String): Boolean = severity(permission) != Severity.NORMAL

    /** Tushunarli nom — katalogda bo'lsa tarjima, bo'lmasa qisqartirilgan xom nom. */
    fun label(context: Context, permission: String): String {
        val res = MAP[permission]?.labelRes
        return if (res != null) context.getString(res) else shortName(permission)
    }

    /** "android.permission.RECORD_AUDIO" → "RECORD_AUDIO" ; "com.foo.bar.BAZ" → "BAZ". */
    fun shortName(permission: String): String =
        permission.substringAfterLast('.').ifBlank { permission }
}
