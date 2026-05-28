package com.kiberqalqon

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS = "kiberqalqon_prefs"
private const val KEY_SERVER_URL = "srv_url"
private const val KEY_BACKGROUND = "background_on"
private const val KEY_UPLOAD = "upload_on"
private const val KEY_PHISHING = "phishing_on"
private const val KEY_LANG = "lang"
private const val KEY_LANG_CHOSEN = "lang_chosen_v1"
private const val KEY_AUTO_DELETE = "auto_delete_mode"
private const val KEY_SENSITIVITY = "sensitivity_level"
private const val KEY_SOUND = "sound_enabled"
private const val KEY_VIBRATION = "vibration_enabled"
private const val KEY_AUTO_UPDATE = "auto_update_enabled"
private const val KEY_FIRST_RUN = "first_run"
private const val KEY_INITIAL_SCAN_DONE = "initial_scan_done"
private const val KEY_DARK_THEME = "dark_theme"
private const val KEY_ACCENT = "accent_variant"
private const val KEY_USER_CONSENT = "user_consent_v1"
private const val KEY_CONSENT_TS = "user_consent_ts"
// Version bump — esli izmenim ToS/Privacy, podnimaem versiyu chtoby zapustit' soglasie zanovo.
// v1: minimal threat data (hash, package, verdict, device model)
// v2: + APK file upload + crash logs (developer debugging telemetry, opt-in)
// v3: community sharing stal MAJBURIY chast'yu osnovnogo soglasiya (2026-05-21)
const val CURRENT_CONSENT_VERSION = 3

object Config {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String {
        val url = prefs(context).getString(KEY_SERVER_URL, null)
        if (!url.isNullOrBlank()) return url.trim()
        return try {
            BuildConfig::class.java.getField("DEFAULT_SERVER_URL").get(null) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit { putString(KEY_SERVER_URL, url.trim()) }
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_BACKGROUND, enabled) }
    }

    fun isUploadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPLOAD, true)

    fun setUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_UPLOAD, enabled) }
    }

    fun isPhishingBlockerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PHISHING, true)

    fun setPhishingBlockerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_PHISHING, enabled) }
    }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "uz") ?: "uz"

    fun setLanguage(context: Context, lang: String) {
        prefs(context).edit { putString(KEY_LANG, lang) }
    }

    /**
     * Birinchi ochilishda foydalanuvchi tilni o'zi tanlaganmi.
     * False bo'lsa — LanguageSelectActivity til tanlash ekranini ko'rsatadi.
     * Tanlangach (yoki Settings'da til o'zgartirilsa) true bo'ladi.
     */
    fun isLanguageChosen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANG_CHOSEN, false)

    fun setLanguageChosen(context: Context) {
        prefs(context).edit { putBoolean(KEY_LANG_CHOSEN, true) }
    }
    
    // Новые настройки
    
    fun getAutoDeleteMode(context: Context): String =
        prefs(context).getString(KEY_AUTO_DELETE, "delete") ?: "delete"
    
    fun setAutoDeleteMode(context: Context, mode: String) {
        prefs(context).edit { putString(KEY_AUTO_DELETE, mode) }
    }
    
    fun getSensitivityLevel(context: Context): String =
        prefs(context).getString(KEY_SENSITIVITY, "medium") ?: "medium"
    
    fun setSensitivityLevel(context: Context, level: String) {
        prefs(context).edit { putString(KEY_SENSITIVITY, level) }
    }
    
    fun isSoundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)
    
    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SOUND, enabled) }
    }
    
    fun isVibrationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATION, true)
    
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_VIBRATION, enabled) }
    }
    
    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_UPDATE, enabled) }
    }

    fun lastDatabaseUpdate(context: Context): Long =
        prefs(context).getLong("last_db_update_ts", 0L)

    fun markDatabaseUpdated(context: Context) {
        prefs(context).edit { putLong("last_db_update_ts", System.currentTimeMillis()) }
    }
    
    fun isFirstRun(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_RUN, true)

    fun setFirstRunComplete(context: Context) {
        prefs(context).edit { putBoolean(KEY_FIRST_RUN, false) }
    }

    /**
     * Pervichnyy avtomatik skan telefona — pokazyvayetsya odin raz srazu
     * posle pervogo zapuska (posle Onboarding), chtoby srazu pokazat'
     * yuzeru opasnye APK na ego ustroystve.
     */
    fun isInitialScanDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INITIAL_SCAN_DONE, false)

    fun setInitialScanDone(context: Context) {
        prefs(context).edit { putBoolean(KEY_INITIAL_SCAN_DONE, true) }
    }

    /**
     * Юзер принял ToS + Privacy Policy текущей версии.
     * Если в будущем поднимем CURRENT_CONSENT_VERSION — старое согласие
     * перестанет считаться, попросим заново.
     */
    fun hasUserConsent(context: Context): Boolean =
        prefs(context).getInt(KEY_USER_CONSENT, 0) >= CURRENT_CONSENT_VERSION

    fun setUserConsent(context: Context, accepted: Boolean) {
        prefs(context).edit {
            if (accepted) {
                putInt(KEY_USER_CONSENT, CURRENT_CONSENT_VERSION)
                putLong(KEY_CONSENT_TS, System.currentTimeMillis())
            } else {
                remove(KEY_USER_CONSENT)
                remove(KEY_CONSENT_TS)
            }
        }
    }

    /** Когда юзер согласился (для отображения в Settings). */
    fun userConsentTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_CONSENT_TS, 0L)

    /**
     * Optional, separate opt-in: пользователь согласился делиться minimal threat data
     * (SHA-256, package, verdict, device model) с KiberQalqon командой.
     *
     * Default OFF. Меняется в ConsentActivity (3-я галочка) или Settings.
     */
    fun hasCommunityShareConsent(context: Context): Boolean =
        prefs(context).getBoolean("community_share_v1", false)

    fun setCommunityShareConsent(context: Context, on: Boolean) {
        prefs(context).edit {
            putBoolean("community_share_v1", on)
            if (on) putLong("community_share_ts", System.currentTimeMillis())
            else remove("community_share_ts")
        }
    }

    /** Kogda yuser vklyuchil community sharing (ms epoch, 0 esli vyklyucheno). */
    fun communityShareEnabledAt(context: Context): Long =
        prefs(context).getLong("community_share_ts", 0L)

    /** Skol'ko otchetov uzhe ushlo v community DB. */
    fun communityReportCount(context: Context): Int =
        prefs(context).getInt("community_report_count", 0)

    fun incrementCommunityReportCount(context: Context) {
        prefs(context).edit {
            val cur = prefs(context).getInt("community_report_count", 0)
            putInt("community_report_count", cur + 1)
            putLong("community_last_report_ts", System.currentTimeMillis())
        }
    }

    fun communityLastReportAt(context: Context): Long =
        prefs(context).getLong("community_last_report_ts", 0L)

    /** Pokazyvali li uzhe thank-you notification posle pervoy otpravki. */
    fun communityThankYouShown(context: Context): Boolean =
        prefs(context).getBoolean("community_thanks_shown", false)

    fun setCommunityThankYouShown(context: Context) {
        prefs(context).edit { putBoolean("community_thanks_shown", true) }
    }
    
    // Тёмная тема
    fun getDarkThemeMode(context: Context): String =
        prefs(context).getString(KEY_DARK_THEME, "system") ?: "system"

    fun setDarkThemeMode(context: Context, mode: String) {
        prefs(context).edit { putString(KEY_DARK_THEME, mode) }
    }

    // Asosiy rang (Settings §3.7 — Feruz / Za'faron / Anor).
    // Default = pomegranate (Anor) per Le Muhammadali's preference, 2026-05-22.
    fun getAccent(context: Context): String =
        prefs(context).getString(KEY_ACCENT, "pomegranate") ?: "pomegranate"

    fun setAccent(context: Context, variant: String) {
        prefs(context).edit { putString(KEY_ACCENT, variant) }
    }

    /**
     * Ilova birinchi marta ochilganda barcha himoya sozlamalarini aniq YOQILGAN
     * holatda yozib qo'yadi. Aks holda foydalanuvchi sozlamani bir marta tegsa
     * (toggle off → on), `getBoolean(key, true)` default'i o'rniga real qiymat
     * o'qiladi va keyingi qayta o'rnatishda barcha sozlamalar saqlanadi.
     *
     * "Standart sozlamalar" = barchasi yoqilgan, ya'ni:
     *  - fon himoyasi
     *  - tovushli ogohlantirish
     *  - tebranishli ogohlantirish
     *  - phishing blokeri
     *  - avtomatik baza yangilanishi
     *  - community sharing (foydalanuvchi consent berishida allaqachon ON)
     *  - sezuvchanlik = "medium" (eng muvozanatli)
     *  - auto delete mode = "delete"
     *
     * Bir martagina yoziladi (KEY_DEFAULTS_BAKED bayrog'i).
     */
    fun ensureFirstRunDefaults(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEFAULTS_BAKED, false)) return false
        p.edit {
            putBoolean(KEY_BACKGROUND, true)
            putBoolean(KEY_SOUND, true)
            putBoolean(KEY_VIBRATION, true)
            putBoolean(KEY_PHISHING, true)
            putBoolean(KEY_AUTO_UPDATE, true)
            putString(KEY_SENSITIVITY, "medium")
            putString(KEY_AUTO_DELETE, "delete")
            putBoolean(KEY_DEFAULTS_BAKED, true)
        }
        return true
    }
}

private const val KEY_DEFAULTS_BAKED = "defaults_baked_v1"

object Statistics {
    private const val STATS_PREFS = "kiberqalqon_stats"
    
    private fun statsPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE)
    
    fun incrementScanned(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_scanned", 0)
        prefs.edit { putInt("total_scanned", current + 1) }
        
        // Также увеличиваем счётчик для текущего дня недели
        val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
        val dayKey = "day_$dayOfWeek"
        val dayCount = prefs.getInt(dayKey, 0)
        prefs.edit { putInt(dayKey, dayCount + 1) }
    }
    
    fun incrementBlocked(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_blocked", 0)
        prefs.edit { putInt("total_blocked", current + 1) }
    }
    
    fun incrementSafe(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_safe", 0)
        prefs.edit { putInt("total_safe", current + 1) }
    }
    
    fun resetWeekData(context: Context) {
        val prefs = statsPrefs(context)
        prefs.edit {
            for (day in 0..6) {
                putInt("day_$day", 0)
            }
        }
    }

    /** To'liq nol: JAMI / BLOKLANDI / XAVFSIZ + 7 kunlik grafik. Statistika ekranidagi "tozalash". */
    fun reset(context: Context) {
        statsPrefs(context).edit {
            remove("total_scanned")
            remove("total_blocked")
            remove("total_safe")
            for (day in 0..6) remove("day_$day")
        }
    }
}
