package com.uzguard

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.uzguard.databinding.ItemNewsTickerBinding

/**
 * Bosh ekrandagi "beruvchi lenta" (ticker) adapteri. Gorizontal RecyclerView'da
 * e'lonlarni uzluksiz aylantiramiz: getItemCount cheksiz (Int.MAX_VALUE), pozitsiya
 * real ro'yxatga modul orqali tushadi — shu sabab lenta uzilmasdan takrorlanaveradi.
 *
 * Rasmlar bir marta yuklanib, url bo'yicha keshlanadi (cheksiz ro'yxatda har safar
 * qayta yuklanmasligi uchun). Kartochka bosilsa — [onClick] orqali to'liq e'lon ochiladi.
 */
class NewsTickerAdapter(
    private val items: List<NewsClient.NewsItem>,
    private val onClick: (NewsClient.NewsItem) -> Unit,
) : RecyclerView.Adapter<NewsTickerAdapter.VH>() {

    private val bmpCache = HashMap<String, Bitmap>()

    // Manfiy kesh: muvaffaqiyatsiz (404/offline/rasm emas) URL FAIL_TTL_MS davomida
    // qayta urilmaydi. Cheksiz aylanuvchi lentada har bind'da qayta so'rov yubormaslik
    // uchun — aks holda radio uyg'onib, batareya/harorat yeyiladi.
    private val failedAt = HashMap<String, Long>()

    val realCount: Int get() = items.size

    inner class VH(val b: ItemNewsTickerBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemNewsTickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position % items.size]
        val ctx = holder.b.root.context

        holder.b.newsItemTitle.text = item.title
        holder.b.newsItemDate.text = shortDate(item.createdAt)

        val accent = when (item.level) {
            "critical" -> R.color.kq_danger
            "warning" -> R.color.kq_warn
            else -> R.color.kq_ink_3
        }
        holder.b.newsAccent.setBackgroundColor(ContextCompat.getColor(ctx, accent))

        if (item.body.isNotBlank()) {
            holder.b.newsItemBody.text = item.body
            holder.b.newsItemBody.visibility = View.VISIBLE
        } else {
            holder.b.newsItemBody.visibility = View.GONE
        }

        bindImage(holder, item)

        holder.b.root.setOnClickListener { onClick(item) }
    }

    private fun bindImage(holder: VH, item: NewsClient.NewsItem) {
        val url = item.imageUrl
        if (!url.startsWith("http")) {
            holder.b.newsItemImage.setImageBitmap(null)
            holder.b.newsThumbCard.visibility = View.GONE
            return
        }
        val cached = bmpCache[url]
        if (cached != null) {
            holder.b.newsItemImage.setImageBitmap(cached)
            holder.b.newsThumbCard.visibility = View.VISIBLE
            return
        }
        // Hali keshda yo'q — yashirib turamiz.
        holder.b.newsItemImage.setImageBitmap(null)
        holder.b.newsThumbCard.visibility = View.GONE
        // Yaqinda muvaffaqiyatsiz bo'lgan bo'lsa — TTL o'tguncha qayta urinmaymiz.
        failedAt[url]?.let { if (System.currentTimeMillis() - it in 0..FAIL_TTL_MS) return }
        NewsClient.loadImage(url) { bmp ->
            if (bmp == null) {
                failedAt[url] = System.currentTimeMillis()
                return@loadImage
            }
            failedAt.remove(url)
            bmpCache[url] = bmp
            // Holder boshqa pozitsiyaga qayta ishlatilgan bo'lishi mumkin — hozir
            // unga bog'langan e'lon shu url bo'lsagina rasmni qo'yamiz.
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && items[pos % items.size].imageUrl == url) {
                holder.b.newsItemImage.setImageBitmap(bmp)
                holder.b.newsThumbCard.visibility = View.VISIBLE
            }
        }
    }

    private companion object {
        // Manfiy kesh muddati (NewsImages bilan bir xil).
        const val FAIL_TTL_MS = 5L * 60 * 1000
    }

    // "2026-05-29T06:00:00Z" → "29.05.2026"; parse qila olmasak — bo'sh.
    private fun shortDate(iso: String): String {
        val d = iso.trim()
        if (d.length < 10) return ""
        val y = d.substring(0, 4)
        val m = d.substring(5, 7)
        val day = d.substring(8, 10)
        val ok = y.all { it.isDigit() } && m.all { it.isDigit() } && day.all { it.isDigit() }
        return if (ok) "$day.$m.$y" else ""
    }
}
