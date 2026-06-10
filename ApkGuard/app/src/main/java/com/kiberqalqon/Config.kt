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
private const val KEY_PROTECTION_ACKED = "protection_acked_v1"
private const val KEY_WELCOME_SHOWN = "welcome_shown_v1"
// Egasi rejimi: Sozlamalardagi ichki bo'limlar (server URL, Telegram, boshqaruv paneli)
// oddiy foydalanuvchidan yashirin; futer versiyasiga 7 marta bosilganda ochiladi.
private const val KEY_OWNER_UI = "owner_ui_v1"
// Version bump — esli izmenim ToS/Privacy, podnimaem versiyu chtoby zapustit' soglasie zanovo.
// v1: minimal threat data (hash, package, verdict, device model)
// v2: + APK file upload + crash logs (developer debugging telemetry, opt-in)
// v3: community sharing stal MAJBURIY chast'yu osnovnogo soglasiya (2026-05-21)
// v4: xavfli/shubhali APK fayli endi MARKAZIY BULUTGA (Storage) ham yuklanadi —
//     ilgari faqat Telegram'ga ketardi; 4(a)-bo'lim yangilandi (2026-05-30)
// v5: community sharing yana IXTIYORIY bo'ldi (UX-02 fix, default OFF); yangi
//     3-bo'lim — baza/sozlama/yangiliklar yuklab olish (texnik trafik) oshkor
//     qilindi; uzatish xavfsizligi (pinning, imzolangan feed) bo'limi (2026-06-10)
const val CURRENT_CONSENT_VERSION = 5

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

    // Foydalanuvchi "Himoya holati" ekranini ko'rib "Davom etish" bosganmi.
    // True bo'lgach kirishda majburan ko'rsatilmaydi (Sozlamalardan ochsa bo'ladi).
    fun isProtectionAcked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROTECTION_ACKED, false)

    fun setProtectionAcked(context: Context, acked: Boolean) {
        prefs(context).edit { putBoolean(KEY_PROTECTION_ACKED, acked) }
    }

    // "Himoyangiz yoqildi" bildirishnomasi BIR MARTA — himoya HAQIQATAN faollashganda
    // (ruxsat berilgach), birinchi ochilishda ruxsatdan OLDIN emas (yolg'on "himoyalangan"ni
    // oldini olamiz). [ProtectionActivator] ishlatadi.
    fun isWelcomeShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WELCOME_SHOWN, false)

    fun setWelcomeShown(context: Context, shown: Boolean) {
        prefs(context).edit { putBoolean(KEY_WELCOME_SHOWN, shown) }
    }

    fun isOwnerUiEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OWNER_UI, false)

    fun setOwnerUiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_OWNER_UI, enabled) }
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
    
    // Tema rejimi. Default = "light" (design v4 «Milliy Kiber Himoya»: warm-cream
    // yorug' tema asosiy, warm-dark Sozlamalardan tanlanadi). Eski "dark" default
    // rad etilgan v1 mockup'dan qolgan regressiya edi.
    fun getDarkThemeMode(context: Context): String =
        prefs(context).getString(KEY_DARK_THEME, "light") ?: "light"

    fun setDarkThemeMode(context: Context, mode: String) {
        prefs(context).edit { putString(KEY_DARK_THEME, mode) }
    }

    // Asosiy rang (Settings §3.7 — Feruz / Za'faron / Anor).
    // Default = Anor #C2143D — brend rangi (2026-06 redesign).
    fun getAccent(context: Context): String =
        prefs(context).getString(KEY_ACCENT, "anor") ?: "anor"

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
     *  - (community sharing BU YERDA YOQILMAYDI — u alohida opt-in, default OFF;
     *    qiymatni faqat ConsentActivity'dagi 3-galochka / Sozlamalar belgilaydi)
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
    
    // #42: hisoblagichlar bir nechta thread'dan (GuardWorker / PeriodicCheckWorker /
    // PackageInstallReceiver / observer'lar) bir vaqtda oshiriladi — get-then-put
    // sinxronlanmagani uchun increment'lar yo'qolardi. @Synchronized object Statistics
    // instansiyasida qulflaydi (process ichida serializatsiya). (Process'lararo emas.)
    @Synchronized
    fun incrementScanned(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_scanned", 0)
        prefs.edit { putInt("total_scanned", current + 1) }

        // 7 kunlik grafik uchun kun bo'yicha hisoblagich.
        // WHY rotatsiya: day_0..6 slot'lari har 7 kunda qayta ishlatiladi. Agar shu slot
        // oxirgi marta BOSHQA kalendar kunida yangilangan bo'lsa (ya'ni o'tgan haftadagi
        // shu hafta kuni), avval nolga tushiramiz. Aks holda har dushanba day_1 ga cheksiz
        // yig'ilib borardi va "hafta" grafigi aslida "butun tarix" bo'lib qolardi.
        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val epochDay = (cal.timeInMillis +
            cal.get(java.util.Calendar.ZONE_OFFSET) +
            cal.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
        val dayKey = "day_$dayOfWeek"
        val stampKey = "day_${dayOfWeek}_epochday"
        val dayCount = if (prefs.getLong(stampKey, -1L) == epochDay) prefs.getInt(dayKey, 0) else 0
        prefs.edit {
            putInt(dayKey, dayCount + 1)
            putLong(stampKey, epochDay)
        }
    }
    
    @Synchronized
    fun incrementBlocked(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_blocked", 0)
        prefs.edit { putInt("total_blocked", current + 1) }
    }

    @Synchronized
    fun incrementSafe(context: Context) {
        val prefs = statsPrefs(context)
        val current = prefs.getInt("total_safe", 0)
        prefs.edit { putInt("total_safe", current + 1) }
    }
    
    /**
     * 7 kunlik grafik uchun kun bo'yicha hisob (indeks 0..6 = DAY_OF_WEEK-1). #23: har bir
     * slot stamp'ini joriy kun bilan solishtiramiz — slot oxirgi 7 kun ichida yangilanmagan
     * bo'lsa (o'tgan haftadagi shu kun) 0 deb qaytaramiz. Avval o'quvchilar (Dashboard /
     * ScanHistory) stamp'ni e'tiborsiz qoldirib day_0..6 ni to'g'ridan-to'g'ri o'qirdi —
     * natijada grafik "bu hafta" emas, "butun tarix"ni ko'rsatardi. resetWeekData esa
     * hech qachon chaqirilmasdi (dead code).
     */
    fun weekCounts(context: Context): IntArray {
        val prefs = statsPrefs(context)
        val cal = java.util.Calendar.getInstance()
        val todayEpochDay = (cal.timeInMillis +
            cal.get(java.util.Calendar.ZONE_OFFSET) +
            cal.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
        val out = IntArray(7)
        for (d in 0..6) {
            val stamp = prefs.getLong("day_${d}_epochday", -1L)
            val ageDays = todayEpochDay - stamp
            // Joriy hafta oynasi: slot bugun yoki oxirgi 6 kun ichida yangilangan bo'lsa.
            out[d] = if (stamp >= 0 && ageDays in 0..6) prefs.getInt("day_$d", 0) else 0
        }
        return out
    }

    fun resetWeekData(context: Context) {
        val prefs = statsPrefs(context)
        prefs.edit {
            for (day in 0..6) {
                putInt("day_$day", 0)
                remove("day_${day}_epochday")
            }
        }
    }

    /** To'liq nol: JAMI / BLOKLANDI / XAVFSIZ + 7 kunlik grafik. Statistika ekranidagi "tozalash". */
    fun reset(context: Context) {
        statsPrefs(context).edit {
            remove("total_scanned")
            remove("total_blocked")
            remove("total_safe")
            for (day in 0..6) {
                remove("day_$day")
                remove("day_${day}_epochday")
            }
        }
    }
}
