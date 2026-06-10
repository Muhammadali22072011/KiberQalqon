package com.kiberqalqon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * «Скрытые / неудаляемые угрозы» — список уже установленных подозрительных приложений
 * ([HiddenThreatScanner]) + пошаговый гид удаления. Code-built UI (без XML), как
 * DiagnosticsActivity — рендерится даже если ресурсы сломаны.
 *
 * Главное правило гида: СНАЧАЛА разоружить (снять device-admin / Accessibility / доступ
 * к уведомлениям), ПОТОМ удалять. Если не помогает — Safe Mode или ADB.
 */
class HiddenThreatsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)

        // v4 «Milliy Kiber Himoya» reskin — fon kq_bg, eyebrow + h-title sarlavha,
        // kartalar kq4_card / kq4_card_danger, pill tugmalar. Logika o'zgarmagan.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(30))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_eyebrow)
            isAllCaps = true
            textSize = 12f
            typeface = font(R.font.onest_bold)
            letterSpacing = 0.02f
            setTextColor(c(R.color.kq_primary))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_title)
            textSize = 24f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.02f
            setTextColor(c(R.color.kq_ink))
            setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_intro)
            textSize = 13.5f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(14))
        })

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_checking)
            textSize = 14f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
        })
        root.addView(container)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(c(R.color.kq_bg))
            isFillViewport = true
            addView(root)
        })

        Thread {
            val findings = try { HiddenThreatScanner.scan(this) } catch (_: Throwable) { emptyList() }
            runOnUiThread { render(findings) }
        }.start()
    }

    private fun render(findings: List<HiddenThreatScanner.Finding>) {
        container.removeAllViews()
        if (findings.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.kq4_misc_hidden_none)
                textSize = 15f
                typeface = font(R.font.onest_semibold)
                setTextColor(c(R.color.kq_safe))
                background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_safe)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            container.addView(TextView(this).apply {
                text = getString(R.string.kq4_misc_hidden_count, findings.size)
                textSize = 13f
                typeface = font(R.font.onest_semibold)
                setTextColor(c(R.color.kq_ink_2))
                setPadding(0, 0, 0, dp(8))
            })
            for (f in findings) container.addView(card(f))
        }
        container.addView(footer())
    }

    private fun card(f: HiddenThreatScanner.Finding): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            background = AppCompatResources.getDrawable(
                context,
                if (f.family != null) R.drawable.kq4_card_danger else R.drawable.kq4_card
            )
        }
        card.addView(TextView(this).apply {
            text = (if (f.family != null) "🔴 " else "⚠️ ") + f.label
            textSize = 16f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.01f
            setTextColor(c(if (f.family != null) R.color.kq_danger_ink else R.color.kq_ink))
        })
        card.addView(TextView(this).apply {
            text = f.pkg
            textSize = 11f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(R.color.kq_ink_3))
        })
        card.addView(TextView(this).apply {
            text = buildString {
                append(getString(R.string.kq4_misc_hidden_reason, f.traits.joinToString(", ") { traitUz(it) }))
                if (f.family != null) append("\n" + getString(R.string.kq4_misc_hidden_blacklist, f.family))
                if (!f.fromPlay) append("\n" + getString(R.string.kq4_misc_hidden_sideload))
                if (f.dangerousPerms.isNotEmpty()) append("\n" + getString(R.string.kq4_misc_hidden_dangerous_perms, f.dangerousPerms.size))
            }
            textSize = 12.5f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(5), 0, dp(8))
        })

        // Гид: сначала разоружить (по порядку), потом удалить.
        if (HiddenThreatScanner.Trait.DEVICE_ADMIN in f.traits) {
            card.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_admin)) {
                openAction(
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                    getString(R.string.kq4_misc_hidden_hint_admin, f.label)
                )
            })
        }
        if (HiddenThreatScanner.Trait.ACCESSIBILITY in f.traits) {
            card.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_access)) {
                openAction(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    getString(R.string.kq4_misc_hidden_hint_access, f.label)
                )
            })
        }
        if (HiddenThreatScanner.Trait.NOTIF_ACCESS in f.traits) {
            card.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_notif)) {
                openAction(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), null)
            })
        }
        card.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_uninstall), danger = true) {
            openAction(Intent(Intent.ACTION_DELETE, Uri.parse("package:${f.pkg}")), null)
        })
        card.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_appinfo)) {
            openAction(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${f.pkg}")), null)
        })
        return card
    }

    private fun footer(): View {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(8))
        }
        f.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_footer_title)
            textSize = 15f
            typeface = font(R.font.onest_bold)
            setTextColor(c(R.color.kq_ink))
            setPadding(0, 0, 0, dp(6))
        })
        f.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_hidden_footer_body)
            textSize = 12.5f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(8))
        })
        val adb = getString(R.string.kq4_misc_hidden_adb_cmd)
        f.addView(TextView(this).apply {
            text = adb
            textSize = 12f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(R.color.kq_ink_2))
            setTextIsSelectable(true)
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_sunken)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        f.addView(actionBtn(getString(R.string.kq4_misc_hidden_act_copy_adb)) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", adb))
            Toast.makeText(this, getString(R.string.kq4_misc_toast_copied), Toast.LENGTH_SHORT).show()
        })
        return f
    }

    /** v4 pill tugma: soft (standart) yoki danger. */
    private fun actionBtn(label: String, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            typeface = font(R.font.onest_bold)
            background = AppCompatResources.getDrawable(
                context,
                if (danger) R.drawable.kq4_btn_danger else R.drawable.kq4_btn_soft
            )
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
            setTextColor(c(if (danger) R.color.white else R.color.kq_ink))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    // ───────────────────── v4 dizayn yordamchilari ─────────────────────

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun font(id: Int): android.graphics.Typeface? =
        try { ResourcesCompat.getFont(this, id) } catch (_: Exception) { null }

    private fun openAction(intent: Intent, hint: String?) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.kq4_misc_hidden_open_fail, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun traitUz(t: HiddenThreatScanner.Trait): String = getString(when (t) {
        HiddenThreatScanner.Trait.HIDDEN_ICON -> R.string.kq4_misc_hidden_trait_hidden_icon
        HiddenThreatScanner.Trait.DEVICE_ADMIN -> R.string.kq4_misc_hidden_trait_admin
        HiddenThreatScanner.Trait.ACCESSIBILITY -> R.string.kq4_misc_hidden_trait_access
        HiddenThreatScanner.Trait.NOTIF_ACCESS -> R.string.kq4_misc_hidden_trait_notif
        HiddenThreatScanner.Trait.BLACKLISTED -> R.string.kq4_misc_hidden_trait_blacklist
    })

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
