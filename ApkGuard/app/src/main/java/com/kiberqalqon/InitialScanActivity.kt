package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityInitialScanBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Birinchi to'liq telefon tekshiruvi. Pokazyvayetsya odin raz srazu posle
 * Onboarding — chtoby user uvidel chto KiberQalqon srazu real'no rabotayet
 * i kakie APK na ego telefone schitayutsya opasnymi.
 *
 *  Phase A — scanning:
 *    FullPhoneScan.findAllApkFiles() v IO, potom ApkScanner.scan() po kazhdomu.
 *    Progress + tekushchiy fayl + counters obnovlyayutsya v real-time.
 *
 *  Phase B — results:
 *    Pokazyvaem tol'ko DANGER/SUSPICIOUS. Pustoy spisok → "Telefoningiz xavfsiz".
 *    Po kazhdoy stroke knopka O'chirish (cherez FileDeleter, kak v AutoScanActivity).
 *    "Hammasini o'chirish" — bulk delete (s confirm dialog).
 *    "Davom etish" → markirovat' kak done i pereyti na DashboardNewActivity.
 *
 * Skip link visible during scan — pozvolyaet propustit' esli user toropitsya.
 */
class InitialScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInitialScanBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** All DANGER/SUSPICIOUS APKs found during the scan, displayed in the list. */
    private val dangerous = mutableListOf<DangerEntry>()

    private data class DangerEntry(
        val path: String,
        val filename: String,
        val sizeBytes: Long,
        val verdict: ScanResult.Verdict,
        val reason: String,
        var rowView: View? = null,
    )

    /** MediaStore.createDeleteRequest consent flow — same pattern as AutoScanActivity. */
    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Pending delete target — set in attemptDelete and consumed here.
        val target = pendingDeleteTarget
        pendingDeleteTarget = null
        if (target != null && result.resultCode == android.app.Activity.RESULT_OK) {
            onItemDeleted(target)
        }
        // Bitta-bitta navbat bilan: oldingisi hal bo'lgach keyingisini so'raymiz.
        // Bir vaqtda bir nechta launch() chaqirilsa, faqat oxirgisi saqlanib qolardi.
        launchNextConsentDelete()
    }
    private var pendingDeleteTarget: DangerEntry? = null

    /** Consent talab qiladigan o'chirishlar navbati — ketma-ket bajariladi. */
    private val consentDeleteQueue = ArrayDeque<DangerEntry>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInitialScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TTS engine'ni oldindan tayyorlaymiz — scan tugagach darhol gapirsin.
        VoiceVerdict.init(this)

        binding.btnSkip.setOnClickListener { goToDashboard() }
        binding.btnContinue.setOnClickListener { goToDashboard() }
        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        // Skan onResume'da boshlanadi — avval "Barcha fayllarga ruxsat" tekshiriladi.
    }

    // Skan faqat BIR marta va faqat fayl ruxsati bo'lganda ishga tushadi.
    private var scanStarted = false
    private var askingAccess = false

    override fun onResume() {
        super.onResume()
        if (scanStarted) return
        // Ruxsatsiz skaner fayllarni KO'RA OLMAYDI va NOTO'G'RI "toza" deb ko'rsatardi —
        // antivirusda eng xavfli xato. Shuning uchun ruxsatsiz umuman skanlamaymiz.
        if (VersionCompat.hasFileScanAccess(this)) startScan()
        else showNeedAccessDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
        VoiceVerdict.shutdown()
    }

    // Fayl ruxsati yo'q — "toza" deb ko'rsatish o'rniga ochiq ogohlantiramiz va
    // to'g'ridan-to'g'ri tegishli sozlamalar ekraniga olib boramiz.
    private fun showNeedAccessDialog() {
        if (askingAccess) return
        askingAccess = true
        AlertDialog.Builder(this)
            .setTitle("Ruxsat kerak")
            .setMessage(
                "Telefon fayllarini (Yuklamalar, Telegram va boshqalar) tekshirish uchun " +
                    "\"Barcha fayllarga ruxsat\" yoqilishi shart. Busiz skaner fayllarni " +
                    "KO'RA OLMAYDI va xavfni topa olmaydi."
            )
            .setCancelable(false)
            .setPositiveButton("Ruxsat berish") { _, _ -> openManageStorage() }
            .setNegativeButton("Keyinroq") { _, _ -> goToDashboard() }
            .setOnDismissListener { askingAccess = false }
            .show()
    }

    private fun startScan() {
        if (scanStarted) return
        scanStarted = true
        scope.launch {
            try {
                val apks = withContext(Dispatchers.IO) {
                    FullPhoneScan.findAllApkFiles(this@InitialScanActivity)
                }
                if (apks.isEmpty()) {
                    presentResults()
                    return@launch
                }

                val total = apks.size
                binding.tvFoundCount.text = total.toString()

                for ((i, apk) in apks.withIndex()) {
                    binding.tvCurrentFile.text = apk.name
                    val pct = (i + 1) * 100 / total
                    binding.tvScanProgress.text = "$pct%"

                    val result = withContext(Dispatchers.IO) {
                        try {
                            ApkScanner.scan(this@InitialScanActivity, apk.path)
                        } catch (e: Throwable) {
                            android.util.Log.w("InitialScan", "scan failed: ${apk.path}", e)
                            null
                        }
                    }
                    val isThreat = result != null && result.verdict != ScanResult.Verdict.SAFE
                    if (isThreat) {
                        dangerous += DangerEntry(
                            path = apk.path,
                            filename = apk.name,
                            sizeBytes = apk.sizeBytes,
                            verdict = result!!.verdict,
                            reason = result.reason,
                        )
                        binding.tvDangerCount.text = dangerous.size.toString()
                    }
                    // Radar'ga ping: yashil = toza, qizil = xavfli/shubhali.
                    binding.radarScan.addPing(isThreat = isThreat)
                    // Statistika UZHE inkrementiruyetsya vnutri ApkScanner.scan() — vtoroy
                    // raz zdes' ne nuzhno, inache kazhdyy fayl uchityvayetsya dvazhdy
                    // (i Dashboard pokazyvayet udvoyennye chisla).

                    // Yield UI thread so the pulse animation breathes.
                    delay(20)
                }
                presentResults()
            } catch (e: Throwable) {
                android.util.Log.e("InitialScan", "scan loop crashed", e)
                presentResults()
            }
        }
    }

    private fun presentResults() {
        binding.layoutScanning.visibility = View.GONE
        binding.layoutResults.visibility = View.VISIBLE
        binding.btnSkip.visibility = View.GONE
        binding.tvHeaderTitle.text = if (dangerous.isEmpty())
            getString(R.string.is_header_safe) else getString(R.string.is_header_done)
        binding.tvHeaderSub.text = if (dangerous.isEmpty())
            getString(R.string.is_sub_no_threats)
        else
            getString(R.string.is_sub_threats_found, dangerous.size)

        if (dangerous.isEmpty()) {
            renderEmptyState()
            VoiceVerdict.speak(this, ScanResult.Verdict.SAFE)
        } else {
            renderDangerList()
            val worst = if (dangerous.any { it.verdict == ScanResult.Verdict.DANGER })
                ScanResult.Verdict.DANGER else ScanResult.Verdict.SUSPICIOUS
            VoiceVerdict.speak(
                this,
                if (worst == ScanResult.Verdict.DANGER)
                    "Ogohlantirish! ${dangerous.size} ta xavfli fayl aniqlandi."
                else
                    "Diqqat! ${dangerous.size} ta shubhali fayl topildi.",
            )
        }
    }

    private fun renderEmptyState() {
        binding.summaryIconTile.setBackgroundResource(R.drawable.kq_icon_tile_safe)
        binding.summaryIcon.setImageResource(R.drawable.ic_check_circle)
        binding.summaryIcon.setColorFilter(getColor(R.color.kq_safe_ink))
        binding.tvSummaryTitle.text = getString(R.string.is_summary_no_threat)
        binding.tvSummarySub.text = getString(R.string.is_summary_no_threat_sub)
        binding.btnDeleteAll.visibility = View.GONE
        binding.tvListHeader.visibility = View.GONE
        binding.dangerList.removeAllViews()
        binding.btnContinue.text = getString(R.string.is_go_main)
    }

    private fun renderDangerList() {
        binding.summaryIconTile.setBackgroundResource(R.drawable.kq_icon_tile_danger)
        binding.summaryIcon.setImageResource(R.drawable.ic_alert_triangle)
        binding.summaryIcon.setColorFilter(getColor(R.color.kq_danger_ink))
        binding.tvSummaryTitle.text = getString(R.string.is_dangerous_found_count, dangerous.size)
        binding.tvSummarySub.text = getString(R.string.is_recommend_delete)
        binding.btnDeleteAll.visibility = View.VISIBLE
        binding.tvListHeader.visibility = View.VISIBLE
        binding.btnContinue.text = getString(R.string.initial_scan_continue)

        binding.dangerList.removeAllViews()
        for (entry in dangerous) {
            val row = buildDangerRow(entry)
            entry.rowView = row
            binding.dangerList.addView(row)
        }
    }

    /** Single row card matching the §3.4 li-row style. */
    private fun buildDangerRow(entry: DangerEntry): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.kq_hairline))
            setCardBackgroundColor(getColor(R.color.kq_bg_elev))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // Top row: icon tile + filename + sub
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val iconTile = android.widget.FrameLayout(this).apply {
            background = getDrawable(
                if (entry.verdict == ScanResult.Verdict.DANGER)
                    R.drawable.kq_icon_tile_danger
                else R.drawable.kq_icon_tile_warn
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val iconImg = ImageView(this).apply {
            setImageResource(R.drawable.ic_apk_box)
            setColorFilter(
                getColor(
                    if (entry.verdict == ScanResult.Verdict.DANGER)
                        R.color.kq_danger_ink
                    else R.color.kq_warn_ink
                )
            )
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        iconTile.addView(iconImg)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }

        val tvName = TextView(this).apply {
            text = entry.filename
            setTextColor(getColor(R.color.kq_ink))
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
        }

        val tvSub = TextView(this).apply {
            text = humanSize(entry.sizeBytes) + " · " + entry.path
            setTextColor(getColor(R.color.kq_ink_3))
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
            (layoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )).apply {
                if (this is LinearLayout.LayoutParams) topMargin = dp(2)
            }.also { layoutParams = it }
        }

        val sevChip = TextView(this).apply {
            text = if (entry.verdict == ScanResult.Verdict.DANGER)
                getString(R.string.kq_sev_crit) else getString(R.string.kq_sev_high)
            background = getDrawable(
                if (entry.verdict == ScanResult.Verdict.DANGER)
                    R.drawable.kq_sev_crit else R.drawable.kq_sev_high
            )
            setTextColor(
                getColor(
                    if (entry.verdict == ScanResult.Verdict.DANGER)
                        R.color.kq_danger_ink else R.color.kq_warn_ink
                )
            )
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        textColumn.addView(tvName)
        textColumn.addView(tvSub)
        topRow.addView(iconTile)
        topRow.addView(textColumn)
        topRow.addView(sevChip)

        // Bottom row: delete button + details button
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

        val btnDelete = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.btn_delete)
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.kq_danger))
            cornerRadius = dp(999)
            setIconResource(R.drawable.ic_trash)
            iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            iconSize = dp(16)
            iconPadding = dp(6)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 2f)
            setOnClickListener { attemptDelete(entry) }
        }

        val btnDetails = com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.btn_details)
            isAllCaps = false
            textSize = 13f
            setTextColor(getColor(R.color.kq_ink))
            cornerRadius = dp(999)
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.kq_hairline_strong))
            strokeWidth = dp(1)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                .apply { marginStart = dp(8) }
            setOnClickListener { openDetails(entry) }
        }

        btnRow.addView(btnDelete)
        btnRow.addView(btnDetails)

        outer.addView(topRow)
        outer.addView(btnRow)
        card.addView(outer)
        return card
    }

    private fun attemptDelete(entry: DangerEntry) {
        try {
            when (val r = FileDeleter.delete(this, entry.path)) {
                FileDeleter.Result.Deleted -> onItemDeleted(entry)
                is FileDeleter.Result.NeedsUserConsent -> {
                    // Navbatga qo'shamiz; agar hozir hech narsa kutilmayotgan bo'lsa,
                    // darhol birinchisini ishga tushiramiz. Aks holda oldingi consent
                    // hal bo'lgach launcher callback keyingisini chaqiradi.
                    consentDeleteQueue.addLast(entry)
                    if (pendingDeleteTarget == null) launchNextConsentDelete()
                }
                FileDeleter.Result.NeedsManageStorage -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.permission_needed))
                        .setMessage(getString(R.string.is_need_all_files))
                        .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openManageStorage() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                is FileDeleter.Result.SandboxedByOwner -> {
                    Toast.makeText(
                        this,
                        getString(R.string.is_sandboxed_owner, r.ownerPackage),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is FileDeleter.Result.Failed -> {
                    Toast.makeText(this, "❌ ${r.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("InitialScan", "delete failed", e)
            Toast.makeText(this, "❌ Xatolik: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Navbatdagi keyingi consent-talab qiluvchi o'chirishni ishga tushiradi.
     * Faqat BITTA IntentSender dialogi bir vaqtda ochiq bo'ladi — launcher callback
     * oldingisi hal bo'lgach buni qayta chaqiradi.
     */
    private fun launchNextConsentDelete() {
        if (pendingDeleteTarget != null) return // allaqachon biri kutilmoqda
        val entry = consentDeleteQueue.removeFirstOrNull() ?: return
        try {
            when (val r = FileDeleter.delete(this, entry.path)) {
                FileDeleter.Result.Deleted -> {
                    // Oraliqda boshqa yo'l bilan o'chirilgan bo'lishi mumkin.
                    onItemDeleted(entry)
                    launchNextConsentDelete()
                }
                is FileDeleter.Result.NeedsUserConsent -> {
                    pendingDeleteTarget = entry
                    deleteConsentLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                }
                else -> {
                    // Boshqa holatlar (ruxsat/sandbox/xato) — bu entryni o'tkazib,
                    // navbatdagi keyingisiga o'tamiz.
                    launchNextConsentDelete()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("InitialScan", "consent delete failed", e)
            launchNextConsentDelete()
        }
    }

    private fun onItemDeleted(entry: DangerEntry) {
        entry.rowView?.let { binding.dangerList.removeView(it) }
        dangerous.remove(entry)
        // "Bloklandi" allaqachon skan paytida sanalgan (ApkScanner, DANGER) — bu yerda qayta emas.

        binding.tvSummaryTitle.text = getString(R.string.is_dangerous_remaining_count, dangerous.size)
        binding.tvDangerCount.text = dangerous.size.toString()

        if (dangerous.isEmpty()) {
            // Vse udaleny — perekhodim na safe-state.
            renderEmptyState()
            binding.tvHeaderTitle.text = getString(R.string.is_header_safe)
            binding.tvHeaderSub.text = getString(R.string.is_all_deleted)
        }
    }

    private fun confirmDeleteAll() {
        if (dangerous.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.is_delete_n_files_title, dangerous.size))
            .setMessage(getString(R.string.is_delete_all_message))
            .setPositiveButton(getString(R.string.is_yes_delete)) { _, _ ->
                // Snimaem snapshot — entries are removed mid-loop in attemptDelete callbacks.
                val snapshot = dangerous.toList()
                for (e in snapshot) attemptDelete(e)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDetails(entry: DangerEntry) {
        val result = ScanResult(
            verdict = entry.verdict,
            reason = entry.reason,
            details = emptyList(),
            dangerousPermissions = emptyList(),
            malwareSignatures = emptyList(),
        )
        startActivity(ScanResultActivity.intent(this, entry.path, result))
    }

    private fun openManageStorage() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(i)
            }
        } catch (e: Exception) {
            android.util.Log.e("InitialScan", "openManageStorage failed", e)
        }
    }

    private fun goToDashboard() {
        Config.setInitialScanDone(this)
        startActivity(Intent(this, DashboardNewActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun humanSize(bytes: Long): String {
        val kb = bytes / 1024
        return if (kb < 1024) "$kb KB" else "%.1f MB".format(kb / 1024.0)
    }
}
