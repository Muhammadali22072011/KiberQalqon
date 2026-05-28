package com.kiberqalqon

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

/**
 * Скрывает уведомления, похожие на фишинг (банк, код, ссылка, Payme и т.д.).
 */
class PhishingNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !Config.isPhishingBlockerEnabled(this)) return
        if (sbn.packageName == packageName) return
        val text = notificationText(sbn)
        if (isPhishingLike(text)) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {}
        }
    }

    private fun notificationText(sbn: StatusBarNotification): String {
        val builder = StringBuilder()
        try {
            val notif = sbn.notification
            notif.extras?.getCharSequence("android.title")?.toString()?.let { builder.append(it).append(" ") }
            notif.extras?.getCharSequence("android.text")?.toString()?.let { builder.append(it).append(" ") }
            notif.extras?.getCharSequence("android.bigText")?.toString()?.let { builder.append(it) }
        } catch (_: Exception) {}
        return builder.toString().lowercase()
    }

    private fun isPhishingLike(text: String): Boolean {
        if (text.isBlank()) return false
        val triggers = listOf(
            "kod", "код", "пароль", "password", "payme", "uzcard", "humo",
            "bank", "банк", "karta", "карта", "confirm", "подтвердит",
            "t.me/", "http://", "https://", "перейди", "нажми", "click",
            "sms", "смс", "pin", "пин", "otp", "перевод", "transfer"
        )
        for (t in triggers) {
            if (text.contains(t)) return true
        }
        return URL_PATTERN.matcher(text).find()
    }

    companion object {
        private val URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)
    }
}
