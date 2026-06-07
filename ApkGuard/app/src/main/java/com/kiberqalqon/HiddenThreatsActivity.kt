package com.kiberqalqon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "🔍 Yashirin / o'chmaydigan tahdidlar"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Telefonda o'rnatilgan, lekin ikonkasini yashirgan yoki o'chirishga qarshilik " +
                "qiladigan ilovalar. Agar ularni o'zingiz bilib o'rnatgan bo'lsangiz — xavfsiz."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(this).apply { text = "Tekshirilmoqda…"; textSize = 14f })
        root.addView(container)

        setContentView(ScrollView(this).apply { addView(root) })

        Thread {
            val findings = try { HiddenThreatScanner.scan(this) } catch (_: Throwable) { emptyList() }
            runOnUiThread { render(findings) }
        }.start()
    }

    private fun render(findings: List<HiddenThreatScanner.Finding>) {
        container.removeAllViews()
        if (findings.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "✓ Yashirin yoki o'chmaydigan tahdid topilmadi."
                textSize = 15f
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            container.addView(TextView(this).apply {
                text = "${findings.size} ta ko'rib chiqishga arzigulik ilova:"
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
            for (f in findings) container.addView(card(f))
        }
        container.addView(footer())
    }

    private fun card(f: HiddenThreatScanner.Finding): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setBackgroundColor(if (f.family != null) 0x22FF3B5C else 0x14808080)
        }
        card.addView(TextView(this).apply {
            text = (if (f.family != null) "🔴 " else "⚠️ ") + f.label
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply { text = f.pkg; textSize = 11f; alpha = 0.7f })
        card.addView(TextView(this).apply {
            text = buildString {
                append("Sabab: ").append(f.traits.joinToString(", ") { traitUz(it) })
                if (f.family != null) append("\nQora ro'yxat: ${f.family}")
                if (!f.fromPlay) append("\nManba: Play Store EMAS (sideload)")
                if (f.dangerousPerms.isNotEmpty()) append("\nXavfli ruxsatlar: ${f.dangerousPerms.size} ta")
            }
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        // Гид: сначала разоружить (по порядку), потом удалить.
        if (HiddenThreatScanner.Trait.DEVICE_ADMIN in f.traits) {
            card.addView(actionBtn("1) Administrator huquqini olib tashlash") {
                openAction(
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                    "Xavfsizlik → Qurilma administratorlari → \"${f.label}\" dan belgini oling. So'ng o'chiring."
                )
            })
        }
        if (HiddenThreatScanner.Trait.ACCESSIBILITY in f.traits) {
            card.addView(actionBtn("2) Accessibility'ni o'chirish") {
                openAction(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    "Maxsus imkoniyatlar → \"${f.label}\" xizmatini o'chiring."
                )
            })
        }
        if (HiddenThreatScanner.Trait.NOTIF_ACCESS in f.traits) {
            card.addView(actionBtn("Bildirishnoma kirishini o'chirish") {
                openAction(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), null)
            })
        }
        card.addView(actionBtn("🗑 O'chirish (uninstall)") {
            openAction(Intent(Intent.ACTION_DELETE, Uri.parse("package:${f.pkg}")), null)
        })
        card.addView(actionBtn("Ilova haqida ma'lumot") {
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
            text = "Agar baribir o'chmasa:"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        f.addView(TextView(this).apply {
            text = "• XAVFSIZ REJIM (Safe Mode): quvvat tugmasini bosib turing → \"Xavfsiz rejim\"ni " +
                "tanlang. Unda barcha begona ilovalar (va ularning Accessibility) vaqtincha o'chadi — " +
                "o'shanda bemalol o'chiring.\n\n" +
                "• Kompyuter orqali (ADB): USB-debug yoqing, telefonni ulang va buyruqni bajaring " +
                "(<paket> o'rniga yuqoridagi paket nomini qo'ying):"
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
        val adb = "adb shell pm uninstall --user 0 <paket>"
        f.addView(TextView(this).apply {
            text = adb
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x14000000)
        })
        f.addView(actionBtn("ADB buyrug'idan nusxa olish") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", adb))
            Toast.makeText(this, "Nusxa olindi", Toast.LENGTH_SHORT).show()
        })
        return f
    }

    private fun actionBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun openAction(intent: Intent, hint: String?) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ochib bo'lmadi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun traitUz(t: HiddenThreatScanner.Trait): String = when (t) {
        HiddenThreatScanner.Trait.HIDDEN_ICON -> "ikonka yashirilgan"
        HiddenThreatScanner.Trait.DEVICE_ADMIN -> "qurilma administratori (o'chirishni bloklaydi)"
        HiddenThreatScanner.Trait.ACCESSIBILITY -> "Accessibility nazorati"
        HiddenThreatScanner.Trait.NOTIF_ACCESS -> "bildirishnomalarga kirish (OTP)"
        HiddenThreatScanner.Trait.BLACKLISTED -> "qora ro'yxatda"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
