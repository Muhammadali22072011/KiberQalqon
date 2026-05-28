package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityLanguageSelectBinding

/**
 * Til tanlash ekrani — eng birinchi ochilishda ko'rsatiladi (LAUNCHER).
 * Til tanlanmaguncha foydalanuvchi UZ va RU orasidan tanlaydi; tanlangach
 * tanlov saqlanadi va SplashActivity'ga o'tiladi. Keyingi ochilishlarda
 * (til allaqachon tanlangan) bu ekran ko'rinmasdan darhol Splash'ga yo'naltiradi.
 *
 * Экран выбора языка — показывается при самом первом запуске. После выбора
 * язык сохраняется и приложение переходит к SplashActivity. На последующих
 * запусках сразу перенаправляет на Splash, не показывая UI.
 */
class LanguageSelectActivity : AppCompatActivity() {

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

        val binding = ActivityLanguageSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUzbek.setOnClickListener { choose("uz") }
        binding.btnRussian.setOnClickListener { choose("ru") }
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
