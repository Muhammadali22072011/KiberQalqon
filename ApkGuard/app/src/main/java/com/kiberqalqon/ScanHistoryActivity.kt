package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityScanHistoryBinding

/**
 * Statistics — redesign §3.8.
 *
 * Live data (всё реальное, ничего не захардкожено):
 *  - Total tiles (JAMI / BLOKLANDI) → SharedPreferences "kiberqalqon_stats"
 *  - 7-day chart → Statistics.day_0..6 + ScanHistory timestamps (bindWeekChart)
 *  - Family bars → counted from ScanHistory entries (bindFamilyBars)
 *  - Sample rows (top 5 actual threats) → ScanHistory.all() (bindSampleRows)
 *  - C2 rows + map markers → ScanHistory threats (bindC2Rows)
 * Единственное декоративное место — фоновая картинка мира в карточке C2;
 * её красные маркеры включаются только при наличии реальных угроз.
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

        binding.toolbar.setNavigationOnClickListener { finish() }
        KqBottomNav.attach(this, KqBottomNav.Tab.STATS)

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ScanHistory.clear(this)
                    // Tiles endi kanonik hisoblagichlardan o'qiladi — ularni ham nollaymiz,
                    // aks holda "tozalash"dan keyin JAMI/BLOKLANDI eski qiymatda qolardi.
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
        // JAMI/BLOKLANDI — kanonik hisoblagichlardan (kiberqalqon_stats); Dashboard ham
        // shulardan o'qiydi. history.size ishlatib bo'lmaydi: u 200 LRU bilan cheklangan,
        // shuning uchun Dashboard bilan turli raqam ko'rsatardi.
        val statsPrefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
        bindTotals(statsPrefs.getInt("total_scanned", 0), statsPrefs.getInt("total_blocked", 0))
        bindWeekChart(history, threats)
        bindFamilyBars(threats)
        bindSampleRows(threats)
        bindC2Rows(threats)
    }

    /**
     * Заполняет 2 строки C2-карты реальными данными из ScanHistory.
     * Берём 2 самые свежие угрозы — показываем filename (короче чем path) + семейство.
     * Если угроз нет вообще — row1 "Tahdid yo'q", row2 спрятан.
     */
    private fun bindC2Rows(threats: List<ScanHistory.Entry>) {
        if (threats.isEmpty()) {
            binding.c2Row1.visibility = View.VISIBLE
            binding.c2Row2.visibility = View.GONE
            binding.c2Domain1.text = getString(R.string.hist_no_threat)
            binding.c2Label1.text = getString(R.string.hist_no_threat_detected)
            binding.c2Chip1.visibility = View.GONE
            binding.c2Marker1.setBackgroundResource(R.drawable.kq_safe_dot)
            // Декоративная карта: без угроз — красных маркеров нет.
            binding.c2MapDot1.visibility = View.GONE
            binding.c2MapDot2.visibility = View.GONE
            return
        }

        // Маркеры на карте отражают реальное число угроз (1-й и 2-й).
        binding.c2MapDot1.visibility = View.VISIBLE
        binding.c2MapDot2.visibility = if (threats.size >= 2) View.VISIBLE else View.GONE

        binding.c2Marker1.setBackgroundResource(R.drawable.kq_marker_danger)
        binding.c2Chip1.visibility = View.VISIBLE
        binding.c2Chip2.visibility = View.VISIBLE

        val first = threats[0]
        binding.c2Row1.visibility = View.VISIBLE
        binding.c2Domain1.text = first.apkName
        binding.c2Label1.text = (inferFamily(first) ?: getString(R.string.hist_unknown)) +
            (first.source?.let { " · $it" } ?: "")

        val second = threats.getOrNull(1)
        if (second != null) {
            binding.c2Row2.visibility = View.VISIBLE
            binding.c2Domain2.text = second.apkName
            binding.c2Label2.text = (inferFamily(second) ?: getString(R.string.hist_unknown)) +
                (second.source?.let { " · $it" } ?: "")
        } else {
            binding.c2Row2.visibility = View.GONE
        }
    }

    /**
     * Заполняет 7-дневный чарт реальными данными:
     *  - высота столбца пропорциональна количеству сканирований в этот день
     *    (из Statistics SharedPrefs `day_0`..`day_6`)
     *  - бейдж сверху = количество DANGER/SUSPICIOUS сканирований в этот день
     *    (считаем по ScanHistory timestamp'ам за последние 7 дней)
     *  - столбец красный (kq_bar_danger) если в этот день была хоть одна угроза,
     *    иначе бирюзовый (kq_bar_primary).
     *
     * Mapping bar1..bar7 → Du, Se, Cho, Pa, Ju, Sh, Ya (Mon..Sun) →
     * Calendar.DAY_OF_WEEK - 1 → day_1, day_2, day_3, day_4, day_5, day_6, day_0.
     */
    private fun bindWeekChart(
        history: List<ScanHistory.Entry>,
        threats: List<ScanHistory.Entry>,
    ) {
        val prefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)
        // [day_1 (Mon), day_2 (Tue), day_3 (Wed), day_4 (Thu), day_5 (Fri), day_6 (Sat), day_0 (Sun)]
        val scansPerDay = intArrayOf(
            prefs.getInt("day_1", 0),
            prefs.getInt("day_2", 0),
            prefs.getInt("day_3", 0),
            prefs.getInt("day_4", 0),
            prefs.getInt("day_5", 0),
            prefs.getInt("day_6", 0),
            prefs.getInt("day_0", 0),
        )

        // Threat counts per UI-day-index (Mon..Sun) — recalculated from history
        // timestamps so the badge stays in sync with actual entries (Statistics
        // только хранит общие счётчики сканирований по дням, не отделяет угрозы).
        val threatsPerDay = IntArray(7)
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (t in threats) {
            if (t.timestamp < cutoff) continue
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t.timestamp }
            // Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon, ..., 7=Sat → UI index 0..6 (Mon..Sun)
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

        // Bar heights are normalized to the max scans per day, capped at 130dp.
        val maxScans = scansPerDay.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxBarHeightDp = 130

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

            // Min visible height — 4dp если что-то было, 0dp если совсем пусто.
            val heightDp = if (scans == 0) 4 else (scans * maxBarHeightDp / maxScans).coerceAtLeast(8)
            val lp = bars[i].layoutParams
            lp.height = dp(heightDp)
            bars[i].layoutParams = lp

            // Цвет: красный если в этот день была угроза.
            bars[i].setBackgroundResource(
                if (threatCount > 0) R.drawable.kq_bar_danger else R.drawable.kq_bar_primary
            )

            // Badge — показываем только если есть угрозы в этот день.
            if (threatCount > 0) {
                badges[i].visibility = View.VISIBLE
                badges[i].text = threatCount.toString()
            } else {
                badges[i].visibility = View.GONE
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun bindTotals(totalScans: Int, totalBlocked: Int) {
        binding.tvStatsTotal.text = totalScans.toString()
        binding.tvStatsBlocked.text = totalBlocked.toString()
    }

    /** Counts threats by inferred family name (parsed from reason string). */
    private fun bindFamilyBars(threats: List<ScanHistory.Entry>) {
        val familyCounts = mutableMapOf<String, Int>(
            "Ajina.Banker" to 0,
            "RoundRift" to 0,
            "SMS Stealer" to 0,
            "Phish overlay" to 0,
        )
        for (t in threats) {
            val fam = inferFamily(t)
            if (fam != null && familyCounts.containsKey(fam)) {
                familyCounts[fam] = familyCounts[fam]!! + 1
            }
        }
        val total = familyCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        fun pct(c: Int): Int = (c * 100 / total).coerceIn(0, 100)

        bindFamilyBar(
            binding.famAjina.root, "Ajina.Banker",
            familyCounts["Ajina.Banker"]!!.toString(),
            pct(familyCounts["Ajina.Banker"]!!), danger = true,
        )
        bindFamilyBar(
            binding.famRoundRift.root, "RoundRift",
            familyCounts["RoundRift"]!!.toString(),
            pct(familyCounts["RoundRift"]!!), danger = true,
        )
        bindFamilyBar(
            binding.famSmsStealer.root, "SMS Stealer",
            familyCounts["SMS Stealer"]!!.toString(),
            pct(familyCounts["SMS Stealer"]!!), danger = false,
        )
        bindFamilyBar(
            binding.famPhish.root, "Phish overlay",
            familyCounts["Phish overlay"]!!.toString(),
            pct(familyCounts["Phish overlay"]!!), danger = false,
        )
    }

    /** Tries to extract a known family name out of reason or signatures. */
    private fun inferFamily(t: ScanHistory.Entry): String? {
        val haystack = (t.reason + " " + t.explanationKeys.joinToString(" ")).lowercase()
        return when {
            "ajina" in haystack -> "Ajina.Banker"
            "roundrift" in haystack || "round_rift" in haystack || "round-rift" in haystack -> "RoundRift"
            "sms" in haystack && "steal" in haystack -> "SMS Stealer"
            "phish" in haystack || "overlay" in haystack -> "Phish overlay"
            else -> null
        }
    }

    /** Sets a family row's track fill width and color. */
    private fun bindFamilyBar(
        root: View,
        name: String,
        count: String,
        fillFractionPercent: Int,
        danger: Boolean,
    ) {
        root.findViewById<TextView>(R.id.tvFamilyName).text = name
        root.findViewById<TextView>(R.id.tvFamilyCount).text = count
        val fill: View = root.findViewById(R.id.vFamilyFill)
        fill.setBackgroundResource(
            if (danger) R.drawable.kq_family_fill_danger
            else R.drawable.kq_family_fill_warn
        )
        val track = fill.parent as ViewGroup
        track.post {
            val trackWidth = track.width
            val targetWidth = (trackWidth * fillFractionPercent / 100).coerceAtLeast(0)
            val lp = fill.layoutParams
            lp.width = targetWidth
            fill.layoutParams = lp
        }
    }

    private fun bindSampleRows(threats: List<ScanHistory.Entry>) {
        val rows = listOf(
            binding.sample1.root,
            binding.sample2.root,
            binding.sample3.root,
            binding.sample4.root,
            binding.sample5.root,
        )

        if (threats.isEmpty()) {
            // Empty state: hide rows 2-5, show "no threats" message in row 1.
            binding.tvSamplesSub.text = getString(R.string.hist_no_samples_yet)
            bindSampleRow(
                rows[0],
                filename = getString(R.string.hist_no_threats_yet),
                pkg = "—",
                family = "—",
                size = "—",
                sha = "—",
                verdict = null,
            )
            rows[0].visibility = View.VISIBLE
            for (i in 1 until rows.size) rows[i].visibility = View.GONE
            return
        }

        // Подзаголовок — реальная дата самой свежей угрозы вместо хардкода даты.
        binding.tvSamplesSub.text =
            getString(R.string.hist_analysis_date_fmt, formatDate(threats.first().timestamp))

        for ((i, row) in rows.withIndex()) {
            val entry = threats.getOrNull(i)
            if (entry == null) {
                row.visibility = View.GONE
            } else {
                row.visibility = View.VISIBLE
                bindSampleRow(
                    row,
                    filename = entry.apkName,
                    pkg = entry.apkPath.substringAfterLast('/').ifBlank { "—" },
                    family = inferFamily(entry) ?: getString(R.string.hist_unknown),
                    size = humanSize(entry.apkPath),
                    sha = "—",
                    verdict = entry.verdict,
                )
                row.setOnClickListener {
                    // Tap → open ScanResultActivity for that sample.
                    val result = ScanResult(
                        verdict = entry.verdict,
                        reason = entry.reason,
                        details = emptyList(),
                        dangerousPermissions = emptyList(),
                        malwareSignatures = emptyList(),
                    )
                    startActivity(ScanResultActivity.intent(this, entry.apkPath, result))
                }
            }
        }
    }

    private fun bindSampleRow(
        root: View,
        filename: String,
        pkg: String,
        family: String,
        size: String,
        sha: String,
        verdict: ScanResult.Verdict?,
    ) {
        root.findViewById<TextView>(R.id.tvSampleName).text = filename
        root.findViewById<TextView>(R.id.tvSamplePkg).text = pkg
        root.findViewById<TextView>(R.id.tagFamily).text = family
        root.findViewById<TextView>(R.id.tagSize).text = size
        root.findViewById<TextView>(R.id.tagSha).text = sha

        // Чип серьёзности — раньше всегда показывал захардкоженный "XAVFLI"
        // (даже на строке "угроз нет"). Теперь по реальному вердикту, либо скрыт.
        val sev = root.findViewById<TextView>(R.id.tvSampleSev)
        when (verdict) {
            ScanResult.Verdict.DANGER -> {
                sev.visibility = View.VISIBLE
                sev.text = getString(R.string.kq_sev_crit)
                sev.setBackgroundResource(R.drawable.kq_sev_crit)
                sev.setTextColor(getColor(R.color.kq_danger_ink))
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                sev.visibility = View.VISIBLE
                sev.text = getString(R.string.kq_sev_high)
                sev.setBackgroundResource(R.drawable.kq_sev_high)
                sev.setTextColor(getColor(R.color.kq_warn_ink))
            }
            else -> sev.visibility = View.GONE
        }
    }

    /** Реальная дата (yyyy-MM-dd) из timestamp записи. */
    private fun formatDate(ts: Long): String {
        if (ts <= 0L) return "—"
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(ts))
    }

    private fun humanSize(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) "—" else {
                val kb = f.length() / 1024
                if (kb < 1024) "$kb KB" else "%.1f MB".format(kb / 1024.0)
            }
        } catch (_: Throwable) {
            "—"
        }
    }
}
