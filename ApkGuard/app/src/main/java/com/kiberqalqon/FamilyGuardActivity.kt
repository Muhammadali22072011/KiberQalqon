package com.uzguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ====== "OILA QALQONI" ======
 *
 * Guruh kodi bilan oilangiz qurilmalarining himoya SOG'LIG'INI ko'rsatadi (faqat ko'rish —
 * masofadan tuzatish YO'Q). Kod kiritilib "Ko'rish" bosilganda:
 *   GET {CLOUD_BASE_URL}/api/devices?family=1&code=<KOD>
 *   header: x-device-secret: Secrets.cloudDeviceSecret()   (CloudTelemetry uslubida)
 *
 * Javob: { ok:true, group:{name,color}, members:[{member_first,member_last,name,last_seen,
 *          risk_score,last_verdict,danger_count,flag}] }  yoki { ok:false, error:'code' }.
 *
 * Tarmoq xatosiga bardoshli (fail-soft) — o'zbekcha xato matni ko'rsatiladi. Kod bilan
 * qurilgan UI (XML'siz). Oxirgi kod "uzguard_family"/"code" prefs'da eslab qolinadi.
 */
class FamilyGuardActivity : AppCompatActivity() {

    private lateinit var codeInput: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var statusView: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        title = "Oila qalqoni"

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
            text = "Oila qalqoni"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
        })
        root.addView(TextView(this).apply {
            text = "Guruh kodini kiriting — oila qurilmalarining himoya holatini ko'ring."
            textSize = 14f
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(6), 0, dp(14))
            setLineSpacing(0f, 1.3f)
        })

        codeInput = EditText(this).apply {
            hint = "Guruh kodi (masalan OILA123)"
            textSize = 16f
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setHintTextColor(col(R.color.kq_ink_3, "#8A8175"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(col(R.color.kq_bg_elev, "#FFFFFF"))
            setText(lastCode())
        }
        root.addView(codeInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "Ko'rish"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { submit() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })

        statusView = TextView(this).apply {
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }
        root.addView(statusView)

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox)

        root.addView(TextView(this).apply {
            text = "Bu — oilangiz qurilmalari sog'lig'i. Masofadan tuzatish yo'q; faqat ko'rish."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(22), 0, 0)
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun submit() {
        val code = codeInput.text?.toString()?.trim()?.uppercase().orEmpty()
        if (code.isEmpty()) {
            showStatus("Avval guruh kodini kiriting.")
            return
        }
        saveCode(code)
        resultBox.removeAllViews()
        showStatus("Yuklanmoqda…")

        val base = baseUrl()
        val secret = deviceSecret()
        if (base == null || secret == null) {
            showStatus("Bulut sozlanmagan. Keyinroq urinib ko'ring.")
            return
        }

        Thread {
            val outcome: String = try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val url = "$base/api/devices?family=1&code=" + java.net.URLEncoder.encode(code, "UTF-8")
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("x-device-secret", secret)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) "net" else body.ifBlank { "net" }
                }
            } catch (_: Throwable) {
                "net"
            }
            runOnUiThread { handleResponse(outcome) }
        }.start()
    }

    private fun handleResponse(raw: String) {
        if (raw == "net") {
            showStatus("Tarmoq xatosi. Internetni tekshirib, qayta urinib ko'ring.")
            return
        }
        val json = try { JSONObject(raw) } catch (_: Throwable) { null }
        if (json == null) {
            showStatus("Serverdan noto'g'ri javob keldi.")
            return
        }
        if (!json.optBoolean("ok", false)) {
            val err = json.optString("error", "")
            showStatus(
                when (err) {
                    "code" -> "Bunday guruh kodi topilmadi. Kodni tekshiring."
                    else -> "So'rov bajarilmadi. Keyinroq urinib ko'ring."
                }
            )
            return
        }

        statusView.visibility = View.GONE
        resultBox.removeAllViews()

        val group = json.optJSONObject("group")
        val groupName = group?.optString("name")?.takeIf { it.isNotBlank() } ?: "Guruh"
        val groupColor = parseColor(group?.optString("color"), col(R.color.kq_primary, "#C2143D"))

        resultBox.addView(TextView(this).apply {
            text = groupName
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(groupColor)
            setPadding(0, dp(18), 0, dp(10))
        })

        val members = json.optJSONArray("members")
        if (members == null || members.length() == 0) {
            resultBox.addView(TextView(this).apply {
                text = "Bu guruhda hali qurilma yo'q."
                textSize = 14.5f
                setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            })
            return
        }
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            resultBox.addView(memberRow(m))
        }
    }

    /** Bitta a'zo qatori: ism, oxirgi faollik, rangli xavf nuqtasi, danger soni, belgi. */
    private fun memberRow(m: JSONObject): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(col(R.color.kq_bg_elev, "#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val riskScore = m.optInt("risk_score", -1)
        val verdict = m.optString("last_verdict", "")
        val dotColor = riskColor(riskScore, verdict)

        // Rangli xavf nuqtasi.
        row.addView(TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(dotColor)
            setPadding(0, 0, dp(12), 0)
        })

        // Matn ustuni: ism + oxirgi faollik.
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = displayName(m)
        col2.addView(TextView(this).apply {
            text = name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
        })
        val sub = buildString {
            append(relativeSeen(m.optString("last_seen", "")))
            val dc = m.optInt("danger_count", 0)
            if (dc > 0) append("  •  $dc ta tahdid")
        }
        col2.addView(TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col2)

        // Belgilangan (flag) — bo'lsa qizil beja.
        val flag = m.optString("flag", "")
        if (flag.isNotBlank() && flag != "null") {
            row.addView(TextView(this).apply {
                text = "⚑ belgilangan"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(col(R.color.kq_danger, "#E0432F"))
            })
        }

        return row
    }

    // ── Yordamchilar ──

    /** Ism: "first last" (bo'lsa), aks holda "name", aks holda "Qurilma". */
    private fun displayName(m: JSONObject): String {
        val first = m.optString("member_first", "").trim()
        val last = m.optString("member_last", "").trim()
        val full = "$first $last".trim()
        if (full.isNotEmpty()) return full
        val name = m.optString("name", "").trim()
        return if (name.isNotEmpty()) name else "Qurilma"
    }

    /** risk_score / last_verdict → nuqta rangi (qizil/sariq/yashil). */
    private fun riskColor(score: Int, verdict: String): Int = when {
        verdict.equals("danger", true) || (score in 62..Int.MAX_VALUE) -> col(R.color.kq_danger, "#E0432F")
        verdict.equals("suspicious", true) || (score in 32..61) -> col(R.color.kq_warn, "#DF8A18")
        else -> col(R.color.kq_safe, "#1A9E54")
    }

    /**
     * last_seen ni o'zbekcha nisbiy vaqtga aylantiradi. Format noma'lum bo'lgani uchun bir
     * necha shaklni sinaymiz (epoch ms/soniya yoki ISO-8601); bo'lmasa xom matnni qaytaramiz.
     */
    private fun relativeSeen(raw: String): String {
        if (raw.isBlank()) return "faollik noma'lum"
        val ms = parseTimestamp(raw) ?: return raw
        val delta = (System.currentTimeMillis() - ms) / 1000
        return when {
            delta < 0 -> "hozir"
            delta < 60 -> "hozir"
            delta < 3600 -> "${delta / 60} daqiqa oldin"
            delta < 86400 -> "${delta / 3600} soat oldin"
            delta < 2592000 -> "${delta / 86400} kun oldin"
            else -> "${delta / 2592000} oy oldin"
        }
    }

    /** Epoch (ms yoki soniya) yoki ISO-8601 ni millisekundlarga (yoki null). */
    private fun parseTimestamp(raw: String): Long? {
        val s = raw.trim()
        s.toLongOrNull()?.let { n ->
            // < ~2001 (soniya) chegarasidan katta bo'lsa ms, aks holda soniya.
            return if (n >= 100_000_000_000L) n else n * 1000
        }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (p in patterns) {
            try {
                val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
                if (p.endsWith("'Z'") || p.contains("XXX")) {
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(s)?.time ?: continue
            } catch (_: Throwable) { /* keyingi shakl */ }
        }
        return null
    }

    private fun parseColor(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        return try { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (_: Throwable) { fallback }
    }

    private fun showStatus(msg: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = msg
    }

    private fun lastCode(): String =
        prefs().getString(KEY_CODE, "") ?: ""

    private fun saveCode(code: String) {
        prefs().edit().putString(KEY_CODE, code).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Faqat HTTPS bazaviy URL (CloudTelemetry bilan bir xil gate). */
    private fun baseUrl(): String? {
        val u = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (u.isBlank() || !u.startsWith("https://")) return null
        return u
    }

    private fun deviceSecret(): String? {
        val s = Secrets.cloudDeviceSecret().trim()
        return if (s.isBlank()) null else s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }

    companion object {
        private const val PREFS = "uzguard_family"
        private const val KEY_CODE = "code"
    }
}
