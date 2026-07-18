package com.uzguard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.uzguard.databinding.ActivityPermissionXrayBinding

/**
 * «Ruxsat rentgeni» — o'rnatilgan ilovalarni ruxsat-KOMBINATSIYASI xavfi bo'yicha tartiblaydi
 * ([PermissionXray]). Sozlamalardagi chevron'dan ochiladi.
 *
 * Har bir satr: ilova nomi + ball-tegi (≥60 xavfli, ≥25 e'tibor) + kombo qisqacha.
 * Satrni bossang — kritik/diqqat ruxsatlar inson tilida ochiladi (PermissionsDetailActivity.classify
 * + kq4_scanres_perm_* izohlari qayta ishlatiladi) + tuzatish tugmalari (ilova sozlamalari / Accessibility).
 *
 * Bu faqat KO'RSATISH (UI) — hech narsani o'chirmaydi, hech qachon istisno tashlamaydi.
 */
class PermissionXrayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionXrayBinding

    // Score → tag rangi chegaralari (spec): ≥60 danger, ≥25 warn, aks holda neutral.
    private val expanded = HashSet<String>()
    private var risks: List<PermissionXray.AppRisk> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityPermissionXrayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        showChecking()
        Thread {
            val found = try { PermissionXray.scan(this) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    risks = found
                    render()
                }
            }
        }.start()
    }

    private fun showChecking() {
        binding.xrayContainer.removeAllViews()
        binding.xrayContainer.addView(TextView(this).apply {
            text = getString(R.string.kq4_xray_checking)
            textSize = 14f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
        })
    }

    private fun render() {
        binding.xrayContainer.removeAllViews()
        if (risks.isEmpty()) {
            binding.xrayContainer.addView(TextView(this).apply {
                text = getString(R.string.kq4_xray_none)
                textSize = 15f
                typeface = font(R.font.onest_semibold)
                setTextColor(c(R.color.kq_safe))
                background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_safe)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }

        binding.xrayContainer.addView(TextView(this).apply {
            text = getString(R.string.kq4_xray_count, risks.size)
            textSize = 13f
            typeface = font(R.font.onest_semibold)
            setTextColor(c(R.color.kq_ink_2))
            setPadding(0, 0, 0, dp(10))
        })

        risks.forEachIndexed { index, risk ->
            val card = buildCard(risk)
            binding.xrayContainer.addView(card)
            AnimationHelper.fadeIn(card, duration = 320, delay = index * 60L)
        }
    }

    /** Bitta ilova kartasi: sarlavha qatori (nom + ball-teg) + kombo + (ochilganda) ruxsat detallari. */
    private fun buildCard(risk: PermissionXray.AppRisk): View {
        val tier = tierOf(risk.score)
        val cardBg = when (tier) {
            Tier.DANGER -> R.drawable.kq4_card_danger
            Tier.WARN -> R.drawable.kq4_card_warn
            Tier.NEUTRAL -> R.drawable.kq4_card
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            background = AppCompatResources.getDrawable(context, cardBg)
            isClickable = true
            isFocusable = true
        }

        // Sarlavha qatori: ilova nomi (+ paket) | ball-teg
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = risk.label
            textSize = 16f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.01f
            setTextColor(c(if (tier == Tier.DANGER) R.color.kq_danger_ink else R.color.kq_ink))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleCol.addView(TextView(this).apply {
            text = risk.pkg
            textSize = 11f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(R.color.kq_ink_3))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        headerRow.addView(titleCol)
        headerRow.addView(scoreTag(risk.score, tier))
        card.addView(headerRow)

        // Kombo qisqacha
        val comboSummary = if (risk.combos.isNotEmpty())
            risk.combos.joinToString("  ·  ")
        else getString(R.string.kq4_xray_no_combo)
        card.addView(TextView(this).apply {
            text = comboSummary
            textSize = 12.5f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(8), 0, 0)
        })

        if (!risk.fromPlay) {
            card.addView(TextView(this).apply {
                text = getString(R.string.kq4_xray_sideload)
                textSize = 12f
                typeface = font(R.font.onest_semibold)
                setTextColor(c(R.color.kq_warn_ink))
                setPadding(0, dp(6), 0, 0)
            })
        }

        // Ochish/yopish ipi
        val hint = TextView(this).apply {
            textSize = 12f
            typeface = font(R.font.onest_semibold)
            setTextColor(c(R.color.kq_primary))
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(hint)

        // Ochilganda ko'rsatiladigan detal qismi
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(detail)

        fun refreshState() {
            val open = risk.pkg in expanded
            hint.text = getString(if (open) R.string.kq4_xray_collapse else R.string.kq4_xray_expand)
            detail.visibility = if (open) View.VISIBLE else View.GONE
            if (open && detail.childCount == 0) buildDetail(detail, risk)
        }
        refreshState()
        card.setOnClickListener {
            if (risk.pkg in expanded) expanded.remove(risk.pkg) else expanded.add(risk.pkg)
            refreshState()
        }

        return card
    }

    /** Ochilgan karta detali: KRITIK + DIQQAT ruxsatlar (inson tilida) + tuzatish tugmalari. */
    private fun buildDetail(detail: LinearLayout, risk: PermissionXray.AppRisk) {
        detail.setPadding(0, dp(12), 0, 0)

        if (risk.critPerms.isNotEmpty()) {
            detail.addView(sectionTitle(getString(R.string.kq4_xray_sec_crit), R.color.kq_danger_ink))
            risk.critPerms.forEachIndexed { i, perm ->
                detail.addView(permRow(perm, PermissionsDetailActivity.Sev.CRIT, i == risk.critPerms.lastIndex))
            }
        }
        if (risk.warnPerms.isNotEmpty()) {
            detail.addView(sectionTitle(getString(R.string.kq4_xray_sec_warn), R.color.kq_warn_ink).apply {
                setPadding(0, dp(10), 0, 0)
            })
            risk.warnPerms.forEachIndexed { i, perm ->
                detail.addView(permRow(perm, PermissionsDetailActivity.Sev.WARN, i == risk.warnPerms.lastIndex))
            }
        }
        if (risk.critPerms.isEmpty() && risk.warnPerms.isEmpty()) {
            detail.addView(TextView(this).apply {
                text = getString(R.string.kq4_xray_no_perms)
                textSize = 12.5f
                typeface = font(R.font.onest_regular)
                setTextColor(c(R.color.kq_ink_3))
            })
        }

        // Tuzatish tugmalari
        detail.addView(actionBtn(getString(R.string.kq4_xray_act_appinfo)) {
            openAction(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${risk.pkg}")))
        })
        // Accessibility kombosi bo'lsa — Accessibility sozlamalariga yo'naltiramiz.
        val hasA11y = risk.critPerms.any { it.substringAfterLast('.').uppercase() == "BIND_ACCESSIBILITY_SERVICE" } ||
            risk.combos.any { it.contains("a11y", true) || it.contains("Accessibility", true) }
        if (hasA11y) {
            detail.addView(actionBtn(getString(R.string.kq4_xray_act_a11y)) {
                openAction(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
    }

    /** inc_kq_perm_row dan inflate — PermissionsDetail bilan bir xil inson-tilidagi izoh. */
    private fun permRow(perm: String, sev: PermissionsDetailActivity.Sev, last: Boolean): View {
        val view = layoutInflater.inflate(R.layout.inc_kq_perm_row, binding.xrayContainer, false)
        val short = perm.substringAfterLast('.').uppercase()
        val ui = PERM_UI[short]

        val avBg = if (sev == PermissionsDetailActivity.Sev.CRIT) R.drawable.kq4_av_danger else R.drawable.kq4_av_warn
        val tintColor = if (sev == PermissionsDetailActivity.Sev.CRIT) R.color.kq_danger else R.color.kq_warn
        view.findViewById<FrameLayout>(R.id.permAv).setBackgroundResource(avBg)
        val icon = view.findViewById<ImageView>(R.id.permIcon)
        icon.setImageResource(ui?.iconRes ?: R.drawable.ic4_key)
        icon.imageTintList = ColorStateList.valueOf(c(tintColor))

        view.findViewById<TextView>(R.id.tvPermLabel).text =
            if (ui != null) getString(ui.titleRes) else PermissionCatalog.label(this, perm)

        val sub = view.findViewById<TextView>(R.id.tvPermSub)
        sub.visibility = View.VISIBLE
        sub.text = if (ui != null) getString(ui.subRes)
        else getString(
            if (sev == PermissionsDetailActivity.Sev.CRIT) R.string.kq4_scanres_perm_generic_crit
            else R.string.kq4_scanres_perm_generic_warn
        )

        view.findViewById<View>(R.id.permDivider).visibility = if (last) View.GONE else View.VISIBLE
        return view
    }

    private fun sectionTitle(text: String, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        typeface = font(R.font.onest_bold)
        isAllCaps = true
        letterSpacing = 0.02f
        setTextColor(c(colorRes))
        setPadding(0, 0, 0, dp(4))
    }

    /** Ball-teg: ≥60 danger, ≥25 warn, aks holda neutral. */
    private fun scoreTag(score: Int, tier: Tier): View {
        val bg = when (tier) {
            Tier.DANGER -> R.drawable.kq4_tag_danger
            Tier.WARN -> R.drawable.kq4_tag_warn
            Tier.NEUTRAL -> R.drawable.kq4_tag_soft
        }
        val ink = when (tier) {
            Tier.DANGER -> R.color.kq_danger_ink
            Tier.WARN -> R.color.kq_warn_ink
            Tier.NEUTRAL -> R.color.kq_ink_2
        }
        return TextView(this).apply {
            text = getString(R.string.kq4_xray_score_tag, score)
            textSize = 12f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(ink))
            background = AppCompatResources.getDrawable(context, bg)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
    }

    /** v4 pill tugma (HiddenThreatsActivity idiomi). */
    private fun actionBtn(label: String, onClick: () -> Unit): View =
        android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            typeface = font(R.font.onest_bold)
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_btn_soft)
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
            setTextColor(c(R.color.kq_ink))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun openAction(intent: Intent) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.kq4_xray_open_fail), Toast.LENGTH_LONG).show()
        }
    }

    private enum class Tier { DANGER, WARN, NEUTRAL }

    private fun tierOf(score: Int): Tier = when {
        score >= 60 -> Tier.DANGER
        score >= 25 -> Tier.WARN
        else -> Tier.NEUTRAL
    }

    /** Ruxsatning inson tilidagi ko'rinishi (nom + izoh + ikonka). */
    private data class PermUi(val titleRes: Int, val subRes: Int, val iconRes: Int)

    companion object {
        // PermissionsDetailActivity.permUi `private` — bu yerda mavjud kq4_scanres_perm_* kalitlarini
        // qayta ishlatamiz; X-ray'ga xos qo'shimcha ruxsatlar uchun kq4_xray_perm_* kalitlari.
        private val PERM_UI: Map<String, PermUi> = mapOf(
            "READ_SMS" to PermUi(R.string.kq4_scanres_perm_read_sms, R.string.kq4_scanres_perm_read_sms_s, R.drawable.ic4_message),
            "RECEIVE_SMS" to PermUi(R.string.kq4_scanres_perm_recv_sms, R.string.kq4_scanres_perm_recv_sms_s, R.drawable.ic4_message),
            "SEND_SMS" to PermUi(R.string.kq4_scanres_perm_send_sms, R.string.kq4_scanres_perm_send_sms_s, R.drawable.ic4_message),
            "BIND_ACCESSIBILITY_SERVICE" to PermUi(R.string.kq4_scanres_perm_a11y, R.string.kq4_scanres_perm_a11y_s, R.drawable.ic4_eye),
            "SYSTEM_ALERT_WINDOW" to PermUi(R.string.kq4_scanres_perm_overlay, R.string.kq4_scanres_perm_overlay_s, R.drawable.ic4_alert),
            "BIND_NOTIFICATION_LISTENER_SERVICE" to PermUi(R.string.kq4_xray_perm_notif, R.string.kq4_xray_perm_notif_s, R.drawable.ic4_bell),
            "BIND_DEVICE_ADMIN" to PermUi(R.string.kq4_xray_perm_admin, R.string.kq4_xray_perm_admin_s, R.drawable.ic4_lock),
            "READ_PHONE_STATE" to PermUi(R.string.kq4_scanres_perm_phone_state, R.string.kq4_scanres_perm_phone_state_s, R.drawable.ic4_phone),
            "READ_CONTACTS" to PermUi(R.string.kq4_scanres_perm_contacts, R.string.kq4_scanres_perm_contacts_s, R.drawable.ic4_user),
            "READ_CALL_LOG" to PermUi(R.string.kq4_scanres_perm_call_log, R.string.kq4_scanres_perm_call_log_s, R.drawable.ic4_phone),
            "REQUEST_INSTALL_PACKAGES" to PermUi(R.string.kq4_scanres_perm_install, R.string.kq4_scanres_perm_install_s, R.drawable.ic4_lock),
            "QUERY_ALL_PACKAGES" to PermUi(R.string.kq4_scanres_perm_query_all, R.string.kq4_scanres_perm_query_all_s, R.drawable.ic4_layers),
            "CAMERA" to PermUi(R.string.kq4_scanres_perm_camera, R.string.kq4_scanres_perm_camera_s, R.drawable.ic4_eye),
            "RECORD_AUDIO" to PermUi(R.string.kq4_scanres_perm_mic, R.string.kq4_scanres_perm_mic_s, R.drawable.ic4_eye),
            "ACCESS_FINE_LOCATION" to PermUi(R.string.kq4_xray_perm_location, R.string.kq4_xray_perm_location_s, R.drawable.ic4_globe)
        )
    }

    // ───────────────────── yordamchilar ─────────────────────

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun font(id: Int): android.graphics.Typeface? =
        try { ResourcesCompat.getFont(this, id) } catch (_: Exception) { null }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
