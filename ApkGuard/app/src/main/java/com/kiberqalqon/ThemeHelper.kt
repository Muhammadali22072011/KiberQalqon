package com.kiberqalqon

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {

    /**
     * Применить тему согласно настройкам (light/dark mode).
     * Вызывается из App.onCreate и переустанавливается через recreate() при смене.
     */
    fun applyTheme(context: Context) {
        val mode = Config.getDarkThemeMode(context)

        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /**
     * Применить accent-overlay тему перед setContentView().
     *
     * KqPrimary attribute разрешается через color state lists в `res/color/kq_primary*.xml`,
     * поэтому каждый @color/kq_primary автоматически перекрашивается под выбранный акцент.
     * Вызывается из каждой Activity.onCreate() до setContentView().
     */
    fun applyAccent(activity: Activity) {
        val themeRes = when (Config.getAccent(activity)) {
            "anor", "pomegranate" -> R.style.Theme_KiberQalqon_Anor
            "zafaron", "saffron" -> R.style.Theme_KiberQalqon_Zafaron
            else -> R.style.Theme_KiberQalqon_Feruz   // default = turkuaz (Yorug' minimal)
        }
        activity.setTheme(themeRes)
    }

    /**
     * Проверить, используется ли тёмная тема сейчас
     */
    fun isDarkTheme(context: Context): Boolean {
        val mode = Config.getDarkThemeMode(context)

        return when (mode) {
            "dark" -> true
            "light" -> false
            else -> {
                val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
