package com.uzguard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.uzguard.databinding.ActivityGroupJoinBinding

/**
 * «Guruhga qo'shilish» — foydalanuvchi guruh KODini kiritadi yoki QR skanerlaydi, so'ng
 * ism/familiya/telefonini yozadi va guruhga qo'shiladi (CloudTelemetry.joinGroup →
 * /api/device/join). Muvaffaqiyatda guruh mahalliy saqlanadi (dashboard bejasi ko'rsatadi).
 *
 * Ikki kirish yo'li:
 *   • Dashboard kartasidan ochiladi (bo'sh forma).
 *   • Tashqi kameradan uzguard://join?code=XXXX chuqur havolasi bilan (kod oldindan to'ldiriladi).
 *
 * Maxfiylik: ism/familiya/telefon foydalanuvchi O'ZI kiritadigan ochiq ma'lumot va faqat
 * guruh egasiga ko'rinadi (formada ogohlantiriladi). CloudTelemetry jim telefon yig'maydi.
 */
class GroupJoinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupJoinBinding
    private var sending = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val code = res.data?.getStringExtra(QrScanActivity.RESULT_JOIN_CODE)
            if (!code.isNullOrBlank()) {
                binding.etCode.setText(code)
                binding.etCode.setSelection(binding.etCode.text?.length ?: 0)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityGroupJoinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnScan.setOnClickListener {
            val i = Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_RETURN_JOIN_CODE, true)
            scanLauncher.launch(i)
        }
        binding.btnJoin.setOnClickListener { submit() }

        prefillFromIntent(intent)
        refreshCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prefillFromIntent(intent)
    }

    /** uzguard://join?code=XXXX chuqur havolasi yoki "code" extra'sidan kodni to'ldiradi. */
    private fun prefillFromIntent(intent: Intent?) {
        val fromExtra = intent?.getStringExtra("code")
        val fromLink = try { intent?.data?.getQueryParameter("code") } catch (e: Throwable) { null }
        val code = (fromExtra ?: fromLink)?.trim()?.uppercase()
        if (!code.isNullOrBlank()) {
            binding.etCode.setText(code)
            binding.etCode.setSelection(binding.etCode.text?.length ?: 0)
        }
    }

    /** Allaqachon guruhda bo'lsa — yuqoridagi kartada rang + nom ko'rsatamiz. */
    private fun refreshCurrent() {
        val g = CloudTelemetry.savedGroup(this)
        if (g == null) {
            binding.cardCurrent.visibility = View.GONE
            return
        }
        binding.cardCurrent.visibility = View.VISIBLE
        binding.dotCurrent.background = dotDrawable(g.color)
        binding.tvCurrent.text = getString(R.string.kq4_group_current_fmt, g.name)
    }

    private fun submit() {
        if (sending) return
        val code = binding.etCode.text?.toString()?.trim().orEmpty().uppercase()
        val first = binding.etFirst.text?.toString()?.trim().orEmpty()
        val last = binding.etLast.text?.toString()?.trim().orEmpty()
        val phoneRaw = binding.etPhone.text?.toString()?.trim().orEmpty()
        val digits = phoneRaw.count { it.isDigit() }

        if (code.isEmpty()) { status(getString(R.string.kq4_group_err_code), false); return }
        if (first.isEmpty() || last.isEmpty() || digits < 7) {
            status(getString(R.string.kq4_group_err_fields), false); return
        }

        sending = true
        binding.btnJoin.isEnabled = false
        status(getString(R.string.kq4_group_sending), null)

        CloudTelemetry.joinGroup(this, code, first, last, phoneRaw) { r ->
            runOnUiThread {
                sending = false
                binding.btnJoin.isEnabled = true
                if (r.ok) {
                    status(getString(R.string.kq4_group_ok, r.groupName ?: code), true)
                    refreshCurrent()
                } else {
                    status(errorText(r.error), false)
                }
            }
        }
    }

    private fun errorText(err: String?): String = when (err) {
        "code" -> getString(R.string.kq4_group_err_code)
        "fields" -> getString(R.string.kq4_group_err_fields)
        "unconfigured" -> getString(R.string.kq4_group_err_unconfigured)
        "net" -> getString(R.string.kq4_group_err_net)
        else -> getString(R.string.kq4_group_err_generic)
    }

    /** tvStatus'ni ko'rsatadi. ok=true → yashil, false → qizil, null → neytral. */
    private fun status(text: String, ok: Boolean?) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = text
        val colorRes = when (ok) {
            true -> R.color.kq_safe
            false -> R.color.kq_danger
            null -> R.color.kq_ink_2
        }
        binding.tvStatus.setTextColor(getColor(colorRes))
    }

    /** Guruh rangi uchun doira drawable (hex noto'g'ri bo'lsa neytral kulrang). */
    private fun dotDrawable(hex: String): GradientDrawable {
        val color = try { Color.parseColor(hex) } catch (e: Throwable) { Color.parseColor("#888888") }
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
}
