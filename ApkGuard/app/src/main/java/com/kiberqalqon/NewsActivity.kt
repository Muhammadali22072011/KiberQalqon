package com.uzguard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uzguard.databinding.ActivityNewsBinding
import com.uzguard.databinding.IncKq4NewsRowBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Yangiliklar — bulut e'lonlarining to'liq ro'yxati (dashboard karuseli "Hammasi" shu yerga
 * olib keladi). Avval kesh darhol ko'rsatiladi (oflayn ham ishlaydi), so'ng fonda tarmoqdan
 * yangilanadi. E'lonlar faqat KO'RINISH — detektsiya/verdiktga aloqasi yo'q.
 */
class NewsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewsBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityNewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.kq4NewsBack.setOnClickListener { finish() }
        binding.kq4NewsList.layoutManager = LinearLayoutManager(this)

        scope.launch {
            val cached = withContext(Dispatchers.IO) { NewsStore.loadCached(this@NewsActivity) }
            show(cached)
            val fresh = withContext(Dispatchers.IO) { NewsStore.refresh(this@NewsActivity) }
            if (fresh != null && fresh != cached) show(fresh)
        }
    }

    private fun show(items: List<NewsStore.Item>) {
        val empty = items.isEmpty()
        binding.kq4NewsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.kq4NewsList.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) binding.kq4NewsList.adapter = NewsAdapter(items)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private inner class NewsAdapter(
        private val items: List<NewsStore.Item>,
    ) : RecyclerView.Adapter<NewsAdapter.VH>() {

        inner class VH(val row: IncKq4NewsRowBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(IncKq4NewsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val r = holder.row

            r.kq4NewsRowTitle.text = item.title
            r.kq4NewsRowBody.text = item.body
            r.kq4NewsRowBody.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE
            r.kq4NewsRowDate.text = NewsUi.humanDate(this@NewsActivity, item.createdAt)
            NewsUi.applyLevelTag(item.level, r.kq4NewsRowTag, r.kq4NewsRowTagDot, r.kq4NewsRowTagText)
            NewsUi.loadImage(scope, r.kq4NewsRowImg, item.imageUrl)

            // Qadalgan e'lon — design kq4_card_selected uslubi: primary-soft fon + primary ramka.
            // Recycle'da ikkala holat ham aniq qo'yiladi (oddiy kartaga qaytarish ham).
            r.kq4NewsRowPin.visibility = if (item.pinned) View.VISIBLE else View.GONE
            val density = resources.displayMetrics.density
            if (item.pinned) {
                r.kq4NewsRowCard.setCardBackgroundColor(getColor(R.color.kq_primary_soft))
                r.kq4NewsRowCard.strokeColor = getColor(R.color.kq_primary)
                r.kq4NewsRowCard.strokeWidth = (1.5f * density).toInt()
            } else {
                r.kq4NewsRowCard.setCardBackgroundColor(getColor(R.color.kq_bg_elev))
                r.kq4NewsRowCard.strokeColor = getColor(R.color.kq_hairline)
                r.kq4NewsRowCard.strokeWidth = (1f * density).toInt()
            }
        }
    }
}
