package com.uzguard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== "30 SONIYALIK TEKSHIRUV" SEHRGARI ======
 *
 * MAVJUD tekshiruvlarni ([SecurityScore]) ketma-ket yuritib, HAR SAFAR BITTA muammoni
 * bir tap bilan tuzatish tugmasi bilan ko'rsatadi va yakunda tinch xulosa beradi.
 *
 * [SecurityScore.capture] + [SecurityScore.evaluate] qurilma holatini (ekran qulfi / fon
 * himoyasi / xavfli ilova / masofaviy boshqaruv / VPN / ADB) baholab, har biriga tuzatish
 * bilan muammolar ro'yxatini beradi. Tuzatish intentlari [SecurityScoreActivity] bilan AYNAN
 * bir xil. Kod bilan qurilgan UI (XML'siz).
 */
class CheckupWizardActivity : AppCompatActivity() {

    private lateinit var card: LinearLayout
    private var issues: List<SecurityScore.Issue> = emptyList()
    private var initialCount = 0
    private var index = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        title = "Tezkor tekshiruv"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(col(R.color.kq_bg, "#FBF7EF"))
            isFillViewport = true
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(card)
        setContentView(scroll)

        loadIssues()
    }

    /** Muammolarni fon oqimida yig'ib, birinchi qadamni ko'rsatamiz. */
    private fun loadIssues() {
        card.removeAllViews()
        card.addView(TextView(this).apply {
            text = "Tekshirilmoqda…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(40), 0, 0)
        })
        Thread {
            val result = try { SecurityScore.evaluate(SecurityScore.capture(this)) }
                catch (_: Throwable) { SecurityScore.Result(0, emptyList()) }
            runOnUiThread {
                issues = result.issues
                initialCount = result.issues.size
                index = 0
                renderStep()
            }
        }.start()
    }

    private fun renderStep() {
        card.removeAllViews()
        if (index >= issues.size) {
            renderSummary()
            return
        }
        val issue = issues[index]

        card.addView(TextView(this).apply {
            text = "Qadam ${index + 1} / ${issues.size}"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_primary, "#C2143D"))
            isAllCaps = true
            letterSpacing = 0.04f
        })

        card.addView(TextView(this).apply {
            text = "⚠  ${issue.title}"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setPadding(0, dp(10), 0, dp(8))
            setLineSpacing(0f, 1.2f)
        })

        card.addView(TextView(this).apply {
            text = issue.advice
            textSize = 15f
            setTextColor(col(R.color.kq_ink_2, "#5C554C"))
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(20))
        })

        card.addView(Button(this).apply {
            text = "Tuzatish  (+${issue.weight})"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { fix(issue.id) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        card.addView(Button(this).apply {
            text = if (index == issues.lastIndex) "Yakunlash" else "Keyingi"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { index++; renderStep() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
    }

    /** Yakuniy karta — qayta baholab, nechtasi tuzatilgan/qolganini ko'rsatamiz. */
    private fun renderSummary() {
        card.removeAllViews()
        card.addView(TextView(this).apply {
            text = "Hisoblanmoqda…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(30), 0, 0)
        })
        Thread {
            val result = try { SecurityScore.evaluate(SecurityScore.capture(this)) }
                catch (_: Throwable) { SecurityScore.Result(0, emptyList()) }
            val remaining = result.issues.size
            val fixed = (initialCount - remaining).coerceAtLeast(0)
            runOnUiThread { showSummary(result.score, fixed, remaining) }
        }.start()
    }

    private fun showSummary(score: Int, fixed: Int, remaining: Int) {
        card.removeAllViews()

        card.addView(TextView(this).apply {
            text = "Tekshiruv tugadi ✓"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_safe, "#1A9E54"))
            setPadding(0, dp(30), 0, dp(12))
        })

        card.addView(TextView(this).apply {
            text = "Xavfsizlik balli: $score / 100"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setPadding(0, 0, 0, dp(14))
        })

        val summary = when {
            initialCount == 0 -> "Hammasi joyida edi — hech narsani tuzatish shart emas."
            remaining == 0 -> "Barcha $initialCount ta muammo tuzatildi. Zo'r!"
            fixed == 0 -> "$remaining ta muammo hali ochiq — istagan vaqtda tuzatishingiz mumkin."
            else -> "$fixed ta tuzatildi, $remaining ta qoldi."
        }
        card.addView(TextView(this).apply {
            text = summary
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_2, "#5C554C"))
            setLineSpacing(0f, 1.35f)
            setPadding(dp(8), 0, dp(8), dp(22))
        })

        if (remaining > 0) {
            card.addView(Button(this).apply {
                text = "Qaytadan tekshirish"
                isAllCaps = false
                textSize = 16f
                setOnClickListener { loadIssues() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        card.addView(Button(this).apply {
            text = "Tayyor"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
    }

    /**
     * Muammo turiga qarab tegishli tizim/ilova ekraniga olib boradi — [SecurityScoreActivity.fix]
     * bilan AYNAN bir xil intentlar.
     */
    private fun fix(id: String) {
        try {
            when (id) {
                "lock" -> startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                "adb" -> startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                "remote" -> startActivity(Intent(this, SideloadAuditActivity::class.java))
                "danger" -> startActivity(
                    Intent(this, MainActivity::class.java).putExtra("trigger_scan", true)
                )
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
    private fun col(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }
}
