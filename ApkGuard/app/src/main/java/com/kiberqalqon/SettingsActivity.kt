package com.kiberqalqon

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.switchmaterial.SwitchMaterial
import com.kiberqalqon.databinding.ActivitySettingsNewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sozlamalar — KiberQalqon redesign §3.7.
 *
 * Bound to activity_settings_new.xml. All Config persistence happens reactively
 * (no Save tugmasi) — each toggle saves immediately. Theme/accent/lang changes
 * call recreate() so the affected views re-bind on the new palette.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsNewBinding

    /** Set to true after first bindState — guards listeners from firing during initial bind. */
    private var ready = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        labelToggleRows()
        labelChevronRows()
        bindState()
        wireListeners()
        injectConsentSection()

        KqBottomNav.attach(this, KqBottomNav.Tab.SETTINGS)

        ready = true
    }

    /** Sets the title + sub on each of the 4 HIMOYA + 1 SERVER toggle rows. */
    private fun labelToggleRows() {
        bindRow(
            binding.rowAutoScan.root,
            title = getString(R.string.set_row_autoscan_title),
            sub = getString(R.string.set_row_autoscan_sub),
            icon = R.drawable.ic_radar_scan,
        )
        bindRow(
            binding.rowAutoDelete.root,
            title = getString(R.string.set_row_autodelete_title),
            sub = getString(R.string.set_row_autodelete_sub),
            icon = R.drawable.ic_trash,
        )
        bindRow(
            binding.rowPhishing.root,
            title = getString(R.string.set_row_phishing_title),
            sub = getString(R.string.set_row_phishing_sub),
            icon = R.drawable.ic_bell_cyber,
        )
        bindRow(
            binding.rowBackground.root,
            title = getString(R.string.set_row_background_title),
            sub = getString(R.string.set_row_background_sub),
            icon = R.drawable.ic_shield,
        )
        bindRow(
            binding.rowUpload.root,
            title = getString(R.string.set_row_upload_title),
            sub = getString(R.string.set_row_upload_sub),
            icon = R.drawable.ic_upload_cyber,
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
        binding.rowAbout.root.findViewById<TextView>(R.id.tvChevronTitle).text = getString(R.string.about_title)
        binding.rowHelp.root.findViewById<TextView>(R.id.tvChevronTitle).text = getString(R.string.set_help_center)
        binding.rowPrivacy.root.findViewById<TextView>(R.id.tvChevronTitle).text = getString(R.string.privacy_title)
    }

    /** Snapshots current Config values into the UI. */
    private fun bindState() {
        // Toggle rows
        toggleOf(binding.rowAutoScan.root).isChecked = Config.isBackgroundEnabled(this)
        toggleOf(binding.rowAutoDelete.root).isChecked = Config.getAutoDeleteMode(this) == "delete"
        toggleOf(binding.rowPhishing.root).isChecked = Config.isPhishingBlockerEnabled(this)
        toggleOf(binding.rowBackground.root).isChecked = Config.isAutoUpdateEnabled(this)
        toggleOf(binding.rowUpload.root).isChecked = Config.isUploadEnabled(this)

        // Server URL display
        val url = Config.getServerUrl(this).ifBlank { getString(R.string.settings_server_url_example) }
        binding.etServerUrl.text = url

        // Theme segmented switch
        applyThemeSegmentUi(isDark = ThemeHelper.isDarkTheme(this))

        // Accent swatches
        applyAccentUi(Config.getAccent(this))

        // Language radios — both isChecked and the drawableEnd icon swap (the
        // RadioButtons use android:button="@null" so the right-side icon IS
        // the selected state to the user, not the radio circle).
        val lang = Config.getLanguage(this)
        binding.radioUzbek.isChecked = lang != "ru"
        binding.radioRussian.isChecked = lang == "ru"
        refreshRadioDrawables(lang)
    }

    private fun refreshRadioDrawables(lang: String) {
        val check = R.drawable.ic_check_circle
        val chevron = R.drawable.ic_chevron_right
        binding.radioUzbek.setCompoundDrawablesWithIntrinsicBounds(
            0, 0, if (lang != "ru") check else chevron, 0
        )
        binding.radioRussian.setCompoundDrawablesWithIntrinsicBounds(
            0, 0, if (lang == "ru") check else chevron, 0
        )
    }

    private fun toggleOf(rowRoot: View): SwitchMaterial =
        rowRoot.findViewById(R.id.swRow)

    private fun wireListeners() {
        // Each HIMOYA toggle → immediate Config save.
        toggleOf(binding.rowAutoScan.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            Config.setBackgroundEnabled(this, on)
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

        // Server URL → edit dialog. Привязываем клик ко ВСЕМУ ряду (rowServerUrl),
        // не только к маленькому TextView c URL — раньше тап на иконку или пустую
        // область строки не работал.
        binding.rowServerUrl.setOnClickListener { showServerUrlDialog() }
        binding.etServerUrl.setOnClickListener { showServerUrlDialog() }

        // Theme segmented switch.
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

        // Accent swatches.
        binding.accentTurquoise.setOnClickListener { setAccentAndReload("turquoise") }
        binding.accentSaffron.setOnClickListener { setAccentAndReload("saffron") }
        binding.accentPomegranate.setOnClickListener { setAccentAndReload("pomegranate") }

        // Language radios. RadioGroup wires mutual exclusion already.
        binding.radioGroupLanguage.setOnCheckedChangeListener { _, id ->
            if (!ready) return@setOnCheckedChangeListener
            val newLang = if (id == R.id.radioRussian) "ru" else "uz"
            refreshRadioDrawables(newLang)
            if (newLang != Config.getLanguage(this)) {
                Config.setLanguage(this, newLang)
                recreate()
            }
        }

        // HAQIDA chevron rows.
        binding.rowAbout.root.setOnClickListener { showAboutDialog() }
        binding.rowHelp.root.setOnClickListener { showHelpDialog() }
        binding.rowPrivacy.root.setOnClickListener { ConsentActivity.openForReview(this) }
    }

    private fun setAccentAndReload(variant: String) {
        if (!ready || variant == Config.getAccent(this)) return
        Config.setAccent(this, variant)
        // recreate() reruns onCreate → ThemeHelper.applyAccent() подхватит новый
        // вариант, и color state lists kq_primary* пересчитают `?attr/kqPrimary`.
        recreate()
    }

    private fun applyThemeSegmentUi(isDark: Boolean) {
        val active = R.drawable.kq_seg_active
        binding.segThemeLight.background = if (!isDark) getDrawable(active) else null
        binding.segThemeDark.background = if (isDark) getDrawable(active) else null
        binding.segThemeLight.setTextColor(
            getColor(if (!isDark) R.color.kq_on_primary else R.color.kq_ink_2)
        )
        binding.segThemeDark.setTextColor(
            getColor(if (isDark) R.color.kq_on_primary else R.color.kq_ink_2)
        )
    }

    private fun applyAccentUi(variant: String) {
        val selected = R.drawable.kq_accent_selected
        binding.accentTurquoise.background = if (variant == "turquoise") getDrawable(selected) else null
        binding.accentSaffron.background = if (variant == "saffron") getDrawable(selected) else null
        binding.accentPomegranate.background = if (variant == "pomegranate") getDrawable(selected) else null

        // Mark the selected name in `kq_ink`, others in `kq_ink_2` — visual feedback.
        val sel = getColor(R.color.kq_ink)
        val unsel = getColor(R.color.kq_ink_2)
        tintLabel(binding.accentTurquoise, if (variant == "turquoise") sel else unsel)
        tintLabel(binding.accentSaffron, if (variant == "saffron") sel else unsel)
        tintLabel(binding.accentPomegranate, if (variant == "pomegranate") sel else unsel)
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

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_about_dialog_title))
            .setMessage(getString(R.string.set_about_dialog_message))
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_help_center))
            .setMessage(getString(R.string.set_help_dialog_message))
            .setPositiveButton(R.string.btn_ok, null)
            .setNeutralButton(getString(R.string.set_help_send_problem)) { _, _ ->
                try {
                    startActivity(android.content.Intent(this, ReportProblemActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_bottom, android.R.anim.fade_out)
                } catch (e: Exception) {
                    Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Adds a Maxfiylik card with community-sharing toggle + revoke button to the bottom. */
    private fun injectConsentSection() {
        try {
            val footer = findFooterTextView() ?: return
            val parent = footer.parent as? LinearLayout ?: return
            val insertAt = parent.indexOfChild(footer) // inject before the footer caption

            val ts = Config.userConsentTimestamp(this)
            val tsStr = if (ts > 0) {
                val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                getString(R.string.set_consent_given_at, fmt.format(Date(ts)))
            } else {
                getString(R.string.set_consent_not_given)
            }

            val eyebrow = TextView(this).apply {
                text = getString(R.string.set_privacy_eyebrow)
                setTextColor(getColor(R.color.kq_primary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.12f
                isAllCaps = true
                setPadding(0, dp(22), 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(getColor(R.color.kq_hairline))
                setCardBackgroundColor(getColor(R.color.kq_bg_elev))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                layoutParams = lp
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            val tsLabel = TextView(this).apply {
                text = tsStr
                setTextColor(getColor(R.color.kq_ink_2))
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            }

            val communityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val communityLabel = TextView(this).apply {
                text = getString(R.string.set_community_label)
                setTextColor(getColor(R.color.kq_ink))
                textSize = 14f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            val communitySwitch = SwitchMaterial(this).apply {
                isChecked = Config.hasCommunityShareConsent(this@SettingsActivity)
                setOnCheckedChangeListener { _, on ->
                    Config.setCommunityShareConsent(this@SettingsActivity, on)
                    toastSaved()
                }
            }
            communityRow.addView(communityLabel)
            communityRow.addView(communitySwitch)

            val divider = View(this).apply {
                setBackgroundColor(getColor(R.color.kq_hairline))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                }
            }

            val revokeRow = TextView(this).apply {
                text = getString(R.string.set_revoke_consent)
                setTextColor(getColor(R.color.kq_danger))
                textSize = 14f
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { confirmRevokeConsent() }
            }

            inner.addView(tsLabel)
            inner.addView(communityRow)
            inner.addView(divider)
            inner.addView(revokeRow)
            card.addView(inner)

            parent.addView(eyebrow, insertAt)
            parent.addView(card, insertAt + 1)
        } catch (e: Throwable) {
            android.util.Log.e("SettingsActivity", "injectConsent failed", e)
        }
    }

    /** Finds the footer caption TextView (KIBERQALQON v7.5 …) so we can inject above it. */
    private fun findFooterTextView(): TextView? {
        fun walk(v: View): TextView? {
            if (v is TextView && v.text?.toString()?.startsWith("KIBERQALQON") == true) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(binding.root)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
