package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.kiberqalqon.databinding.ActivityLanguageSelectBinding

/**
 * Til tanlash ekrani — eng birinchi ochilishda ko'rsatiladi (LAUNCHER).
 * v4 «Milliy Kiber Himoya» dizayni (screens1.jsx → Language): UZ/RU kartalari
 * tanlanadi (tanlangani primary-soft + check, tanlanmagani oddiy karta + halqa),
 * so'ng "Davom etish" bosilganda tanlov saqlanadi va SplashActivity'ga o'tiladi.
 * Keyingi ochilishlarda (til allaqachon tanlangan) bu ekran ko'rinmasdan
 * darhol Splash'ga yo'naltiradi.
 *
 * Экран выбора языка — показывается при самом первом запуске. Выбор делается
 * карточками (как в дизайне v4), сохраняется по кнопке «Davom etish». На
 * последующих запусках сразу перенаправляет на Splash, не показывая UI.
 */
class LanguageSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageSelectBinding

    /** Joriy tanlov — dizayndagidek boshlang'ich qiymat "uz". */
    private var selected = "uz"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Til allaqachon tanlangan — ekranni umuman ko'rsatmaymiz, darhol Splash'ga.
        if (Config.isLanguageChosen(this)) {
            goToSplash()
            return
        }

        ThemeHelper.applyAccent(this)
        binding = ActivityLanguageSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUzbek.setOnClickListener { select("uz") }
        binding.btnRussian.setOnClickListener { select("ru") }
        binding.btnContinue.setOnClickListener { choose(selected) }
        renderSelection()
    }

    private fun select(lang: String) {
        selected = lang
        renderSelection()
    }

    /** Kartalarning tanlangan/tanlanmagan ko'rinishini dizayn bo'yicha chizadi. */
    private fun renderSelection() {
        val uzSelected = selected == "uz"
        binding.btnUzbek.setBackgroundResource(
            if (uzSelected) R.drawable.kq4_card_selected else R.drawable.kq4_card
        )
        binding.btnRussian.setBackgroundResource(
            if (uzSelected) R.drawable.kq4_card else R.drawable.kq4_card_selected
        )
        bindCheck(binding.ivCheckUz, uzSelected)
        bindCheck(binding.ivCheckRu, !uzSelected)
    }

    /** Tanlangan: primary check-circle; tanlanmagan: hairline halqa (tint'siz). */
    private fun bindCheck(iv: ImageView, on: Boolean) {
        if (on) {
            iv.setImageResource(R.drawable.ic4_check_circle)
            ImageViewCompat.setImageTintList(
                iv, ContextCompat.getColorStateList(this, R.color.kq_primary)
            )
        } else {
            iv.setImageResource(R.drawable.kq4_lang_ring)
            ImageViewCompat.setImageTintList(iv, null)
        }
    }

    private fun choose(lang: String) {
        Config.setLanguage(this, lang)
        Config.setLanguageChosen(this)
        // Tanlangan til keyingi Activity'da (Splash) attachBaseContext orqali qo'llanadi.
        goToSplash()
    }

    private fun goToSplash() {
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }
}
