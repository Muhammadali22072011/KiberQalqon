package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.*
import com.kiberqalqon.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ApkAdapter
    private var hasPermission = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var multiPathObserver: MultiPathFileObserver? = null

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
            checkPermission()

            // Единая нижняя нав — активна вкладка Skaner.
            KqBottomNav.attach(this, KqBottomNav.Tab.SCAN)

            // Запускаем периодическую проверку через WorkManager
            startPeriodicCheck()
            
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
        if (hasPermission) {
            binding.switchBackground.isChecked = Config.isBackgroundEnabled(this)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopFileObserver()
    }
    
    private fun startFileObserver() {
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

    private fun checkPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        hasPermission = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        
        binding.cardPermission.visibility = if (hasPermission) View.GONE else View.VISIBLE
        binding.contentMain.visibility = if (hasPermission) View.VISIBLE else View.GONE
        
        if (hasPermission) {
            // Показываем пустой список
            binding.tvCount.text = getString(R.string.apk_count, 0)
            
            // Если фоновая защита включена - сразу ищем APK
            if (Config.isBackgroundEnabled(this)) {
                scope.launch {
                    delay(500)
                    startAutoProtection()
                }
            } else {
                // Запускаем только FileObserver
                scope.launch {
                    delay(500)
                    startFileObserver()
                }
            }
        }
    }

    private fun requestStoragePermission() {
        // Android 13+ uchun POST_NOTIFICATIONS runtime'da so'ralishi shart, aks holda
        // popup chiqmaganida bildirishnoma ham chiqmaydi — foydalanuvchi virus borligini bilmaydi.
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // POST_NOTIFICATIONS ixtiyoriy — agar foydalanuvchi rad etsa ham storage ruxsati borligi yetadi.
            // Faqat asosiy storage ruxsatlar grant bo'lganini tekshiramiz.
            val storageGranted = permissions.indices.all { i ->
                val perm = permissions[i]
                if (perm == Manifest.permission.POST_NOTIFICATIONS) true
                else grantResults[i] == PackageManager.PERMISSION_GRANTED
            }
            if (storageGranted) {
                hasPermission = true
                binding.cardPermission.visibility = View.GONE
                binding.contentMain.visibility = View.VISIBLE
                
                // Показываем пустой список
                binding.tvCount.text = getString(R.string.apk_count, 0)
                
                // Если фоновая защита включена - сразу ищем APK
                if (Config.isBackgroundEnabled(this)) {
                    scope.launch {
                        delay(500)
                        startAutoProtection()
                    }
                } else {
                    // Запускаем только FileObserver
                    scope.launch {
                        delay(500)
                        startFileObserver()
                    }
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
     * Запуск периодической проверки через WorkManager
     */
    private fun startPeriodicCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true) // Только если батарея не низкая
                .build()
            
            val periodicWork = PeriodicWorkRequestBuilder<PeriodicCheckWorker>(
                15, TimeUnit.MINUTES // Каждые 15 минут
            )
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "periodic_apk_check",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            
            android.util.Log.d("MainActivity", "✅ Периодическая проверка запущена (каждые 15 минут)")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Ошибка запуска WorkManager", e)
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
