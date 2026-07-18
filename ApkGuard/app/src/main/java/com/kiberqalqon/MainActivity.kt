/*
 *  #### #  # #### #  #    #  # #### #  #     ← END (asosiy ekran / UI)
 *  #    #  # #    # #     #  # #  # #  #
 *  ###  #  # #    ##      #### #  # #  #
 *  #    #  # #    # #       #  #  # #  #
 *  #    #### #### #  #      #  #### ####
 *  Bu kod Muhammadaliniki. O'g'irlama. — UzGuard
 */
package com.uzguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.uzguard.databinding.ActivityMainBinding
import com.uzguard.databinding.DialogNewsBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ApkAdapter
    private var hasPermission = false
    // Ruxsat berilgach skan/kuzatuvchini bir martagina ishga tushiramiz —
    // har onResume'da (masalan, sozlamalardan qaytganda) takror skan bo'lmasligi uchun.
    private var scanStarted = false
    // O'rnatilgan ilovalar skani bir vaqtda BITTA ishlashi uchun guard (tez-tez bosishda takror ishga tushmasin).
    @Volatile private var installedScanRunning = false
    // v4: joriy filtr (Hammasi/Xavfli/Xavfsiz) — chip ko'rinishini renderChips() shu orqali chizadi.
    private var currentFilter = ApkAdapter.Filter.ALL
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var multiPathObserver: MultiPathFileObserver? = null

    // PERF: "Hammasini skanlash" bir vaqtning o'zida HAR BIR APK uchun coroutine ochardi
    // (apks.forEach { launch(Dispatchers.IO) }) — Dispatchers.IO 64 thread'gacha ko'taradi,
    // Telegram/Downloads'da 50-200 APK bo'lsa o'nlab og'ir skan (SHA-256/ZIP/DEX) bir vaqtda
    // ishlab BARCHA yadroni band qilardi → telefon qizardi. Endi bir vaqtda ko'pi bilan N ta
    // skan (yadrolarning yarmi, 2..4 oralig'ida) — fayllar o'sha-o'sha, movJ o'sha-o'sha,
    // faqat parallellik cheklangan. Aniqlash kuchi O'ZGARMAYDI.
    private val scanGate = Semaphore(
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    )

    // Beruvchi lenta (news ticker) holati.
    private var tickerAdapter: NewsTickerAdapter? = null
    private var lastNewsSig: String? = null
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var tickerPaused = false
    private var tickerAccum = 0f
    // PERF: lenta har 16ms da (≈60fps) RecyclerView.scrollBy chaqirib UI-thread'ni doimiy
    // band qilardi. Endi har 32ms (≈30fps) — sokin marquee uchun ko'zga bilinmaydi, lekin
    // UI ish ikki barobar kamayadi. Tezlik o'sha-o'sha: qadam 2 barobar (1.4dp/32ms ≈ 44dp/s).
    private val tickerStepPx by lazy { (resources.displayMetrics.density * 1.4f).coerceAtLeast(1f) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupAdapter()
            setupButtons()
            setupFilters()
            refreshPermissionState()
            updateLiveCard()

            // Единая нижняя нав — активна вкладка Skaner.
            KqBottomNav.attach(this, KqBottomNav.Tab.SCAN)

            // Показываем диалог при первом запуске
            if (Config.isFirstRun(this)) {
                showFirstRunDialog()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.toast_init_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupAdapter() {
        adapter = ApkAdapter(emptyList()) { item -> scanItem(item) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // Empty state ko'rinishini avto-toggle: itemCount=0 da ko'rsatamiz.
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshEmptyState()
        })
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        // totalCount (filtrsiz): filtr 0 qator ko'rsatsa ham "Tizim toza" deb aldamaymiz.
        binding.layoutEmpty.visibility = if (adapter.totalCount == 0) View.VISIBLE else View.GONE
    }

    /**
     * Qator bosilganda YAGONA skan yo'li. UX-08: 30s timeout (avval 5s edi — bujetli
     * telefonlarda katta APK ulgurmasdi). Ilgari startAutoProtection() adapterni o'z
     * 5s-callback'i bilan QAYTA yaratardi va real foydalanishda har doim o'sha qisqa yo'l
     * ishlardi, xatolar esa jim yutilardi (tvCount «Tekshirilmoqda...» da osilib qolardi).
     * Endi adapter qayta yaratilmaydi — ikkala holat ham shu funksiyadan o'tadi.
     */
    private fun scanItem(item: ApkItem) {
        scope.launch {
            try {
                binding.tvCount.text = getString(R.string.scanning)
                binding.btnScan.isEnabled = false

                val result = withTimeout(30000) {
                    withContext(Dispatchers.IO) {
                        try {
                            ApkScanner.scan(this@MainActivity, item.file.absolutePath)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Scan error", e)
                            null
                        }
                    }
                }

                binding.tvCount.text = getString(R.string.apk_count, adapter.totalCount)

                if (result != null) {
                    // v4: real verdiktni qatorda av/tag sifatida ko'rsatamiz + filtr hisoblari.
                    adapter.setVerdict(item.file.absolutePath, result.verdict)
                    updateFilterCounts()
                    startActivity(ScanResultActivity.intent(this@MainActivity, item.file.absolutePath, result))
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.kq4_skaner_err_scan_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: TimeoutCancellationException) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.kq4_skaner_err_scan_timeout),
                    Toast.LENGTH_SHORT
                ).show()
                binding.tvCount.text = getString(R.string.apk_count, adapter.totalCount)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Critical error", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.kq4_skaner_err_generic, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
                binding.tvCount.text = getString(R.string.apk_count, adapter.totalCount)
            } finally {
                binding.btnScan.isEnabled = true
            }
        }
    }

    // ─── Yangiliklar / e'lonlar lentasi (cloud'dan) ──────────────────────────
    // Egasi panelda e'lon yozadi; ilova faqat o'qiydi (x-device-secret). Cloud
    // sozlanmagan, rozilik yo'q yoki lenta bo'sh bo'lsa — bo'lim yashiriladi.
    private fun loadNews() {
        if (!Config.hasUserConsent(this)) {
            binding.newsSection.visibility = View.GONE
            return
        }
        NewsClient.fetch(CloudTelemetry.savedGroupCode(this)) { result ->
            if (isFinishing || isDestroyed) return@fetch
            when (result) {
                is NewsClient.Result.Success -> renderNews(result.items)
                else -> {
                    // NotConfigured / NetworkError — jim yashiramiz (bor lentani buzmaymiz).
                    if (tickerAdapter == null) {
                        binding.newsSection.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderNews(items: List<NewsClient.NewsItem>) {
        if (items.isEmpty()) {
            binding.newsSection.visibility = View.GONE
            stopTicker()
            tickerAdapter = null
            lastNewsSig = null
            binding.newsTicker.adapter = null
            return
        }

        val rv = binding.newsTicker
        if (rv.layoutManager == null) {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.setHasFixedSize(true)
            attachTickerTouchPause(rv)
        }

        // Bir xil e'lonlar bo'lsa — adapterni qayta qurmaymiz (rasm keshi saqlanadi,
        // har onResume'da qayta yuklab miltillamaydi). Faqat lentani qayta yoqamiz.
        val sig = items.joinToString("|") { it.id + "" + it.imageUrl + "" + it.title }
        if (sig == lastNewsSig && tickerAdapter != null) {
            binding.newsSection.visibility = View.VISIBLE
            startTicker(rv)
            return
        }
        lastNewsSig = sig

        val ad = NewsTickerAdapter(items) { openNewsDialog(it) }
        tickerAdapter = ad
        rv.adapter = ad
        // Cheksiz ro'yxat o'rtasidan boshlaymiz — foydalanuvchi ikki tomonga ham sura oladi.
        rv.scrollToPosition(items.size * 1000)
        binding.newsSection.visibility = View.VISIBLE
        startTicker(rv)
    }

    // Lentani uzluksiz suradi (har ~16ms da bir oz). Bir nechta e'lon bo'lsagina harakatlanadi.
    private fun startTicker(rv: RecyclerView) {
        stopTicker()
        tickerPaused = false
        val r = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (!tickerPaused && (tickerAdapter?.realCount ?: 0) > 1) {
                    tickerAccum += tickerStepPx
                    val dx = tickerAccum.toInt()
                    if (dx > 0) {
                        rv.scrollBy(dx, 0)
                        tickerAccum -= dx
                    }
                }
                tickerHandler.postDelayed(this, TICKER_FRAME_MS)
            }
        }
        tickerRunnable = r
        tickerHandler.postDelayed(r, TICKER_FRAME_MS)
    }

    private fun stopTicker() {
        tickerRunnable?.let { tickerHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    // Foydalanuvchi lentaga tegsa — to'xtaymiz; qo'yib yuborgach biroz kutib davom etamiz.
    // false qaytaramiz → kartochka bosilishi (klik) baribir ishlaydi.
    private fun attachTickerTouchPause(rv: RecyclerView) {
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> tickerPaused = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        tickerHandler.postDelayed({ tickerPaused = false }, 1800)
                }
                return false
            }
        })
    }

    // Kartochka bosilganda to'liq e'lonni dialogda ochamiz (rasm + matn).
    private fun openNewsDialog(item: NewsClient.NewsItem) {
        val db = DialogNewsBinding.inflate(layoutInflater)
        db.newsDlgTitle.text = item.title
        db.newsDlgDate.text = shortDate(item.createdAt)
        if (item.body.isNotBlank()) {
            db.newsDlgBody.text = item.body
        } else {
            db.newsDlgBody.visibility = View.GONE
        }

        val (lvlText, lvlColor) = when (item.level) {
            "critical" -> getString(R.string.kq4_skaner_news_level_critical) to R.color.kq_danger
            "warning" -> getString(R.string.kq4_skaner_news_level_warning) to R.color.kq_warn
            else -> getString(R.string.kq4_skaner_news_level_info) to R.color.kq_primary
        }
        db.newsDlgLevel.text = lvlText
        db.newsDlgLevel.setTextColor(ContextCompat.getColor(this, lvlColor))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(db.root)
            .create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        db.newsDlgClose.setOnClickListener { dialog.dismiss() }

        if (item.imageUrl.startsWith("http")) {
            NewsClient.loadImage(item.imageUrl) { bmp ->
                if (bmp != null && !isFinishing && !isDestroyed) {
                    db.newsDlgImage.setImageBitmap(bmp)
                    db.newsDlgImage.visibility = View.VISIBLE
                }
            }
        }
        dialog.show()
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
    
    private fun setupButtons() {
        binding.btnGrant.setOnClickListener { requestStoragePermission() }

        // "Hozir tekshirish" hero CTA — handler bilan birga skanlash chaqiriladi.
        // UX-08: QO'LDA skan endi fon rejimiga BOG'LIQ EMAS. Avval fon o'chiq bo'lsa tugma faqat
        // toast berardi — batareyani tejash uchun fon'ni o'chirgan foydalanuvchi skan tugmasini
        // butunlay yo'qotardi. Qo'lda tekshiruv ruxsat bo'lsa doimo ishlaydi.
        val scanHandler = View.OnClickListener {
            if (hasPermission) {
                // manual_scan telemetriyasi: avval faqat o'lik findAndShowApks() ichida edi,
                // shuning uchun hodisa hech qachon ketmasdi. Endi real skan yo'lida.
                try { TelemetryReporter.reportManualScan(this, "MainActivity → Skaner tugma") } catch (_: Throwable) {}
                startAutoProtection()
            } else {
                Toast.makeText(this, getString(R.string.toast_grant_storage_first), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnScan.setOnClickListener(scanHandler)
        binding.btnRefresh.setOnClickListener(scanHandler)

        binding.switchBackground.isChecked = Config.isBackgroundEnabled(this)
        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            Config.setBackgroundEnabled(this, checked)
            // BG-02: tumbler xizmatni HAQIQATAN boshqaradi (start/stop), faqat Config'ga yozmaydi.
            if (checked) {
                ProtectionService.start(this)
                if (hasPermission) startAutoProtection()
            } else {
                ProtectionService.stop(this)
                try {
                    (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(ProtectionService.NOTIFICATION_ID)
                } catch (_: Throwable) {}
            }
            // v4: live-karta (Avto-himoya yoniq/o'chiq) holatini sinxron yangilaymiz.
            updateLiveCard()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Кнопка Dashboard
        binding.btnDashboard?.setOnClickListener {
            startActivity(Intent(this, DashboardNewActivity::class.java))
        }
        
        binding.btnLang.setOnClickListener {
            try {
                val current = Config.getLanguage(this)
                val next = if (current == "ru") "uz" else "ru"
                Config.setLanguage(this, next)
                recreate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Долгое нажатие на кнопку языка - выбор темы
        binding.btnLang.setOnLongClickListener {
            showThemeDialog()
            true
        }
    }

    // ─── v4 Skaner UI: filtr chiplari + live-karta ───────────────────────────

    /** Chip kliklarini va live-kartani ulaydi (dizayn screens1.jsx → Apps). */
    private fun setupFilters() {
        binding.chipAll.setOnClickListener { applyFilter(ApkAdapter.Filter.ALL) }
        binding.chipBad.setOnClickListener { applyFilter(ApkAdapter.Filter.BAD) }
        binding.chipOk.setOnClickListener { applyFilter(ApkAdapter.Filter.OK) }
        renderChips()
        updateFilterCounts()

        // Live-karta: o'chiq bo'lsa — bosish himoyani yoqadi (switch listener xizmatni
        // ishga tushiradi); yoniq bo'lsa — himoya holati ekraniga olib boradi.
        binding.cardLive.setOnClickListener {
            if (Config.isBackgroundEnabled(this)) {
                startActivity(Intent(this, ProtectionStatusActivity::class.java))
            } else {
                binding.switchBackground.isChecked = true
                updateLiveCard()
            }
        }
    }

    private fun applyFilter(f: ApkAdapter.Filter) {
        currentFilter = f
        adapter.setFilter(f)
        renderChips()
    }

    private fun renderChips() {
        bindChip(binding.chipAll, binding.chipAllLabel, binding.chipAllCount,
            currentFilter == ApkAdapter.Filter.ALL)
        bindChip(binding.chipBad, binding.chipBadLabel, binding.chipBadCount,
            currentFilter == ApkAdapter.Filter.BAD)
        bindChip(binding.chipOk, binding.chipOkLabel, binding.chipOkCount,
            currentFilter == ApkAdapter.Filter.OK)
    }

    /** design .chip / .chip.on: pilyulya fon + matn rangi + beydj foni. */
    private fun bindChip(chip: View, label: TextView, count: TextView, on: Boolean) {
        chip.setBackgroundResource(
            if (on) R.drawable.kq4_skaner_chip_on else R.drawable.kq4_skaner_chip_off
        )
        val c = ContextCompat.getColor(this, if (on) R.color.kq_on_primary else R.color.kq_ink_2)
        label.setTextColor(c)
        count.setTextColor(c)
        count.setBackgroundResource(
            if (on) R.drawable.kq4_skaner_badge_on else R.drawable.kq4_tag_soft
        )
    }

    private fun updateFilterCounts() {
        val c = adapter.counts()
        binding.chipAllCount.text = c.all.toString()
        binding.chipBadCount.text = c.bad.toString()
        binding.chipOkCount.text = c.ok.toString()
    }

    /** Live-karta: Avto-himoya yoniq (safe/Jonli) yoki o'chiq (warn/O'chiq). */
    private fun updateLiveCard() {
        val on = Config.isBackgroundEnabled(this)
        if (on) {
            binding.avLiveBox.setBackgroundResource(R.drawable.kq4_av_safe)
            binding.avLiveIcon.setImageResource(R.drawable.ic4_check_circle)
            binding.avLiveIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.kq_safe))
            binding.tvLiveTitle.text = getString(R.string.kq4_skaner_live_on_title)
            binding.tvLiveSub.text = getString(R.string.kq4_skaner_live_on_sub)
            binding.tagLive.setBackgroundResource(R.drawable.kq4_tag_safe)
            val ink = ContextCompat.getColor(this, R.color.kq_safe_ink)
            binding.tagLiveDot.imageTintList = ColorStateList.valueOf(ink)
            binding.tagLiveText.setTextColor(ink)
            binding.tagLiveText.text = getString(R.string.kq4_live)
        } else {
            binding.avLiveBox.setBackgroundResource(R.drawable.kq4_av_warn)
            binding.avLiveIcon.setImageResource(R.drawable.ic4_alert)
            binding.avLiveIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.kq_warn))
            binding.tvLiveTitle.text = getString(R.string.kq4_skaner_live_off_title)
            binding.tvLiveSub.text = getString(R.string.kq4_skaner_live_off_sub)
            binding.tagLive.setBackgroundResource(R.drawable.kq4_tag_warn)
            val ink = ContextCompat.getColor(this, R.color.kq_warn_ink)
            binding.tagLiveDot.imageTintList = ColorStateList.valueOf(ink)
            binding.tagLiveText.setTextColor(ink)
            binding.tagLiveText.text = getString(R.string.kq4_skaner_live_off_tag)
        }
    }
    
    /**
     * Диалог выбора темы
     */
    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val current = when (Config.getDarkThemeMode(this)) {
            "system" -> 0
            "light" -> 1
            "dark" -> 2
            else -> 0
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.theme_dialog_title))
            .setSingleChoiceItems(themes, current) { dialog, which ->
                val mode = when (which) {
                    0 -> "system"
                    1 -> "light"
                    2 -> "dark"
                    else -> "system"
                }
                Config.setDarkThemeMode(this, mode)
                ThemeHelper.applyTheme(this)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel_generic), null)
            .show()
    }
    
    /**
     * Запуск автоматической защиты
     */
    private fun startAutoProtection() {
        scope.launch(Dispatchers.IO) {
            try {
                // Показываем индикатор загрузки
                withContext(Dispatchers.Main) {
                    binding.loadingLayout.visibility = View.VISIBLE
                    binding.tvCount.visibility = View.GONE
                    binding.btnRefresh.isEnabled = false
                }
                
                // Запускаем FileObserver в главном потоке
                withContext(Dispatchers.Main) {
                    startFileObserver()
                }

                // ENG AVVAL — telefonga O'RNATILGAN ilovalarni tekshiramiz (foydalanuvchi talabi:
                // "birinchi navbatda o'rnatilganlarni skanla"). Fayl-skani (pastda) faqat APK fayllarni
                // topadi; o'rnatilgan zararli ilova esa shu yerda topiladi va DARHOL o'chirishga chiqariladi.
                // Rasmiy do'kon (Play Market) ilovalari o'tkaziladi. Fayl-skani bilan parallel ishlaydi,
                // shuning uchun o'rnatilgan tahdid fayl qidiruvini kutmaydi.
                if (!installedScanRunning) {
                    installedScanRunning = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val threats = ApkScanner.scanInstalledForThreats(applicationContext)
                            val dangers = threats.filter { it.result.verdict == ScanResult.Verdict.DANGER }
                            // YANGI (avval ko'rilmagan) tahdidlar — bildirishnoma + DARHOL o'chirish oynasi.
                            // Ketgan tahdidlar to'plamdan chiqadi (qayta paydo bo'lsa yana ogohlantiriladi).
                            val prefs = getSharedPreferences("uzguard_rescan", android.content.Context.MODE_PRIVATE)
                            val notified = prefs.getStringSet("installed_threat_notified", emptySet()) ?: emptySet()
                            val fresh = dangers.filter { it.pkg !in notified }
                            prefs.edit().putStringSet("installed_threat_notified", dangers.map { it.pkg }.toSet()).apply()
                            if (threats.isEmpty()) return@launch
                            withContext(Dispatchers.Main) {
                                for (t in fresh) {
                                    try {
                                        NotificationHelper.showInstalledDangerNotification(
                                            applicationContext, t.pkg, t.label, t.result
                                        )
                                    } catch (_: Throwable) {}
                                    // DARHOL o'chirish: tizim uninstall oynasini ochamiz. Android boshqa
                                    // ilovani JIMGINA o'chirishga RUXSAT BERMAYDI — foydalanuvchi "O'chirish"ni
                                    // bosishi shart; biz oynani darhol chiqaramiz, faqat bir tap qoladi.
                                    launchUninstall(t.pkg)
                                }
                                val msg = if (dangers.isNotEmpty())
                                    "⚠️ ${dangers.size} ta o'rnatilgan virus — o'chirishga chiqarildi"
                                else
                                    "⚠️ ${threats.size} ta o'rnatilgan ilova shubhali"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("MainActivity", "installed-apps scan error", e)
                        } finally {
                            installedScanRunning = false
                        }
                    }
                }

                // Асинхронно сканируем все существующие APK с таймаутом
                android.util.Log.d("MainActivity", "🔍 Начинаю поиск APK...")
                
                val apks = withTimeout(15000) { // Таймаут 15 секунд
                    try {
                        ApkScanner.findApkFiles(applicationContext)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error finding APK", e)
                        emptyList()
                    }
                }
                
                android.util.Log.d("MainActivity", "✅ Найдено APK: ${apks.size}")
                
                withContext(Dispatchers.Main) {
                    // Ro'yxatni yangilaymiz. Adapterni QAYTA YARATMAYMIZ: avval bu yerda
                    // o'zining 5s-timeout'li callback'i bilan yangi ApkAdapter qurilardi va
                    // setupAdapter'dagi 30s yo'l (UX-08) hech qachon ishlamasdi. updateList
                    // bilan bo'sh-holat observer'i, joriy filtr va yagona scanItem() saqlanadi.
                    adapter.updateList(apks)
                    updateFilterCounts()
                    if (apks.isNotEmpty()) binding.recycler.scheduleLayoutAnimation()
                    binding.tvCount.text = getString(R.string.apk_count, apks.size)
                    
                    // Скрываем индикатор загрузки
                    binding.loadingLayout.visibility = View.GONE
                    binding.tvCount.visibility = View.VISIBLE
                    binding.btnRefresh.isEnabled = true
                    
                    if (apks.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_apk_found_count, apks.size), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_apk_not_found), Toast.LENGTH_SHORT).show()
                    }
                }
                
                // Проверяем каждый APK асинхронно — НО scanGate bilan parallellik cheklangan
                // (yuqoridagi izohga qarang): bir vaqtda ko'pi bilan N ta skan, qolganlari navbatda.
                apks.forEach { apkItem ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = scanGate.withPermit {
                                ApkScanner.scan(applicationContext, apkItem.path)
                            }

                            withContext(Dispatchers.Main) {
                                // v4: real verdikt qatorga (av/tag) va chip hisoblariga tushadi.
                                adapter.setVerdict(apkItem.path, result.verdict)
                                updateFilterCounts()
                                // Если опасный - показываем уведомление и удаляем
                                if (result.verdict == ScanResult.Verdict.DANGER) {
                                    showDangerNotification(apkItem.path, result)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Scan error: ${apkItem.path}", e)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.e("MainActivity", "Timeout searching APK", e)
                withContext(Dispatchers.Main) {
                    binding.loadingLayout.visibility = View.GONE
                    binding.tvCount.text = getString(R.string.apk_count, 0)
                    binding.tvCount.visibility = View.VISIBLE
                    binding.btnRefresh.isEnabled = true
                    Toast.makeText(this@MainActivity, getString(R.string.toast_search_timeout), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Auto protection error", e)
                withContext(Dispatchers.Main) {
                    // Скрываем индикатор загрузки при ошибке
                    binding.loadingLayout.visibility = View.GONE
                    binding.tvCount.text = getString(R.string.apk_count, 0)
                    binding.tvCount.visibility = View.VISIBLE
                    binding.btnRefresh.isEnabled = true
                    Toast.makeText(this@MainActivity, getString(R.string.toast_error_generic, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Zararli O'RNATILGAN ilovani o'chirish — tizim uninstall oynasini DARHOL ochadi.
     * DIQQAT: Android bir ilovaga boshqasini JIMGINA (fonda) o'chirishga ruxsat bermaydi —
     * foydalanuvchi tizim dialogida "O'chirish"ni bosishi shart. Biz shu dialogni bir tapga
     * yaqinlashtiramiz. Skan foreground'da ishlagani uchun Activity ochish ruxsat etilgan.
     */
    private fun launchUninstall(pkg: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "uninstall launch failed for $pkg", e)
        }
    }

    /**
     * Список-skan'da topilgan xavfli fayl haqida xabar berish.
     *
     * Avval startActivity(AutoScanActivity) chaqirardi — natijada bir nechta xavfli APK
     * topilsa, AutoScanActivity'lar stack'da to'planib qolardi: foydalanuvchi orqaga
     * bossa keyingisi chiqib, dasturdan chiqa olmasdi. Endi faqat notification
     * ko'rsatamiz, foydalanuvchi o'zi tap qilib AutoScanActivity'ni ochadi.
     */
    private fun showDangerNotification(apkPath: String, result: ScanResult) {
        try {
            val file = File(apkPath)
            NotificationHelper.showFoundApkNotification(
                applicationContext,
                file,
                result.verdict,
                result.reason
            )
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Notification error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // BG-01: ilova ochilganda real-time himoyani idempotent qaytaramiz. Ruxsat berilgach shu yerda
        // xizmat yoqiladi va birinchi marta "Himoyangiz yoqildi" chiqadi (foreground-start har doim ruxsat).
        ProtectionActivator.activateIfReady(this)
        // Foydalanuvchi tashqi "Barcha fayllarga ruxsat" ekranidan qaytgan bo'lishi
        // mumkin — holatni qayta tekshiramiz va ruxsat ENDIGINA berilgan bo'lsa
        // skanni ishga tushiramiz (scanStarted bilan har resume'da takrorlanmaydi).
        refreshPermissionState()
        if (hasPermission) {
            binding.switchBackground.isChecked = Config.isBackgroundEnabled(this)
            if (!scanStarted) {
                scanStarted = true
                startScanningAfterGrant()
            }
        } else {
            scanStarted = false
        }
        // v4: live-karta holati (Sozlamalardan qaytganda ham to'g'ri ko'rinsin).
        updateLiveCard()
        loadNews()
    }

    override fun onPause() {
        super.onPause()
        // Ekran ko'rinmasa — lentani to'xtatamiz (Handler callback'lari osilib qolmasin).
        stopTicker()
        // attachTickerTouchPause postDelayed bilan qo'ygan anonim lambda'larni ham tozalaymiz.
        tickerHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopFileObserver()
        stopTicker()
        // BARCHA kutilayotgan callback'larni (scroll runnable + touch-pause lambda'lar)
        // o'chiramiz, aks holda ular destroy'dan keyin Activity'ni ushlab turardi.
        tickerHandler.removeCallbacksAndMessages(null)
    }
    
    private fun startFileObserver() {
        // Fon himoyasi yoqilgan bo'lsa (default), real-time kuzatuvchini
        // ProtectionService 24/7 yuritadi — bu yerda takror ishga tushirmaymiz,
        // aks holda bitta yangi APK ikki observer'ga tushib, ikki marta
        // download_detected telemetriya/bildirishnoma yuborardi. MainActivity faqat
        // fon himoyasi O'CHIRILGAN holatda (service yo'q) ekran ochiqligida kuzatadi.
        if (Config.isBackgroundEnabled(this)) {
            multiPathObserver = null
            android.util.Log.d("MainActivity", "Skip MainActivity observer — ProtectionService owns it 24/7")
            return
        }
        try {
            // Используем MultiPathFileObserver для мониторинга всех папок!
            multiPathObserver = MultiPathFileObserver(applicationContext, scope)
            multiPathObserver?.startWatching()
            
            android.util.Log.d("MainActivity", "✅ MultiPathFileObserver started - watching ALL folders!")
            Toast.makeText(this, getString(R.string.toast_observer_started), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ Error starting MultiPathFileObserver", e)
            Toast.makeText(this, getString(R.string.toast_observer_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopFileObserver() {
        try {
            multiPathObserver?.stopWatching()
            multiPathObserver = null
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping FileObserver", e)
        }
    }

    /**
     * Faqat HOLATNI yangilaydi (skan ishga tushirmaydi): skanerga kerakli to'liq
     * fayl kirishimiz bormi. Android 11+ da bu — "Barcha fayllarga ruxsat"
     * (MANAGE_EXTERNAL_STORAGE). Ilgari bu yerda READ_MEDIA_IMAGES tekshirilardi —
     * lekin u faqat RASMLARGA kirish beradi, APK fayllarga emas; natijada Splash'da
     * "Barcha fayllarga ruxsat" berilgan bo'lsa ham Skaner varag'i "ruxsat yo'q"
     * kartasini ko'rsatib, hech narsa topa olmasdi. Endi Splash bilan bir xil,
     * yagona VersionCompat tekshiruvidan foydalanamiz.
     */
    private fun refreshPermissionState() {
        hasPermission = VersionCompat.hasFileScanAccess(this)
        binding.cardPermission.visibility = if (hasPermission) View.GONE else View.VISIBLE
        binding.contentMain.visibility = if (hasPermission) View.VISIBLE else View.GONE
        if (hasPermission) {
            binding.tvCount.text = getString(R.string.apk_count, 0)
        }
    }

    /** Ruxsat berilgach bir marta: fon himoyasi yoqilgan bo'lsa skan, aks holda kuzatuvchi. */
    private fun startScanningAfterGrant() {
        if (Config.isBackgroundEnabled(this)) {
            scope.launch {
                delay(500)
                startAutoProtection()
            }
        } else {
            scope.launch {
                delay(500)
                startFileObserver()
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: APK fayllarni topish/o'qish/o'chirish uchun YAGONA yo'l —
            // "Barcha fayllarga ruxsat" tizim ekrani. READ_MEDIA_* (rasm/video/audio)
            // APK fayllarga kirish bermaydi.
            openAllFilesAccessSettings()
            return
        }
        // Android 10 va pastda — oddiy runtime ruxsat (legacy external storage).
        val perms = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms, 100)
    }

    /** Android 11+ "Barcha fayllarga ruxsat" ekranini ochadi (qaytganda onResume qayta tekshiradi). */
    private fun openAllFilesAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                android.util.Log.e("MainActivity", "all-files settings intent failed", e2)
                Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            refreshPermissionState()
            if (hasPermission) {
                if (!scanStarted) {
                    scanStarted = true
                    startScanningAfterGrant()
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Показать диалог при первом запуске
     */
    private fun showFirstRunDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.first_run_title))
                .setMessage(getString(R.string.first_run_message))
                .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    Config.setFirstRunComplete(this)
                }
                .setNegativeButton(getString(R.string.btn_later)) { _, _ ->
                    Config.setFirstRunComplete(this)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Ошибка показа диалога", e)
        }
    }

    companion object {
        // Yangiliklar lentasi kadr oralig'i (ms). 32ms ≈ 30fps — sokin marquee uchun
        // yetarli, eski 16ms (60fps) ga nisbatan UI-thread ishini ikki barobar kamaytiradi.
        private const val TICKER_FRAME_MS = 32L
    }
}
