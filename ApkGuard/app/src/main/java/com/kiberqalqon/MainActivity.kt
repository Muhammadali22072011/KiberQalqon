/*
 *  #### #  # #### #  #    #  # #### #  #     ← END (asosiy ekran / UI)
 *  #    #  # #    # #     #  # #  # #  #
 *  ###  #  # #    ##      #### #  # #  #
 *  #    #  # #    # #       #  #  # #  #
 *  #    #### #### #  #      #  #### ####
 *  Bu kod Muhammadaliniki. O'g'irlama. — KiberQalqon
 */
package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.kiberqalqon.databinding.ActivityMainBinding
import com.kiberqalqon.databinding.DialogNewsBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ApkAdapter
    private var hasPermission = false
    // Ruxsat berilgach skan/kuzatuvchini bir martagina ishga tushiramiz —
    // har onResume'da (masalan, sozlamalardan qaytganda) takror skan bo'lmasligi uchun.
    private var scanStarted = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var multiPathObserver: MultiPathFileObserver? = null

    // Beruvchi lenta (news ticker) holati.
    private var tickerAdapter: NewsTickerAdapter? = null
    private var lastNewsSig: String? = null
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var tickerPaused = false
    private var tickerAccum = 0f
    // Kadrlararo siljish (~0.7dp/16ms ≈ 44dp/s) — sokin, o'qish mumkin bo'lgan tezlik.
    private val tickerStepPx by lazy { (resources.displayMetrics.density * 0.7f).coerceAtLeast(1f) }

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
            refreshPermissionState()

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
        adapter = ApkAdapter(emptyList()) { item ->
            scope.launch {
                try {
                    binding.tvCount.text = getString(R.string.scanning)
                    binding.btnScan.isEnabled = false
                    
                    val result = withTimeout(5000) { // Таймаут 5 секунд на сканирование
                        withContext(Dispatchers.IO) {
                            try {
                                ApkScanner.scan(this@MainActivity, item.file.absolutePath)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Scan error", e)
                                null
                            }
                        }
                    }
                    
                    binding.tvCount.text = getString(R.string.apk_count, adapter.itemCount)
                    
                    if (result != null) {
                        startActivity(ScanResultActivity.intent(this@MainActivity, item.file.absolutePath, result))
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Faylni tekshirib bo'lmadi",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: TimeoutCancellationException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tekshirish juda ko'p vaqt oldi",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.tvCount.text = getString(R.string.apk_count, adapter.itemCount)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Critical error", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Xatolik: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.tvCount.text = getString(R.string.apk_count, adapter.itemCount)
                } finally {
                    binding.btnScan.isEnabled = true
                }
            }
        }
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
        binding.layoutEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    // ─── Yangiliklar / e'lonlar lentasi (cloud'dan) ──────────────────────────
    // Egasi panelda e'lon yozadi; ilova faqat o'qiydi (x-device-secret). Cloud
    // sozlanmagan, rozilik yo'q yoki lenta bo'sh bo'lsa — bo'lim yashiriladi.
    private fun loadNews() {
        if (!Config.hasUserConsent(this)) {
            binding.newsSection.visibility = View.GONE
            return
        }
        NewsClient.fetch { result ->
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
                tickerHandler.postDelayed(this, 16)
            }
        }
        tickerRunnable = r
        tickerHandler.postDelayed(r, 16)
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
            "critical" -> "Muhim" to R.color.kq_danger
            "warning" -> "Ogohlantirish" to R.color.kq_warn
            else -> "E'lon" to R.color.kq_primary
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
        val scanHandler = View.OnClickListener {
            if (hasPermission && Config.isBackgroundEnabled(this)) {
                startAutoProtection()
            } else if (!hasPermission) {
                Toast.makeText(this, getString(R.string.toast_grant_storage_first), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_enable_background), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnScan.setOnClickListener(scanHandler)
        binding.btnRefresh.setOnClickListener(scanHandler)

        binding.switchBackground.isChecked = Config.isBackgroundEnabled(this)
        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            Config.setBackgroundEnabled(this, checked)
            
            // При включении - сразу запускаем автоматическое сканирование
            if (checked && hasPermission) {
                startAutoProtection()
            }
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
                    // Обновляем список
                    adapter = ApkAdapter(apks) { item ->
                        scope.launch {
                            try {
                                binding.tvCount.text = getString(R.string.scanning)
                                
                                val result = withTimeout(5000) {
                                    withContext(Dispatchers.IO) {
                                        ApkScanner.scan(this@MainActivity, item.file.absolutePath)
                                    }
                                }
                                
                                binding.tvCount.text = getString(R.string.apk_count, adapter.itemCount)
                                
                                if (result != null) {
                                    startActivity(ScanResultActivity.intent(this@MainActivity, item.file.absolutePath, result))
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Scan error", e)
                            }
                        }
                    }
                    binding.recycler.adapter = adapter
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
                
                // Проверяем каждый APK асинхронно
                apks.forEach { apkItem ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = ApkScanner.scan(applicationContext, apkItem.path)
                            
                            // Если опасный - показываем уведомление и удаляем
                            if (result.verdict == ScanResult.Verdict.DANGER) {
                                withContext(Dispatchers.Main) {
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

    private fun findAndShowApks() {
        if (!hasPermission) return

        try { TelemetryReporter.reportManualScan(this, "MainActivity → Skanlash tugma") } catch (_: Throwable) {}

        scope.launch {
            try {
                // Показываем индикатор загрузки
                withContext(Dispatchers.Main) {
                    binding.tvCount.text = getString(R.string.scanning)
                    binding.btnScan.isEnabled = false
                }
                
                val list = withTimeout(3000) { // Таймаут 3 секунды
                    withContext(Dispatchers.IO) {
                        try {
                            ApkScanner.findApkFiles(this@MainActivity)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error finding APKs", e)
                            emptyList()
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    adapter.updateList(list)
                    // Ro'yxat kelganda satrlar ketma-ket (stagger) suriladi.
                    if (list.isNotEmpty()) binding.recycler.scheduleLayoutAnimation()
                    binding.tvCount.text = getString(R.string.apk_count, list.size)

                    if (list.isEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Downloads papkasida APK fayllar topilmadi",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    binding.tvCount.text = getString(R.string.apk_count, 0)
                    Toast.makeText(
                        this@MainActivity,
                        "Qidiruv juda ko'p vaqt oldi",
                        Toast.LENGTH_SHORT
                    ).show()
                    adapter.updateList(emptyList())
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Critical error", e)
                withContext(Dispatchers.Main) {
                    binding.tvCount.text = getString(R.string.apk_count, 0)
                    Toast.makeText(
                        this@MainActivity,
                        "Xatolik: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    adapter.updateList(emptyList())
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnScan.isEnabled = true
                }
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
}
