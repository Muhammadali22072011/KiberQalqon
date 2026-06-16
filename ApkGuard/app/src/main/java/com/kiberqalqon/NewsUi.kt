package com.uzguard

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * Yangiliklar uchun umumiy ko'rinish-yordamchilari — dashboard karuseli
 * (DashboardNewActivity) va to'liq ro'yxat (NewsActivity) bir xil teg/sana/rasm
 * mantiqdan foydalanadi, nusxa ko'chirmaymiz.
 */
object NewsUi {

    /** Daraja tegi: fon drawable + matn rangi + yorliq (panel level ranglari bilan 1:1). */
    fun applyLevelTag(level: String, tag: View, dot: View, text: TextView) {
        val (bgRes, inkRes, labelRes) = when (level) {
            "critical" -> Triple(R.drawable.kq4_tag_danger, R.color.kq_danger_ink, R.string.kq4_news_level_crit)
            "warning" -> Triple(R.drawable.kq4_tag_warn, R.color.kq_warn_ink, R.string.kq4_news_level_warn)
            else -> Triple(R.drawable.kq4_tag_soft, R.color.kq_ink_2, R.string.kq4_news_level_info)
        }
        val ink = ContextCompat.getColor(text.context, inkRes)
        tag.setBackgroundResource(bgRes)
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(ink)
        text.setText(labelRes)
        text.setTextColor(ink)
    }

    /** "Bugun" / "Kecha" / "N kun avval" / undan eski — "12 iyun" (joriy locale'da). */
    fun humanDate(ctx: Context, ts: Long): String {
        if (ts <= 0) return ""
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 86_400_000L
        return when {
            ts >= startOfToday -> ctx.getString(R.string.kq4_news_today)
            ts >= startOfToday - day -> ctx.getString(R.string.kq4_news_yesterday)
            ts >= startOfToday - 6 * day -> {
                // Yarim tunga to'g'ri kelgan vaqt (diff = day'ning aniq karrasi) "N+1 kun"
                // bo'lib ketmasin — ceil ishlatamiz (oldingi shox diff > day kafolatlaydi).
                val daysAgo = Math.ceil((startOfToday - ts).toDouble() / day).toInt()
                ctx.getString(R.string.kq4_news_days_ago, daysAgo)
            }
            else -> try {
                val locale = ctx.resources.configuration.locales[0]
                SimpleDateFormat("d MMMM", locale).format(java.util.Date(ts))
            } catch (_: Throwable) { "" }
        }
    }

    /**
     * Rasmni fonda yuklab qo'yadi. url yo'q → GONE. Bor → darhol placeholder-fon bilan
     * ko'rinadi, yuklangach bitmap; yuklab bo'lmasa (oflayn) yashiriladi. view.tag bilan
     * recycle-poygadan himoya: javob kelganda view boshqa e'longa bog'langan bo'lsa qo'ymaymiz.
     */
    fun loadImage(
        scope: CoroutineScope,
        view: ImageView,
        url: String?,
        onDone: ((loaded: Boolean) -> Unit)? = null,
    ) {
        view.setImageDrawable(null)
        view.tag = url
        if (url.isNullOrBlank()) {
            view.visibility = View.GONE
            onDone?.invoke(false)
            return
        }
        view.visibility = View.VISIBLE
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { NewsImages.load(url) }
            if (view.tag != url) return@launch
            val loaded = bmp != null
            if (loaded) view.setImageBitmap(bmp) else view.visibility = View.GONE
            // Rasm yuklanmasa (oflayn/buzuq), karta matniga ko'proq joy berish uchun xabar
            // beramiz — aks holda 96dp rasm joyi yo'qoladi-yu, matn 2 qatorda qotib qoladi.
            onDone?.invoke(loaded)
        }
    }
}
