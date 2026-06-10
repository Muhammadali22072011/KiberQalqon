package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kiberqalqon.databinding.ActivityBankGuardBinding

/**
 * «Bank himoyasi» — qurilmaga o'rnatilgan SOXTA bank ilovalarini ([BankAppAudit]) ko'rsatadi.
 *
 * Dashboard'dagi "Bank himoyasi" katagi va Sozlamalardagi chevron'dan ochiladi. v4 dizayn:
 * fon kq_bg, eyebrow + h-title sarlavha. Har bir soxta ilova qizil (DANGER) karta sifatida
 * sabablari bilan ko'rsatiladi; har bir karta uchun "O'chirish" + "Ilova haqida" tugmalari.
 * Toza bo'lsa — yashil bo'sh holat ("Soxta bank ilovasi topilmadi").
 *
 * Skan asosiy thread'da emas (PackageManager + ikonka hash sekin). Hech narsani o'chirmaydi
 * (root'siz mumkin emas) — foydalanuvchini Android'ning standart o'chirish dialogiga olib boradi.
 */
class BankGuardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBankGuardBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityBankGuardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        Thread {
            // null = skan UMUMAN bajarilmadi (xato). Bo'sh ro'yxat = haqiqatan toza. Farqlash SHART
            // (false-SAFE bo'lmasligi uchun — xatoda yashil "toza" ko'rsatmaymiz).
            val findings = try { BankAppAudit.scan(this) } catch (_: Throwable) { null }
            runOnUiThread { if (!isFinishing && !isDestroyed) render(findings) }
        }.start()
    }

    private fun render(findings: List<BankAppAudit.Finding>?) {
        val container = binding.bankContainer
        container.removeAllViews()

        // Skan bajarilmadi (null) → neytral/ogohlantirish holati, HECH QACHON yashil "toza" emas.
        if (findings == null) {
            binding.tvCount.text = getString(R.string.kq4_bank_failed_status)
            container.addView(failedStateCard())
            return
        }

        val fakes = findings.filter { it.isFake }
        if (fakes.isEmpty()) {
            binding.tvCount.text = getString(R.string.kq4_bank_safe_status)
            container.addView(emptyStateCard())
            return
        }

        binding.tvCount.text = getString(R.string.kq4_bank_count, fakes.size)
        fakes.forEachIndexed { i, f ->
            val card = fakeCard(f)
            container.addView(card)
            AnimationHelper.fadeIn(card, duration = 320, delay = i * 60L)
        }
    }

    /** Yashil "hech narsa topilmadi" kartasi. */
    private fun emptyStateCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_safe)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.kq4_bank_none_title)
            textSize = 16f
            typeface = font(R.font.onest_bold)
            setTextColor(c(R.color.kq_safe_ink))
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.kq4_bank_none_body)
            textSize = 13f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    /** Sariq "tekshirib bo'lmadi" (xato) kartasi — false-SAFE bermaslik uchun yashil EMAS. */
    private fun failedStateCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_warn)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.kq4_bank_failed_title)
            textSize = 16f
            typeface = font(R.font.onest_bold)
            setTextColor(c(R.color.kq_warn_ink))
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.kq4_bank_failed_body)
            textSize = 13f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(6), 0, dp(8))
        })
        card.addView(actionBtn(getString(R.string.kq4_bank_retry)) {
            // Qayta urinish — ekranni qayta yaratamiz (skan yangidan ishlaydi).
            recreate()
        })
        return card
    }

    /** Bitta soxta bank ilovasi — qizil DANGER karta. */
    private fun fakeCard(f: BankAppAudit.Finding): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            background = AppCompatResources.getDrawable(context, R.drawable.kq4_card_danger)
        }

        // Sarlavha: "Soxta bank ilovasi"
        card.addView(TextView(this).apply {
            text = getString(R.string.kq4_bank_fake_title)
            textSize = 12f
            isAllCaps = true
            letterSpacing = 0.02f
            typeface = font(R.font.onest_bold)
            setTextColor(c(R.color.kq_danger))
        })
        // Ilova nomi
        card.addView(TextView(this).apply {
            text = f.label
            textSize = 17f
            typeface = font(R.font.onest_bold)
            letterSpacing = -0.01f
            setTextColor(c(R.color.kq_danger_ink))
            setPadding(0, dp(2), 0, 0)
        })
        // Paket nomi (mono)
        card.addView(TextView(this).apply {
            text = f.pkg
            textSize = 11f
            typeface = font(R.font.ssmono_medium)
            setTextColor(c(R.color.kq_ink_3))
        })
        // Qaysi bankka taqlid qilmoqda
        f.impersonates?.let { bank ->
            card.addView(TextView(this).apply {
                text = getString(R.string.kq4_bank_mimics, bank.label)
                textSize = 12.5f
                typeface = font(R.font.onest_semibold)
                setTextColor(c(R.color.kq_ink_2))
                setPadding(0, dp(6), 0, 0)
            })
        }
        // Sabablar ro'yxati
        card.addView(TextView(this).apply {
            text = f.reasons.joinToString("\n") { "• " + reasonUz(it) }
            textSize = 12.5f
            typeface = font(R.font.onest_regular)
            setTextColor(c(R.color.kq_ink_2))
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(7), 0, dp(8))
        })

        // Amallar: o'chirish (DANGER) + ilova haqida
        card.addView(actionBtn(getString(R.string.kq4_bank_act_uninstall), danger = true) {
            openAction(Intent(Intent.ACTION_DELETE, Uri.parse("package:${f.pkg}")))
        })
        card.addView(actionBtn(getString(R.string.kq4_bank_act_appinfo)) {
            openAction(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${f.pkg}")))
        })
        return card
    }

    /** v4 pill tugma: soft (standart) yoki danger. */
    private fun actionBtn(label: String, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            typeface = font(R.font.onest_bold)
            background = AppCompatResources.getDrawable(
                context,
                if (danger) R.drawable.kq4_btn_danger else R.drawable.kq4_btn_soft
            )
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(44)
            minimumHeight = dp(44)
            setTextColor(c(if (danger) R.color.white else R.color.kq_ink))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun reasonUz(r: BankAppAudit.Reason): String = getString(when (r) {
        BankAppAudit.Reason.LABEL_LOOKS_LIKE_BANK -> R.string.kq4_bank_reason_label
        BankAppAudit.Reason.ICON_IMPERSONATION -> R.string.kq4_bank_reason_icon
        BankAppAudit.Reason.NOT_PLAY_INSTALL -> R.string.kq4_bank_reason_notplay
        BankAppAudit.Reason.CERT_NOT_OFFICIAL -> R.string.kq4_bank_reason_cert
        BankAppAudit.Reason.PACKAGE_TYPOSQUAT -> R.string.kq4_bank_reason_typosquat
    })

    private fun openAction(intent: Intent) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.kq4_bank_open_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ───────────────────── v4 dizayn yordamchilari ─────────────────────

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun font(id: Int): Typeface? =
        try { ResourcesCompat.getFont(this, id) } catch (_: Exception) { null }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
