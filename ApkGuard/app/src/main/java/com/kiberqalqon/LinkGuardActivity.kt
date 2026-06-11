package com.kiberqalqon

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * ====== HAVOLA QALQONI (link interceptor) ======
 *
 * Foydalanuvchi KiberQalqon'ni STANDART havola ochuvchi qilib tanlasa, har bir tashqi
 * http(s) havola (SMS, brauzer, ilovalar — Telegram ichki brauzeri BUNDAN mustasno, uni
 * Android ushlatmaydi) AVVAL shu ko'rinmas Activity'ga keladi. Bu yerda:
 *
 *   • Funksiya o'chiq bo'lsa → havola jim brauzerga uzatiladi (oddiy o'tkazgich).
 *   • [LinkScanner] OFFLINE tahlil qiladi:
 *       – XAVFSIZ → jim haqiqiy brauzerda ochiladi (oyna chiqmaydi).
 *       – SHUBHALI / XAVFLI → [LinkBlockActivity] to'liq ekran ogohlantirishi ochiladi
 *         (logo + «Bu shubhali sayt» + sabablar + «Yopish»). Sayt OCHILMAYDI.
 *
 * Ko'rinmas (translucent) — sof [Activity] (AppCompat EMAS: translucent mavzuda AppCompat
 * createSubDecor'da qulaydi — [ShareUrlReceiverActivity] bilan bir xil sabab). Brauzer tanlov
 * oynasi uchun framework [AlertDialog] ishlatiladi (AppCompat dialog kerak emas).
 *
 * Halqa himoyasi: brauzerga uzatishda [LinkForwarder] paketni ANIQ belgilaydi (setPackage),
 * shuning uchun havola hech qachon o'zimizga qaytib kelmaydi.
 */
class LinkGuardActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl()
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        // Funksiya o'chiq — sof o'tkazgich (havolalar baribir ishlashi shart).
        if (!Config.isLinkGuardEnabled(this)) {
            forwardOrPick(url)
            return
        }

        val verdict = try {
            LinkScanner.analyze(url)
        } catch (_: Throwable) {
            null
        }

        when (verdict?.verdict) {
            ScanResult.Verdict.DANGER, ScanResult.Verdict.SUSPICIOUS -> {
                startActivity(LinkBlockActivity.intent(this, url, verdict))
                finish()
            }
            // XAVFSIZ yoki tahlil qilib bo'lmadi (LinkScanner hech qachon throw qilmaydi,
            // lekin himoya uchun) → jim ochamiz.
            else -> forwardOrPick(url)
        }
    }

    /** Intent ma'lumotidan (yoki EXTRA_TEXT zaxirasidan) URL'ni oladi. */
    private fun extractUrl(): String? {
        intent?.dataString?.trim()?.let { if (it.isNotEmpty()) return it }
        return intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
    }

    /**
     * Havolani jim brauzerga uzatadi; maqbul brauzer aniqlanmasa (masalan bizning o'zimiz
     * standart bo'lsa) — bir martalik tanlov oynasi ko'rsatib, tanlovni saqlaydi.
     */
    private fun forwardOrPick(url: String) {
        val target = LinkForwarder.silentTarget(this)
        if (target != null && LinkForwarder.open(this, url, target)) {
            finish()
            return
        }

        val options = LinkForwarder.browserOptions(this)
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.kq4_link_no_browser, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val labels = options.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_link_pick_browser)
            .setItems(labels) { _, which ->
                val pick = options[which]
                Config.setPreferredBrowser(this, pick.pkg)
                LinkForwarder.open(this, url, pick.pkg)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
