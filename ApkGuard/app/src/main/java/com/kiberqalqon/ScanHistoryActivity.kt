package com.kiberqalqon

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityScanHistoryBinding
import com.kiberqalqon.databinding.ItemScanHistoryBinding

/**
 * Stats — «Statistika» vkladkasi, v4 dizayn (screens2.jsx → Stats).
 *
 * Jonli ma'lumotlar (hech narsa hardkod emas):
 *  - 2 stat-karta (Tekshirilgan fayl / Topildi va o'chirildi) →
 *    SharedPreferences "kiberqalqon_stats" (total_scanned / total_blocked)
 *  - 7 kunlik grafik → day_0..day_6 + day_N_epochday (staleness bilan) +
 *    ScanHistory timestamp'laridan kunlik tahdid bejjlari
 *  - «Qanday xavflar topildi» → ScanHistory'dagi haqiqiy tahdidlar kategoriya
 *    bo'yicha guruhlanadi (reason + explanationKeys'dan)
 *  - «Topilgan fayllar» → ScanHistory.all() ichidagi SAFE bo'lmagan yozuvlar,
 *    bosilganda ScanResultActivity ochiladi
 */
class ScanHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanHistoryBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityScanHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        KqBottomNav.attach(this, KqBottomNav.Tab.STATS)

        // Dizayndagi ghost CTA — hisobotni qayta o'qish.
        binding.btnRefresh.setOnClickListener { refreshAll() }

        // Eski funksiya saqlanadi: tarix + kanonik hisoblagichlarni tozalash.
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ScanHistory.clear(this)
                    // Tiles endi kanonik hisoblagichlardan o'qiladi — ularni ham nollaymiz,
                    // aks holda "tozalash"dan keyin raqamlar eski qiymatda qolardi.
                    Statistics.reset(this)
                    refreshAll()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        val history = ScanHistory.all(this)
        val threats = history.filter { it.verdict != ScanResult.Verdict.SAFE }
        // Kanonik hisoblagichlar (kiberqalqon_stats) — Dashboard ham shulardan o'qiydi.
        // history.size ishlatib bo'lmaydi: u 200 LRU bilan cheklangan.
        val statsPrefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
        bindTotals(statsPrefs.getInt("total_scanned", 0), statsPrefs.getInt("total_blocked", 0))
        bindWeekChart(threats)
        bindCategoryBars(threats)
        bindThreatList(threats)
    }

    private fun bindTotals(totalScans: Int, totalBlocked: Int) {
        binding.tvStatsTotal.text = totalScans.toString()
        binding.tvStatsBlocked.text = totalBlocked.toString()
    }

    /**
     * 7 kunlik grafik (haqiqiy ma'lumotlar):
     *  - ustun balandligi = 12% minimal + 78% × (kun skanlari / max)
     *    (dizayn: `height: (d.n/max)*78 + 12 %`, maydon 104dp)
     *  - kun ichida tahdid bo'lsa ustun danger rangida + tepada 18dp bejj
     *  - skан soni day_0..day_6 dan, staleness bilan (slot 7 kundan eski
     *    bo'lsa 0 — aks holda grafik «butun tarix»ni ko'rsatardi)
     *
     * Mapping bar1..bar7 → Du..Ya (Mon..Sun) → day_1..day_6, day_0.
     */
    private fun bindWeekChart(threats: List<ScanHistory.Entry>) {
        val prefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
        val today = java.util.Calendar.getInstance().let {
            (it.timeInMillis + it.get(java.util.Calendar.ZONE_OFFSET) +
                it.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
        }
        fun dayCount(d: Int): Int {
            val stamp = prefs.getLong("day_${d}_epochday", -1L)
            return if (stamp >= 0 && today - stamp in 0..6) prefs.getInt("day_$d", 0) else 0
        }
        // [day_1 (Mon), …, day_6 (Sat), day_0 (Sun)]
        val scansPerDay = intArrayOf(
            dayCount(1), dayCount(2), dayCount(3), dayCount(4), dayCount(5), dayCount(6), dayCount(0),
        )

        // Tahdidlar soni UI-kun bo'yicha (Mon..Sun) — ScanHistory timestamp'laridan,
        // bejj haqiqiy yozuvlar bilan sinxron bo'lishi uchun.
        val threatsPerDay = IntArray(7)
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (t in threats) {
            if (t.timestamp < cutoff) continue
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t.timestamp }
            val uiIdx = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                java.util.Calendar.SUNDAY -> 6
                else -> -1
            }
            if (uiIdx in 0..6) threatsPerDay[uiIdx]++
        }

        val maxScans = scansPerDay.maxOrNull()?.coerceAtLeast(1) ?: 1
        val areaPx = dp(BAR_AREA_DP)

        val bars = listOf(
            binding.bar1, binding.bar2, binding.bar3, binding.bar4,
            binding.bar5, binding.bar6, binding.bar7,
        )
        val badges = listOf(
            binding.barBadge1, binding.barBadge2, binding.barBadge3, binding.barBadge4,
            binding.barBadge5, binding.barBadge6, binding.barBadge7,
        )

        for (i in 0..6) {
            val scans = scansPerDay[i]
            val threatCount = threatsPerDay[i]

            // Dizayn: (n/max)*78% + 12% — bo'sh kun ham 12% «poydevor» ko'rsatadi.
            val fraction = 0.12f + 0.78f * scans / maxScans
            val lp = bars[i].layoutParams
            lp.height = (areaPx * fraction).toInt().coerceAtLeast(dp(4))
            bars[i].layoutParams = lp

            bars[i].setBackgroundResource(
                if (threatCount > 0) R.drawable.kq4_stats_bar_bad else R.drawable.kq4_stats_bar
            )

            if (threatCount > 0) {
                badges[i].visibility = View.VISIBLE
                badges[i].text = threatCount.toString()
            } else {
                badges[i].visibility = View.GONE
            }
        }
    }

    /**
     * «Qanday xavflar topildi» — haqiqiy tahdidlarni 4 kategoriya bo'yicha guruhlaydi:
     * Bank o'g'irlash / Yashirin yuklash / Soxta SMS / Boshqa.
     * Hisoblagich «v / jami», progress = v / jami. «Boshqa» 0 bo'lsa yashirinadi.
     */
    private fun bindCategoryBars(threats: List<ScanHistory.Entry>) {
        val total = threats.size
        val counts = IntArray(4)
        for (t in threats) counts[categorize(t)]++

        bindCategoryRow(binding.tvCatCount1, binding.vCatFill1, counts[CAT_BANK], total)
        bindCategoryRow(binding.tvCatCount2, binding.vCatFill2, counts[CAT_DROPPER], total)
        bindCategoryRow(binding.tvCatCount3, binding.vCatFill3, counts[CAT_SMS], total)
        bindCategoryRow(binding.tvCatCount4, binding.vCatFill4, counts[CAT_OTHER], total)

        // 3 asosiy kategoriya doim ko'rinadi; «Boshqa» faqat haqiqiy son bo'lsa.
        binding.rowCat4.visibility = if (counts[CAT_OTHER] > 0) View.VISIBLE else View.GONE
    }

    /** reason + explanationKeys ichidan kategoriya aniqlaydi (vердиктга tegmaydi). */
    private fun categorize(t: ScanHistory.Entry): Int {
        val hay = (t.reason + " " + t.explanationKeys.joinToString(" ")).lowercase()
        return when {
            // Bank o'g'irlash: banker oilalari + overlay/phishing (bank parolini o'g'irlash)
            "ajina" in hay || "banker" in hay || "bank" in hay ||
                "overlay" in hay || "phish" in hay -> CAT_BANK
            // Yashirin yuklash: dropper oilalari / o'rnatish-yuklash belgilar
            "roundrift" in hay || "round_rift" in hay || "round-rift" in hay ||
                "dropper" in hay || "download" in hay || "install" in hay -> CAT_DROPPER
            // Soxta SMS: SMS o'qish/yuborish bilan bog'liq belgilar
            "sms" in hay -> CAT_SMS
            else -> CAT_OTHER
        }
    }

    /** Kategoriya qatori: «v / jami» matni + progress to'ldirish kengligi. */
    private fun bindCategoryRow(countView: TextView, fill: View, count: Int, total: Int) {
        countView.text = getString(R.string.kq4_stats_count_fmt, count, total)
        val track = fill.parent as ViewGroup
        track.post {
            val lp = fill.layoutParams
            lp.width = if (total <= 0) 0 else (track.width * count / total).coerceAtLeast(0)
            fill.layoutParams = lp
        }
    }

    /**
     * «Topilgan fayllar» — SAFE bo'lmagan yozuvlar ro'yxati (.li qatorlar).
     * Qator bosilganda o'sha yozuv uchun ScanResultActivity ochiladi (eski xatti-harakat).
     */
    private fun bindThreatList(threats: List<ScanHistory.Entry>) {
        binding.listThreats.removeAllViews()
        if (threats.isEmpty()) {
            binding.tvThreatsEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvThreatsEmpty.visibility = View.GONE

        val shown = threats.take(MAX_LIST_ROWS)
        for ((i, entry) in shown.withIndex()) {
            val row = ItemScanHistoryBinding.inflate(layoutInflater, binding.listThreats, false)
            row.tvName.text = entry.apkName
            row.tvSub.text = listOfNotNull(sourceLabel(entry.source), timeLabel(entry.timestamp))
                .joinToString(" · ")
                .ifBlank { getString(R.string.hist_unknown) }

            // Tag/av — haqiqiy verdikt bo'yicha (DANGER default XML'da; SUSPICIOUS → warn).
            // Hech qachon yolg'on SAFE ko'rsatilmaydi — ro'yxatga faqat SAFE bo'lmaganlar tushadi.
            if (entry.verdict == ScanResult.Verdict.SUSPICIOUS) {
                row.avWrap.setBackgroundResource(R.drawable.kq4_av_warn)
                row.ivAvIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_warn))
                row.tagWrap.setBackgroundResource(R.drawable.kq4_tag_warn)
                row.tagDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_warn_ink))
                row.tagVerdict.setText(R.string.kq4_suspicious)
                row.tagVerdict.setTextColor(getColor(R.color.kq_warn_ink))
            }

            // .li:last-child { border-bottom: none }
            row.vDivider.visibility = if (i == shown.lastIndex) View.GONE else View.VISIBLE

            row.root.setOnClickListener {
                val result = ScanResult(
                    verdict = entry.verdict,
                    reason = entry.reason,
                    details = emptyList(),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = emptyList(),
                )
                startActivity(ScanResultActivity.intent(this, entry.apkPath, result))
            }
            binding.listThreats.addView(row.root)
        }
    }

    /** Manba kodi (telegram/whatsapp/…) → foydalanuvchiga ko'rinadigan nom. */
    private fun sourceLabel(source: String?): String? = when (source?.lowercase()) {
        null -> null
        "telegram" -> getString(R.string.kq4_stats_src_telegram)
        "whatsapp" -> getString(R.string.kq4_stats_src_whatsapp)
        "download", "downloads" -> getString(R.string.kq4_stats_src_download)
        "share" -> getString(R.string.kq4_stats_src_share)
        "scan" -> getString(R.string.kq4_stats_src_scan)
        else -> source
    }

    /** Lokallashgan nisbiy vaqt («5 daqiqa oldin» uslubida). */
    private fun timeLabel(ts: Long): String? {
        if (ts <= 0L) return null
        return DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Grafik ustun maydoni balandligi (dizayn: 104px). */
        private const val BAR_AREA_DP = 104

        /** Ro'yxatdagi maksimal qatorlar (200 LRU tarix uchun yetarli + UI yengil). */
        private const val MAX_LIST_ROWS = 50

        private const val CAT_BANK = 0
        private const val CAT_DROPPER = 1
        private const val CAT_SMS = 2
        private const val CAT_OTHER = 3
    }
}
