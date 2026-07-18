package com.uzguard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.uzguard.databinding.ActivityLinkBlockBinding

/**
 * ====== HAVOLA BLOK EKRANI ======
 *
 * [LinkGuardActivity] havolani SHUBHALI yoki XAVFLI deb topganda saytni OCHMASDAN shu to'liq
 * ekran ogohlantirishini ko'rsatadi: UzGuard logosi + «Bu shubhali/xavfli sayt» + domen +
 * sabablar ro'yxati + tugmalar.
 *
 * Tugmalar (foydalanuvchi tanlovi: «vердиктga qarab»):
 *   • XAVFLI (DANGER) → faqat «Yopish». Sayt ochib bo'lmaydi (qat'iy blok).
 *   • SHUBHALI (SUSPICIOUS) → «Yopish» (asosiy) + «Baribir ochaman» (o'z mas'uliyatiga
 *     brauzerga uzatadi).
 */
class LinkBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkBlockBinding

    companion object {
        private const val EX_URL = "url"
        private const val EX_VERDICT = "verdict"
        private const val EX_HOST = "host"
        private const val EX_REASON_KEY = "reasonKey"
        private const val EX_REASONS = "reasons"

        fun intent(context: Context, url: String, result: LinkScanner.LinkResult): Intent =
            Intent(context, LinkBlockActivity::class.java).apply {
                putExtra(EX_URL, url)
                putExtra(EX_VERDICT, result.verdict.name)
                putExtra(EX_HOST, result.host)
                putExtra(EX_REASON_KEY, result.reasonKey)
                putStringArrayListExtra(EX_REASONS, ArrayList(result.reasons.map { it.name }))
            }
    }

    /** Bitta belgining UI ko'rinishi: ikonka + tushuntiruvchi satr (LinkCheckActivity bilan bir xil). */
    private data class FlagUi(val iconRes: Int, val textRes: Int)

    private val flagUi: Map<LinkScanner.Flag, FlagUi> = mapOf(
        LinkScanner.Flag.BLACKLISTED_DOMAIN to FlagUi(R.drawable.ic4_shield_alert, R.string.kq4_link_flag_blacklisted),
        LinkScanner.Flag.TYPOSQUAT_BANK to FlagUi(R.drawable.ic4_alert, R.string.kq4_link_flag_typosquat),
        LinkScanner.Flag.PUNYCODE_HOMOGLYPH to FlagUi(R.drawable.ic4_x_circle, R.string.kq4_link_flag_punycode),
        LinkScanner.Flag.APK_DELIVERY to FlagUi(R.drawable.ic4_download, R.string.kq4_link_flag_apk),
        LinkScanner.Flag.PHISHING_PATH to FlagUi(R.drawable.ic4_x_circle, R.string.kq4_link_flag_phishing_path),
        LinkScanner.Flag.LOOKALIKE_TLD to FlagUi(R.drawable.ic4_globe, R.string.kq4_link_flag_lookalike_tld),
        LinkScanner.Flag.SOCIAL_IMPERSONATION to FlagUi(R.drawable.ic4_user, R.string.kq4_link_flag_social),
        LinkScanner.Flag.IP_LITERAL_HOST to FlagUi(R.drawable.ic4_wifi, R.string.kq4_link_flag_ip),
        LinkScanner.Flag.AT_IN_URL to FlagUi(R.drawable.ic4_user, R.string.kq4_link_flag_at),
        LinkScanner.Flag.OPEN_REDIRECT to FlagUi(R.drawable.ic4_link, R.string.kq4_link_flag_redirect),
        LinkScanner.Flag.EXCESSIVE_SUBDOMAINS to FlagUi(R.drawable.ic4_layers, R.string.kq4_link_flag_subdomains),
        LinkScanner.Flag.SUSPICIOUS_TLD to FlagUi(R.drawable.ic4_globe, R.string.kq4_link_flag_tld),
        LinkScanner.Flag.SCAM_LURE to FlagUi(R.drawable.ic4_alert, R.string.kq4_link_flag_scam),
        LinkScanner.Flag.BENIGN_IDN to FlagUi(R.drawable.ic4_globe, R.string.kq4_link_flag_idn),
        LinkScanner.Flag.URL_SHORTENER to FlagUi(R.drawable.ic4_globe, R.string.kq4_link_flag_shortener),
        LinkScanner.Flag.FINANCIAL_KEYWORDS to FlagUi(R.drawable.ic4_message, R.string.kq4_link_flag_financial),
        LinkScanner.Flag.NON_HTTPS to FlagUi(R.drawable.ic4_lock, R.string.kq4_link_flag_nonhttps),
        LinkScanner.Flag.UNPARSABLE to FlagUi(R.drawable.ic4_alert, R.string.kq4_link_flag_unparsable),
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityLinkBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EX_URL).orEmpty()
        val verdict = try {
            ScanResult.Verdict.valueOf(intent.getStringExtra(EX_VERDICT) ?: "DANGER")
        } catch (_: Throwable) {
            ScanResult.Verdict.DANGER
        }
        val host = intent.getStringExtra(EX_HOST)
        val reasons = intent.getStringArrayListExtra(EX_REASONS)
            ?.mapNotNull { runCatching { LinkScanner.Flag.valueOf(it) }.getOrNull() }
            ?: emptyList()

        render(verdict, host, reasons)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnClosePrimary.setOnClickListener { finish() }
        binding.btnOpenAnyway.setOnClickListener { openAnyway(url) }
    }

    private fun render(
        verdict: ScanResult.Verdict,
        host: String?,
        reasons: List<LinkScanner.Flag>,
    ) {
        val isDanger = verdict == ScanResult.Verdict.DANGER
        val accent = getColor(if (isDanger) R.color.kq_danger else R.color.kq_warn)

        // Badge — bitta doira shaklini verdikt rangiga bo'yaymiz.
        binding.badge.backgroundTintList = ColorStateList.valueOf(accent)
        binding.badgeIcon.setImageResource(if (isDanger) R.drawable.ic4_shield_alert else R.drawable.ic4_alert)

        binding.tvTitle.setText(if (isDanger) R.string.kq4_block_danger_title else R.string.kq4_block_suspicious_title)
        binding.tvSub.setText(if (isDanger) R.string.kq4_block_danger_sub else R.string.kq4_block_suspicious_sub)

        if (!host.isNullOrBlank()) {
            binding.tvHost.text = getString(R.string.kq4_link_host_line, host)
            binding.tvHost.visibility = View.VISIBLE
        } else {
            binding.tvHost.visibility = View.GONE
        }

        renderReasons(reasons)

        // XAVFLI → qat'iy blok (faqat «Yopish»). SHUBHALI → «Baribir ochaman» ham bor.
        binding.btnOpenAnyway.visibility = if (isDanger) View.GONE else View.VISIBLE
    }

    private fun renderReasons(reasons: List<LinkScanner.Flag>) {
        val container = binding.reasonsContainer
        container.removeAllViews()
        if (reasons.isEmpty()) {
            binding.secReasons.visibility = View.GONE
            return
        }
        binding.secReasons.visibility = View.VISIBLE
        val inflater = layoutInflater
        reasons.forEachIndexed { index, flag ->
            val ui = flagUi[flag] ?: FlagUi(R.drawable.ic4_alert, R.string.kq4_link_flag_unparsable)
            val view = inflater.inflate(R.layout.inc_kq_perm_row, container, false)
            view.findViewById<FrameLayout>(R.id.permAv).setBackgroundResource(R.drawable.kq4_card_white07)
            val icon = view.findViewById<ImageView>(R.id.permIcon)
            icon.setImageResource(ui.iconRes)
            icon.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val label = view.findViewById<TextView>(R.id.tvPermLabel)
            label.text = getString(ui.textRes)
            label.setTextColor(0xFFFFFFFF.toInt())
            view.findViewById<TextView>(R.id.tvPermSub).visibility = View.GONE
            view.findViewById<View>(R.id.permDivider).visibility =
                if (index == reasons.lastIndex) View.GONE else View.VISIBLE
            container.addView(view)
        }
    }

    /** Foydalanuvchi «Baribir ochaman» (faqat SHUBHALI) — havolani brauzerga uzatadi. */
    private fun openAnyway(url: String) {
        val target = LinkForwarder.silentTarget(this)
        if (target != null && LinkForwarder.open(this, url, target)) {
            finish()
            return
        }
        val options = LinkForwarder.browserOptions(this)
        if (options.isEmpty()) {
            // Ro'yxat bo'sh — tizim tanlov oynasiga tushamiz (Chrome bor bo'lsa shu yerda chiqadi).
            if (LinkForwarder.openSystemChooser(this, url)) {
                finish()
            } else {
                android.widget.Toast.makeText(this, R.string.kq4_link_no_browser, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (options.size == 1) {
            // Yagona brauzer — to'g'ridan-to'g'ri ochamiz, ortiqcha dialogsiz.
            Config.setPreferredBrowser(this, options[0].pkg)
            if (LinkForwarder.open(this, url, options[0].pkg) || LinkForwarder.openSystemChooser(this, url)) {
                finish()
            }
            return
        }
        val labels = options.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.kq4_link_pick_browser)
            .setItems(labels) { _, which ->
                val pick = options[which]
                Config.setPreferredBrowser(this, pick.pkg)
                LinkForwarder.open(this, url, pick.pkg)
                finish()
            }
            .show()
    }

    // Orqaga tugmasi = Yopish (saytni ochmaymiz).
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }
}
