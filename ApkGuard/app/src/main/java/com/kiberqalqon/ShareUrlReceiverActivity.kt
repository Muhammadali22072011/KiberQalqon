package com.uzguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.util.regex.Pattern

/**
 * «Havolani ulashish» qabul qiluvchisi (text/plain SEND). Telegram/SMS/brauzer'dan havola
 * ulashilganda ochiladi — xom matndan birinchi http(s) URL'ni ajratadi va [LinkCheckActivity]'ga
 * "url" extra bilan uzatadi.
 *
 * Bu — TINIQ (translucent) oraliq Activity (NOT AppCompat — AppCompat tiniq mavzuda
 * createSubDecor'da qulaydi, [ShareReceiverActivity] bilan bir xil sabab). exported=true,
 * lekin u faqat normallashtiradi va ichki exported=false LinkCheckActivity'ni ochadi
 * (ixtiyoriy apk_path injeksiyasiga o'rin yo'q — bu yerda faqat matn).
 *
 * URL topilmasa → toast (kq4_link_no_url) + finish. Hech qachon throw qilmaydi.
 */
class ShareUrlReceiverActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val url = extractFirstUrl(text)
            if (url == null) {
                Toast.makeText(this, R.string.kq4_link_no_url, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // Bayroqsiz ochamiz (QrScanActivity bilan bir xil) — bu tiniq qabul qiluvchi darhol
            // finish() bo'ladi, shuning uchun yangi task shart emas. NEW_TASK|CLEAR_TOP esa ichki
            // LinkCheckActivity'ni mavjud ilova task'iga qo'shib, ustidagi ekranlarni tozalab
            // yuborardi (begona ulashish app stack'ini buzgan keraksiz cross-app ta'sir).
            val launch = Intent(this, LinkCheckActivity::class.java).apply {
                putExtra(LinkCheckActivity.EXTRA_URL, url)
            }
            startActivity(launch)
        } catch (_: Throwable) {
            try { Toast.makeText(this, R.string.kq4_link_no_url, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        } finally {
            finish()
        }
    }

    /**
     * Xom matndan birinchi http(s) URL'ni ajratadi. Topilmasa null. Agar aniq URL bo'lmasa-yu,
     * butun matn domen-simon bo'lsa (probelsiz, nuqtali), uni nomzod sifatida qaytaramiz —
     * LinkScanner baribir uni qayta normallashtiradi/baholaydi (false-safe bermaydi).
     */
    private fun extractFirstUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = URL_PATTERN.matcher(text)
        if (m.find()) return m.group()?.trim()
        // Sxema yo'q — bitta "so'z" bo'lib, nuqtali (domen-simon) bo'lsa nomzod.
        val firstToken = text.split(Regex("\\s+")).firstOrNull { it.contains('.') }
        return firstToken?.takeIf { it.length in 3..2048 }
    }

    companion object {
        private val URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)
    }
}
