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
        // Ishonchli yuboruvchilar (banklar, Payme/Click/Uzcard, telekom, SMS/dialer,
        // messenjerlar, tizim ilovalari) hech qachon bloklanmaydi — aks holda haqiqiy
        // OTP/SMS/o'tkazma bildirishnomalari yashirilib qolardi.
        if (AppReputation.isTrusted(sbn.packageName)) return
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

    /**
     * Faqat HAQIQIY fishingni bloklaydi: bitta keng kalit so'z ("bank", "click", ...)
     * yetarli EMAS. Verdict uchun ikkala shart birga bo'lishi shart:
     *   1) bildirishnomada havola bor (http/https yoki t.me/), VA
     *   2) moliyaviy/maxfiy kalit so'z bor (kod/parol/karta/o'tkazma/OTP ...).
     * Bu kombinatsiya odatda ishonchsiz paketdan keladigan "havolaga bos va kodni
     * kirit" turidagi soxta xabarlarga xos. (Yuboruvchi paketi onNotificationPosted'da
     * AppReputation.isTrusted orqali allaqachon oqlangan bo'ladi.)
     */
    private fun isPhishingLike(text: String): Boolean {
        if (text.isBlank()) return false
        val hasLink = text.contains("http://") || text.contains("https://") ||
            text.contains("t.me/") || URL_PATTERN.matcher(text).find()
        if (!hasLink) return false
        val financialKeywords = listOf(
            "kod", "код", "parol", "пароль", "password", "payme", "uzcard", "humo",
            "bank", "банк", "karta", "карта", "tasdiq", "confirm", "подтвердит",
            "click", "sms", "смс", "pin", "пин", "otp", "о'tkazma", "перевод", "transfer"
        )
        return financialKeywords.any { text.contains(it) }
    }

    companion object {
        private val URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)
    }
}
