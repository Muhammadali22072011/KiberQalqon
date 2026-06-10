package com.kiberqalqon

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kiberqalqon.databinding.ItemApkBinding

/**
 * Skaner ro'yxati adapteri — v4 «.li» qatorlar (design screens1.jsx → Apps).
 *
 * Verdiktlar FAQAT shu sessiyada haqiqatan o'tkazilgan skan natijalaridan olinadi
 * (MainActivity setVerdict chaqiradi). Eski tarixdan ko'chirmaymiz: fayl o'sha
 * yo'lda almashgan bo'lishi mumkin — soxta SAFE ko'rsatish mumkin emas.
 * Hali tekshirilmagan fayl neytral («Tekshirilmagan») ko'rinadi.
 */
class ApkAdapter(
    private var items: List<ApkItem>,
    private val onCheck: (ApkItem) -> Unit
) : RecyclerView.Adapter<ApkAdapter.VH>() {

    /** Dizayn filtri: Hammasi / Xavfli (danger+shubhali) / Xavfsiz. */
    enum class Filter { ALL, BAD, OK }

    data class Counts(val all: Int, val bad: Int, val ok: Int)

    // path → verdict (faqat joriy sessiya skan natijalari).
    private val verdicts = HashMap<String, ScanResult.Verdict>()
    private var filter = Filter.ALL
    private var shown: List<ApkItem> = items

    /** To'liq (filtrsiz) ro'yxat hajmi — "Topilgan APK: N" va bo'sh holat uchun. */
    val totalCount: Int get() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemApkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = shown[position]
        val b = holder.binding
        val ctx = b.root.context

        b.tvName.text = item.name
        b.tvSub.text = ctx.getString(
            R.string.kq4_skaner_sub_format, sourceLabel(ctx, item), item.sizeFormatted
        )

        when (verdicts[item.path]) {
            ScanResult.Verdict.DANGER -> bindVerdict(
                b, R.drawable.kq4_av_danger, R.drawable.ic4_file, R.color.kq_danger,
                R.drawable.kq4_tag_danger, R.color.kq_danger_ink, ctx.getString(R.string.kq4_danger)
            )
            ScanResult.Verdict.SUSPICIOUS -> bindVerdict(
                b, R.drawable.kq4_av_warn, R.drawable.ic4_file, R.color.kq_warn,
                R.drawable.kq4_tag_warn, R.color.kq_warn_ink, ctx.getString(R.string.kq4_suspicious)
            )
            ScanResult.Verdict.SAFE -> bindVerdict(
                b, R.drawable.kq4_av_safe, R.drawable.ic4_check_circle, R.color.kq_safe,
                R.drawable.kq4_tag_safe, R.color.kq_safe_ink, ctx.getString(R.string.kq4_safe)
            )
            null -> bindVerdict(
                b, R.drawable.kq4_av_neutral, R.drawable.ic4_file, R.color.kq_ink_2,
                R.drawable.kq4_tag_soft, R.color.kq_ink_2, ctx.getString(R.string.kq4_skaner_tag_unknown)
            )
        }

        // design .li:last-child { border-bottom: none }
        b.rowDivider.visibility = if (position == shown.lastIndex) View.GONE else View.VISIBLE

        // Qator bosilishi — mavjud xulq: faylni skan qilish (MainActivity callback).
        b.root.setOnClickListener { onCheck(item) }
    }

    private fun bindVerdict(
        b: ItemApkBinding,
        avBg: Int,
        iconRes: Int,
        iconColor: Int,
        tagBg: Int,
        tagInk: Int,
        label: String
    ) {
        val ctx = b.root.context
        b.avBox.setBackgroundResource(avBg)
        b.avIcon.setImageResource(iconRes)
        b.avIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, iconColor))
        b.tagBox.setBackgroundResource(tagBg)
        val ink = ContextCompat.getColor(ctx, tagInk)
        b.tagDot.imageTintList = ColorStateList.valueOf(ink)
        b.tagText.setTextColor(ink)
        b.tagText.text = label
    }

    /** Sub-satr manbasi: papkadan sodda nom (dizayn: "{manba} · {hajm}"). */
    private fun sourceLabel(ctx: Context, item: ApkItem): String {
        val p = item.path.lowercase()
        return when {
            p.contains("telegram") -> "Telegram"
            p.contains("whatsapp") -> "WhatsApp"
            p.contains("download") -> ctx.getString(R.string.kq4_skaner_src_downloads)
            else -> item.file.parentFile?.name ?: ""
        }
    }

    override fun getItemCount(): Int = shown.size

    fun updateList(newList: List<ApkItem>) {
        items = newList
        rebuild()
    }

    fun setFilter(f: Filter) {
        filter = f
        rebuild()
    }

    /** Skan natijasini qayd etish — qator av/tag rangi va filtr hisoblari yangilanadi. */
    fun setVerdict(path: String, verdict: ScanResult.Verdict) {
        verdicts[path] = verdict
        rebuild()
    }

    fun counts(): Counts {
        var bad = 0
        var ok = 0
        for (i in items) when (verdicts[i.path]) {
            ScanResult.Verdict.DANGER, ScanResult.Verdict.SUSPICIOUS -> bad++
            ScanResult.Verdict.SAFE -> ok++
            null -> Unit
        }
        return Counts(items.size, bad, ok)
    }

    private fun rebuild() {
        shown = when (filter) {
            Filter.ALL -> items
            Filter.BAD -> items.filter {
                val v = verdicts[it.path]
                v == ScanResult.Verdict.DANGER || v == ScanResult.Verdict.SUSPICIOUS
            }
            Filter.OK -> items.filter { verdicts[it.path] == ScanResult.Verdict.SAFE }
        }
        notifyDataSetChanged()
    }

    class VH(val binding: ItemApkBinding) : RecyclerView.ViewHolder(binding.root)
}
