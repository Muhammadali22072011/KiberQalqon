package com.uzguard

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.CompoundButtonCompat

/**
 * Экран настройки Telegram-телеметрии:
 *  - Поле для bot token (от @BotFather)
 *  - Поле для chat_id группы (с минусом для групп)
 *  - Тумблер "Включить"
 *  - Кнопка "Тест" — шлёт пробное сообщение
 *
 * Без layout XML — сделан кодом, чтобы работал даже если ресурсы упали.
 */
class TelemetrySettingsActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var cbEnabled: CheckBox
    private lateinit var cbListen: CheckBox
    private lateinit var cbSendApk: CheckBox

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)

        val prefs = getSharedPreferences("uzguard_telemetry", Context.MODE_PRIVATE)

        // v4 «Milliy Kiber Himoya» reskin — fon kq_bg, eyebrow + h-title sarlavha,
        // kq4_input maydonlar, pill tugmalar. Logika o'zgarmagan.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.kq_bg))
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_tg_eyebrow)
            isAllCaps = true
            textSize = 12f
            typeface = font(R.font.onest_bold)
            letterSpacing = 0.02f
            setTextColor(c(R.color.kq_primary))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_tg_title)
            textSize = 24f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.02f
            setTextColor(c(R.color.kq_ink))
            setPadding(0, dp(6), 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.kq4_misc_tg_intro)
            textSize = 14f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(16))
        })

        // --- BOT TOKEN ---
        root.addView(label(getString(R.string.kq4_misc_tg_label_token)))
        etToken = input(getString(R.string.kq4_misc_tg_hint_token_ex)).apply {
            setText(prefs.getString("tg_bot_token", "") ?: "")
        }
        root.addView(etToken)

        // --- CHAT ID ---
        root.addView(label(getString(R.string.kq4_misc_tg_label_chat)))
        etChatId = input(getString(R.string.kq4_misc_tg_hint_chat_ex)).apply {
            setText(prefs.getString("tg_chat_id", "") ?: "")
        }
        root.addView(etChatId)

        // --- ENABLED ---
        cbEnabled = check(getString(R.string.kq4_misc_tg_check_enable)).apply {
            isChecked = prefs.getBoolean("tg_enabled", false)
            setPadding(paddingLeft, dp(16), paddingRight, dp(4))
        }
        root.addView(cbEnabled)

        // --- LISTEN COMMANDS ---
        cbListen = check(getString(R.string.kq4_misc_tg_check_listen)).apply {
            isChecked = prefs.getBoolean("tg_listen_commands", false)
        }
        root.addView(cbListen)

        root.addView(hint(getString(R.string.kq4_misc_tg_hint_listen)))

        // --- SEND APK FILE ---
        cbSendApk = check(getString(R.string.kq4_misc_tg_check_send_apk)).apply {
            isChecked = prefs.getBoolean("tg_send_apk", false)
        }
        root.addView(cbSendApk)

        root.addView(hint(getString(R.string.kq4_misc_tg_hint_send_apk)))

        // --- БУФЕР ---
        val spacer = TextView(this)
        root.addView(spacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // --- BUTTONS ---
        root.addView(v4Btn(getString(R.string.kq4_misc_tg_btn_save), primary = true) { save() })
        root.addView(v4Btn(getString(R.string.kq4_misc_tg_btn_test)) { sendTest() })
        root.addView(v4Btn(getString(R.string.kq4_misc_tg_btn_panel)) { sendPanel() })
        root.addView(v4Btn(getString(R.string.kq4_misc_tg_btn_autodetect)) { autoDetectChatId() })
        root.addView(v4Btn(getString(R.string.kq4_misc_tg_btn_guide)) { showGuide() })

        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(c(R.color.kq_bg))
            addView(root)
        })
    }

    private fun save() {
        val token = etToken.text.toString().trim()
        val chatId = etChatId.text.toString().trim()
        // UX-12: token YOKI chat_id o'zgargan bo'lsa — biriktirilgan EGA (tg_owner_user_id) va
        // update offset (tg_update_offset) ni TOZALAYMIZ. Aks holda: (a) yangi botda update_id eski
        // offset'dan kichik bo'lib komandalar abadiy yutiladi; (b) egasi gate fail-closed bo'lgani uchun
        // boshqa akkaunt/guruhga o'tilganда butun panel jim bloklanardi (faqat app-data tozalash qutqarardi).
        run {
            val p = getSharedPreferences("uzguard_telemetry", MODE_PRIVATE)
            val oldToken = p.getString("tg_bot_token", "").orEmpty()
            val oldChat = p.getString("tg_chat_id", "").orEmpty()
            if (oldToken != token || oldChat != chatId) {
                p.edit().remove("tg_owner_user_id").remove("tg_update_offset").apply()
            }
        }
        TelemetryReporter.configure(this, token, chatId, cbEnabled.isChecked)
        TelegramBot.setListenEnabled(this, cbListen.isChecked)
        TelegramBot.setSendApkEnabled(this, cbSendApk.isChecked)
        // Запускаем/останавливаем poller в зависимости от чекбокса.
        if (cbListen.isChecked && cbEnabled.isChecked) {
            TelegramCommandPoller.start(this)
        } else {
            TelegramCommandPoller.stop(this)
        }
        Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_saved), Toast.LENGTH_SHORT).show()
    }

    private fun sendPanel() {
        save()
        if (!TelemetryReporter.isConfigured(this)) {
            Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_need_config), Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                CommandRouter.sendPanel(applicationContext)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_panel_sent), Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_error_generic, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Avto-aniqlash chat_id: dergaem getUpdates s tokenom, ishchem poslednij chat.
     * Polzovatel' dolzhen pered etim napisat' v gruppu (lyuboe soobschenie / start).
     */
    private fun autoDetectChatId() {
        val token = etToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_need_token), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_searching), Toast.LENGTH_LONG).show()

        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val url = "https://api.telegram.org/bot$token/getUpdates?limit=20"
                val req = okhttp3.Request.Builder().url(url).get().build()
                val raw = client.newCall(req).execute().use { it.body?.string() ?: "" }
                val json = org.json.JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_bad_token), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val arr = json.optJSONArray("result") ?: org.json.JSONArray()
                // Berem poslednij chat iz updates (predpochtaem group/supergroup).
                var foundChat: String? = null
                var foundTitle: String? = null
                for (i in arr.length() - 1 downTo 0) {
                    val u = arr.optJSONObject(i) ?: continue
                    val msg = u.optJSONObject("message") ?: u.optJSONObject("callback_query")?.optJSONObject("message")
                    val chat = msg?.optJSONObject("chat") ?: continue
                    val type = chat.optString("type", "")
                    if (type == "group" || type == "supergroup") {
                        foundChat = chat.optLong("id").toString()
                        foundTitle = chat.optString("title", "")
                        break
                    }
                    // Fallback — pervyj lyuboy chat esli grupp net.
                    if (foundChat == null) {
                        foundChat = chat.optLong("id").toString()
                        foundTitle = chat.optString("title", "").ifBlank { chat.optString("first_name", "") }
                    }
                }
                runOnUiThread {
                    if (foundChat != null) {
                        etChatId.setText(foundChat)
                        Toast.makeText(
                            this,
                            getString(R.string.kq4_misc_tg_toast_found, foundTitle ?: "", foundChat),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.kq4_misc_tg_toast_not_found),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_error_generic, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sendTest() {
        save()
        if (!TelemetryReporter.isConfigured(this)) {
            Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_need_config), Toast.LENGTH_LONG).show()
            return
        }
        TelemetryReporter.report(this, "TEST",
            "Test xabar — UzGuard telemetriya ishlayapti!\n" +
            "Vaqt: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
        )
        Toast.makeText(this, getString(R.string.kq4_misc_tg_toast_test_sent), Toast.LENGTH_LONG).show()
    }

    private fun showGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.kq4_misc_tg_guide_title)
            .setMessage(R.string.kq4_misc_tg_guide_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ───────────────────── v4 dizayn yordamchilari ─────────────────────

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun font(id: Int): android.graphics.Typeface? =
        try { ResourcesCompat.getFont(this, id) } catch (_: Exception) { null }

    /** Maydon ustidagi yorliq — Onest SemiBold 13.5sp ink-2. */
    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        typeface = font(R.font.onest_semibold)
        setTextColor(c(R.color.kq_ink_2))
        setPadding(dp(4), dp(12), 0, dp(6))
    }

    /** Kichik izoh — ink-3 12sp. */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = font(R.font.onest_regular)
        setTextColor(c(R.color.kq_ink_3))
        setPadding(dp(8), 0, 0, dp(4))
    }

    /** v4 input — kq4_input fon (r24, hairline-2), mono shrift token/chat_id uchun. */
    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        typeface = font(R.font.ssmono_medium)
        setTextColor(c(R.color.kq_ink))
        setHintTextColor(c(R.color.kq_ink_3))
        background = AppCompatResources.getDrawable(context, R.drawable.kq4_input)
        backgroundTintList = null
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    /** v4 checkbox — kq_primary buttonTint, Onest Regular ink. */
    private fun check(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        textSize = 14f
        typeface = font(R.font.onest_regular)
        setTextColor(c(R.color.kq_ink))
        CompoundButtonCompat.setButtonTintList(
            this, ColorStateList.valueOf(c(R.color.kq_primary))
        )
        setPadding(paddingLeft, dp(4), paddingRight, dp(4))
    }

    /** v4 pill tugma: kq4_btn_primary / kq4_btn_soft fon, Onest Bold, h≥46dp. */
    private fun v4Btn(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14.5f
            typeface = font(R.font.onest_bold)
            background = AppCompatResources.getDrawable(
                context,
                if (primary) R.drawable.kq4_btn_primary else R.drawable.kq4_btn_soft
            )
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(46)
            minimumHeight = dp(46)
            setTextColor(c(if (primary) R.color.kq_on_primary else R.color.kq_ink))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
}
