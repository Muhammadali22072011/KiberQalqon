package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
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
        binding.dashboardContent.post {
            AnimationHelper.cascadeChildren(binding.dashboardContent, delayBetween = 70)
        }

        setupUI()
        // loadStatistics() bu yerda chaqirilmaydi — onResume() har doim onCreate'dan keyin
        // keladi va statistikani o'zi yuklaydi (ikki marta yuklashning hojati yo'q).
    }

    private fun setupUI() {
        // ── v4 TopBar (inc_kq4_topbar) ──────────────────────────────────────
        // UZ / RU — tap bilan til almashtiriladi va Activity yangi locale'da qayta tug'iladi.
        binding.kq4Topbar.kq4LangCode.text =
            if (Config.getLanguage(this) == "ru") "RU" else "UZ"
        binding.kq4Topbar.kq4BtnLang.setOnClickListener {
            val cur = Config.getLanguage(this)
            val next = if (cur == "ru") "uz" else "ru"
            Config.setLanguage(this, next)
            recreate()
        }

        // Qo'ng'iroqcha → Karantin (dizayn TopBar: bell → quarantine).
        binding.kq4Topbar.kq4BtnBell.setOnClickListener {
            startActivity(Intent(this, QuarantineActivity::class.java))
        }

        // ── HERO CTA ────────────────────────────────────────────────────────
        binding.btnQuickScan.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            loadStatistics()
        }

        // ── 3 raqam kartasi ────────────────────────────────────────────────
        binding.tileScanned.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.tileDeleted.setOnClickListener {
            startActivity(Intent(this, QuarantineActivity::class.java))
        }
        binding.tileProtection.setOnClickListener {
            startActivity(Intent(this, ProtectionStatusActivity::class.java))
        }

        // "Hammasi" → to'liq skaner ro'yxati.
        binding.cardApkList.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
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
        updateProtectionStatusBar()
        updateGuardDots()
        // Hero (halqa, %, sarlavha, rang) va hisoblagichlar ham qaytishda yangilanishi shart —
        // aks holda topbar yashil "Himoya yoqilgan", hero esa eski sariq holatda qoladi
        // (masalan, ProtectionStatusActivity'da himoya yoqilgandan keyin). Yengil ish:
        // SharedPreferences + Quarantine.list Dispatchers.IO'da; countUp teng qiymatda
        // miltillamaydi, ring.setValue animatsiyalanadi.
        loadStatistics()
    }

    /** TopBar: real himoya holati — matn (yoqilgan/o'chiq) + nuqta rangi (safe/warn). */
    private fun updateProtectionStatusBar() {
        val on = Config.isBackgroundEnabled(this)
        binding.kq4Topbar.kq4TopSub.setText(
            if (on) R.string.kq4_protection_on else R.string.kq4_protection_off
        )
        binding.kq4Topbar.kq4TopDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (on) R.color.kq_safe else R.color.kq_warn)
        )
    }

    /**
     * "Himoya qatlamlari" 2×2 nuqtalari — modul REAL yoqilgan bo'lsa yashil, aks holda sariq:
     *  - Fayllar nazorati = fon real-time monitoring (Config.isBackgroundEnabled)
     *  - Bank himoyasi    = APK skaner har doim ishlaydi → doim yashil
     *  - SMS himoyasi     = phishing-bildirishnoma filtri (flag + notification-access ruxsati)
     *  - Internet himoyasi= DNS-sinkhole VPN (opt-in; ruxsat berilgan bo'lsa prepare()==null)
     */
    private fun updateGuardDots() {
        setGuardDot(binding.kq4GuardFilesDot, Config.isBackgroundEnabled(this))
        setGuardDot(binding.kq4GuardBankDot, true)

        val smsOn = Config.isPhishingBlockerEnabled(this) &&
            try {
                NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            } catch (_: Throwable) { false }
        setGuardDot(binding.kq4GuardSmsDot, smsOn)

        val netOn = try {
            VpnFilterService.prepareIntent(this) == null
        } catch (_: Throwable) { false }
        setGuardDot(binding.kq4GuardNetDot, netOn)
    }

    private fun setGuardDot(dot: View, enabled: Boolean) {
        dot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (enabled) R.color.kq_safe else R.color.kq_warn)
        )
    }

    /**
     * `appList` ichiga barcha user-app'larni (system'sis) ro'yxat sifatida joylaydi.
     * Har bir satr: real ikonka + ilova nomi + paket · manba + verdict-tag.
     * Verdict `kiberqalqon_rescan` SharedPreferences'dan o'qiladi
     * (InstalledAppsRescanWorker har 24 soatda yangilaydi).
     * Skan qilinmagan ilova uchun tag — kulrang "Tekshirilmagan".
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

    private enum class InstallSource {
        PLAY_MARKET,
        GALAXY_STORE,
        HUAWEI_GALLERY,
        AMAZON,
        SIDELOAD,
        PRE_INSTALLED,
        UNKNOWN,
    }

    /** Manba yorlig'i — lokalizatsiya qilinadigan resurs (Tizim/Noma'lum tarjima bo'ladi). */
    private fun sourceLabelRes(source: InstallSource): Int = when (source) {
        InstallSource.PLAY_MARKET -> R.string.kq4_dash_src_play
        InstallSource.GALAXY_STORE -> R.string.kq4_dash_src_galaxy
        InstallSource.HUAWEI_GALLERY -> R.string.kq4_dash_src_huawei
        InstallSource.AMAZON -> R.string.kq4_dash_src_amazon
        InstallSource.SIDELOAD -> R.string.kq4_dash_src_sideload
        InstallSource.PRE_INSTALLED -> R.string.kq4_dash_src_system
        InstallSource.UNKNOWN -> R.string.kq4_dash_src_unknown
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
        val history = ScanHistory.all(this)

        // HERO: "So'nggi tekshiruv: …" — real oxirgi skan vaqti (yangi yozuv ro'yxat boshida).
        val lastTs = history.firstOrNull()?.timestamp ?: 0L
        binding.kq4DashLastScan.text = getString(
            R.string.kq4_dash_last_scan,
            if (lastTs > 0) humanAgo(lastTs) else getString(R.string.kq4_dash_never)
        )

        val recent = history
            .filter { it.verdict != ScanResult.Verdict.SAFE }
            .take(3)

        val rows = listOf(
            binding.threatRow1.root,
            binding.threatRow2.root,
            binding.threatRow3.root,
        )

        // Ko'rinadigan satrlar orasidagina hairline-ajratgich (design .li border-bottom).
        binding.kq4ThreatDiv1.visibility = if (recent.size >= 2) View.VISIBLE else View.GONE
        binding.kq4ThreatDiv2.visibility = if (recent.size >= 3) View.VISIBLE else View.GONE

        if (recent.isEmpty()) {
            // История пуста — показываем 1 строку-заглушку "ничего не найдено", остальные прячем.
            bindThreatRow(
                rows[0],
                filename = getString(R.string.kq4_dash_empty_title),
                sub = getString(R.string.kq4_dash_empty_sub),
                verdict = ScanResult.Verdict.SAFE,
            )
            rows[0].visibility = View.VISIBLE
            rows[0].setOnClickListener(null)
            rows[0].isClickable = false
            rows[1].visibility = View.GONE
            rows[2].visibility = View.GONE
            return
        }

        for ((i, row) in rows.withIndex()) {
            val entry = recent.getOrNull(i)
            if (entry == null) {
                row.visibility = View.GONE
            } else {
                row.visibility = View.VISIBLE
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

    /** Dizayn .li sub: "{manba} orqali · {vaqt}". */
    private fun buildThreatSub(entry: ScanHistory.Entry): String {
        val source = (entry.source?.takeIf { it.isNotBlank() }
            ?: getString(R.string.kq4_dash_source_scan))
            .replaceFirstChar { it.uppercase() }
        return getString(R.string.kq4_dash_via_time, source, humanAgo(entry.timestamp))
    }

    private fun humanAgo(ts: Long): String {
        if (ts <= 0) return getString(R.string.kq4_dash_ago_recent)
        val delta = (System.currentTimeMillis() - ts) / 1000
        return when {
            delta < 60 -> getString(R.string.kq4_dash_ago_now)
            delta < 3600 -> getString(R.string.kq4_dash_ago_min, delta / 60)
            delta < 86400 -> getString(R.string.kq4_dash_ago_hour, delta / 3600)
            else -> getString(R.string.kq4_dash_ago_day, delta / 86400)
        }
    }

    /**
     * v4 .li satrini verdict bo'yicha bo'yaydi:
     *  DANGER     → av danger + ic4_file (qizil) + tag "Xavfli"
     *  SUSPICIOUS → av warn + ic4_file (sariq) + tag "Shubhali"
     *  SAFE       → faqat bo'sh-holat uchun: av safe + ic4_check_circle, tag yashirin.
     * Verdict mantiqi o'zgarmaydi — bu faqat ko'rinish.
     */
    private fun bindThreatRow(
        root: View,
        filename: String,
        sub: String,
        verdict: ScanResult.Verdict = ScanResult.Verdict.DANGER,
    ) {
        root.findViewById<TextView>(R.id.tvThreatName).text = filename
        root.findViewById<TextView>(R.id.tvThreatSub).text = sub

        val av = root.findViewById<View>(R.id.avThreat)
        val icon = root.findViewById<ImageView>(R.id.ivThreatIcon)
        val tag = root.findViewById<View>(R.id.tagThreat)
        val tagDot = root.findViewById<View>(R.id.tagThreatDot)
        val sev = root.findViewById<TextView>(R.id.tvThreatSev)

        when (verdict) {
            ScanResult.Verdict.DANGER -> {
                av.setBackgroundResource(R.drawable.kq4_av_danger)
                icon.setImageResource(R.drawable.ic4_file)
                icon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_danger))
                tag.visibility = View.VISIBLE
                tag.setBackgroundResource(R.drawable.kq4_tag_danger)
                tagDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_danger_ink))
                sev.text = getString(R.string.kq4_danger)
                sev.setTextColor(getColor(R.color.kq_danger_ink))
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                av.setBackgroundResource(R.drawable.kq4_av_warn)
                icon.setImageResource(R.drawable.ic4_file)
                icon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_warn))
                tag.visibility = View.VISIBLE
                tag.setBackgroundResource(R.drawable.kq4_tag_warn)
                tagDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_warn_ink))
                sev.text = getString(R.string.kq4_suspicious)
                sev.setTextColor(getColor(R.color.kq_warn_ink))
            }
            ScanResult.Verdict.SAFE -> {
                av.setBackgroundResource(R.drawable.kq4_av_safe)
                icon.setImageResource(R.drawable.ic4_check_circle)
                icon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_safe))
                tag.visibility = View.GONE
            }
        }
    }

    /**
     * inc_dashboard_app_row layout satrini o'rnatilgan ilova ma'lumotlari bilan to'ldiradi.
     * Sub: "paket · manba"; o'ngda verdict-tag (safe/warn/danger, skan qilinmagan — soft).
     */
    private fun bindAppRow(root: View, data: AppRowData) {
        val iconView = root.findViewById<ImageView>(R.id.ivAppIcon)
        if (data.icon != null) {
            iconView.setImageDrawable(data.icon)
        } else {
            iconView.setImageResource(R.drawable.ic_shield)
        }

        root.findViewById<TextView>(R.id.tvAppName).text = data.label
        root.findViewById<TextView>(R.id.tvAppPkg).text = getString(
            R.string.kq4_dash_pkg_src, data.pkgName, getString(sourceLabelRes(data.source))
        )

        // Verdict-tag: yashil=safe, sariq=shubhali, qizil=xavfli, kulrang=skan qilinmagan.
        val tag = root.findViewById<View>(R.id.tagApp)
        val tagDot = root.findViewById<View>(R.id.tagAppDot)
        val tagText = root.findViewById<TextView>(R.id.tvAppTag)
        val (bgRes, inkColor, textRes) = when (data.verdict) {
            "DANGER" -> Triple(R.drawable.kq4_tag_danger, R.color.kq_danger_ink, R.string.kq4_danger)
            "SUSPICIOUS" -> Triple(R.drawable.kq4_tag_warn, R.color.kq_warn_ink, R.string.kq4_suspicious)
            "SAFE" -> Triple(R.drawable.kq4_tag_safe, R.color.kq_safe_ink, R.string.kq4_safe)
            else -> Triple(R.drawable.kq4_tag_soft, R.color.kq_ink_2, R.string.kq4_dash_not_scanned)
        }
        tag.setBackgroundResource(bgRes)
        tagDot.backgroundTintList = ColorStateList.valueOf(getColor(inkColor))
        tagText.setText(textRes)
        tagText.setTextColor(getColor(inkColor))

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

        // UX-06: karantin soni HAQIQIY karantindan (Quarantine.list), statistika emas.
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
        // Raqamlar jonli sanaladi (count-up), darhol o'rnatilmaydi.
        AnimationHelper.countUp(binding.tvTotalScanned, stats.totalScanned, startDelay = 120)
        // Dizayn (screens1.jsx): plitka №2 "O'chirildi" → go quarantine, ya'ni REAL
        // o'chirilgan/karantindagi fayllar soni (Quarantine.list), blok hisoblagichi emas.
        // Aks holda plitkadagi raqam Karantin ekranidagi raqam bilan mos kelmaydi.
        AnimationHelper.countUp(binding.tvTotalBlocked, stats.quarantineCount, startDelay = 220)

        val protectionLevel = calculateProtectionLevel(stats)
        binding.tvProtectionPct.text = getString(R.string.kq4_dash_pct, protectionLevel)

        // HERO holati — porog'lar eski SpeedometerView.statusColor() bilan 1:1:
        // >=80 yashil "himoyalangan", 50..79 sariq "to'liq emas", <50 qizil "xavf ostida".
        val (titleRes, colorRes, iconRes) = when {
            protectionLevel >= 80 ->
                Triple(R.string.kq4_dash_hero_safe, R.color.kq_safe, R.drawable.ic4_shield_check)
            protectionLevel >= 50 ->
                Triple(R.string.kq4_dash_hero_warn, R.color.kq_warn, R.drawable.ic4_shield_alert)
            else ->
                Triple(R.string.kq4_dash_hero_danger, R.color.kq_danger, R.drawable.ic4_shield_alert)
        }
        val statusColor = getColor(colorRes)
        binding.kq4DashHeroTitle.setText(titleRes)
        binding.kq4DashHeroIcon.setImageResource(iconRes)
        binding.kq4DashHeroIcon.imageTintList = ColorStateList.valueOf(statusColor)
        binding.kq4DashRing.ringColor = statusColor
        binding.kq4DashRing.setValue(protectionLevel.toFloat())
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
