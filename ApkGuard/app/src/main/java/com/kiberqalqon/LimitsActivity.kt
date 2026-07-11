package com.uzguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== "NIMADAN HIMOYA QILA OLMAYMIZ" (halol cheklovlar) ======
 *
 * Foydalanuvchiga UzGuard'ning HAQIQIY chegaralarini ochiq aytadi — har biriga "nima qilish
 * kerak" qadami bilan. Antivirus "hamma narsani hal qiladi" degan yolg'on taassurot bermaymiz.
 *
 * Kod bilan qurilgan UI (XML'siz) — ba'zi cheklovlar jonli holatga bog'lanadi (masalan VPN
 * filtri hozir yoqilganmi), qolgani statik matn.
 */
class LimitsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        title = "Cheklovlar — halol"

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
            text = "Nimadan himoya qila OLMAYMIZ"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
        })
        root.addView(TextView(this).apply {
            text = "UzGuard kuchli, lekin sehr emas. Quyidagilar bizning qo'limizdan kelmaydi — " +
                "ularni faqat SIZ hal qila olasiz."
            textSize = 14f
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(6), 0, dp(14))
            setLineSpacing(0f, 1.35f)
        })

        // 1) Telegram/ilova ichida yuklangan APK — bloklaymiz, o'chira olmaymiz.
        root.addView(
            limitCard(
                "Telegram/ilova ichidagi APK'ni o'chira olmaymiz",
                "Telegram (yoki boshqa ilova) yuklab olgan .apk fayl o'sha ilovaning yopiq " +
                    "papkasida (/Android/data) yotadi. Android qoidasiga ko'ra unga BIZ (yoki " +
                    "boshqa ilova) tegolmaydi — faqat bloklab, ogohlantira olamiz.",
                "Nima qilish kerak: xavfli faylni yuborgan Telegram XABARINI o'chiring.",
            )
        )

        // 2) Ilova ichidagi brauzer havola qalqonini chetlab o'tadi (VPN filtri qamrab oladi).
        val vpnOn = try { Config.isVpnFilterEnabled(this) } catch (_: Throwable) { false }
        root.addView(
            limitCard(
                "Ilova ichidagi brauzerlar havola qalqonini chetlab o'tadi",
                "Telegram/Instagram ichida ochiladigan havolalar tashqi brauzerga chiqmaydi, " +
                    "shuning uchun havola qalqoni ularni ushlamaydi. VPN/C2 filtri esa bu yo'lni " +
                    "ham qamrab oladi." +
                    if (vpnOn) "\n\n✓ Hozir VPN filtri YOQILGAN — bu yo'l himoyalangan."
                    else "\n\n✗ Hozir VPN filtri O'CHIQ.",
                "Nima qilish kerak: Sozlamalardan VPN filtrini yoqing.",
            )
        )

        // 3) Ovozli (telefon qo'ng'irog'i) firibgarlik — hech bir ilova to'liq himoya qila olmaydi.
        root.addView(
            limitCard(
                "Ovozli (qo'ng'iroq) firibgarlikdan himoya qila olmaymiz",
                "\"Bank xodimi\" bo'lib qo'ng'iroq qilib, sizni kod/parol aytishga ko'ndiradigan " +
                    "firibgarlikni hech bir ilova to'liq to'sa olmaydi — bu texnik emas, psixologik hujum.",
                "Nima qilish kerak: telefonda HECH KIMGA kod/parol/karta ma'lumotini aytmang.",
            )
        )

        // 4) O'rnatish qalqoni (Accessibility) o'chiq bo'lsa himoya zaiflashadi.
        val shieldOn = try { InstallProtectionGuide.isShieldServiceEnabled(this) } catch (_: Throwable) { false }
        root.addView(
            limitCard(
                "O'rnatish qalqoni (Accessibility) o'chiq bo'lsa — himoya zaiflashadi",
                "Jonli o'rnatish qalqoni zararli o'rnatishni avtomatik bekor qiladi, lekin u " +
                    "Maxsus imkoniyatlar (Accessibility) sozlamasida yoqilishi kerak." +
                    if (shieldOn) "\n\n✓ Hozir o'rnatish qalqoni YOQILGAN."
                    else "\n\n✗ Hozir o'rnatish qalqoni O'CHIQ.",
                "Nima qilish kerak: Sozlamalar → Maxsus imkoniyatlardan UzGuard'ni yoqing.",
            )
        )

        root.addView(TextView(this).apply {
            text = "Biz kamchiliklarimizni yashirmaymiz — chunki haqiqiy himoya sizning " +
                "ehtiyotkorligingizdan boshlanadi."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.kq_ink_3, "#8A8175"))
            setPadding(0, dp(16), 0, 0)
            setLineSpacing(0f, 1.3f)
        })
    }

    /** Bitta cheklov kartochkasi: sarlavha + tushuntirish + "nima qilish kerak" qadami. */
    private fun limitCard(title: String, body: String, action: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(col(R.color.kq_bg_elev, "#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = "⚠  $title"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_ink, "#2A2622"))
            setLineSpacing(0f, 1.2f)
        })
        card.addView(TextView(this).apply {
            text = body
            textSize = 13.5f
            setTextColor(col(R.color.kq_ink_2, "#5C554C"))
            setPadding(0, dp(6), 0, dp(8))
            setLineSpacing(0f, 1.35f)
        })
        card.addView(TextView(this).apply {
            text = "➜  $action"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(R.color.kq_primary, "#C2143D"))
            setLineSpacing(0f, 1.3f)
        })
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }
}
