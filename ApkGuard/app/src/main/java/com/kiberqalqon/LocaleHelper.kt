package com.uzguard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    fun apply(context: Context): Context {
        val lang = Config.getLanguage(context)
        val locale = when (lang) {
            "uz" -> Locale("uz")
            else -> Locale("ru")
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
        @Suppress("DEPRECATION")
        config.locale = locale
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context
    }
}
