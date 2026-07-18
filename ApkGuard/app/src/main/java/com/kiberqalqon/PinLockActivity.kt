package com.uzguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== HIMOYA QULFI — PIN KIRITISH EKRANI ======
 *
 * Ikki rejim (intent extra [EXTRA_MODE]):
 *   "set"    — yangi PIN o'rnatish (ikki marta kiritiladi, mos kelsa saqlanadi).
 *   "verify" — mavjud PIN'ni tekshirish (bir marta; to'g'ri bo'lsa RESULT_OK).
 *
 * To'g'ri/o'rnatilganda: setResult(RESULT_OK); finish(). Bekor qilinsa — RESULT_CANCELED.
 * PIN [PinStore]da tuzli SHA-256 sifatida saqlanadi (xom PIN hech qachon saqlanmaydi).
 * Kod bilan qurilgan UI (XML'siz) — raqamli klaviatura.
 */
class PinLockActivity : AppCompatActivity() {

    private var verifyMode = true      // false = "set"
    private var firstEntry: String? = null   // "set" rejimida 1-kiritish
    private val entered = StringBuilder()

    private lateinit var titleView: TextView
    private lateinit var dotsView: TextView
    private lateinit var subtitleView: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        setResult(RESULT_CANCELED)

        val mode = try { intent?.getStringExtra(EXTRA_MODE) } catch (_: Throwable) { null }
        verifyMode = mode != MODE_SET
        // "verify" so'ralsa-yu PIN umuman o'rnatilmagan bo'lsa — to'sadigan narsa yo'q, o'tkazamiz.
        if (verifyMode && !PinStore.isSet(this)) {
            setResult(RESULT_OK); finish(); return
        }

        setContentView(buildUi())
        updateUi()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.kq_bg, "#FBF7EF"))
            setPadding(dp(24), dp(28), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleView = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(titleView)

        subtitleView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(subtitleView)

        // Kiritilgan raqamlar soni — nuqtalar bilan.
        dotsView = TextView(this).apply {
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_primary, "#C2143D"))
            letterSpacing = 0.3f
            setPadding(0, 0, 0, dp(28))
        }
        root.addView(dotsView)

        // Raqamli klaviatura: [1 2 3][4 5 6][7 8 9][⌫ 0 C].
        root.addView(keyRow("1", "2", "3"))
        root.addView(keyRow("4", "5", "6"))
        root.addView(keyRow("7", "8", "9"))
        root.addView(keyRow(KEY_DEL, "0", KEY_CLEAR))

        return root
    }

    private fun keyRow(a: String, b: String, c: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        row.addView(keyButton(a))
        row.addView(keyButton(b))
        row.addView(keyButton(c))
        return row
    }

    private fun keyButton(key: String): Button = Button(this).apply {
        text = when (key) {
            KEY_DEL -> "⌫"
            KEY_CLEAR -> "C"
            else -> key
        }
        isAllCaps = false
        textSize = 22f
        layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
        setOnClickListener { onKey(key) }
    }

    private fun onKey(key: String) {
        when (key) {
            KEY_DEL -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            KEY_CLEAR -> entered.setLength(0)
            else -> if (entered.length < PIN_LEN) entered.append(key)
        }
        updateDots()
        if (entered.length == PIN_LEN) {
            // 4-raqam kiritildi — avtomatik yakunlaymiz (kichik kechikish bilan, dots yangilansin).
            dotsView.post { onPinComplete() }
        }
    }

    private fun onPinComplete() {
        val pin = entered.toString()
        entered.setLength(0)
        updateDots()

        if (verifyMode) {
            if (PinStore.verify(this, pin)) {
                setResult(RESULT_OK); finish()
            } else {
                toast("PIN noto'g'ri. Qaytadan urinib ko'ring.")
            }
            return
        }

        // "set" rejimi — ikki bosqich.
        val first = firstEntry
        if (first == null) {
            firstEntry = pin
            updateUi()  // "Qaytaring" bosqichiga o'tamiz
        } else if (first == pin) {
            PinStore.set(this, pin)
            toast("PIN o'rnatildi ✓")
            setResult(RESULT_OK); finish()
        } else {
            firstEntry = null
            toast("PIN'lar mos kelmadi. Boshidan kiriting.")
            updateUi()
        }
    }

    private fun updateUi() {
        if (verifyMode) {
            titleView.text = "Qulfni oching"
            subtitleView.text = "UzGuard PIN kodini kiriting"
        } else if (firstEntry == null) {
            titleView.text = "Yangi PIN o'rnating"
            subtitleView.text = "4 xonali PIN o'ylab toping"
        } else {
            titleView.text = "PIN'ni tasdiqlang"
            subtitleView.text = "O'sha 4 raqamni qayta kiriting"
        }
        updateDots()
        ensureFooter()
    }

    private fun updateDots() {
        val filled = entered.length
        // ●●○○ ko'rinishi.
        dotsView.text = buildString {
            for (i in 0 until PIN_LEN) append(if (i < filled) "●" else "○")
        }
    }

    private var footerAdded = false
    /** Halol futer — bir marta qo'shiladi (root'ning oxiriga). */
    private fun ensureFooter() {
        if (footerAdded) return
        footerAdded = true
        val root = (findViewById<View>(android.R.id.content) as? ViewGroup)
            ?.getChildAt(0) as? LinearLayout ?: return
        root.addView(TextView(this).apply {
            text = "Eslatma: ilovani tizim sozlamalaridan majburan to'xtatish/o'chirish PIN'ni " +
                "chetlab o'tishi mumkin."
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(dp(8), dp(24), dp(8), 0)
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SET = "set"
        const val MODE_VERIFY = "verify"

        private const val PIN_LEN = 4
        private const val KEY_DEL = "del"
        private const val KEY_CLEAR = "clear"
    }
}
