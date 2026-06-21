package com.uzguard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.uzguard.databinding.ActivityInitialScanBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Birinchi to'liq telefon tekshiruvi — v4 «Milliy Kiber Himoya» dizayni
 * (design_v4_extracted/screens3.jsx → InitialScan). Pokazyvayetsya odin raz srazu
 * posle Onboarding.
 *
 *  Faza A — skan:
 *    FullPhoneScan.findAllApkFiles() v IO, potom ApkScanner.scan() po kazhdomu.
 *    KqRingView (190dp, stroke 12, accent rang) + % + joriy fayl + hisoblagichlar
 *    real vaqtda yangilanadi.
 *
 *  Faza B — natija:
 *    Faqat DANGER/SUSPICIOUS ko'rsatiladi: 80dp doira + "{N} ta xavfli fayl topildi"
 *    + karta-ro'yxat (.li qatorlar: av 48dp + nom + manba·hajm + "Xavfli" tag).
 *    Qator bosilsa — ScanResultActivity (batafsil + o'chirish).
 *    "Hammasini o'chirish" — bulk delete (confirm dialog bilan, FileDeleter orqali).
 *    "Asosiy ekranga o'tish" → done deb belgilab Dashboard/ProtectionStatus'ga.
 *
 * Skip skan paytida ko'rinadi — pozvolyaet propustit' esli user toropitsya.
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
        ThemeHelper.applyAccent(this)
        binding = ActivityInitialScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v4 Ring: stroke 12, rang — joriy aksent (?attr/kqPrimary), track default surface-2.
        binding.scanRing.strokeWidthDp = 12f
        binding.scanRing.ringColor = resolveAccentColor()

        // TTS engine'ni oldindan tayyorlaymiz — scan tugagach darhol gapirsin.
        VoiceVerdict.init(this)

        binding.btnSkip.setOnClickListener { goToDashboard() }
        binding.btnContinue.setOnClickListener { goToDashboard() }
        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        // Skan onResume'da boshlanadi — avval "Barcha fayllarga ruxsat" tekshiriladi.
    }

    /** ?attr/kqPrimary (aksent overlay) → rang; topilmasa — kq_primary fallback. */
    private fun resolveAccentColor(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(R.attr.kqPrimary, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
        } else {
            ContextCompat.getColor(this, R.color.kq_primary)
        }
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
            .setTitle(getString(R.string.permission_needed))
            .setMessage(getString(R.string.kq4_is_perm_msg))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.grant)) { _, _ -> openManageStorage() }
            .setNegativeButton(getString(R.string.btn_later)) { _, _ -> goToDashboard(markDone = false) }
            .setOnDismissListener { askingAccess = false }
            .show()
    }

    private fun startScan() {
        if (scanStarted) return
        scanStarted = true
        // Faza A boshlanishi: "fayllar qidirilmoqda" — uzun qidiruvda (ko'p faylli telefon)
        // ekran "qotib qolgandek" ko'rinmasin. Ilgari bu yerda hech narsa yangilanmasdi va
        // butun fayl tizimi obhod qilinguncha ekran 0% da turardi.
        binding.tvCurrentFile.text = getString(R.string.kq4_is_searching)
        binding.tvScanProgress.text = ""
        scope.launch {
            try {
                val apks = withContext(Dispatchers.IO) {
                    // Tez yo'l: MediaStore indeksidan (yangi/katta telefonlarda ham darhol
                    // topadi) + to'liq rekursiv yurish (endi vaqt byudjeti bilan — hech qachon
                    // cheksiz osilmaydi). Yo'l bo'yicha dedup qilamiz.
                    val fast = try {
                        ApkScanner.findApkFiles(this@InitialScanActivity)
                    } catch (_: Throwable) { emptyList<ApkItem>() }
                    val deep = try {
                        FullPhoneScan.findAllApkFiles(this@InitialScanActivity)
                    } catch (_: Throwable) { emptyList<ApkItem>() }
                    val seen = HashSet<String>(fast.size + deep.size)
                    (fast + deep).filter { seen.add(it.path) }
                }
                if (apks.isEmpty()) {
                    presentResults()
                    return@launch
                }

                val total = apks.size
                binding.tvFoundCount.text = total.toString()

                // OPTIMIZATSIYA: ilgari APK'lar BIRMA-BIR (ketma-ket) skanlanardi — ko'p faylli
                // telefonda sekin va uzoq. Endi cheklangan PARALLEL (SCAN_CONCURRENCY ta bir
                // vaqtda) — ~bir necha barobar tez. Cheksiz EMAS — loyihada qizish tarixi bor,
                // shuning uchun bir vaqtda atigi 3 ta (qizishni nazoratda ushlaymiz).
                var done = 0
                for (chunk in apks.chunked(SCAN_CONCURRENCY)) {
                    if (!isActive) break
                    val scanned = withContext(Dispatchers.IO) {
                        chunk.map { apk ->
                            async {
                                apk to try {
                                    ApkScanner.scan(this@InitialScanActivity, apk.path)
                                } catch (e: Throwable) {
                                    android.util.Log.w("InitialScan", "scan failed: ${apk.path}", e)
                                    null
                                }
                            }
                        }.awaitAll()
                    }
                    for ((apk, result) in scanned) {
                        done++
                        binding.tvCurrentFile.text = apk.name
                        val pct = done * 100 / total
                        binding.tvScanProgress.text = "$pct%"
                        binding.scanRing.setValue(pct.toFloat(), animate = false)
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
                        // Statistika ApkScanner.scan() ICHIDA sanaladi — bu yerda qayta emas.
                    }
                    yield() // animatsiya/UI nafas olsin
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

        if (dangerous.isEmpty()) {
            renderEmptyState(allDeleted = false)
            VoiceVerdict.speak(this, ScanResult.Verdict.SAFE)
        } else {
            renderDangerList()
            val worst = if (dangerous.any { it.verdict == ScanResult.Verdict.DANGER })
                ScanResult.Verdict.DANGER else ScanResult.Verdict.SUSPICIOUS
            VoiceVerdict.speak(
                this,
                getString(
                    if (worst == ScanResult.Verdict.DANGER) R.string.kq4_is_tts_danger
                    else R.string.kq4_is_tts_susp,
                    dangerous.size,
                ),
            )
        }
    }

    /**
     * Toza holat: 80dp doira safe-soft + ic4_check_circle, karta ichida bo'sh-holat
     * xabari. allDeleted=true — hammasi o'chirilgandan keyin (dizayndagi
     * "Barcha xavfli fayllar o'chirildi"), false — boshidan hech narsa topilmagan.
     */
    private fun renderEmptyState(allDeleted: Boolean) {
        binding.resultCircle.setBackgroundResource(R.drawable.kq4_circle_safe_soft)
        binding.resultIcon.setImageResource(R.drawable.ic4_check_circle)
        binding.resultIcon.imageTintList =
            ColorStateList.valueOf(getColor(R.color.kq_safe))
        binding.tvHeaderTitle.text = getString(R.string.is_header_safe)
        binding.tvHeaderSub.text = getString(
            if (allDeleted) R.string.is_all_deleted else R.string.is_sub_no_threats
        )
        binding.tvEmptyText.text = getString(
            if (allDeleted) R.string.kq4_is_all_deleted else R.string.kq4_is_none_found
        )
        binding.btnDeleteAll.visibility = View.GONE
        binding.dangerList.removeAllViews()
        binding.dangerList.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
    }

    /** Xavfli holat: 80dp doira danger-soft + ic4_shield_alert + ro'yxat + bulk delete. */
    private fun renderDangerList() {
        binding.resultCircle.setBackgroundResource(R.drawable.kq4_circle_danger_soft)
        binding.resultIcon.setImageResource(R.drawable.ic4_shield_alert)
        binding.resultIcon.imageTintList =
            ColorStateList.valueOf(getColor(R.color.kq_danger))
        binding.tvHeaderTitle.text = getString(R.string.kq4_is_found_count, dangerous.size)
        binding.tvHeaderSub.text = getString(R.string.is_recommend_delete)
        binding.btnDeleteAll.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.dangerList.visibility = View.VISIBLE
        populateRows()
    }

    /** Ro'yxatni to'liq qayta quradi — har o'chirishdan keyin divider'lar to'g'ri qoladi. */
    private fun populateRows() {
        binding.dangerList.removeAllViews()
        for ((i, entry) in dangerous.withIndex()) {
            if (i > 0) binding.dangerList.addView(buildDivider())
            val row = buildDangerRow(entry)
            entry.rowView = row
            binding.dangerList.addView(row)
        }
    }

    /** .li qatorlar orasidagi 1px hairline (dizayndagi border-bottom). */
    private fun buildDivider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.kq_hairline))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1),
        )
    }

    /**
     * Dizayn .li qatori: av 48dp (kq4_av_danger + ic4_file) · nom (KQ4.RowTitle) +
     * manba·hajm (KQ4.RowSub) · o'ngda tag "Xavfli"/"Shubhali". Bosilsa — batafsil
     * (ScanResultActivity, u yerda o'chirish ham bor).
     */
    private fun buildDangerRow(entry: DangerEntry): View {
        val danger = entry.verdict == ScanResult.Verdict.DANGER

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            isClickable = true
            isFocusable = true
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { openDetails(entry) }
        }

        // av 48dp r18: danger — danger_bg + kq_danger; suspicious — warn variant
        val av = FrameLayout(this).apply {
            setBackgroundResource(if (danger) R.drawable.kq4_av_danger else R.drawable.kq4_av_warn)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        av.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic4_file)
            imageTintList = ColorStateList.valueOf(
                getColor(if (danger) R.color.kq_danger else R.color.kq_warn)
            )
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = Gravity.CENTER
            }
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(13) }
        }

        // .ttl — 600 15.5sp kq_ink
        textColumn.addView(TextView(this).apply {
            text = entry.filename
            typeface = ResourcesCompat.getFont(this@InitialScanActivity, R.font.onest_semibold)
            setTextColor(getColor(R.color.kq_ink))
            textSize = 15.5f
            letterSpacing = -0.01f
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
        })

        // .sub — manba (papka nomi) · hajm, 13sp kq_ink_3
        val folder = java.io.File(entry.path).parentFile?.name.orEmpty()
        textColumn.addView(TextView(this).apply {
            text = if (folder.isEmpty()) humanSize(entry.sizeBytes)
            else "$folder · ${humanSize(entry.sizeBytes)}"
            typeface = ResourcesCompat.getFont(this@InitialScanActivity, R.font.onest_regular)
            setTextColor(getColor(R.color.kq_ink_3))
            textSize = 13f
            ellipsize = android.text.TextUtils.TruncateAt.END
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) }
        })

        // tag danger/warn: pilyulya h30 + 7dp nuqta + matn 12.5sp 700
        val inkColor = getColor(if (danger) R.color.kq_danger_ink else R.color.kq_warn_ink)
        val tag = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (danger) R.drawable.kq4_tag_danger else R.drawable.kq4_tag_warn)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30),
            ).apply { marginStart = dp(10) }
        }
        tag.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@InitialScanActivity, R.drawable.kq4_dot)
            backgroundTintList = ColorStateList.valueOf(inkColor)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        })
        tag.addView(TextView(this).apply {
            text = getString(if (danger) R.string.kq4_danger else R.string.kq4_suspicious)
            typeface = ResourcesCompat.getFont(this@InitialScanActivity, R.font.onest_bold)
            setTextColor(inkColor)
            textSize = 12.5f
            letterSpacing = -0.01f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(7) }
        })

        row.addView(av)
        row.addView(textColumn)
        row.addView(tag)
        return row
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
            Toast.makeText(
                this,
                getString(R.string.toast_error_generic, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
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
        dangerous.remove(entry)
        // "Bloklandi" allaqachon skan paytida sanalgan (ApkScanner, DANGER) — bu yerda qayta emas.
        binding.tvDangerCount.text = dangerous.size.toString()

        if (dangerous.isEmpty()) {
            // Vse udaleny — perekhodim na safe-state (dizayn: check + "Barcha ... o'chirildi").
            renderEmptyState(allDeleted = true)
        } else {
            binding.tvHeaderTitle.text =
                getString(R.string.is_dangerous_remaining_count, dangerous.size)
            // Qolgan ro'yxatni qayta quramiz — divider'lar to'g'ri joylashsin.
            populateRows()
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

    // markDone=false: foydalanuvchi skanni KEYINGA qoldirdi (ruxsat bermay "Keyinroq") — bir
    // martalik to'liq tekshiruv "bajarildi" deb belgilanmaydi, keyingi safar yana taklif qilinadi.
    private fun goToDashboard(markDone: Boolean = true) {
        if (markDone) Config.setInitialScanDone(this)
        // Ilk skandan keyin — agar himoya hali tasdiqlanmagan yoki majburiy ruxsatlardan
        // biri yetishmasa — BITTA ekranli ro'yxatga (ProtectionStatus) yo'naltiramiz: o'sha
        // yerda overlay/bildirishnoma/JOYLASHUV bir joyda yoqiladi. Splash'dan marafon
        // olib tashlangani uchun bu — ruxsatlarni so'raydigan yagona, sodda joy.
        // Skan "keyinroq"ga qoldirilgan bo'lsa (markDone=false) — to'g'ridan Dashboard.
        val next = if (markDone &&
            (!Config.isProtectionAcked(this) ||
                !ProtectionStatusActivity.allCriticalPermissionsGranted(this))
        ) {
            ProtectionStatusActivity::class.java
        } else {
            DashboardNewActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun humanSize(bytes: Long): String {
        val kb = bytes / 1024
        return if (kb < 1024) "$kb KB" else "%.1f MB".format(kb / 1024.0)
    }

    companion object {
        // Bir vaqtda parallel skanlanadigan APK soni. 3 — sekvensialdan sezilarli tez,
        // lekin cheklangan (qizishni nazoratda ushlaydi; loyihada qizish tarixi bor).
        private const val SCAN_CONCURRENCY = 3
    }
}
