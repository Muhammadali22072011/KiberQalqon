package com.uzguard

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
import androidx.appcompat.widget.SwitchCompat
import com.uzguard.databinding.ActivityAboutBinding
import com.uzguard.databinding.ActivitySettingsNewBinding
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

    /** VPN ruxsat oynasi (VpnService.prepare) natijasi — tasdiq bo'lsa filtr start. */
    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Config.setVpnFilterEnabled(this, true)
            VpnFilterService.start(this)
            toastSaved()
        } else {
            // Ruxsat berilmadi — toggle'ni LISTENER'NI ISHGA TUSHIRMASDAN qaytaramiz.
            // Aks holda o'chirish-listener'i PIN so'rardi (hech qachon yoqilmagan filtr
            // uchun!) va PIN bekor qilinsa toggle yolg'on ON holatda qolib ketardi.
            ready = false
            toggleOf(binding.rowVpnFilter.root).isChecked = false
            ready = true
            Toast.makeText(this, getString(R.string.kq4_vpn_perm_denied), Toast.LENGTH_SHORT).show()
        }
    }

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

        // Play flavor: notification-listener yo'q (BuildConfig.NOTIF_LISTENER=false,
        // xizmat manifest'dan olib tashlangan) — anti-fishing qatori "zombi" bo'lib
        // qolmasin (doim OFF ko'rinib, yoqilgach jim qaytib tushardi): butunlay yashiramiz.
        if (!BuildConfig.NOTIF_LISTENER) {
            binding.rowPhishing.root.visibility = View.GONE
            binding.divPhishing.visibility = View.GONE
        }

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
        // QO'SHIMCHA — DNS C2-filtri (tajribaviy, opt-in).
        bindRow(
            binding.rowVpnFilter.root,
            title = getString(R.string.kq4_set_row_vpn),
            sub = getString(R.string.kq4_set_row_vpn_sub),
            icon = R.drawable.ic4_wifi,
        )
        // QO'SHIMCHA — Havola qalqoni (link interceptor).
        bindRow(
            binding.rowLinkGuard.root,
            title = getString(R.string.kq4_set_row_linkguard),
            sub = getString(R.string.kq4_set_row_linkguard_sub),
            icon = R.drawable.ic4_link,
        )
        // QO'SHIMCHA — Uyg'otuvchi signal (baland sirena tunda topilgan tahdidda).
        bindRow(
            binding.rowLoudAlarm.root,
            title = getString(R.string.kq4_set_row_alarm),
            sub = getString(R.string.kq4_set_row_alarm_sub),
            icon = R.drawable.ic4_bell,
        )
        // "Toza dastur" (2026-08-13): auto-yangilanish / haftalik hisobot / yangilik push /
        // Wi-Fi straj / masofaviy boshqaruv / "ilova yangilandi" tumblerlari OLIB TASHLANDI —
        // bu funksiyalar endi doim yoqiq (Config'da hardcode true). Server upload (Gen-1)
        // butunlay o'chirildi.
        // QO'SHIMCHA — Ishonchli ro'yxat (UserWhitelist boshqaruvi).
        bindChevronWithSub(
            binding.rowTrustList.root,
            getString(R.string.kq4_set_row_trustlist),
            getString(R.string.kq4_set_row_trustlist_sub),
            R.drawable.ic4_check_circle,
        )
        binding.rowTrustList.root.setOnClickListener { showTrustListDialog() }
    }

    /**
     * Ishonchli ro'yxat dialogi: yozuvlar ro'yxati, yozuvga bosilsa — olib tashlash tasdig'i.
     * Bo'sh bo'lsa — tushuntiruvchi xabar. (Alohida Activity'siz, soddalik uchun dialog.)
     */
    private fun showTrustListDialog() {
        val items = UserWhitelist.entries(this)
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.kq4_set_row_trustlist)
                .setMessage(R.string.kq4_trustlist_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = items.map { e ->
            val kind = if (e.type == UserWhitelist.TYPE_APP) "📦" else "📄"
            val name = e.label.ifBlank { e.key.take(16) + "…" }
            "$kind $name"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_set_row_trustlist)
            .setItems(labels) { _, which ->
                val e = items[which]
                AlertDialog.Builder(this)
                    .setTitle(R.string.kq4_trustlist_remove_title)
                    .setMessage(getString(R.string.kq4_trustlist_remove_msg, e.label.ifBlank { e.key.take(16) }))
                    .setPositiveButton(R.string.kq4_trustlist_remove_yes) { _, _ ->
                        UserWhitelist.remove(this, e.type, e.key)
                        Toast.makeText(this, R.string.kq4_trustlist_removed, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.kq4_btn_later, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindRow(root: View, title: String, sub: String, icon: Int) {
        root.findViewById<TextView>(R.id.tvRowTitle).text = title
        root.findViewById<TextView>(R.id.tvRowSub).text = sub
        try {
            root.findViewById<android.widget.ImageView>(R.id.ivRowIcon).setImageResource(icon)
        } catch (_: Throwable) { /* icon optional */ }
    }

    private fun labelChevronRows() {
        // HAQIDA ("Yordam" qatori olib tashlandi — "Muammo haqida xabar" bilan bitta oynani ochardi).
        bindChevron(binding.rowAbout.root, getString(R.string.about_title), R.drawable.ic4_heart)
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
        // Yangi flagman ekranlar — xavfsizlik balli + sideload audit.
        bindChevronWithSub(
            binding.rowSecurityScore.root,
            getString(R.string.kq4_set_row_score),
            getString(R.string.kq4_set_row_score_sub),
            R.drawable.ic4_shield_check,
        )
        bindChevronWithSub(
            binding.rowSideloadAudit.root,
            getString(R.string.kq4_set_row_sideload),
            getString(R.string.kq4_set_row_sideload_sub),
            R.drawable.ic4_layers,
        )
        // "Hammasini qo'sh" to'lqini (2026-07-11): xabar tekshiruvi + tez tekshiruv + oila qalqoni
        // + himoya qulfi (PIN) + halol cheklovlar.
        bindChevronWithSub(
            binding.rowScamCheck.root,
            getString(R.string.kq4_set_row_scamcheck),
            getString(R.string.kq4_set_row_scamcheck_sub),
            R.drawable.ic4_alert,
        )
        // "30 soniyalik tekshiruv" (CheckupWizard) olib tashlandi — Xavfsizlik balli
        // ekrani bilan to'liq dublikat edi.
        bindChevronWithSub(
            binding.rowFamilyGuard.root,
            getString(R.string.kq4_set_row_family),
            getString(R.string.kq4_set_row_family_sub),
            R.drawable.ic4_shield_check,
        )
        bindChevronWithSub(
            binding.rowPinLock.root,
            getString(R.string.kq4_set_row_pin),
            getString(if (PinStore.isSet(this)) R.string.kq4_set_row_pin_on else R.string.kq4_set_row_pin_sub),
            R.drawable.ic4_lock,
        )
        bindChevronWithSub(
            binding.rowLimits.root,
            getString(R.string.kq4_set_row_limits),
            getString(R.string.kq4_set_row_limits_sub),
            R.drawable.ic4_help,
        )
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
        toggleOf(binding.rowVpnFilter.root).isChecked = Config.isVpnFilterEnabled(this)
        toggleOf(binding.rowLinkGuard.root).isChecked = Config.isLinkGuardEnabled(this)
        toggleOf(binding.rowLoudAlarm.root).isChecked = Config.isLoudAlarmEnabled(this)

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

    /** Fon himoyasi tumblerini HAQIQATAN qo'llaydi (Config + ProtectionService start/stop). */
    private fun applyBackgroundEnabled(on: Boolean) {
        Config.setBackgroundEnabled(this, on)
        // BG-02: tumbler xizmatni HAQIQATAN boshqaradi (faqat Config'ga yozib qo'ymaydi).
        if (on) {
            ProtectionService.start(this)
        } else {
            ProtectionService.stop(this)
            try {
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(ProtectionService.NOTIFICATION_ID)
            } catch (_: Throwable) {}
        }
        toastSaved()
    }

    // PIN-gate: himoya sozlamasini O'CHIRISH oldidan PIN so'raladi (o'rnatilgan bo'lsa).
    // Avval faqat fon-himoya tumbleri gate'lanardi — firibgar "VPN'ni o'chiring, avto-
    // o'chirishni o'chiring" deb qolgan tumblerlarni bemalol o'chirtira olardi.
    private var pendingPinAction: (() -> Unit)? = null
    private var pendingPinRevert: (() -> Unit)? = null

    private fun requirePinThen(revert: () -> Unit, action: () -> Unit) {
        if (!PinStore.isSet(this)) { action(); return }
        pendingPinAction = action
        pendingPinRevert = revert
        startActivityForResult(
            Intent(this, PinLockActivity::class.java)
                .putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_VERIFY),
            RC_PIN_PROTECT,
        )
    }

    /** PIN natijasi: tasdiqlansa kutayotgan amal bajariladi, aks holda tumbler qaytariladi. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_PIN_PROTECT -> {
                if (resultCode == RESULT_OK) {
                    pendingPinAction?.invoke()
                } else {
                    // Bekor qilindi / noto'g'ri PIN — sozlama YOQILGAN qoladi, tumblerni
                    // qaytaramiz (ready=false bilan listenerni qayta ishga tushirmasdan).
                    ready = false
                    pendingPinRevert?.invoke()
                    ready = true
                }
                pendingPinAction = null
                pendingPinRevert = null
            }
            RC_PIN_DISABLE -> {
                if (resultCode == RESULT_OK) {
                    PinStore.clear(this)
                    Toast.makeText(this, getString(R.string.kq4_pin_disabled), Toast.LENGTH_SHORT).show()
                    refreshPinRowSub()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // PIN o'rnatish/o'zgartirish ekranidan qaytganda subtitr yangilansin.
        if (ready) refreshPinRowSub()
    }

    private fun refreshPinRowSub() {
        bindChevronWithSub(
            binding.rowPinLock.root,
            getString(R.string.kq4_set_row_pin),
            getString(if (PinStore.isSet(this)) R.string.kq4_set_row_pin_on else R.string.kq4_set_row_pin_sub),
            R.drawable.ic4_lock,
        )
    }

    /**
     * Havola qalqoni yoqilganda — interceptor faqat UzGuard STANDART havola ochuvchi
     * bo'lsagina ishlaydi. Foydalanuvchiga buni tushuntirib, tizim «standart ilovalar»
     * ekranini ochishni taklif qilamiz (Telegram ichki brauzeri haqida eslatma bilan).
     */
    private fun showLinkGuardSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_linkguard_setup_title)
            .setMessage(R.string.kq4_linkguard_setup_msg)
            .setPositiveButton(R.string.kq4_linkguard_setup_open) { _, _ ->
                openDefaultAppsSettings()
            }
            .setNegativeButton(R.string.kq4_btn_later, null)
            .show()
    }

    /** Tizim «Standart ilovalar» ekranini ochadi (bo'lmasa — ilova tafsilotlari). */
    private fun openDefaultAppsSettings() {
        val candidates = listOf(
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            },
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) { /* keyingisini sinaymiz */ }
        }
    }

    private fun wireListeners() {
        // Each HIMOYA toggle → immediate Config save. Har bir himoya tumblerini
        // O'CHIRISH PIN ostida (firibgar skripti "antivirusni o'chiring"ni buzish uchun).
        toggleOf(binding.rowAutoScan.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (!on) {
                requirePinThen(revert = { toggleOf(binding.rowAutoScan.root).isChecked = true }) {
                    applyBackgroundEnabled(false)
                }
            } else {
                applyBackgroundEnabled(true)
            }
        }
        toggleOf(binding.rowAutoDelete.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (!on) {
                requirePinThen(revert = { toggleOf(binding.rowAutoDelete.root).isChecked = true }) {
                    Config.setAutoDeleteMode(this, "warn")
                    toastSaved()
                }
            } else {
                Config.setAutoDeleteMode(this, "delete")
                toastSaved()
            }
        }
        toggleOf(binding.rowPhishing.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (!on) {
                requirePinThen(revert = { toggleOf(binding.rowPhishing.root).isChecked = true }) {
                    Config.setPhishingBlockerEnabled(this, false)
                    toastSaved()
                }
            } else {
                Config.setPhishingBlockerEnabled(this, true)
                toastSaved()
                // KEY GAP fix: listener uchun bildirishnoma-kirish ruxsati hech qayerda
                // so'ralmasdi — toggle yoqiq bo'lsa ham xizmat hech qachon bind bo'lmasdi.
                // Endi yoqishda ruxsat yo'q bo'lsa tizim ekraniga yo'naltiramiz.
                if (BuildConfig.NOTIF_LISTENER && !isNotifListenerGranted()) {
                    showPhishingAccessDialog()
                }
            }
        }
        toggleOf(binding.rowVpnFilter.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (on) {
                // VpnService.prepare null = ruxsat allaqachon bor; aks holda tizim oynasi.
                val prepare = try { VpnFilterService.prepareIntent(this) } catch (_: Throwable) { null }
                if (prepare == null) {
                    Config.setVpnFilterEnabled(this, true)
                    VpnFilterService.start(this)
                    toastSaved()
                } else {
                    vpnPermissionLauncher.launch(prepare)
                }
            } else {
                requirePinThen(revert = { toggleOf(binding.rowVpnFilter.root).isChecked = true }) {
                    Config.setVpnFilterEnabled(this, false)
                    VpnFilterService.stop(this)
                    toastSaved()
                }
            }
        }
        toggleOf(binding.rowLinkGuard.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (!on) {
                requirePinThen(revert = { toggleOf(binding.rowLinkGuard.root).isChecked = true }) {
                    Config.setLinkGuardEnabled(this, false)
                    toastSaved()
                }
            } else {
                Config.setLinkGuardEnabled(this, true)
                toastSaved()
                // Yoqilganda — foydalanuvchiga bizni standart havola ochuvchi qilishni eslatamiz
                // (interceptor faqat shunda ishlaydi).
                showLinkGuardSetupDialog()
            }
        }
        toggleOf(binding.rowLoudAlarm.root).setOnCheckedChangeListener { _, on ->
            if (!ready) return@setOnCheckedChangeListener
            if (!on) {
                requirePinThen(revert = { toggleOf(binding.rowLoudAlarm.root).isChecked = true }) {
                    Config.setLoudAlarmEnabled(this, false)
                    try { AlarmSiren.stop() } catch (_: Throwable) {}
                    toastSaved()
                }
            } else {
                Config.setLoudAlarmEnabled(this, true)
                toastSaved()
            }
        }

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
        binding.rowSecurityScore.root.setOnClickListener {
            startActivity(Intent(this, SecurityScoreActivity::class.java))
        }
        binding.rowSideloadAudit.root.setOnClickListener {
            startActivity(Intent(this, SideloadAuditActivity::class.java))
        }
        binding.rowScamCheck.root.setOnClickListener {
            startActivity(Intent(this, ScamMessageActivity::class.java))
        }
        binding.rowFamilyGuard.root.setOnClickListener {
            startActivity(Intent(this, FamilyGuardActivity::class.java))
        }
        // Himoya qulfi: o'rnatilmagan bo'lsa — yangi PIN; o'rnatilgan bo'lsa —
        // o'zgartirish (eski PIN so'raladi, PinLockActivity ichida) yoki O'CHIRISH
        // (avval PIN tasdig'i). Avval PIN'ni o'chirish umuman MUMKIN EMAS edi
        // (PinStore.clear hech qayerdan chaqirilmasdi).
        binding.rowPinLock.root.setOnClickListener {
            if (!PinStore.isSet(this)) {
                startActivity(Intent(this, PinLockActivity::class.java)
                    .putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_SET))
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.kq4_set_row_pin)
                .setItems(arrayOf(getString(R.string.kq4_pin_change), getString(R.string.kq4_pin_disable))) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, PinLockActivity::class.java)
                            .putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_SET))
                        1 -> startActivityForResult(
                            Intent(this, PinLockActivity::class.java)
                                .putExtra(PinLockActivity.EXTRA_MODE, PinLockActivity.MODE_VERIFY),
                            RC_PIN_DISABLE,
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.rowLimits.root.setOnClickListener {
            startActivity(Intent(this, LimitsActivity::class.java))
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

    /** Anti-fishing listener'iga bildirishnoma-kirish berilganmi. */
    private fun isNotifListenerGranted(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
    } catch (_: Throwable) { false }

    /** Anti-fishing uchun tizim "Bildirishnoma kirishi" ekranini taklif qiladi. */
    private fun showPhishingAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_phishing_access_title)
            .setMessage(R.string.kq4_phishing_access_msg)
            .setPositiveButton(R.string.kq4_phishing_access_open) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Throwable) {}
            }
            .setNegativeButton(R.string.kq4_btn_later, null)
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
                val tprefs = getSharedPreferences("uzguard_telemetry", Context.MODE_PRIVATE)
                tprefs.edit().putBoolean("tg_enabled", false).apply()
                Toast.makeText(this, getString(R.string.set_consent_revoked), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.set_no), null)
            .show()
    }

    private fun toastSaved() {
        Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
    }

    companion object {
        // Himoya sozlamasini o'chirish oldidan PIN tekshiruvi natijasi.
        private const val RC_PIN_PROTECT = 0x9101
        // PIN'ni butunlay o'chirish oldidan tasdiq.
        private const val RC_PIN_DISABLE = 0x9102
    }
}
