package com.kiberqalqon

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityLinkCheckBinding

/**
 * v4 dizayn «Havola tekshirgich» (link checker — eng qimmatli funksiya).
 *
 * Telegram / SMS / QR'dan kelgan havolani OFFLINE evristika ([LinkScanner]) bilan baholaydi.
 * Ikki rejim:
 *   (a) extra "url" bilan ochilsa (ShareUrlReceiver / QR / dashboard) → darhol skan;
 *   (b) qo'lda: foydalanuvchi havolani yopishtiradi → "Tekshirish".
 *
 * Hech qachon noto'g'ri "XAVFSIZ" ko'rsatmaydi — [LinkScanner] bo'sh/o'qib bo'lmaydiganni
 * SHUBHALI deb qaytaradi. Bu faqat KO'RSATISH (UI).
 */
class LinkCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkCheckBinding

    companion object {
        const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, LinkCheckActivity::class.java).putExtra(EXTRA_URL, url)
    }

    /** Bitta belgining UI ko'rinishi: ikonka + tushuntiruvchi satr. */
    private data class FlagUi(val iconRes: Int, val textRes: Int)

    /** Har bir [LinkScanner.Flag] → ic4 ikonkasi + kq4_link_flag_* satri. */
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
        binding = ActivityLinkCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCheck.setOnClickListener { runCheck(binding.kq4Input.text?.toString().orEmpty()) }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }

        // (a) extra "url" bilan ochilgan bo'lsa — darhol skan.
        val incoming = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (incoming.isNotEmpty()) {
            binding.kq4Input.setText(incoming)
            runCheck(incoming)
        }
    }

    private fun pasteFromClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            if (text.isBlank()) {
                toast(getString(R.string.kq4_link_clip_empty))
                return
            }
            binding.kq4Input.setText(text.trim())
            runCheck(text.trim())
        } catch (_: Throwable) {
            toast(getString(R.string.kq4_link_clip_empty))
        }
    }

    /** Havolani tahlil qiladi va natijani ko'rsatadi. [LinkScanner] sof/sinxron — tarmoq yo'q. */
    private fun runCheck(raw: String) {
        hideKeyboard()
        val result = try {
            LinkScanner.analyze(raw)
        } catch (_: Throwable) {
            // Himoya: LinkScanner allaqachon hech qachon throw qilmaydi, ammo false-safe bermaymiz.
            LinkScanner.LinkResult(
                ScanResult.Verdict.SUSPICIOUS, raw, null,
                "kq4_link_reason_unparsable", listOf(LinkScanner.Flag.UNPARSABLE)
            )
        }
        render(result)
    }

    private fun render(result: LinkScanner.LinkResult) {
        val verdict = result.verdict

        // Banner foni / rang / ikonka — verdiktga qarab.
        val bannerBg = when (verdict) {
            ScanResult.Verdict.SAFE -> R.drawable.kq4_card_safe
            ScanResult.Verdict.SUSPICIOUS -> R.drawable.kq4_card_warn
            ScanResult.Verdict.DANGER -> R.drawable.kq4_card_danger
        }
        val avBg = when (verdict) {
            ScanResult.Verdict.SAFE -> R.drawable.kq4_av_safe
            ScanResult.Verdict.SUSPICIOUS -> R.drawable.kq4_av_warn
            ScanResult.Verdict.DANGER -> R.drawable.kq4_av_danger
        }
        val statusColor = getColor(
            when (verdict) {
                ScanResult.Verdict.SAFE -> R.color.kq_safe
                ScanResult.Verdict.SUSPICIOUS -> R.color.kq_warn
                ScanResult.Verdict.DANGER -> R.color.kq_danger
            }
        )
        val inkColor = getColor(
            when (verdict) {
                ScanResult.Verdict.SAFE -> R.color.kq_safe_ink
                ScanResult.Verdict.SUSPICIOUS -> R.color.kq_warn_ink
                ScanResult.Verdict.DANGER -> R.color.kq_danger_ink
            }
        )
        val verdictIcon = when (verdict) {
            ScanResult.Verdict.SAFE -> R.drawable.ic4_shield_check
            ScanResult.Verdict.SUSPICIOUS -> R.drawable.ic4_alert
            ScanResult.Verdict.DANGER -> R.drawable.ic4_shield_alert
        }
        val verdictTextRes = when (verdict) {
            ScanResult.Verdict.SAFE -> R.string.kq4_safe
            ScanResult.Verdict.SUSPICIOUS -> R.string.kq4_suspicious
            ScanResult.Verdict.DANGER -> R.string.kq4_danger
        }

        binding.cardBanner.setBackgroundResource(bannerBg)
        binding.bannerIconAv.setBackgroundResource(avBg)
        binding.bannerIcon.setImageResource(verdictIcon)
        binding.bannerIcon.imageTintList = ColorStateList.valueOf(statusColor)
        binding.tvBannerVerdict.text = getString(verdictTextRes)
        binding.tvBannerVerdict.setTextColor(inkColor)
        binding.tvBannerReason.text = resolveReason(result.reasonKey)
        binding.tvBannerReason.setTextColor(inkColor)
        binding.cardBanner.visibility = View.VISIBLE
        AnimationHelper.fadeIn(binding.cardBanner, duration = 320, delay = 0L)

        // Tekshirilgan URL (mono).
        binding.tvCheckedUrl.text = result.host?.let { getString(R.string.kq4_link_host_line, it) } ?: result.url
        binding.tvCheckedUrl.visibility = View.VISIBLE

        // Sabablar ro'yxati (inc_kq_perm_row inflate).
        renderReasons(result)
    }

    private fun renderReasons(result: LinkScanner.LinkResult) {
        val container = binding.reasonsContainer
        container.removeAllViews()

        // SAFE bo'lsa va belgi yo'q — sabablar seksiyasini yashiramiz, faqat banner qoladi.
        if (result.reasons.isEmpty()) {
            binding.secReasons.visibility = View.GONE
            return
        }
        binding.secReasons.visibility = View.VISIBLE

        // Belgi rangi — verdiktga qarab (DANGER qattiq belgilar qizil, qolganlari sariq).
        val avBg: Int
        val tintColor: Int
        when (result.verdict) {
            ScanResult.Verdict.DANGER -> {
                avBg = R.drawable.kq4_av_danger; tintColor = getColor(R.color.kq_danger)
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                avBg = R.drawable.kq4_av_warn; tintColor = getColor(R.color.kq_warn)
            }
            ScanResult.Verdict.SAFE -> {
                avBg = R.drawable.kq4_av_safe; tintColor = getColor(R.color.kq_safe)
            }
        }
        val tint = ColorStateList.valueOf(tintColor)

        val inflater = layoutInflater
        result.reasons.forEachIndexed { index, flag ->
            val ui = flagUi[flag] ?: FlagUi(R.drawable.ic4_alert, R.string.kq4_link_flag_unparsable)
            val view = inflater.inflate(R.layout.inc_kq_perm_row, container, false)
            view.findViewById<FrameLayout>(R.id.permAv).setBackgroundResource(avBg)
            val icon = view.findViewById<ImageView>(R.id.permIcon)
            icon.setImageResource(ui.iconRes)
            icon.imageTintList = tint
            view.findViewById<TextView>(R.id.tvPermLabel).text = getString(ui.textRes)
            view.findViewById<TextView>(R.id.tvPermSub).visibility = View.GONE
            view.findViewById<View>(R.id.permDivider).visibility =
                if (index == result.reasons.lastIndex) View.GONE else View.VISIBLE
            container.addView(view)
            AnimationHelper.fadeIn(view, duration = 320, delay = index * 60L)
        }
    }

    /**
     * reasonKey (kq4_link_reason_*) → tarjima qilingan satr. STATIK `when` (getIdentifier emas):
     * release'da isShrinkResources faqat kod orqali nomi bilan chaqirilgan resurslarni o'chirib
     * yubormasligi uchun har bir satr to'g'ridan-to'g'ri R.string sifatida yoziladi.
     */
    private fun resolveReason(reasonKey: String): String = when (reasonKey) {
        "kq4_link_reason_safe" -> getString(R.string.kq4_link_reason_safe)
        "kq4_link_reason_danger" -> getString(R.string.kq4_link_reason_danger)
        "kq4_link_reason_unparsable" -> getString(R.string.kq4_link_reason_unparsable)
        else -> getString(R.string.kq4_link_reason_suspicious)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.kq4Input.windowToken, 0)
        } catch (_: Throwable) { /* muhim emas */ }
    }

    private fun toast(msg: String) {
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }
}
