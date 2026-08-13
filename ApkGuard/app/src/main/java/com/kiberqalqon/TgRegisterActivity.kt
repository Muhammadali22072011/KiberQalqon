package com.uzguard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.uzguard.databinding.ActivityTgRegisterBinding

/**
 * «Ro'yxatdan o'tish» SHLAGBAUMI — bosh ekrandan oldingi oxirgi qadam.
 *
 * Oqim:
 *   1. Tugma bosiladi → serverdan bir martalik token olinadi (CloudTelemetry.tgStart).
 *   2. https://t.me/<bot>?start=<token> ochiladi. Telegram START'ni O'ZI bosadi va
 *      token botga ketadi — bot qaysi qurilma kelganini shundan biladi.
 *   3. Bot ismni so'raydi, so'ng «Raqamni yuborish» tugmasi bilan TASDIQLANGAN
 *      telefonni oladi (foydalanuvchi raqamni qo'lda yozmaydi).
 *   4. Ekran fonda holatni so'rab turadi (polling 3s). "done" kelganda bayroq
 *      qo'yiladi va marshrut davom etadi.
 *
 * Nega polling, FCM emas: loyihada FCM umuman yo'q (yangiliklar ham polling bilan
 * keladi) — bitta qo'shimcha bog'liqlik va Google hisobi kerak bo'lardi. Ekran
 * ko'rinib turgan paytdagina so'raladi, batareyaga ta'siri yo'q.
 *
 * Telegram'dan qaytish: bot xabaridagi tugma /back.html sahifasini ochadi, u esa
 * uzguard://open havolasini beradi → shu ekran qayta ochiladi va allaqachon "done"
 * ko'rib, o'zi o'tkazib yuboradi. Foydalanuvchi ilovaga QO'LDA qaytsa ham xuddi shu
 * bo'ladi (onResume polling'ni qayta yoqadi) — ya'ni tugma ishlamasa ham oqim buzilmaydi.
 */
class TgRegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTgRegisterBinding

    private val ui = Handler(Looper.getMainLooper())
    private var polling = false
    private var routed = false
    private var opening = false

    /** Serverdan olingan ro'yxat tokeni (polling shu bo'yicha boradi). */
    private var regToken: String? = null

    /** Telegram allaqachon ochilganmi — matnlarni "kutish" holatiga o'tkazamiz. */
    private var launched = false

    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling) return
            checkStatus()
            ui.postDelayed(this, POLL_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityTgRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // uzguard://open bilan (bot xabaridagi tugmadan) kelgan bo'lishi mumkin —
        // u holda ro'yxat allaqachon tugagan, shunchaki o'tkazib yuboramiz.
        if (!StartRouter.needsTgRegistration(this)) { route(); return }

        regToken = CloudTelemetry.savedTgRegToken(this)

        binding.btnRegister.setOnClickListener { startRegistration() }
        binding.btnRefresh.setOnClickListener {
            status(getString(R.string.kq4_tgreg_checking), null)
            checkStatus()
        }
        binding.btnRefresh.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (routed) return
        // Telegram'dan qaytdi — holat allaqachon "done" bo'lishi mumkin.
        polling = true
        ui.removeCallbacks(pollTick)
        ui.post(pollTick)
    }

    override fun onPause() {
        polling = false
        ui.removeCallbacks(pollTick)
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        ui.removeCallbacks(pollTick)
        super.onDestroy()
    }

    /** Shlagbaumdan orqaga qaytib bo'lmaydi — ilova bosh ekrani ro'yxatsiz ochilmaydi. */
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // --- 1-qadam: token olish va Telegram'ni ochish ----------------------------

    private fun startRegistration() {
        if (opening) return
        opening = true
        binding.btnRegister.isEnabled = false
        status(getString(R.string.kq4_tgreg_preparing), null)

        CloudTelemetry.tgStart(this) { r ->
            runOnUiThread {
                opening = false
                binding.btnRegister.isEnabled = true
                when {
                    r.ok && r.status == "done" -> {
                        // Server "allaqachon" dedi (ilova qayta o'rnatilgan bo'lishi mumkin).
                        Config.setTgRegistered(this, true)
                        route()
                    }
                    r.ok && r.url != null -> {
                        regToken = r.token
                        openTelegram(r.url)
                    }
                    else -> status(errorText(r.error), false)
                }
            }
        }
    }

    private fun openTelegram(url: String) {
        // https://t.me/... — Telegram o'rnatilgan bo'lsa OS uni ochadi; bo'lmasa brauzer
        // t.me sahifasini ko'rsatadi (u yerdan ham botga o'tish mumkin). Ataylab
        // `setPackage("org.telegram.messenger")` QO'YMAYMIZ: foydalanuvchida Telegram X,
        // Plus Messenger yoki boshqa forkdir — qattiq paket nomi ularni sindirardi.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            launched = true
            showWaiting()
        } catch (e: ActivityNotFoundException) {
            status(getString(R.string.kq4_tgreg_err_no_telegram), false)
        } catch (e: Throwable) {
            android.util.Log.w("TgRegister", "openTelegram failed", e)
            status(getString(R.string.kq4_tgreg_err_generic), false)
        }
    }

    /** Telegram ochilgach ekran "kutish" ko'rinishiga o'tadi. */
    private fun showWaiting() {
        binding.tvWaiting.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE
        binding.btnRegister.setText(R.string.kq4_tgreg_open_again)
        status(getString(R.string.kq4_tgreg_waiting), null)
    }

    // --- 2-qadam: holat pollingi ----------------------------------------------

    private fun checkStatus() {
        val t = regToken ?: return
        CloudTelemetry.tgStatus(this, t) { st ->
            if (st == null) return@tgStatus            // tarmoq — keyingi tick'da qayta urinadi
            runOnUiThread {
                if (routed || isFinishing || isDestroyed) return@runOnUiThread
                when (st) {
                    "done" -> {
                        Config.setTgRegistered(this, true)
                        status(getString(R.string.kq4_tgreg_ok), true)
                        polling = false
                        ui.removeCallbacks(pollTick)
                        ui.postDelayed({ route() }, 700)   // muvaffaqiyat matni ko'rinib qolsin
                    }
                    "await_name" -> if (launched) status(getString(R.string.kq4_tgreg_step_name), null)
                    "await_phone" -> if (launched) status(getString(R.string.kq4_tgreg_step_phone), null)
                    else -> { /* new / unknown — kutamiz */ }
                }
            }
        }
    }

    // --- Marshrut --------------------------------------------------------------

    private fun route() {
        if (routed) return
        routed = true
        polling = false
        ui.removeCallbacks(pollTick)
        startActivity(Intent(this, StartRouter.after(this, TgRegisterActivity::class.java)))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // --- UI yordamchilari ------------------------------------------------------

    private fun errorText(err: String?): String = when (err) {
        "unconfigured" -> getString(R.string.kq4_tgreg_err_unconfigured)
        "net" -> getString(R.string.kq4_tgreg_err_net)
        else -> getString(R.string.kq4_tgreg_err_generic)
    }

    /** tvStatus: ok=true → yashil, false → qizil, null → neytral. */
    private fun status(text: String, ok: Boolean?) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(
            getColor(
                when (ok) {
                    true -> R.color.kq_safe
                    false -> R.color.kq_danger
                    null -> R.color.kq_ink_2
                }
            )
        )
    }

    companion object {
        private const val POLL_MS = 3000L
    }
}
