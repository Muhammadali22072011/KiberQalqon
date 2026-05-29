package com.kiberqalqon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.button.MaterialButton

/**
 * Maxfiy kirish ekrani (rollar tizimi) — Sozlamalardagi 4 tugma kombinatsiyasi
 * to'g'ri kiritilgach ochiladi (SecretAccess.onTap → true).
 *
 * Bosqichlar ("qulflar" 2→4):
 *   2) soatlik kod
 *   3) login + parol
 *   4) server rolni qaytaradi (RoleAccessClient) → rol paneli
 *
 * exported=false + FLAG_SECURE (skrinshot/ekran yozuvi bloklanadi). Recents'da
 * ko'rinmaydi (manifest: excludeFromRecents).
 */
class SecretAccessActivity : AppCompatActivity() {

    private enum class Stage { CODE, CREDS, LOADING, RESULT_OK, RESULT_OFF, OWNER_SECRET, OWNER_CODE }

    private lateinit var content: LinearLayout
    private var enteredCode: String = ""
    private var enteredLogin: String = ""

    // Owner mode (kodni ko'rsatish) — joriy kod va u yangilanguncha qolgan soniya.
    private val ui = Handler(Looper.getMainLooper())
    private var ownerTicker: Runnable? = null
    private var ownerSecondsLeft = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}

        // Maxfiy ekran — skrinshot va ekran yozuvini taqiqlaymiz.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.kq_bg))
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)

        render(Stage.CODE)
    }

    // ---- Bosqichlarni chizish ---------------------------------------------

    private fun render(stage: Stage) {
        // Owner-kod taymeri faqat OWNER_CODE bosqichida ishlaydi.
        if (stage != Stage.OWNER_CODE) stopOwnerTicker()
        content.removeAllViews()
        content.addView(header())

        when (stage) {
            Stage.CODE -> renderCode()
            Stage.CREDS -> renderCreds()
            Stage.LOADING -> renderLoading()
            Stage.RESULT_OK -> renderResultOk()
            Stage.RESULT_OFF -> renderServerOff()
            Stage.OWNER_SECRET -> renderOwnerSecret()
            Stage.OWNER_CODE -> renderOwnerCode()
        }
    }

    private fun renderCode() {
        content.addView(label("Soatlik kod"))
        content.addView(body("Bu kod har soatda yangilanadi va faqat tashkilot rahbarida bo'ladi. Undan oling va kiriting."))
        val input = input("______", numeric = true)
        content.addView(input)
        content.addView(primaryButton("Davom etish") {
            val v = input.text?.toString()?.trim().orEmpty()
            if (v.length < 4) {
                toast("Kodni kiriting")
                return@primaryButton
            }
            enteredCode = v
            render(Stage.CREDS)
        })
    }

    private fun renderCreds() {
        content.addView(label("Login va parol"))
        content.addView(body("Sizga berilgan login va parolni kiriting."))

        val loginInput = input("Login", numeric = false).apply {
            setText(enteredLogin)
        }
        val passInput = input("Parol", numeric = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(loginInput)
        content.addView(passInput)

        content.addView(primaryButton("Kirish") {
            enteredLogin = loginInput.text?.toString()?.trim().orEmpty()
            val pass = passInput.text?.toString().orEmpty()
            if (enteredLogin.isBlank() || pass.isBlank()) {
                toast("Login va parolni kiriting")
                return@primaryButton
            }
            render(Stage.LOADING)
            RoleAccessClient.login(this, enteredCode, enteredLogin, pass) { result ->
                handleResult(result)
            }
        })
        content.addView(textButton("Orqaga") { render(Stage.CODE) })
    }

    private fun renderLoading() {
        content.addView(label("Tekshirilmoqda…"))
        content.addView(body("Server bilan bog'lanmoqda."))
    }

    private fun renderResultOk() {
        val role = SecretAccess.roleName(this) ?: "Xodim"
        content.addView(label("Xush kelibsiz"))
        content.addView(body("Rol: $role"))

        val perms = SecretAccess.permissions(this)
        if (perms.isNotEmpty()) {
            content.addView(sectionTitle("Ruxsatlar"))
            perms.sorted().forEach { content.addView(bullet(it)) }
        }
        val comps = SecretAccess.components(this)
        if (comps.isNotEmpty()) {
            content.addView(sectionTitle("Komponentlar"))
            comps.sorted().forEach { content.addView(bullet(it)) }
        }
        if (perms.isEmpty() && comps.isEmpty()) {
            content.addView(body("Bu rolga hali huquqlar biriktirilmagan."))
        }

        content.addView(primaryButton("Chiqish") {
            SecretAccess.clearSession(this)
            finish()
        })
    }

    private fun renderServerOff() {
        content.addView(label("Server hali sozlanmagan"))
        content.addView(body("Rollar tizimi markaziy serverga ulanmagan (build'da CLOUD_BASE_URL bo'sh). Server sozlangach bu yer ishlaydi."))
        content.addView(primaryButton("Yopish") { finish() })
    }

    private fun handleResult(result: RoleAccessClient.Result) {
        when (result) {
            is RoleAccessClient.Result.Success -> {
                SecretAccess.saveSession(this, result.roleName, result.permissions, result.components)
                render(Stage.RESULT_OK)
            }
            is RoleAccessClient.Result.Failure -> {
                toast("Rad etildi: ${result.reason}")
                render(Stage.CREDS)
            }
            is RoleAccessClient.Result.NetworkError -> {
                toast("Ulanish xatosi: ${result.reason}")
                render(Stage.CREDS)
            }
            RoleAccessClient.Result.NotConfigured -> render(Stage.RESULT_OFF)
        }
    }

    // ---- Kichik UI quruvchilar (XML'siz) ----------------------------------

    private fun header(): View = TextView(this).apply {
        text = "MAXFIY KIRISH"
        setTextColor(getColor(R.color.kq_primary))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.18f
        isAllCaps = true
        setPadding(0, 0, 0, dp(16))
        // Egasi uchun yashirin kirish: sarlavhani uzoq bossa — kodni ko'rsatish rejimi.
        // Operator buni bilmaydi; bilsa ham admin sirisiz kodni ololmaydi.
        setOnLongClickListener {
            render(if (SecretAccess.hasOwnerSecret(this@SecretAccessActivity)) Stage.OWNER_CODE else Stage.OWNER_SECRET)
            true
        }
    }

    private fun label(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(getColor(R.color.kq_ink))
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(getColor(R.color.kq_ink_2))
        textSize = 13f
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun body(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(getColor(R.color.kq_ink_2))
        textSize = 15f
        setPadding(0, 0, 0, dp(14))
    }

    private fun bullet(t: String): TextView = TextView(this).apply {
        text = "•  $t"
        setTextColor(getColor(R.color.kq_ink))
        textSize = 15f
        setPadding(dp(4), dp(3), 0, dp(3))
    }

    private fun input(hint: String, numeric: Boolean): AppCompatEditText =
        AppCompatEditText(this).apply {
            this.hint = hint
            setHintTextColor(getColor(R.color.kq_ink_2))
            setTextColor(getColor(R.color.kq_ink))
            textSize = 17f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
        }

    private fun primaryButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setBackgroundColor(getColor(R.color.kq_primary))
            setTextColor(getColor(R.color.kq_on_primary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun textButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.kq_ink_2))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
            setOnClickListener { onClick() }
        }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
