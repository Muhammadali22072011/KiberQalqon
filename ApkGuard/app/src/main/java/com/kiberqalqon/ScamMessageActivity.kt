package com.uzguard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== "BU XABAR FIRIBGARLIKMI?" EKRANI ======
 *
 * Matnni ikki yo'l bilan oladi:
 *   (a) boshqa ilovadan ULASHISH (ACTION_SEND, text/plain — EXTRA_TEXT);
 *   (b) ekrandagi maydonga yopishtirish/yozish.
 *
 * [ScamTextAnalyzer] (offline) topgan SIGNALLARni ro'yxat qilib ko'rsatadi va HALOL futer
 * bilan yakunlaydi — bu yakuniy hukm emas, faqat belgilar. Kod bilan qurilgan UI (XML'siz).
 */
class ScamMessageActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var resultBox: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        title = "Xabar tekshiruvi"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(col(R.color.kq_bg, "#FBF7EF"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Bu xabar tekshiruvi"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
        })
        root.addView(TextView(this).apply {
            text = "Shubhali SMS yoki Telegram xabarini shu yerga joylang — offline tekshiramiz."
            textSize = 14f
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(6), 0, dp(12))
            setLineSpacing(0f, 1.3f)
        })

        input = EditText(this).apply {
            hint = "Xabar matnini shu yerga joylang…"
            textSize = 15f
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setHintTextColor(col(R.color.kq_ink_3, "#8A8175"))
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(col(R.color.kq_bg_elev, "#FFFFFF"))
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "Tekshirish"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { runAnalysis(input.text?.toString().orEmpty()) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox)

        // Halol futer — doim ko'rinadi.
        root.addView(TextView(this).apply {
            text = "Bu — signallar, yakuniy hukm emas. Shubha bo'lsa — hech kimga kod/parol bermang."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(20), 0, 0)
            setLineSpacing(0f, 1.3f)
        })

        // ACTION_SEND bilan kelgan matnni oldindan to'ldirib, darhol tahlil qilamiz.
        val shared = sharedText()
        if (!shared.isNullOrBlank()) {
            input.setText(shared)
            runAnalysis(shared)
        }
    }

    /** ACTION_SEND text/plain bilan kelgan matnni oladi (bo'lmasa null). */
    private fun sharedText(): String? {
        return try {
            if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } else null
        } catch (_: Throwable) { null }
    }

    private fun runAnalysis(text: String) {
        resultBox.removeAllViews()
        if (text.isBlank()) {
            resultBox.addView(hintLine("Avval xabar matnini kiriting."))
            return
        }
        val result = try { ScamTextAnalyzer.analyze(text) }
            catch (_: Throwable) { ScamTextAnalyzer.Result(emptyList(), "shubhali") }

        // Daraja bezagi.
        val (label, color) = when (result.riskLevel) {
            "xavfli" -> "Xavf belgilari kuchli" to col(R.color.kq_danger, "#E0432F")
            "shubhali" -> "Shubhali belgilar bor" to col(R.color.kq_warn, "#DF8A18")
            else -> "Aniq xavf belgisi topilmadi" to col(R.color.kq_safe, "#1A9E54")
        }
        resultBox.addView(TextView(this).apply {
            text = label
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setPadding(0, dp(16), 0, dp(8))
        })

        if (result.signals.isEmpty()) {
            resultBox.addView(TextView(this).apply {
                text = "Aniq xavf belgisi topilmadi, lekin ehtiyot bo'ling — firibgarlik har xil " +
                    "ko'rinishda bo'ladi."
                textSize = 14.5f
                setTextColor(col(R.color.kq_ink_2, "#5C554C"))
                setLineSpacing(0f, 1.35f)
            })
            return
        }

        result.signals.forEach { sig -> resultBox.addView(signalRow(sig)) }
    }

    /** Bitta signal qatori — ogohlantirish belgisi + izoh. */
    private fun signalRow(text: String): TextView = TextView(this).apply {
        this.text = "⚠  $text"
        textSize = 14.5f
        setTextColor(col(R.color.kq_ink, "#2A2622"))
        setPadding(0, dp(6), 0, dp(6))
        setLineSpacing(0f, 1.3f)
        setBackgroundColor(col(R.color.kq_bg_elev, "#FFFFFF"))
    }

    private fun hintLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(col(R.color.kq_ink_3, "#8A8175"))
        setPadding(0, dp(12), 0, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }
}
