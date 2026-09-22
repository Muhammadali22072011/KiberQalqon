package com.uzguard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.uzguard.databinding.ActivityDashboardNewBinding
import com.uzguard.databinding.IncKq4NewsCardBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class DashboardNewActivity : AppCompatActivity() {

    companion object {
        /** "Qurilma holati" docскани uchun stale-chegara — kunlik InstalledAppsRescanWorker
         *  sikli bilan sinxron, alohida batareya sarfi manbai qo'shilmasin (SPEC_autoscan.md §5.3). */
        private const val STALE_MS = 24 * 60 * 60 * 1000L

        /** Docскан rotatsiyasi kursori — workerning "rescan_rotate_cursor"idan ATAYLAB alohida,
         *  ikkalasi bir-biriga "oyoq ostida" bo'lmasin (SPEC_autoscan.md §2.2). */
        private const val KEY_DASH_CURSOR = "installed_scan_dash_cursor"

        /** Umumiy gate: populateInstalledApps() ichki catch-up sikli va "Qurilma holati"
         *  docскани bir vaqtda ishlamasin (SPEC_autoscan.md §2.3, termal xavf).
         *  @Volatile Boolean check-then-act EMAS (T findings #22) — ikkita mustaqil korutina
         *  (setupDeviceStatusCard→startDeviceStatusDoscan va populateInstalledApps) o'zaro
         *  bog'liqsiz `withContext(Dispatchers.IO)` suspend nuqtalari orasida "!inFlight"ni
         *  bir vaqtda true deb o'qib, ikkalasi ham parallel ApkScanner skanini boshlashi mumkin
         *  edi. Mutex.tryLock() — atom "band bo'lsa o'tkazib yuborish" semantikasi beradi. */
        private val installedScanMutex = Mutex()
    }

    private lateinit var binding: ActivityDashboardNewBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // O'rnatilgan ilovalar ro'yxatini to'ldiruvchi oxirgi korutina — qayta-resume'da bekor qilinadi.
    private var installedAppsJob: Job? = null
    // "Qurilma holati" kartochkasi docскани — Activity qayta yaratilganda bekor qilinadi (onDestroy).
    private var cardDoscanJob: Job? = null
    // null = holat hali aniqlanmagan (skeleton); true/false — oxirgi ko'rsatilgan danger/safe holati
    // (danger→safe rang o'tishini aniqlash uchun, 7.2-band).
    private var lastDeviceStatusIsDanger: Boolean? = null
    // Kartochka birinchi marta shu Activity hayoti davomida chizilganda BIR MARTA fadeIn (7.2 §1).
    private var deviceStatusFirstBuildDone = false

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
        loadNews()
        setupDeviceStatusCard()
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

        // ── Tezkor tekshiruv plitkalari (havola / QR) ─────────────────────
        binding.tileLinkCheck.setOnClickListener {
            startActivity(Intent(this, LinkCheckActivity::class.java))
        }
        binding.tileQrCheck.setOnClickListener {
            startActivity(Intent(this, QrScanActivity::class.java))
        }

        // "Bank himoyasi" katagi → soxta bank ilovalari auditi.
        binding.kq4GuardBankCell.setOnClickListener {
            startActivity(Intent(this, BankGuardActivity::class.java))
        }

        // Guruh kartasi → guruhga qo'shilish / holat. refreshGroupCard() onResume'da yangilaydi.
        binding.cardGroup.setOnClickListener {
            startActivity(Intent(this, GroupJoinActivity::class.java))
        }

        // "Hammasi" → to'liq skaner ro'yxati.
        binding.cardApkList.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // "Hammasi" (yangiliklar) → to'liq e'lonlar ro'yxati.
        binding.kq4NewsAll.setOnClickListener {
            startActivity(Intent(this, NewsActivity::class.java))
        }

        // Karusel sahifalari orasida 10dp bo'shliq + nuqta-indikator sinxroni.
        binding.kq4NewsPager.setPageTransformer(
            MarginPageTransformer((10 * resources.displayMetrics.density).toInt())
        )
        binding.kq4NewsPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateNewsDots(position)
        })

        // Единая нижняя нав. — активна вкладка HOME.
        KqBottomNav.attach(this, KqBottomNav.Tab.HOME)

        AnimationHelper.ripple(binding.btnQuickScan)
    }

    // ─────────────────────────── Yangiliklar karuseli ───────────────────────────

    /** Karuselda ko'rsatiladigan e'lonlar soni (server tartibi: qadalgan → yangi). */
    private val newsCarouselLimit = 5

    /**
     * Avval kesh (oflayn ham darhol), keyin fonda tarmoqdan yangilash (NewsStore o'zi
     * throttle qiladi). Ikkalasi ham bo'sh bo'lsa seksiya GONE qoladi — bulutga
     * ulanmagan qurilmada bo'sh blok ko'rinmaydi.
     */
    private fun loadNews() {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { NewsStore.loadCached(this@DashboardNewActivity) }
            if (cached.isNotEmpty()) showNews(cached)
            val fresh = withContext(Dispatchers.IO) { NewsStore.refresh(this@DashboardNewActivity) }
            if (fresh != null && fresh != cached) showNews(fresh)
        }
    }

    private fun showNews(items: List<NewsStore.Item>) {
        val top = items.take(newsCarouselLimit)
        if (top.isEmpty()) {
            binding.kq4NewsSection.visibility = View.GONE
            return
        }
        binding.kq4NewsSection.visibility = View.VISIBLE
        // cascadeChildren GONE seksiyani ham animatsiya qilgan — qoldiq alpha bo'lmasin.
        binding.kq4NewsSection.alpha = 1f
        binding.kq4NewsSection.translationY = 0f
        binding.kq4NewsPager.adapter = NewsPagerAdapter(top) {
            startActivity(Intent(this, NewsActivity::class.java))
        }
        buildNewsDots(top.size)
        updateNewsDots(binding.kq4NewsPager.currentItem.coerceIn(0, top.size - 1))
    }

    private fun buildNewsDots(count: Int) {
        val dots = binding.kq4NewsDots
        dots.removeAllViews()
        if (count < 2) return
        val d = resources.displayMetrics.density
        repeat(count) {
            dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.kq4_dot)
                layoutParams = LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply {
                    marginStart = (3 * d).toInt()
                    marginEnd = (3 * d).toInt()
                }
            })
        }
    }

    /** Faol nuqta — cho'zilgan pill (16dp) + primary; qolganlari 6dp hairline. */
    private fun updateNewsDots(active: Int) {
        val dots = binding.kq4NewsDots
        val d = resources.displayMetrics.density
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = ((if (i == active) 16 else 6) * d).toInt()
            }
            dot.backgroundTintList = ColorStateList.valueOf(
                getColor(if (i == active) R.color.kq_primary else R.color.kq_hairline_strong)
            )
        }
    }

    /** ViewPager2 sahifa-adapteri: har sahifa — inc_kq4_news_card (rasm + teg + sarlavha + matn). */
    private inner class NewsPagerAdapter(
        private val items: List<NewsStore.Item>,
        private val onClick: () -> Unit,
    ) : RecyclerView.Adapter<NewsPagerAdapter.VH>() {

        inner class VH(val card: IncKq4NewsCardBinding) : RecyclerView.ViewHolder(card.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(IncKq4NewsCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val c = holder.card
            c.kq4NewsTitle.text = item.title
            c.kq4NewsBody.text = item.body
            c.kq4NewsBody.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE
            c.kq4NewsDate.text = NewsUi.humanDate(this@DashboardNewActivity, item.createdAt)
            NewsUi.applyLevelTag(item.level, c.kq4NewsTag, c.kq4NewsTagDot, c.kq4NewsTagText)
            // maxLines RASM HAQIQATAN ko'ringaniga qarab: rasm bo'lsa 2 qator, rasm yo'q/
            // yuklanmasa 5 qator (aks holda rasm joyi yo'qoladi-yu, matn 2 qatorda qotib,
            // 240dp sahifada bo'sh joy qoladi).
            NewsUi.loadImage(scope, c.kq4NewsImg, item.imageUrl) { loaded ->
                c.kq4NewsBody.maxLines = if (loaded) 2 else 5
            }
            c.root.setOnClickListener { onClick() }
        }
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
        // "Qurilma holati" kartochkasi — faqat kesh (verdict_$pkg) o'qiladi, yangi scan() yo'q
        // (SPEC_autoscan.md §5.2). Shu tufayli ScanResultActivity'da virus o'chirilib qaytilganda
        // kartochka darhol danger→safe rangga o'tadi.
        refreshDeviceStatusCardFromCache()
        // Due-tekshiruvni onResume'da HAM qaytaramiz (T findings #12): onCreate'dagi birinchi
        // urinish paytida gate (installedScanMutex) band bo'lgani uchun docскан BOSHLANMAGAN
        // bo'lishi mumkin edi — onResume boshqa hech qachon uni qayta so'ramasdi, kartochka esa
        // keyingi Activity qayta yaratilgunga qadar skeleton/eskirgan holatda qotib qolardi.
        checkDeviceStatusDueAndMaybeDoscan()
        updateProtectionStatusBar()
        updateGuardDots()
        refreshGroupCard()
        // Hero (halqa, %, sarlavha, rang) va hisoblagichlar ham qaytishda yangilanishi shart —
        // aks holda topbar yashil "Himoya yoqilgan", hero esa eski sariq holatda qoladi
        // (masalan, ProtectionStatusActivity'da himoya yoqilgandan keyin). Yengil ish:
        // SharedPreferences + Quarantine.list Dispatchers.IO'da; countUp teng qiymatda
        // miltillamaydi, ring.setValue animatsiyalanadi.
        loadStatistics()
    }

    /**
     * Guruh kartasi: qurilma guruhga qo'shilgan bo'lsa — rang + guruh nomi + "✓ Guruhdasiz";
     * aks holda — "Guruhga qo'shilish" taklifi (primary rangli nuqta). CloudTelemetry.savedGroup
     * mahalliy holatdan o'qiydi (tarmoq so'rovi yo'q).
     */
    private fun refreshGroupCard() {
        val g = CloudTelemetry.savedGroup(this)
        if (g == null) {
            binding.groupTitle.text = getString(R.string.kq4_group_join_title)
            binding.groupSub.text = getString(R.string.kq4_group_join_sub)
            binding.groupDot.background = groupDot(getColor(R.color.kq_primary))
        } else {
            binding.groupTitle.text = g.name
            binding.groupSub.text = getString(R.string.kq4_group_joined_sub)
            val color = try { Color.parseColor(g.color) } catch (e: Throwable) { getColor(R.color.kq_primary) }
            binding.groupDot.background = groupDot(color)
        }
    }

    /** Guruh nuqtasi uchun doira drawable. */
    private fun groupDot(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

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

    // ═══════════════════════ "Qurilma holati" kartochkasi (SPEC_autoscan.md §5) ═══════════════

    /**
     * Bir martalik (onCreate) sozlash: ring qalinligi, tap-listenerlar, va — agar fayl-skan
     * ruxsati bo'lsa — arzon due-tekshiruv (faqat PackageManager + prefs, hech qanday scan()
     * chaqiruvi yo'q). Due bo'lsa va boshqa installed-skan ketmayotgan bo'lsa — docскан
     * boshlanadi (§5.4). onResume() esa faqat keshdan yengil rasm chizadi (§5.2).
     */
    private fun setupDeviceStatusCard() {
        binding.ringDeviceStatus.strokeWidthDp = 8f
        binding.cardDeviceStatus.setOnClickListener { onDeviceStatusCardClick() }
        binding.tvDeviceStatusReasonToggle.setOnClickListener { toggleDeviceStatusReason() }

        if (!VersionCompat.hasFileScanAccess(this)) {
            showDeviceStatusNoAccess()
            return
        }

        checkDeviceStatusDueAndMaybeDoscan()
    }

    /**
     * Due-tekshiruv (arzon: faqat PackageManager + prefs, hech qanday scan() chaqiruvi yo'q) —
     * onCreate()dan (setupDeviceStatusCard) VA onResume()dan (T findings #12) chaqiriladi.
     * Ikkinchisi bo'lmasa, agar birinchi urinishda gate (installedScanMutex) band bo'lgani
     * uchun docскан BOSHLANMAGAN bo'lsa, bu holat Activity qayta yaratilgunga qadar hech
     * qachon qayta tekshirilmasdi.
     */
    private fun checkDeviceStatusDueAndMaybeDoscan() {
        if (!VersionCompat.hasFileScanAccess(this)) return
        scope.launch {
            val (due, currentCount, currentMaxFirstInstall) = withContext(Dispatchers.IO) {
                val userPackages = userPackagesForStatusCheck(packageManager)
                val count = userPackages.size
                val maxFirstInstall = userPackages.maxOfOrNull { it.firstInstallTime } ?: 0L
                val lastTs = Config.installedScanLastTs(this@DashboardNewActivity)
                val isDue = lastTs == 0L ||
                    (System.currentTimeMillis() - lastTs) >= STALE_MS ||
                    count != Config.knownInstalledCount(this@DashboardNewActivity) ||
                    maxFirstInstall > Config.knownMaxFirstInstallTs(this@DashboardNewActivity)
                Triple(isDue, count, maxFirstInstall)
            }
            refreshDeviceStatusCardFromCache()
            if (due && !installedScanMutex.isLocked) {
                startDeviceStatusDoscan(currentCount, currentMaxFirstInstall)
            }
        }
    }

    /**
     * populateInstalledApps() bilan BIR XIL filtr (system'siz, o'zimizdan tashqari) — due-tekshiruv
     * shu ro'yxatning soni va eng yangi o'rnatish vaqti bo'yicha ishlaydi (SPEC_autoscan.md §5.3).
     */
    private fun userPackagesForStatusCheck(
        pm: android.content.pm.PackageManager
    ): List<android.content.pm.PackageInfo> {
        val packages = try { pm.getInstalledPackages(0) } catch (_: Throwable) { return emptyList() }
        return packages.filter { p ->
            val app = p.applicationInfo ?: return@filter false
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isSelf = p.packageName == packageName || p.packageName == "${packageName}.debug"
            (!isSystem || updatedSystem) && !isSelf
        }
    }

    /** Bitta user-paketning eng og'ir keshlangan natijasi (agregatsiya uchun). */
    private data class DeviceStatusAggregate(val dangerOrSuspCount: Int, val worst: ScanResult?)

    /**
     * `uzguard_rescan`dagi `verdict_$pkg`larni user-paketlar bo'yicha agregatsiya qiladi —
     * hech qanday yangi scan() chaqirilmaydi (faqat SharedPreferences + kerak bo'lsa
     * ScanCache.get — ikkalasi ham arzon, O(1)). DANGER verdikti SUSPICIOUS'dan ustun turadi:
     * "Nega xavfli?" doim eng og'ir topilgan natijani ko'rsatishi kerak.
     */
    private fun aggregateDeviceStatus(): DeviceStatusAggregate {
        val userPackages = userPackagesForStatusCheck(packageManager)
        val rescanPrefs = getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
        var count = 0
        var worst: ScanResult? = null
        for (p in userPackages) {
            val sourceDir = p.applicationInfo?.sourceDir
            var verdictStr = rescanPrefs.getString("verdict_${p.packageName}", null)
            // rescanPrefs hali bo'sh bo'lishi mumkin — masalan InitialScanActivity'ning
            // birinchi-ishga-tushirish skani natijani faqat ScanCache'ga yozadi, uzguard_rescan'ga
            // EMAS (T findings #18). populateInstalledApps() bilan bir xil ScanCache fallback:
            // aks holda kartochka onboarding'dan darhol keyin YOLG'ON "hammasi xavfsiz" ko'rsatishi
            // mumkin, garchi Faza A2 aynan shu ilovada DANGER topgan bo'lsa ham.
            if (verdictStr == null && sourceDir != null) {
                verdictStr = try {
                    ScanCache.get(applicationContext, sourceDir)?.verdict?.name
                } catch (_: Throwable) { null }
            }
            var verdict = when (verdictStr) {
                "DANGER" -> ScanResult.Verdict.DANGER
                "SUSPICIOUS" -> ScanResult.Verdict.SUSPICIOUS
                else -> null
            }
            // populateInstalledApps() bilan BIR XIL ikki qoida (T findings #10): keshdagi
            // eskirgan vердikt emas, aynan shu qoidalar asosiy manba — aks holda shu ekrandagi
            // ikkita UI elementi (appList vs "Qurilma holati" kartochkasi) bir-biriga zid
            // ma'lumot ko'rsatishi mumkin (masalan, Play Market ilovasi uchun eski DANGER keshi).
            val knownBad = try {
                MaliciousPackages.maliciousFamily(p.packageName)
            } catch (_: Throwable) { null }
            verdict = when {
                knownBad != null -> ScanResult.Verdict.DANGER
                ApkScanner.isFromTrustedStore(applicationContext, p.packageName) -> null
                else -> verdict
            }
            verdict ?: continue
            count++
            if (worst == null || (worst.verdict != ScanResult.Verdict.DANGER && verdict == ScanResult.Verdict.DANGER)) {
                worst = (sourceDir?.let { ScanCache.get(applicationContext, it) }) ?: ScanResult(
                    verdict = verdict,
                    reason = "",
                    details = emptyList(),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = emptyList(),
                )
            }
        }
        return DeviceStatusAggregate(count, worst)
    }

    /** onResume() va docскан tugagandan keyin chaqiriladigan yengil (kesh-only) rejim. */
    private fun refreshDeviceStatusCardFromCache() {
        if (!VersionCompat.hasFileScanAccess(this)) {
            showDeviceStatusNoAccess()
            return
        }
        scope.launch {
            val aggregate = withContext(Dispatchers.IO) { aggregateDeviceStatus() }
            applyDeviceStatusCardState(aggregate.dangerOrSuspCount, aggregate.worst)
        }
    }

    /** Ruxsat yo'q holati — tap ProtectionStatusActivity'ga olib boradi, skan boshlanmaydi. */
    private fun showDeviceStatusNoAccess() {
        if (!deviceStatusFirstBuildDone) {
            deviceStatusFirstBuildDone = true
            AnimationHelper.fadeIn(binding.cardDeviceStatus, duration = 320)
        }
        binding.ringDeviceStatus.ringColor = getColor(R.color.kq_warn)
        binding.ringDeviceStatus.setValue(0f, animate = false)
        binding.ivDeviceStatusIcon.setImageResource(R.drawable.ic4_shield_alert)
        binding.ivDeviceStatusIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_warn))
        binding.tvDeviceStatusTitle.text = getString(R.string.kq4_ds_card_no_access)
        binding.tvDeviceStatusSub.text = ""
        binding.tvDeviceStatusReason.visibility = View.GONE
        binding.tvDeviceStatusReasonToggle.visibility = View.GONE
        lastDeviceStatusIsDanger = null
    }

    /**
     * Kartochka holatini (rang/ikonka/matn/halqa) yangilaydi. `count==0` va hech qachon
     * skan bo'lmagan bo'lsa — "xavfsiz" deb DA'VO QILMAYMIZ (hech qachon yolg'on SAFE), skeleton
     * ko'rsatamiz. Skeleton→holat va danger→safe o'tishlari — bir martalik animatsiyalar (§7.2).
     */
    private fun applyDeviceStatusCardState(count: Int, worst: ScanResult?) {
        if (!deviceStatusFirstBuildDone) {
            deviceStatusFirstBuildDone = true
            AnimationHelper.fadeIn(binding.cardDeviceStatus, duration = 320)
        }

        val neverScanned = Config.installedScanLastTs(this) == 0L
        val isDanger = count > 0

        if (neverScanned && !isDanger) {
            binding.ringDeviceStatus.ringColor = getColor(R.color.kq_ink_3)
            binding.ringDeviceStatus.setValue(0f, animate = false)
            binding.ivDeviceStatusIcon.setImageResource(R.drawable.ic4_shield_check)
            binding.ivDeviceStatusIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.kq_ink_3))
            binding.tvDeviceStatusTitle.text = getString(R.string.kq4_ds_card_skeleton)
            binding.tvDeviceStatusSub.text = ""
            binding.tvDeviceStatusReasonToggle.visibility = View.GONE
            binding.tvDeviceStatusReason.visibility = View.GONE
            return
        }

        val prevDanger = lastDeviceStatusIsDanger
        lastDeviceStatusIsDanger = isDanger

        val targetColor = getColor(if (isDanger) R.color.kq_danger else R.color.kq_safe)
        binding.ivDeviceStatusIcon.setImageResource(
            if (isDanger) R.drawable.ic4_shield_alert else R.drawable.ic4_shield_check
        )
        binding.ivDeviceStatusIcon.imageTintList = ColorStateList.valueOf(targetColor)

        when {
            prevDanger == true && !isDanger -> {
                // Danger→Safe: foydalanuvchi virusni o'chirib qaytdi — rang silliq o'tadi.
                AnimationHelper.animateColorTransition(getColor(R.color.kq_danger), targetColor, duration = 600) {
                    binding.ringDeviceStatus.ringColor = it
                    binding.ringDeviceStatus.invalidate()
                }
                binding.ringDeviceStatus.setValue(100f, animate = false)
            }
            prevDanger != isDanger -> {
                // Birinchi marta aniqlandi yoki safe→danger — bitta bounce+shake (continuous EMAS).
                binding.ringDeviceStatus.ringColor = targetColor
                binding.ringDeviceStatus.setValue(100f, animate = false)
                if (isDanger) {
                    AnimationHelper.bounce(binding.ivDeviceStatusIcon, duration = 320)
                    AnimationHelper.shake(binding.ivDeviceStatusIcon, duration = 280)
                }
            }
            else -> binding.ringDeviceStatus.ringColor = targetColor
        }

        binding.tvDeviceStatusTitle.text = if (isDanger)
            getString(R.string.kq4_ds_card_danger, count)
        else
            getString(R.string.kq4_ds_card_safe)

        // DIQQAT (kod bilan spekaning kelishmovchiligi): kq4_ds_card_last_check shablonida
        // "oldin"/"назад" so'zi ALLAQACHON qattiq yozilgan (strings_kq4_dashboard.xml:64,
        // values-ru xuddi shu faylda ":51" — "назад" bilan), lekin shu faylda ALLAQACHON bor
        // humanAgo() funksiyasi ham o'zining natijasiga "oldin"/"назад"ni ICHIGA qo'shib
        // qaytaradi (kq4_dash_ago_min/hour/day). Ikkalasini birga ishlatsak — "2 soat oldin
        // oldin" chiqadi. strings_kq4_dashboard.xml — T6 uchun ruxsat etilgan fayl EMAS,
        // shuning uchun shu faylni tuzatolmayman; buning o'rniga humanAgo()ni xuddi shu faylda
        // yuqorida (populateThreatRows) ishlatilgan kq4_dash_last_scan ("So'nggi tekshiruv: %1$s",
        // suffikssiz shablon) bilan qo'shib ishlataman — natija to'g'ri chiqadi, faqat
        // kq4_ds_card_last_check resursi ishlatilmay qoladi (deviations_from_spec'ga yozilgan).
        val lastTs = Config.installedScanLastTs(this)
        binding.tvDeviceStatusSub.text = getString(R.string.kq4_dash_last_scan, humanAgo(lastTs))

        if (isDanger && worst != null) {
            binding.tvDeviceStatusReasonToggle.visibility = View.VISIBLE
            binding.tvDeviceStatusReason.text = humanReadableReason(worst)
        } else {
            binding.tvDeviceStatusReasonToggle.visibility = View.GONE
            binding.tvDeviceStatusReason.visibility = View.GONE
        }
    }

    /** "Nega xavfli?" — malwareSignatures prefiksiga qarab odam o'qiy oladigan sabab (§5.5). */
    private fun humanReadableReason(result: ScanResult): String {
        val sig = result.malwareSignatures.firstOrNull()
        return when {
            sig?.startsWith("hash:") == true ->
                getString(R.string.kq4_ds_reason_hash, sig.substringAfter("hash:"))
            sig?.startsWith("cert:") == true ->
                getString(R.string.kq4_ds_reason_cert, sig.substringAfter("cert:"))
            sig?.startsWith("pkg:") == true ->
                getString(R.string.kq4_ds_reason_pkg, sig.substringAfter("pkg:"))
            sig?.startsWith("signature-mismatch:") == true ->
                getString(R.string.kq4_ds_reason_mismatch)
            // T findings #23: bu uchtasi ilgari `else` orqali ApkScanner'ning o'zbekcha+ingliz
            // texnik jargon aralash xom `result.reason`iga tushib qolardi, ruscha tarjimasiz.
            sig == "zip-encryption-evasion" ->
                getString(R.string.kq4_ds_reason_zip_evasion)
            sig?.startsWith("impersonation:") == true ->
                getString(R.string.kq4_ds_reason_impersonation, sig.substringAfter("impersonation:").substringBefore(":"))
            sig?.startsWith("homoglyph:") == true ->
                getString(R.string.kq4_ds_reason_homoglyph, sig.substringAfter("homoglyph:"))
            else -> result.reason
        }
    }

    private fun toggleDeviceStatusReason() {
        val reason = binding.tvDeviceStatusReason
        if (reason.visibility == View.VISIBLE) {
            reason.visibility = View.GONE
        } else {
            AnimationHelper.fadeIn(reason, duration = 200)
        }
    }

    /**
     * Kartochkaga tap: ruxsat yo'q bo'lsa — ProtectionStatusActivity; DANGER holatida —
     * mavjud threat-panelga scroll (alohida "tahdidlar" ekrani yo'q, eng oddiy variant,
     * SPEC_autoscan.md §5 item 8); aks holda — tinchlantiruvchi toast.
     */
    private fun onDeviceStatusCardClick() {
        if (!VersionCompat.hasFileScanAccess(this)) {
            startActivity(Intent(this, ProtectionStatusActivity::class.java))
            return
        }
        // lastDeviceStatusIsDanger == null — kartochka hali skeleton holatida (tekshiruv hali
        // tugamagan). Bunda "Hammasi joyida" deb YOLG'ON tinchlantirmaymiz (T findings #21) —
        // kartochka o'zi bir vaqtning o'zida "Tekshirilmoqda kutilmoqda…" ko'rsatib turibdi.
        when (lastDeviceStatusIsDanger) {
            true -> scrollToThreatPanel()
            false -> Toast.makeText(this, getString(R.string.kq4_ds_card_toast_safe), Toast.LENGTH_SHORT).show()
            null -> Unit
        }
    }

    /**
     * `cardThreatPanel` ScrollView ichida bir necha LinearLayout ichida joylashgan —
     * `View.top` faqat bevosita ota-view'ga nisbatan bo'lgani uchun to'g'ridan-to'g'ri
     * ishlatib bo'lmaydi (chuqurlikdagi barcha marginlar/paddinglar hisobga olinmay qoladi).
     * Shuning uchun ikkala view'ning oynadagi mutlaq joylashuvi farqi orqali scroll qilamiz.
     */
    private fun scrollToThreatPanel() {
        val scrollView = binding.dashboardScroll
        val target = binding.cardThreatPanel
        scrollView.post {
            val scrollLoc = IntArray(2)
            val targetLoc = IntArray(2)
            scrollView.getLocationInWindow(scrollLoc)
            target.getLocationInWindow(targetLoc)
            val delta = targetLoc[1] - scrollLoc[1] + scrollView.scrollY
            scrollView.smoothScrollTo(0, delta)
        }
    }

    /**
     * Docскан (§5.4): ротация kursori + umumiy gate orqali. `onProgress` IO threadda
     * chaqiriladi — UI'ni `runOnUiThread` bilan Main'ga o'tkazamiz, halqa esa
     * `AnimationHelper.throttledRingUpdate` bilan buferlanadi (220мс, §7.3).
     */
    private fun startDeviceStatusDoscan(installedCountAtDue: Int, maxFirstInstallAtDue: Long) {
        // tryLock() — atom "band bo'lsa o'tkazib yuborish" (T findings #22). Bu funksiya
        // faqat gate BO'SH ekani allaqachon aniqlangandan keyin chaqiriladi, shuning uchun
        // muvaffaqiyatsizlik amalda kutilmaydi — lekin tekshiruv-va-belgilash orasidagi
        // poyga oynasini yopish uchun baribir shart (oldingi @Volatile Boolean buni ta'minlamasdi).
        if (!installedScanMutex.tryLock()) return
        val rescanPrefs = getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
        // Eski `cardDoscanJob?.cancel()` shu yerda O'LIK KOD edi (T findings #15): gate
        // tufayli bu nuqtaga faqat oldingi job yo'q/tugagan bo'lsagina yetib kelinadi.
        cardDoscanJob = scope.launch(Dispatchers.IO) {
            val cursor = rescanPrefs.getInt(KEY_DASH_CURSOR, 0)
            var lastIdx = cursor
            var totalSeen = 0   // 0 = onProgress hech chaqirilmadi → kursor o'zgarmaydi
            val threats = ApkScanner.scanInstalledForThreats(
                context = applicationContext,
                limit = 40,
                startIndex = cursor,
                loadIcons = true,
                timeBudgetMs = null,
                isCancelled = { !isActive },
                onProgress = { idx, total, _, label, _, _ ->
                    lastIdx = idx
                    totalSeen = total
                    val pct = if (total > 0) (idx + 1) * 100f / total else 100f
                    runOnUiThread {
                        AnimationHelper.throttledRingUpdate(binding.ringDeviceStatus, pct)
                        binding.tvDeviceStatusTitle.text = getString(R.string.kq4_ds_card_progress, label)
                    }
                }
            )
            threats.forEach { t ->
                rescanPrefs.edit().putString("verdict_${t.pkg}", t.result.verdict.name).apply()
            }
            // Birorta paket ko'rilmagan bo'lsa (0 ta ilova yoki darhol bekor qilindi) — kursor
            // joyida qoladi, aks holda (lastIdx+1)%1 uni noldan boshlab yuborardi.
            if (totalSeen > 0) {
                rescanPrefs.edit().putInt(KEY_DASH_CURSOR, (lastIdx + 1) % totalSeen).apply()
            }
            Config.markInstalledScanDone(applicationContext, installedCountAtDue, maxFirstInstallAtDue)
            refreshDeviceStatusCardFromCache()
        }
        cardDoscanJob?.invokeOnCompletion { installedScanMutex.unlock() }
    }

    /**
     * `appList` ichiga barcha user-app'larni (system'sis) ro'yxat sifatida joylaydi.
     * Har bir satr: real ikonka + ilova nomi + paket · manba + verdict-tag.
     * Verdict `uzguard_rescan` SharedPreferences'dan o'qiladi
     * (InstalledAppsRescanWorker har 24 soatda yangilaydi).
     * Skan qilinmagan ilova uchun tag — kulrang "Tekshirilmagan".
     *
     * PackageManager.loadIcon/loadLabel sekin bo'lishi mumkin (telefonda 30-60 ilova bilan),
     * shuning uchun ro'yxat va metadata IO threadda yig'iladi, UI'ga main thread'da kiritamiz.
     */
    private fun populateInstalledApps() {
        // DIQQAT (T findings #11): bu yerda ILGARI butun funksiyani (ro'yxatni qayta qurishni
        // ham) to'sadigan gate bor edi — agar "Qurilma holati" docскани ketayotgan bo'lsa,
        // appList HECH QACHON yangilanmasdi (faqat qayta-skan qilinmasdi). Endi gate faqat
        // pastdagi ICHKI catch-up siklini to'sadi (haqiqiy ApkScanner.scan() chaqiruvlari,
        // termal xavf, SPEC_autoscan.md §2.3) — ro'yxatning o'zi HAR DOIM PackageManager'dan
        // qayta quriladi, shu bilan o'rnatilgan/o'chirilgan ilova darhol ko'rinadi.
        val container = binding.appList
        container.removeAllViews()

        // Tez pause/resume'da oldingi (hali IO'dagi) korutina qaytib, ro'yxatni IKKINCHI marta
        // qo'shib qo'ymasin (har ilova ikki marta ko'rinardi) — avvalgisini bekor qilamiz.
        installedAppsJob?.cancel()
        installedAppsJob = scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = try {
                    pm.getInstalledPackages(0)
                } catch (e: Throwable) {
                    android.util.Log.w("Dashboard", "getInstalledPackages failed", e)
                    return@withContext emptyList<AppRowData>()
                }

                // Faqat user-apps (system'larni o'tkazamiz, lekin updated-system'larni qoldiramiz —
                // u yerda ham sideload-attack apdeytlari uchraydi). UzGuard o'zini o'tkazadi.
                val userApps = packages.filter { p ->
                    val app = p.applicationInfo ?: return@filter false
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val updatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val isSelf = p.packageName == packageName || p.packageName == "${packageName}.debug"
                    (!isSystem || updatedSystem) && !isSelf
                }

                val rescanPrefs = getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
                userApps.mapNotNull { p ->
                    val app = p.applicationInfo ?: return@mapNotNull null
                    val label = try { app.loadLabel(pm).toString() } catch (_: Throwable) { p.packageName }
                    val icon = try { app.loadIcon(pm) } catch (_: Throwable) { null }
                    val sourceDir = try { app.sourceDir } catch (_: Throwable) { null }
                    var verdict = rescanPrefs.getString("verdict_${p.packageName}", null)

                    // Rescan-prefs hali bo'sh bo'lsa (kunlik worker hali yurmagan) — skan
                    // keshidan (ScanCache, sourceDir bo'yicha) oxirgi natijani olamiz. Shunda
                    // avval skan qilingan ilovalar "Tekshirilmagan" bo'lib qolmaydi.
                    if (verdict == null && sourceDir != null) {
                        verdict = try {
                            ScanCache.get(applicationContext, sourceDir)?.verdict?.name
                        } catch (_: Throwable) { null }
                    }

                    // Ma'lum virus paketlari ro'yxati — InstalledAppsRescanWorker'dan oldin
                    // yana bir himoya qatlami. Foydalanuvchi ilovani 1 sekund oldin o'rnatgan
                    // bo'lsa ham, agar paket nomi qora ro'yxatda bo'lsa — darhol DANGER.
                    val knownBad = try {
                        MaliciousPackages.maliciousFamily(p.packageName)
                    } catch (_: Throwable) { null }
                    if (knownBad != null) verdict = "DANGER"

                    // Rasmiy do'kondan (Play Market / Galaxy / AppGallery / Mi / RuStore...) o'rnatilgan
                    // ilova — HAR DOIM XAVFSIZ deb ko'rsatamiz va UMUMAN SKANLAMAYMIZ. Play Protect uni
                    // allaqachon tekshirgan, /data/app'dagi base.apk'ni boshqa ilova almashtira olmaydi,
                    // va har bir o'rnatilgan ilovani qayta skanlash telefonni bekorga qizdiradi. verdict==null
                    // sharti YO'Q — eski false-positive to'lqinidan qolgan noto'g'ri "DANGER" keshini ham
                    // shu yerda tozalaymiz. Ma'lum zararli paket (knownBad) bu qoidadan YUQORI turadi —
                    // u yagona istisno bo'lib, baribir DANGER bo'lib qoladi.
                    if (knownBad == null &&
                        ApkScanner.isFromTrustedStore(applicationContext, p.packageName)) {
                        verdict = "SAFE"
                    }

                    val source = detectInstallSource(pm, p.packageName)
                    AppRowData(label, p.packageName, icon, verdict, source, sourceDir)
                }.sortedBy { it.label.lowercase() }
            }

            val inflater = layoutInflater
            val rowByPkg = HashMap<String, View>(rows.size)
            for (data in rows) {
                val row = inflater.inflate(R.layout.inc_dashboard_app_row, container, false)
                bindAppRow(row, data)
                container.addView(row)
                rowByPkg[data.pkgName] = row
            }

            // Skan qilinmagan ("Tekshirilmagan") ilovalarni ekran ochiqligida DARHOL
            // tekshiramiz — endi foydalanuvchi serdagi "Tekshirilmagan" tegida abadiy qotib
            // qolmaydi. Ilgari verdiktni faqat kunlik InstalledAppsRescanWorker to'ldirardi
            // (u esa +2 soat kechikib ishga tushib, 50 ta bilan cheklanib, OEM tomonidan
            // o'ldirilishi mumkin edi) — yangi o'rnatishda hamma ilova "Tekshirilmagan" turardi.
            // Natija uzguard_rescan'ga + ScanCache'ga tushadi, shuning uchun keyingi
            // ochilishlarda qayta skan bo'lmaydi (bir necha ochilishda to'liq konvergensiya).
            // Skan xato/timeout bersa — teg "Tekshirilmagan" bo'lib qoladi (hech qachon yolg'on XAVFSIZ).
            val rescanPrefs = getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
            // QIZISH-FIKSI (2026-07-09, qurilmada am profile bilan isbotlangan): avval timeout
            // bo'lgan skan `?: continue` bilan scannedNow'ni OSHIRMAY o'tib ketardi. Katta ilova
            // (Telegram, 50MB) 8s ichida ulgurmaydi → natija tashlanadi → verdikt saqlanmaydi →
            // HAR onResume'da o'sha ilovalar QAYTA skanlanadi (hech qachon konvergensiya yo'q).
            // Ustiga withTimeoutOrNull bloklovchi ApkScanner.scan'ni TO'XTATA OLMAYDI (kooperativ
            // bekor qilish) — skan oxirigacha ishlab, natijasi bekorga tashlanardi. Telefon shu
            // tsiklda qizirdi. Endi: (1) HAR urinish limitga sanaladi; (2) timeout bo'lgan ilova
            // 24 soat backoff oladi (kunlik InstalledAppsRescanWorker baribir tekshiradi).
            var attempted = 0
            var foundThreatOnOpen = false   // ilova ochilishida o'rnatilgan (sideload) virus topildimi
            val nowMs = System.currentTimeMillis()
            // Ushbu ichki catch-up sikl haqiqiy ApkScanner.scan() chaqiradi — "Qurilma holati"
            // docскани bilan bir vaqtda ishlamasligi uchun umumiy gate shu yerda ham o'rnatiladi.
            // tryLock() — agar boshqa installed-skan (masalan startDeviceStatusDoscan) ALLAQACHON
            // ketayotgan bo'lsa, bu sikl BUTUNLAY o'tkazib yuboriladi (SPEC_autoscan.md §2.3,
            // T findings #11/#22) — lekin yuqorida qurilgan ro'yxat baribir ekranga chiqadi.
            if (installedScanMutex.tryLock()) {
                try {
                    for (data in rows) {
                        if (attempted >= 40) break           // bitta ochilishda ko'pi bilan 40 urinish — qizib ketmasin
                        if (data.verdict != null) continue   // allaqachon verdikti bor — o'tkazamiz
                        val sourceDir = data.sourceDir ?: continue
                        if (nowMs < rescanPrefs.getLong("slow_until_${data.pkgName}", 0L)) continue
                        attempted++
                        val scanned = withContext(Dispatchers.IO) {
                            try { withTimeoutOrNull(8000) { ApkScanner.scan(applicationContext, sourceDir) } }
                            catch (_: Throwable) { null }
                        }
                        if (scanned == null) {
                            rescanPrefs.edit()
                                .putLong("slow_until_${data.pkgName}", nowMs + 24L * 60 * 60 * 1000)
                                .apply()
                            continue
                        }
                        rescanPrefs.edit().putString("verdict_${data.pkgName}", scanned.verdict.name).apply()
                        // Tegni jonli yangilaymiz — foydalanuvchi tekshiruv ketayotganini ko'radi.
                        rowByPkg[data.pkgName]?.let { applyAppTag(it, scanned.verdict.name) }
                        if (scanned.verdict != ScanResult.Verdict.SAFE) foundThreatOnOpen = true
                        yield()
                    }
                } finally {
                    installedScanMutex.unlock()
                }
            }
            // Ilova ochilganda inline skan o'rnatilgan (sideload) ilovada VIRUS topgan bo'lsa —
            // "So'nggi tahdidlar" panelini DARHOL yangilaymiz. Aks holda topilgan virus faqat
            // keyingi onResume'da ko'rinardi (panel skan tugashidan OLDIN chizilgan). Play Market
            // ilovalari umuman skanlanmaydi (yuqorida SAFE) — shuning uchun bu yerga tushmaydi.
            if (foundThreatOnOpen) populateThreatRows()
        }
    }

    private data class AppRowData(
        val label: String,
        val pkgName: String,
        val icon: android.graphics.drawable.Drawable?,
        val verdict: String?,
        val source: InstallSource,
        val sourceDir: String?,
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
        applyAppTag(root, data.verdict)

        // Ilovaga bosilsa — uning ruxsatlarini batafsil ko'rsatamiz (+ tap = real tekshiruv).
        root.setOnClickListener { openAppDetails(data, root) }
    }

    /** Satrning verdict-yorlig'ini (rang+matn) o'rnatadi. Skan tugagach jonli yangilash uchun ham. */
    private fun applyAppTag(root: View, verdict: String?) {
        val tag = root.findViewById<View>(R.id.tagApp)
        val tagDot = root.findViewById<View>(R.id.tagAppDot)
        val tagText = root.findViewById<TextView>(R.id.tvAppTag)
        val (bgRes, inkColor, textRes) = when (verdict) {
            "DANGER" -> Triple(R.drawable.kq4_tag_danger, R.color.kq_danger_ink, R.string.kq4_danger)
            "SUSPICIOUS" -> Triple(R.drawable.kq4_tag_warn, R.color.kq_warn_ink, R.string.kq4_suspicious)
            "SAFE" -> Triple(R.drawable.kq4_tag_safe, R.color.kq_safe_ink, R.string.kq4_safe)
            else -> Triple(R.drawable.kq4_tag_soft, R.color.kq_ink_2, R.string.kq4_dash_not_scanned)
        }
        tag.setBackgroundResource(bgRes)
        tagDot.backgroundTintList = ColorStateList.valueOf(getColor(inkColor))
        tagText.setText(textRes)
        tagText.setTextColor(getColor(inkColor))
    }

    /**
     * O'rnatilgan ilovaga bosilganda — ScanResultActivity'da uning ruxsatlarini
     * (xavfli + boshqalar) ochib beradi. Iloji bo'lsa real skan verdikti bilan;
     * skan cho'zilib ketsa yoki xato bersa — saqlangan verdict (yo'q bo'lsa SHUBHALI,
     * hech qachon yolg'on XAVFSIZ emas). Ruxsatlar baribir paket orqali o'qiladi.
     */
    private fun openAppDetails(data: AppRowData, row: View) {
        Toast.makeText(this, getString(R.string.autoscan_scanning), Toast.LENGTH_SHORT).show()
        scope.launch {
            val sourceDir = data.sourceDir ?: withContext(Dispatchers.IO) {
                try { packageManager.getApplicationInfo(data.pkgName, 0).sourceDir }
                catch (_: Throwable) { null }
            }
            val scanned = withContext(Dispatchers.IO) {
                if (sourceDir != null) {
                    try { withTimeoutOrNull(8000) { ApkScanner.scan(applicationContext, sourceDir) } }
                    catch (_: Throwable) { null }
                } else null
            }
            val result = scanned ?: ScanResult(
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

            // Tap = HAQIQIY tekshiruv. Real skan bo'lgan bo'lsa — verdiktni saqlaymiz, shunda
            // ilova endi "Tekshirilmagan" bo'lib qolmaydi (keyingi ochilishda ham). Satr
            // yorlig'ini darhol yangilaymiz — foydalanuvchi tekshiruv bo'lganini ko'radi.
            if (scanned != null) {
                getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
                    .edit().putString("verdict_${data.pkgName}", scanned.verdict.name).apply()
            }
            applyAppTag(row, result.verdict.name)

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
        val prefs = getSharedPreferences("uzguard_stats", Context.MODE_PRIVATE)

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
        cardDoscanJob?.cancel()
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
