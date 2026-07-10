package com.uzguard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== XAVFSIZLIK BALLI EKRANI (0–100) ======
 *
 * [SecurityScore] hisoblagan ballni katta raqam + [KqScoreView] yoy bilan ko'rsatadi,
 * ostида — tuzatilishi kerak bo'lgan muammolar, har biri "Tuzatish" tugmasi bilan
 * tegishli tizim/ilova ekraniga olib boradi. onResume'da qayta hisoblaydi — foydalanuvchi
 * muammoni tuzatib qaytsa, ball darhol o'sadi ("100 gacha yetkaz" o'yini).
 *
 * Kod bilan qurilgan UI (XML'siz).
 */
class SecurityScoreActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var scoreView: KqScoreView
    private lateinit var scoreNumber: TextView
    private lateinit var scoreCaption: TextView
    private lateinit var issuesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = LocaleHelper.apply(this)
        title = "Xavfsizlik balli"

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(color(R.color.kq_bg, "#FBF7EF"))
            isFillViewport = true
        }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, dp(32))
        }
        scroll.addView(container)
        setContentView(scroll)

        // Gauge (yoy + markazda raqam).
        val gauge = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
            }
        }
        scoreView = KqScoreView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        scoreNumber = TextView(ctx).apply {
            textSize = 46f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.kq_ink, "#2A2622"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        gauge.addView(scoreView)
        gauge.addView(scoreNumber)
        container.addView(gauge)

        scoreCaption = TextView(ctx).apply {
            textSize = 15f; gravity = Gravity.CENTER
            setTextColor(color(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(scoreCaption)

        container.addView(TextView(ctx).apply {
            text = "Tuzatilishi kerak:"
            textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.kq_ink, "#2A2622"))
            setPadding(0, dp(8), 0, dp(4))
        })
        issuesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(issuesBox)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Thread {
            val result = try { SecurityScore.evaluate(SecurityScore.capture(this)) }
                catch (_: Throwable) { SecurityScore.Result(0, emptyList()) }
            runOnUiThread { render(result) }
        }.start()
    }

    private fun render(result: SecurityScore.Result) {
        scoreNumber.text = result.score.toString()
        scoreView.setScore(result.score)
        val (cap, c) = when (SecurityScore.band(result.score)) {
            "safe" -> "Ajoyib! Qurilmangiz yaxshi himoyalangan." to color(R.color.kq_safe, "#1A9E54")
            "warn" -> "Yaxshi, lekin yaxshilash mumkin." to color(R.color.kq_warn, "#DF8A18")
            else -> "Diqqat: qurilmangiz zaif. Quyidagilarni tuzating." to color(R.color.kq_danger, "#E0432F")
        }
        scoreCaption.text = cap
        scoreCaption.setTextColor(c)

        issuesBox.removeAllViews()
        if (result.issues.isEmpty()) {
            issuesBox.addView(TextView(this).apply {
                text = "✅ Hammasi joyida — 100/100!"
                textSize = 15f; setTextColor(color(R.color.kq_safe, "#1A9E54"))
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        result.issues.forEach { issuesBox.addView(issueCard(it)) }
    }

    private fun issueCard(issue: SecurityScore.Issue): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14)
            setPadding(p, dp(12), p, dp(12))
            setBackgroundColor(color(R.color.kq_bg_elev, "#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = "⚠ ${issue.title}"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.kq_ink, "#2A2622"))
        })
        card.addView(TextView(this).apply {
            text = issue.advice
            textSize = 13f; setTextColor(color(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(2), 0, dp(8))
        })
        card.addView(Button(this).apply {
            text = "Tuzatish  (+${issue.weight})"
            isAllCaps = false
            setOnClickListener { fix(issue.id) }
        })
        return card
    }

    /** Muammo turiga qarab tegishli tizim/ilova ekraniga olib boradi. */
    private fun fix(id: String) {
        try {
            when (id) {
                "lock" -> startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                "adb" -> startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                "remote" -> startActivity(Intent(this, SideloadAuditActivity::class.java))
                "danger" -> startActivity(Intent(this, MainActivity::class.java)
                    .putExtra("trigger_scan", true))
                "background" -> {
                    Config.setBackgroundEnabled(this, true)
                    try { ProtectionService.start(this) } catch (_: Throwable) {}
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                "vpn" -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        } catch (_: Throwable) {
            try { startActivity(Intent(this, SettingsActivity::class.java)) } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }
}
