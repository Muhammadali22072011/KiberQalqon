package com.kiberqalqon

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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

        val prefs = getSharedPreferences("kiberqalqon_telemetry", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "🤖 Telegram telemetriya"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Barcha hodisalar (skaner, o'rnatish, xato, crash) sizning Telegram " +
                   "guruhingizga yuboriladi."
            textSize = 13f
            setPadding(0, 0, 0, dp(16))
        })

        // --- BOT TOKEN ---
        root.addView(TextView(this).apply {
            text = "Bot tokeni (@BotFather dan):"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        })
        etToken = EditText(this).apply {
            hint = "7234567890:AAGxx..."
            setText(prefs.getString("tg_bot_token", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(etToken)

        // --- CHAT ID ---
        root.addView(TextView(this).apply {
            text = "Guruh Chat ID (minus bilan, masalan -1001234567890):"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
        })
        etChatId = EditText(this).apply {
            hint = "-1001234567890"
            setText(prefs.getString("tg_chat_id", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(etChatId)

        // --- ENABLED ---
        cbEnabled = CheckBox(this).apply {
            text = "Telemetriyani yoqish (xabarlar yuborish)"
            isChecked = prefs.getBoolean("tg_enabled", false)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(cbEnabled)

        // --- LISTEN COMMANDS ---
        cbListen = CheckBox(this).apply {
            text = "🎧 Telegram'dan komandalar qabul qilish"
            isChecked = prefs.getBoolean("tg_listen_commands", false)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(cbListen)

        root.addView(TextView(this).apply {
            text = "    (guruhda /start yozing — boshqaruv paneli chiqadi)"
            textSize = 11f
            setPadding(0, 0, 0, dp(4))
        })

        // --- SEND APK FILE ---
        cbSendApk = CheckBox(this).apply {
            text = "📤 Skanerdan keyin APK faylni guruhga yuborish"
            isChecked = prefs.getBoolean("tg_send_apk", false)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(cbSendApk)

        root.addView(TextView(this).apply {
            text = "    (faqat xavfli/shubhali APK'lar yuboriladi, 50MB gacha)"
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        })

        // --- БУФЕР ---
        val spacer = TextView(this)
        root.addView(spacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // --- BUTTONS ---
        val btnSave = Button(this).apply {
            text = "💾 Saqlash"
            setOnClickListener { save() }
        }
        root.addView(btnSave)

        val btnTest = Button(this).apply {
            text = "🧪 Test xabar"
            setOnClickListener { sendTest() }
        }
        root.addView(btnTest)

        val btnPanel = Button(this).apply {
            text = "🎛 Boshqaruv panelini yuborish"
            setOnClickListener { sendPanel() }
        }
        root.addView(btnPanel)

        val btnAutoChat = Button(this).apply {
            text = "🤖 Chat ID'ni avto-aniqlash"
            setOnClickListener { autoDetectChatId() }
        }
        root.addView(btnAutoChat)

        val btnGuide = Button(this).apply {
            text = "❓ Qanday sozlash?"
            setOnClickListener { showGuide() }
        }
        root.addView(btnGuide)

        setContentView(root)
    }

    private fun save() {
        val token = etToken.text.toString().trim()
        val chatId = etChatId.text.toString().trim()
        // UX-12: token YOKI chat_id o'zgargan bo'lsa — biriktirilgan EGA (tg_owner_user_id) va
        // update offset (tg_update_offset) ni TOZALAYMIZ. Aks holda: (a) yangi botda update_id eski
        // offset'dan kichik bo'lib komandalar abadiy yutiladi; (b) egasi gate fail-closed bo'lgani uchun
        // boshqa akkaunt/guruhga o'tilganда butun panel jim bloklanardi (faqat app-data tozalash qutqarardi).
        run {
            val p = getSharedPreferences("kiberqalqon_telemetry", MODE_PRIVATE)
            val oldToken = p.getString("tg_bot_token", "").orEmpty()
            val oldChat = p.getString("tg_chat_id", "").orEmpty()
            if (oldToken != token || oldChat != chatId) {
                p.edit().remove("tg_owner_user_id").remove("tg_update_offset").apply()
            }
        }
        // Token yoki chat_id bo'sh bo'lsa telemetriyani YOQIB BO'LMAYDI. Ilgari save()
        // ularni tekshirmasdan `configure(..., cbEnabled.isChecked)` chaqirardi va shartsiz
        // "Saqlandi" chiqarardi: foydalanuvchi ikkala katakchani belgilab, maydonlarni bo'sh
        // qoldirsa ham "muvaffaqiyat" ko'rardi, TelegramCommandPoller esa fon rejimida bo'sh
        // token bilan Telegram API'ga urinib, jim yiqilib turaverardi — hech qanday xato
        // ko'rinmasdi va telemetriya ishlayotgandek tuyulardi.
        val credsOk = token.isNotBlank() && chatId.isNotBlank()
        val enable = cbEnabled.isChecked && credsOk

        TelemetryReporter.configure(this, token, chatId, enable)
        TelegramBot.setListenEnabled(this, cbListen.isChecked && credsOk)
        TelegramBot.setSendApkEnabled(this, cbSendApk.isChecked)
        // Запускаем/останавливаем poller в зависимости от чекбокса.
        if (cbListen.isChecked && enable) {
            TelegramCommandPoller.start(this)
        } else {
            TelegramCommandPoller.stop(this)
        }

        if (cbEnabled.isChecked && !credsOk) {
            // Katakcha belgilangan, lekin ma'lumot yetarli emas — buni ochiq aytamiz.
            cbEnabled.isChecked = false
            cbListen.isChecked = false
            Toast.makeText(
                this,
                "Saqlanmadi: avval bot token va chat_id ni kiriting",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, "Saqlandi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendPanel() {
        save()
        if (!TelemetryReporter.isConfigured(this)) {
            Toast.makeText(this, "Avval token va chat_id kiriting", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                CommandRouter.sendPanel(applicationContext)
                runOnUiThread {
                    Toast.makeText(this, "Panel guruhga yuborildi", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Avval bot tokenni kiriting", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Qidirilmoqda... guruhga /start yozing", Toast.LENGTH_LONG).show()

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
                        Toast.makeText(this, "Bot tokeni xato yoki bloklangan", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, "Topildi: $foundTitle ($foundChat)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Hech narsa topilmadi. Guruhga /start yozib qayta urinib ko'ring.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sendTest() {
        save()
        if (!TelemetryReporter.isConfigured(this)) {
            Toast.makeText(this, "Avval token va chat_id kiriting", Toast.LENGTH_LONG).show()
            return
        }
        TelemetryReporter.report(this, "TEST",
            "Test xabar — KiberQalqon telemetriya ishlayapti!\n" +
            "Vaqt: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
        )
        Toast.makeText(this, "Test yuborildi — guruhda tekshiring", Toast.LENGTH_LONG).show()
    }

    private fun showGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Telegram bot yaratish")
            .setMessage(
                "1. Telegram → @BotFather toping\n" +
                "2. /newbot bosib bot yarating, tokenni oling\n\n" +
                "3. Yangi guruh yarating va botingizni qo'shing\n\n" +
                "4. Guruhga biror xabar yozing\n\n" +
                "5. Brauzerda oching:\n" +
                "   api.telegram.org/bot<TOKEN>/getUpdates\n" +
                "   chat ni id ni oling (minus bilan)\n\n" +
                "6. Bu yerga token va chat_id ni kiriting va Test bosing!"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
