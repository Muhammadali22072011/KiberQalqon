package com.uzguard

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
        // 1) Bildirishnomadagi havolalarni [LinkScanner] orqali tekshiramiz. Telegram in-app
        //    brauzeri LinkGuard'ni chetlab o'tadi — bu yo'l shu teshikni yopadi: xabar matnidagi
        //    URL XAVFLI bo'lsa foydalanuvchini ogohlantiramiz (bildirishnomani O'CHIRMAYMIZ —
        //    bu qonuniy xabar bo'lishi mumkin, faqat havola xavfli).
        scanLinks(text)
        // 2) Klassik fishing shakli (havola + moliyaviy kalit so'z) — bildirishnomani yashiramiz.
        if (isPhishingLike(text)) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {}
        }
    }

    /**
     * Matndan barcha http(s) havolalarni ajratib, har birini [LinkScanner] bilan tekshiradi.
     * XAVFLI (DANGER) verdikt bo'lsa — bir marta (URL bo'yicha dedup) ogohlantiradi.
     */
    private fun scanLinks(text: String) {
        try {
            val matcher = URL_PATTERN.matcher(text)
            var count = 0
            while (matcher.find() && count < 5) {
                count++
                val url = matcher.group().trimEnd('.', ',', ')', ']', '»', '"', '\'')
                if (url.length < 8) continue
                val res = LinkScanner.analyze(url)
                if (res.verdict != ScanResult.Verdict.DANGER) continue
                val prefs = getSharedPreferences("uzguard_notif_links", MODE_PRIVATE)
                val key = "warned_${url.hashCode()}"
                if (prefs.getBoolean(key, false)) continue
                prefs.edit().putBoolean(key, true).apply()
                try {
                    NotificationHelper.showPhishingLinkNotification(this, res.url, res.host)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
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
