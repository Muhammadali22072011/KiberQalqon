package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import com.kiberqalqon.databinding.ActivityAboutBinding
import com.kiberqalqon.databinding.ActivitySettingsNewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sozlamalar — v4 «Milliy Kiber Himoya» (screens2.jsx → Settings).
 *
 * Bound to activity_settings_new.xml. All Config persistence happens reactively
 * (no Save tugmasi) — each toggle saves immediately. Theme/accent/lang changes
 * call recreate() so the affected views re-bind on the new palette.
 *
 * «Loyiha haqida» (About) manifestga yangi Activity qo'shmasdan shu yerda
 * to'liq ekran overlay (activity_about.xml) sifatida ko'rsatiladi.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsNewBinding

    /** About overlay (activity_about.xml) — null bo'lsa yopiq. */
    private var aboutBinding: ActivityAboutBinding? = null

    /** Set to true after first bindState — guards listeners from firing during initial bind. */
    private var ready = false

    /** Egasi rejimi uchun futer versiyasiga ketma-ket bosishlar soni. */
    private var footerTapCount = 0
    private var footerLastTapAt = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Миграция легаси-значений акцента ("turquoise"/"pomegranate"/"saffron") к
        // каноническим ("feruz"/"anor"/"zafaron") ДО applyAccent: иначе ThemeHelper
        // не узнаёт старое значение и молча применяет Anor, хотя пользователь на
        // прошлых версиях выбирал, например, Feruz (false UI state).
        migrateLegacyAccent()
        ThemeHelper.applyAccent(this)
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindVersionLabels()
        labelToggleRows()
        labelChevronRows()
        bindState()
        wireListeners()
        bindConsentSection()
        applyOwnerRowsVisibility()

        KqBottomNav.attach(this, KqBottomNav.Tab.SETTINGS)

        // Sozlamalar bo'limlari ketma-ket, yengil suriladi (Yorug' minimal kirish).
        binding.settingsContent.post {
            AnimationHelper.cascadeChildren(binding.settingsContent, delayBetween = 60)
        }

        ready = true
    }

    /** Profil kartochkasi + futerga real BuildConfig versiyani yozadi. */
    private fun bindVersionLabels() {
        binding.tvProfileVersion.text =
            getString(R.string.kq4_set_profile_version, BuildConfig.VERSION_NAME)
        binding.tvSettingsFooter.text =
            getString(R.string.kq4_set_footer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    /** Sets the title + sub + ic4 icon on each of the HIMOYA + QO'SHIMCHA toggle rows. */
    private fun labelToggleRows() {
        bindRow(
            binding.rowAutoScan.root,
            title = getString(R.string.kq4_set_row_autoscan_title),
            sub = getString(R.string.kq4_set_row_autoscan_sub),
            icon = R.drawable.ic4_scan,
        )
        bindRow(
            binding.rowAutoDelete.root,
            title = getString(R.string.kq4_set_row_autodelete_title),
            sub = getString(R.string.kq4_set_row_autodelete_sub),
            icon = R.drawable.ic4_trash,
        )
        bindRow(
            binding.rowPhishing.root,
            title = getString(R.string.kq4_set_row_phishing_title),
            sub = getString(R.string.kq4_set_row_phishing_sub),
            icon = R.drawable.ic4_message,
        )
        bindRow(
            binding.rowBackground.root,
            title = getString(R.string.kq4_set_row_background_title),
            sub = getString(R.string.kq4_set_row_background_sub),
            icon = R.drawable.ic4_refresh,
        )
        bindRow(
            binding.rowUpload.root,
            title = getString(R.string.set_row_upload_title),
            sub = getString(R.string.set_row_upload_sub),
            icon = R.drawable.ic4_wifi,
        )
        // QO'SHIMCHA — haftalik hisobot bildirishnomasi.
        bindRow(
            binding.rowWeeklyReport.root,
            title = getString(R.string.kq4_set_row_weekly),
            sub = getString(R.string.kq4_set_row_weekly_sub),
            icon = R.drawable.ic4_chart,
        )
    }

    private fun bindRow(root: View, title: String, sub: String, icon: Int) {
        root.findViewById<TextView>(R.id.tvRowTitle).text = title
        root.findViewById<TextView>(R.id.tvRowSub).text = sub
        try {
            root.findViewById<android.widget.ImageView>(R.id.ivRowIcon).setImageResource(icon)
        } catch (_: Throwable) { /* icon optional */ }
    }

    private fun labelChevronRows() {
        // HAQIDA
        bindChevron(binding.rowAbout.root, getString(R.string.about_title), R.drawable.ic4_heart)
        bindChevron(binding.rowHelp.root, getString(R.string.set_help_center), R.drawable.ic4_help)
        bindChevron(binding.rowReportProblem.root, getString(R.string.kq4_set_row_report), R.drawable.ic4_alert)
        // QO'SHIMCHA — flagman ekranlar + maxfiylik + boshqaruv paneli.
        bindChevron(binding.rowHiddenThreats.root, getString(R.string.kq4_set_row_hidden), R.drawable.ic4_eye)
        bindChevron(binding.rowTelegram.root, getString(R.string.kq4_set_row_telegram), R.drawable.ic4_bell)
        bindChevron(binding.rowProtectionStatus.root, getString(R.string.kq4_set_row_protection), R.drawable.ic4_shield_check)
        // Yangi flagman ekranlar (subtitr bilan): soxta bank skaneri + ruxsatlar X-nuri.
        bindChevronWithSub(
            binding.rowBankGuard.root,
            getString(R.string.kq4_set_row_bankguard),
            getString(R.string.kq4_set_row_bankguard_sub),
            R.drawable.ic4_card,
        )
        bindChevronWithSub(
            binding.rowPermXray.root,
            getString(R.string.kq4_set_row_xray),
            getString(R.string.kq4_set_row_xray_sub),
            R.drawable.ic4_layers,
        )
        bindChevron(binding.rowPrivacy.root, getString(R.string.privacy_title), R.drawable.ic4_lock)
        bindChevron(binding.rowAdminPanel.root, getString(R.string.kq4_set_row_admin), R.drawable.ic4_key)
    }

    private fun bindChevron(root: View, title: String, icon: Int) {
        root.findViewById<TextView>(R.id.tvChevronTitle).text = title
        try {
            root.findViewById<android.widget.ImageView>(R.id.ivChevronIcon).setImageResource(icon)
        } catch (_: Throwable) { /* icon optional */ }
    }

    /** bindChevron + subtitr (tvChevronSub sukut bo'yicha gone → ko'rsatamiz). */
    private fun bindChevronWithSub(root: View, title: String, sub: String, icon: Int) {
        bindChevron(root, title, icon)
        try {
            root.findViewById<TextView>(R.id.tvChevronSub).apply {
                text = sub
                visibility = View.VISIBLE
            }
        } catch (_: Throwable) { /* sub optional */ }
    }

    /** Snapshots current Config values into the UI. */
    private fun bindState() {
        // Toggle rows
        toggleOf(binding.rowAutoScan.root).isChecked = Config.isBackgroundEnabled(this)
        toggleOf(binding.rowAutoDelete.root).isChecked = Config.getAutoDeleteMode(this) == "delete"
        toggleOf(binding.rowPhishing.root).isChecked = Config.isPhishingBlockerEnabled(this)
        toggleOf(binding.rowBackground.root).isChecked = Config.isAutoUpdateEnabled(this)
        toggleOf(binding.rowUpload.root).isChecked = Config.isUploadEnabled(this)
        toggleOf(binding.rowWeeklyReport.root).isChecked = Config.isWeeklyReportEnabled(this)

        // Server URL display
        val url = Config.getServerUrl(this).ifBlank { getString(R.string.settings_server_url_example) }
        binding.etServerUrl.text = url

        // Theme segmented switch
        applyThemeSegmentUi(isDark = ThemeHelper.isDarkTheme(this))

        // Accent swatches
        applyAccentUi(Config.getAccent(this))

        // Language rows — faol til o'ngda check_circle (primary), boshqasi chevron.
        applyLanguageUi(Config.getLanguage(this))
    }

    private fun applyLanguageUi(lang: String) {
        val uzActive = lang != "ru"
        binding.ivLangUzState.setImageResource(
            if (uzActive) R.drawable.ic4_check_circle else R.drawable.ic4_chevron
        )
        binding.ivLangUzState.setColorFilter(
            getColor(if (uzActive) R.color.kq_primary else R.color.kq_ink_3)
        )
        binding.ivLangRuState.setImageResource(
            if (!uzActive) R.drawable.ic4_check_circle else R.drawable.ic4_chevron
        )
        binding.ivLangRuState.setColorFilter(
            getColor(if (!uzActive) R.color.kq_primary else R.color.kq_ink_3)
        )
    }

    private fun toggleOf(rowRoot: View): SwitchCompat =
        rowRoot.findViewById(R.id.swRow)

    private fun wireListeners() {
        // Each HIMOYA toggle → immediate Config save.
        toggleOf(binding.rowAutoScan.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setBackgroundEnabled(this, on)
            // BG-02: tumbler endi xizmatni HAQIQATAN boshqaradi. Avval faqat Config'ga yozib qo'yardi —
            // "o'chirdim" deganда ProtectionService va doimiy bildirishnoma turaverardi, "yoqdim" deganда
            // esa xizmat ishga tushmasdi (real-time himoya qaytmas edi). Endi: yoqilsa start, o'chsa stop.
            if (on) {
                ProtectionService.start(this)
            } else {
                ProtectionService.stop(this)
                // Doimiy "faol" bildirishnomasini darhol olib tashlaymiz (xizmat to'xtagach foreground
                // bildirishnoma odatda o'chadi, lekin refresh() bilan qo'yilgan nusxa qolmasligi uchun).
                try {
                    (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(ProtectionService.NOTIFICATION_ID)
                } catch (_: Throwable) {}
            }
            toastSaved()
        }
        toggleOf(binding.rowAutoDelete.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setAutoDeleteMode(this, if (on) "delete" else "warn")
            toastSaved()
        }
        toggleOf(binding.rowPhishing.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setPhishingBlockerEnabled(this, on)
            toastSaved()
        }
        toggleOf(binding.rowBackground.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setAutoUpdateEnabled(this, on)
            toastSaved()
        }
        toggleOf(binding.rowUpload.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setUploadEnabled(this, on)
            toastSaved()
        }
        toggleOf(binding.rowWeeklyReport.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setWeeklyReportEnabled(this, on)
            toastSaved()
        }

        // Server URL → edit dialog. Привязываем клик ко ВСЕМУ ряду (rowServerUrl),
        // не только к маленькому TextView c URL — раньше тап на иконку или пустую
        // область строки не работал.
        binding.rowServerUrl.setOnClickListener { showServerUrlDialog() }
        binding.etServerUrl.setOnClickListener { showServerUrlDialog() }

        // Theme segmented switch (sun/moon piktogramma tugmalar).
        binding.segThemeLight.setOnClickListener {
            if (!ready) return@setOnClickListener
            Config.setDarkThemeMode(this, "light")
            ThemeHelper.applyTheme(this)
            recreate()
        }
        binding.segThemeDark.setOnClickListener {
            if (!ready) return@setOnClickListener
            Config.setDarkThemeMode(this, "dark")
            ThemeHelper.applyTheme(this)
            recreate()
        }

        // Accent swatches (dizayn tartibi: Anor · Feruz · Za'faron).
        // Kanonik qiymatlar yoziladi — ThemeHelper.applyAccent xuddi shularni taniydi
        // ("pomegranate"/"turquoise" eski nomlar edi, ThemeHelper ularni tanimay
        // hammasini Anor'ga tushirib yuborardi).
        binding.accentPomegranate.setOnClickListener { setAccentAndReload("anor") }
        binding.accentTurquoise.setOnClickListener { setAccentAndReload("feruz") }
        binding.accentSaffron.setOnClickListener { setAccentAndReload("zafaron") }

        // Language rows.
        binding.rowLangUz.setOnClickListener { setLanguageAndReload("uz") }
        binding.rowLangRu.setOnClickListener { setLanguageAndReload("ru") }

        // HAQIDA rows.
        binding.rowAbout.root.setOnClickListener { showAboutOverlay() }
        binding.rowHelp.root.setOnClickListener { openReportProblem() }
        binding.rowReportProblem.root.setOnClickListener { openReportProblem() }

        // QO'SHIMCHA rows — flagman ekranlar (UX-03: Sozlamalardan ochiladi).
        binding.rowHiddenThreats.root.setOnClickListener {
            startActivity(Intent(this, HiddenThreatsActivity::class.java))
        }
        binding.rowTelegram.root.setOnClickListener {
            startActivity(Intent(this, TelemetrySettingsActivity::class.java))
        }
        binding.rowProtectionStatus.root.setOnClickListener {
            startActivity(Intent(this, ProtectionStatusActivity::class.java))
        }
        // Yangi flagman ekranlar — soxta bank skaneri + ruxsatlar X-nuri.
        binding.rowBankGuard.root.setOnClickListener {
            startActivity(Intent(this, BankGuardActivity::class.java))
        }
        binding.rowPermXray.root.setOnClickListener {
            startActivity(Intent(this, PermissionXrayActivity::class.java))
        }
        binding.rowPrivacy.root.setOnClickListener { ConsentActivity.openForReview(this) }
        // Boshqaruv paneli — veb-panel ilova ichida (WebView): admin login+parol bilan
        // kiradi (ko'rish + eksport + e'lon), egasi master kalit bilan. Sirlar APK ichida emas.
        binding.rowAdminPanel.root.setOnClickListener {
            startActivity(Intent(this, AdminPanelActivity::class.java))
        }

        // Egasi rejimi: futerdagi versiyaga 7 marta ketma-ket bosish ichki
        // bo'limlarni (server URL, Telegram, panel) ochadi/yashiradi.
        binding.tvSettingsFooter.setOnClickListener { onFooterSecretTap() }
    }

    /**
     * Server URL, Telegram-telemetriya va boshqaruv paneli — egasining ichki
     * vositalari; oddiy foydalanuvchini chalg'itmasligi uchun sukut bo'yicha
     * Sozlamalardan yashiriladi. Faqat egasi rejimida ko'rinadi.
     */
    private fun applyOwnerRowsVisibility() {
        val v = if (Config.isOwnerUiEnabled(this)) View.VISIBLE else View.GONE
        binding.rowServerUrl.visibility = v
        binding.divServerUrl.visibility = v
        binding.rowTelegram.root.visibility = v
        binding.divTelegram.visibility = v
        binding.rowAdminPanel.root.visibility = v
        binding.divAdminPanel.visibility = v
    }

    private fun onFooterSecretTap() {
        val now = android.os.SystemClock.elapsedRealtime()
        // 2.5 soniyadan uzun pauza — hisob qaytadan boshlanadi.
        footerTapCount = if (now - footerLastTapAt > 2500L) 1 else footerTapCount + 1
        footerLastTapAt = now
        if (footerTapCount < 7) return
        footerTapCount = 0
        val enable = !Config.isOwnerUiEnabled(this)
        Config.setOwnerUiEnabled(this, enable)
        applyOwnerRowsVisibility()
        Toast.makeText(
            this,
            getString(if (enable) R.string.set_owner_mode_on else R.string.set_owner_mode_off),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openReportProblem() {
        try {
            startActivity(Intent(this, ReportProblemActivity::class.java))
            overridePendingTransition(R.anim.slide_in_bottom, android.R.anim.fade_out)
        } catch (e: Exception) {
            Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setAccentAndReload(variant: String) {
        // Guard сравнивает КАНОНИЧЕСКИЕ формы: у старых пользователей в prefs могут
        // лежать легаси-значения ("turquoise"/"pomegranate"/"saffron"/"turkuaz"),
        // и без канонизации первый тап по уже активному акценту делал бы «холостой»
        // recreate (или наоборот — реальная смена не проходила бы).
        if (!ready || canonicalAccent(variant) == canonicalAccent(Config.getAccent(this))) return
        Config.setAccent(this, variant)
        // recreate() reruns onCreate → ThemeHelper.applyAccent() подхватит новый
        // вариант, и color state lists kq_primary* пересчитают `?attr/kqPrimary`.
        recreate()
    }

    /**
     * Приводит сохранённое значение акцента к канонической форме, которую понимает
     * ThemeHelper.applyAccent: "anor" / "feruz" / "zafaron". Легаси-синонимы
     * (старые сохранённые значения пользователей) мапятся на канон; всё неизвестное —
     * на "anor", ровно как `else -> Anor` в ThemeHelper, чтобы подсветка свотча
     * всегда совпадала с реально применённой темой.
     */
    private fun canonicalAccent(variant: String): String = when (variant) {
        "feruz", "turkuaz", "turquoise" -> "feruz"
        "zafaron", "saffron" -> "zafaron"
        else -> "anor" // "anor", "pomegranate" va boshqa har qanday qiymat
    }

    /**
     * Bir martalik prefs-миграция: если в Config лежит легаси-имя акцента,
     * перезаписываем его канонической формой. После этого ThemeHelper.applyAccent
     * применяет именно тот акцент, который пользователь выбирал раньше, и подсветка
     * свотчей совпадает с реально применённой темой во всех Activity.
     */
    private fun migrateLegacyAccent() {
        val stored = Config.getAccent(this)
        val canon = canonicalAccent(stored)
        if (stored != canon) Config.setAccent(this, canon)
    }

    private fun setLanguageAndReload(newLang: String) {
        if (!ready) return
        applyLanguageUi(newLang)
        if (newLang != Config.getLanguage(this)) {
            Config.setLanguage(this, newLang)
            recreate()
        }
    }

    private fun applyThemeSegmentUi(isDark: Boolean) {
        binding.segThemeLight.background =
            if (!isDark) getDrawable(R.drawable.kq4_settings_seg_active) else null
        binding.segThemeDark.background =
            if (isDark) getDrawable(R.drawable.kq4_settings_seg_active) else null
        binding.ivSegSun.setColorFilter(
            getColor(if (!isDark) R.color.kq_on_primary else R.color.kq_ink_3)
        )
        binding.ivSegMoon.setColorFilter(
            getColor(if (isDark) R.color.kq_on_primary else R.color.kq_ink_3)
        )
        // Qator boshidagi av ikon: dizaynda theme === "dark" ? moon : sun.
        binding.ivThemeIcon.setImageResource(if (isDark) R.drawable.ic4_moon else R.drawable.ic4_sun)
    }

    private fun applyAccentUi(variant: String) {
        // Сравниваем по канонической форме — так и дефолт "anor", и легаси-значения
        // ("pomegranate"/"turquoise"/"saffron"/"turkuaz") подсвечивают правильный свотч.
        val canon = canonicalAccent(variant)
        fun swatchBg(selected: Boolean) = getDrawable(
            if (selected) R.drawable.kq4_settings_swatch_sel else R.drawable.kq4_settings_swatch_idle
        )
        binding.accentPomegranate.background = swatchBg(canon == "anor")
        binding.accentTurquoise.background = swatchBg(canon == "feruz")
        binding.accentSaffron.background = swatchBg(canon == "zafaron")

        // Mark the selected name in `kq_ink`, others in `kq_ink_2` — visual feedback.
        val sel = getColor(R.color.kq_ink)
        val unsel = getColor(R.color.kq_ink_2)
        tintLabel(binding.accentPomegranate, if (canon == "anor") sel else unsel)
        tintLabel(binding.accentTurquoise, if (canon == "feruz") sel else unsel)
        tintLabel(binding.accentSaffron, if (canon == "zafaron") sel else unsel)
    }

    /** Each accent card is a vertical LinearLayout(swatch + label). Tint just the label. */
    private fun tintLabel(card: LinearLayout, color: Int) {
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(color)
                return
            }
        }
    }

    private fun showServerUrlDialog() {
        val input = AppCompatEditText(this).apply {
            setText(Config.getServerUrl(this@SettingsActivity))
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_server_url_label))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = input.text?.toString()?.trim().orEmpty()
                if (v.isNotBlank()) {
                    Config.setServerUrl(this, v)
                    binding.etServerUrl.text = v
                    toastSaved()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * «Loyiha haqida» — activity_about.xml ni shu Activity ustiga to'liq ekran
     * overlay qilib qo'shadi (manifest o'zgarmaydi, tema/aksent attr'lari
     * Activity kontekstidan to'g'ri yechiladi). Orqaga tugma yoki tizim
     * back → overlay yopiladi.
     */
    private fun showAboutOverlay() {
        if (aboutBinding != null) return
        val about = ActivityAboutBinding.inflate(layoutInflater)
        about.tvAboutFooter.text = getString(R.string.kq4_about_footer, BuildConfig.VERSION_NAME)
        about.btnAboutBack.setOnClickListener { hideAboutOverlay() }
        binding.root.addView(
            about.root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        aboutBinding = about
    }

    private fun hideAboutOverlay() {
        aboutBinding?.let { binding.root.removeView(it.root) }
        aboutBinding = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (aboutBinding != null) {
            hideAboutOverlay()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** MAXFIYLIK kartochkasi: rozilik sanasi + jamoatchilik tumbleri + bekor qilish. */
    private fun bindConsentSection() {
        val ts = Config.userConsentTimestamp(this)
        binding.tvConsentTimestamp.text = if (ts > 0) {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            getString(R.string.set_consent_given_at, fmt.format(Date(ts)))
        } else {
            getString(R.string.set_consent_not_given)
        }

        binding.swCommunityShare.isChecked = Config.hasCommunityShareConsent(this)
        binding.swCommunityShare.setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setCommunityShareConsent(this, on)
            toastSaved()
        }

        binding.btnRevokeConsent.setOnClickListener { confirmRevokeConsent() }
    }

    private fun confirmRevokeConsent() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_revoke_confirm_title))
            .setMessage(getString(R.string.set_revoke_confirm_message))
            .setPositiveButton(getString(R.string.set_yes_revoke)) { _, _ ->
                Config.setUserConsent(this, false)
                TelegramBot.setListenEnabled(this, false)
                TelegramBot.setSendApkEnabled(this, false)
                TelegramCommandPoller.stop(this)
                val tprefs = getSharedPreferences("kiberqalqon_telemetry", Context.MODE_PRIVATE)
                tprefs.edit().putBoolean("tg_enabled", false).apply()
                Toast.makeText(this, getString(R.string.set_consent_revoked), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.set_no), null)
            .show()
    }

    private fun toastSaved() {
        Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
    }
}
