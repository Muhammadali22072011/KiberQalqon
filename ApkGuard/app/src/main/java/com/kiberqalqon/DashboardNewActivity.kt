package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityDashboardNewBinding
import kotlinx.coroutines.*

class DashboardNewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardNewBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityDashboardNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AnimationHelper.fadeIn(binding.root, duration = 400)
        AnimationHelper.glowPulse(binding.imgLiveDot, duration = 1200)

        // Hero meter ortida yumshoq, tinch pulsatsiya qiluvchi "scan-ring" (akssent rangida).
        binding.imgScanGlow.setImageResource(R.drawable.kq_scan_ring)
        AnimationHelper.glowPulse(binding.imgScanGlow, duration = 2200)

        binding.dashboardContent.post {
            AnimationHelper.cascadeChildren(binding.dashboardContent, delayBetween = 70)
        }

        setupUI()
        loadStatistics()
    }

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            // Ekran o'tishi endi tema (KqWindowAnimations) orqali — yagona, yengil uslub.
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Skaner ekraniga o'tish — pastki navigatsiya bilan BIR XIL yo'l orqali
        // (KqBottomNav.go), aks holda har bosishda back-stack'da MainActivity'ning
        // yangi nusxasi to'planardi.
        binding.btnQuickScan.setOnClickListener {
            KqBottomNav.go(this, MainActivity::class.java)
        }

        // UX-01: "Karantin" plitkasi — karantin ekranini ochadi (tiklash/butunlay o'chirish).
        binding.tileQuarantine.setOnClickListener {
            startActivity(Intent(this, QuarantineActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            loadStatistics()
        }

        binding.cardApkList.setOnClickListener {
            KqBottomNav.go(this, MainActivity::class.java)
        }

        // UZ / RU toggle — раньше пилюля не имела click handler, теперь
        // тапом переключаем язык и пересоздаём Activity на новой локали.
        binding.tvLangCode.text = if (Config.getLanguage(this) == "ru") "RU" else "UZ"
        binding.btnLangToggle.setOnClickListener {
            val cur = Config.getLanguage(this)
            val next = if (cur == "ru") "uz" else "ru"
            Config.setLanguage(this, next)
            recreate()
        }

        // Единая нижняя нав. — активна вкладка HOME.
        KqBottomNav.attach(this, KqBottomNav.Tab.HOME)

        AnimationHelper.ripple(binding.btnQuickScan)
    }

    override fun onResume() {
        super.onResume()
        // BG-01: ilova ochilganda real-time himoyani (idempotent) qaytaramiz. Ruxsat berilgach,
        // aynan shu yerda xizmat yoqiladi va birinchi marta "Himoyangiz yoqildi" chiqadi
        // (foreground-Activity'dan start HAR DOIM ruxsat etiladi — Android 12+ OEM-kill'dan keyin ham).
        ProtectionActivator.activateIfReady(this)
        // Перерисовываем threat row'ы и список установленных приложений при каждом
        // возврате — пользователь мог в другом экране совершить новый scan,
        // установить/удалить приложение, и dashboard должен это отразить.
        populateThreatRows()
        populateInstalledApps()
    }

    /**
     * `appList` ichiga barcha user-app'larni (system'sis) ro'yxat sifatida joylaydi.
     * Har bir satr: real ikonka + ilova nomi + paket nomi + verdict-rang nuqta.
     * Verdict `kiberqalqon_rescan` SharedPreferences'dan o'qiladi
     * (InstalledAppsRescanWorker har 24 soatda yangilaydi).
     * Skan qilinmagan ilova uchun nuqta kulrang.
     *
     * PackageManager.loadIcon/loadLabel sekin bo'lishi mumkin (telefonda 30-60 ilova bilan),
     * shuning uchun ro'yxat va metadata IO threadda yig'iladi, UI'ga main thread'da kiritamiz.
     */
    private fun populateInstalledApps() {
        val container = binding.appList
        container.removeAllViews()

        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = try {
                    pm.getInstalledPackages(0)
                } catch (e: Throwable) {
                    android.util.Log.w("Dashboard", "getInstalledPackages failed", e)
                    return@withContext emptyList<AppRowData>()
                }

                // Faqat user-apps (system'larni o'tkazamiz, lekin updated-system'larni qoldiramiz —
                // u yerda ham sideload-attack apdeytlari uchraydi). KiberQalqon o'zini o'tkazadi.
                val userApps = packages.filter { p ->
                    val app = p.applicationInfo ?: return@filter false
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val updatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val isSelf = p.packageName == packageName || p.packageName == "${packageName}.debug"
                    (!isSystem || updatedSystem) && !isSelf
                }

                val rescanPrefs = getSharedPreferences("kiberqalqon_rescan", Context.MODE_PRIVATE)
                userApps.mapNotNull { p ->
                    val app = p.applicationInfo ?: return@mapNotNull null
                    val label = try { app.loadLabel(pm).toString() } catch (_: Throwable) { p.packageName }
                    val icon = try { app.loadIcon(pm) } catch (_: Throwable) { null }
                    var verdict = rescanPrefs.getString("verdict_${p.packageName}", null)

                    // Ma'lum virus paketlari ro'yxati — InstalledAppsRescanWorker'dan oldin
                    // yana bir himoya qatlami. Foydalanuvchi ilovani 1 sekund oldin o'rnatgan
                    // bo'lsa ham, agar paket nomi qora ro'yxatda bo'lsa — darhol DANGER.
                    val knownBad = try {
                        MaliciousPackages.maliciousFamily(p.packageName)
                    } catch (_: Throwable) { null }
                    if (knownBad != null) verdict = "DANGER"

                    val source = detectInstallSource(pm, p.packageName)
                    AppRowData(label, p.packageName, icon, verdict, source)
                }.sortedBy { it.label.lowercase() }
            }

            val inflater = layoutInflater
            for (data in rows) {
                val row = inflater.inflate(R.layout.inc_dashboard_app_row, container, false)
                bindAppRow(row, data)
                container.addView(row)
            }
        }
    }

    private data class AppRowData(
        val label: String,
        val pkgName: String,
        val icon: android.graphics.drawable.Drawable?,
        val verdict: String?,
        val source: InstallSource,
    )

    private enum class InstallSource(val labelText: String, val isTrusted: Boolean) {
        PLAY_MARKET("Play Market", true),
        GALAXY_STORE("Galaxy Store", true),
        HUAWEI_GALLERY("AppGallery", true),
        AMAZON("Amazon", true),
        SIDELOAD("Sideload", false),
        PRE_INSTALLED("Tizim", true),
        UNKNOWN("Noma'lum", false),
    }

    /**
     * PackageManager.getInstallSourceInfo (API 30+) yoki getInstallerPackageName (eski).
     * Qaytarilgan installer-package mashhur do'kon nomlariga solishtirib, ilova qaerdan
     * o'rnatilganini aniqlaymiz.
     *
     * Bu — virus aniqlash uchun MUHIM signal: Play Market'dan kelgan ilova 99% xavfsiz,
     * sideload (null/com.android.packageinstaller) — har doim shubhali, foydalanuvchi
     * o'zi APK fayl orqali o'rnatgan.
     */
    private fun detectInstallSource(pm: android.content.pm.PackageManager, pkg: String): InstallSource {
        val installer = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (_: Throwable) { null }

        return when (installer) {
            "com.android.vending" -> InstallSource.PLAY_MARKET
            "com.sec.android.app.samsungapps" -> InstallSource.GALAXY_STORE
            "com.huawei.appmarket" -> InstallSource.HUAWEI_GALLERY
            "com.amazon.venezia" -> InstallSource.AMAZON
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller" -> InstallSource.SIDELOAD
            null, "" -> InstallSource.PRE_INSTALLED
            else -> InstallSource.UNKNOWN
        }
    }

    /** Берём 3 последние DANGER/SUSPICIOUS записи из ScanHistory и кладём в hero list. */
    private fun populateThreatRows() {
        val recent = ScanHistory.all(this)
            .filter { it.verdict != ScanResult.Verdict.SAFE }
            .take(3)

        val rows = listOf(
            binding.threatRow1.root,
            binding.threatRow2.root,
            binding.threatRow3.root,
        )

        if (recent.isEmpty()) {
            // История пуста — показываем 1 строку-заглушку "ничего не найдено", остальные прячем.
            bindThreatRow(
                rows[0],
                filename = "Hozircha xavf topilmadi",
                sub = "Yangi APK aniqlanganda bu yerda paydo bo'ladi",
                verdict = ScanResult.Verdict.SAFE,
            )
            rows[0].visibility = android.view.View.VISIBLE
            rows[1].visibility = android.view.View.GONE
            rows[2].visibility = android.view.View.GONE
            return
        }

        for ((i, row) in rows.withIndex()) {
            val entry = recent.getOrNull(i)
            if (entry == null) {
                row.visibility = android.view.View.GONE
            } else {
                row.visibility = android.view.View.VISIBLE
                bindThreatRow(
                    row,
                    filename = entry.apkName,
                    sub = buildThreatSub(entry),
                    verdict = entry.verdict,
                )
                // Тап на строку угрозы → открыть детали этого скана.
                // По дизайну §3.3: "Dashboard threat row tap | Open ScanResultActivity with sampleId."
                row.setOnClickListener {
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

    private fun buildThreatSub(entry: ScanHistory.Entry): String {
        val source = entry.source?.takeIf { it.isNotBlank() } ?: "skan"
        val ago = humanAgo(entry.timestamp)
        return "${source.replaceFirstChar { it.uppercase() }} · $ago"
    }

    private fun humanAgo(ts: Long): String {
        if (ts <= 0) return "yaqinda"
        val delta = (System.currentTimeMillis() - ts) / 1000
        return when {
            delta < 60 -> "hozirgina"
            delta < 3600 -> "${delta / 60} daq oldin"
            delta < 86400 -> "${delta / 3600} soat oldin"
            else -> "${delta / 86400} kun oldin"
        }
    }

    private fun bindThreatRow(
        root: android.view.View,
        filename: String,
        sub: String,
        verdict: ScanResult.Verdict = ScanResult.Verdict.DANGER,
    ) {
        root.findViewById<TextView>(R.id.tvThreatName).text = filename
        root.findViewById<TextView>(R.id.tvThreatSub).text = sub
        val sev = root.findViewById<TextView>(R.id.tvThreatSev)
        when (verdict) {
            ScanResult.Verdict.DANGER -> {
                sev.text = getString(R.string.kq_sev_crit)
                sev.setBackgroundResource(R.drawable.kq_sev_crit)
                sev.setTextColor(getColor(R.color.kq_danger_ink))
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                sev.text = getString(R.string.kq_sev_high)
                sev.setBackgroundResource(R.drawable.kq_sev_high)
                sev.setTextColor(getColor(R.color.kq_warn_ink))
            }
            ScanResult.Verdict.SAFE -> {
                sev.text = getString(R.string.kq_sev_safe)
                sev.setBackgroundResource(R.drawable.kq_sev_safe)
                sev.setTextColor(getColor(R.color.kq_safe_ink))
            }
        }
    }

    /**
     * inc_dashboard_app_row layout satrini o'rnatilgan ilova ma'lumotlari bilan to'ldiradi.
     * Verdict bo'yicha o'ng tomondagi nuqta rangini sozlaydi.
     * Install source (Play Market/Sideload/...) — paket nomi tagidagi badge.
     */
    private fun bindAppRow(root: android.view.View, data: AppRowData) {
        val iconView = root.findViewById<ImageView>(R.id.ivAppIcon)
        if (data.icon != null) {
            iconView.setImageDrawable(data.icon)
        } else {
            iconView.setImageResource(R.drawable.ic_shield)
        }

        root.findViewById<TextView>(R.id.tvAppName).text = data.label
        root.findViewById<TextView>(R.id.tvAppPkg).text = data.pkgName

        // Install source badge: yashil=ishonchli do'kon, sariq=sideload, kulrang=noma'lum
        val sourceBadge = root.findViewById<TextView>(R.id.tvAppSource)
        if (sourceBadge != null) {
            val isVirus = data.verdict == "DANGER"
            sourceBadge.text = when {
                isVirus -> "⚠️ VIRUS"
                else -> data.source.labelText
            }
            val sourceColor = when {
                isVirus -> getColor(R.color.kq_danger)
                data.source.isTrusted -> getColor(R.color.kq_safe_ink)
                data.source == InstallSource.SIDELOAD -> getColor(R.color.kq_warn_ink)
                else -> getColor(R.color.kq_ink_3)
            }
            sourceBadge.setTextColor(sourceColor)
        }

        // Verdict-nuqta: yashil=safe, sariq=shubhali, qizil=xavfli, kulrang=skan qilinmagan.
        val dot = root.findViewById<android.view.View>(R.id.vAppStatusDot)
        val dotColor = when (data.verdict) {
            "DANGER" -> getColor(R.color.kq_danger)
            "SUSPICIOUS" -> getColor(R.color.kq_warn)
            "SAFE" -> getColor(R.color.kq_safe)
            else -> getColor(R.color.kq_ink_3)
        }
        dot?.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)

        // Ilovaga bosilsa — uning ruxsatlarini batafsil ko'rsatamiz.
        root.setOnClickListener { openAppDetails(data) }
    }

    /**
     * O'rnatilgan ilovaga bosilganda — ScanResultActivity'da uning ruxsatlarini
     * (xavfli + boshqalar) ochib beradi. Iloji bo'lsa real skan verdikti bilan;
     * skan cho'zilib ketsa yoki xato bersa — saqlangan verdict (yo'q bo'lsa SHUBHALI,
     * hech qachon yolg'on XAVFSIZ emas). Ruxsatlar baribir paket orqali o'qiladi.
     */
    private fun openAppDetails(data: AppRowData) {
        Toast.makeText(this, getString(R.string.autoscan_scanning), Toast.LENGTH_SHORT).show()
        scope.launch {
            val sourceDir = withContext(Dispatchers.IO) {
                try { packageManager.getApplicationInfo(data.pkgName, 0).sourceDir }
                catch (_: Throwable) { null }
            }
            val result = withContext(Dispatchers.IO) {
                val scanned = if (sourceDir != null) {
                    try { withTimeoutOrNull(8000) { ApkScanner.scan(applicationContext, sourceDir) } }
                    catch (_: Throwable) { null }
                } else null
                scanned ?: ScanResult(
                    verdict = when (data.verdict) {
                        "DANGER" -> ScanResult.Verdict.DANGER
                        "SUSPICIOUS" -> ScanResult.Verdict.SUSPICIOUS
                        "SAFE" -> ScanResult.Verdict.SAFE
                        else -> ScanResult.Verdict.SUSPICIOUS
                    },
                    reason = "",
                    details = emptyList(),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = emptyList()
                )
            }
            startActivity(
                ScanResultActivity.intent(
                    this@DashboardNewActivity,
                    sourceDir ?: "",
                    result,
                    data.pkgName
                )
            )
        }
    }

    private fun loadStatistics() {
        scope.launch {
            try {
                val stats = withContext(Dispatchers.IO) { getStatistics() }
                updateUI(stats)
            } catch (e: Exception) {
                android.util.Log.e("Dashboard", "Error loading stats", e)
            }
        }
    }

    private fun getStatistics(): Statistics {
        val prefs = getSharedPreferences("kiberqalqon_stats", Context.MODE_PRIVATE)

        // UX-06: "Karantin" plitkasi uchun HAQIQIY karantin sonini olamiz (avval total_safe —
        // xavfsiz skanlar soni ko'rsatilardi, ya'ni karantin bo'sh bo'lsa ham "Karantin: 154").
        val quarantineCount = try { Quarantine.list(this).size } catch (_: Throwable) { 0 }
        return Statistics(
            totalScanned = prefs.getInt("total_scanned", 0),
            totalBlocked = prefs.getInt("total_blocked", 0),
            totalSafe = prefs.getInt("total_safe", 0),
            quarantineCount = quarantineCount,
            weekData = getWeekData(prefs)
        )
    }

    private fun getWeekData(prefs: android.content.SharedPreferences): List<Int> {
        // #23: staleness-aware — slot oxirgi 7 kun ichida yangilanmagan bo'lsa (o'tgan
        // haftadagi shu kun) 0. Aks holda grafik "bu hafta" emas, "butun tarix"ni ko'rsatardi.
        val cal = java.util.Calendar.getInstance()
        val today = (cal.timeInMillis +
            cal.get(java.util.Calendar.ZONE_OFFSET) +
            cal.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
        return (0..6).map { day ->
            val stamp = prefs.getLong("day_${day}_epochday", -1L)
            if (stamp >= 0 && today - stamp in 0..6) prefs.getInt("day_$day", 0) else 0
        }
    }

    private fun updateUI(stats: Statistics) {
        // "Yorug' minimal": raqamlar jonli sanaladi (count-up), darhol o'rnatilmaydi.
        AnimationHelper.countUp(binding.tvTotalScanned, stats.totalScanned, startDelay = 120)
        AnimationHelper.countUp(binding.tvTotalBlocked, stats.totalBlocked, startDelay = 220)
        // UX-06: "Karantin" plitkasi — endi haqiqiy karantin fayllar soni (Quarantine.list).
        AnimationHelper.countUp(binding.tvTotalSafe, stats.quarantineCount, startDelay = 320)

        val protectionLevel = calculateProtectionLevel(stats)
        binding.speedometer.setProtectionLevel(protectionLevel, animate = true)
    }

    private fun calculateProtectionLevel(stats: Statistics): Int {
        var level = 50

        if (Config.isBackgroundEnabled(this)) {
            level += 30
        }

        if (stats.weekData.sum() > 0) {
            level += 10
        }

        if (stats.totalScanned > 0) {
            val blockRate = stats.totalBlocked.toFloat() / stats.totalScanned
            if (blockRate < 0.1f) {
                level += 10
            }
        }

        return level.coerceIn(0, 100)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    data class Statistics(
        val totalScanned: Int,
        val totalBlocked: Int,
        val totalSafe: Int,
        val quarantineCount: Int,
        val weekData: List<Int>
    )
}
