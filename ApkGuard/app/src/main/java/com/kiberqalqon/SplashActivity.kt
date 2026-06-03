package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kiberqalqon.databinding.ActivitySplashBinding

/**
 * Splash Screen с логотипом и запросом разрешений
 */
class SplashActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySplashBinding
    private val PERMISSION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101

    // Joylashuvni shu sessiyada so'radikmi (loop bo'lmasligi uchun). PERSIST QILMAYMIZ:
    // ruxsat berilmaган bo'lsa, keyingi ishga tushishda QAYTA so'raymiz — aks holda bir
    // marta o'tkazib yuborilsa xaritada qurilma umuman ko'rinmay qoladi.
    private var locationAskedThisSession = false

    // Batareya optimizatsiyasi dialogini shu sessiyada so'radikmi. Rad etilsa (yoki tizim
    // ekranidan ozod qilmasdan qaytsa) QAYTA ko'rsatmaymiz — aks holda checkPermissions
    // har safar shu bosqichga qaytib, dialog cheksiz takrorlanardi va foydalanuvchi
    // keyingi bosqichlarga (joylashuv / bildirishnoma / Dashboard) umuman o'ta olmasdi.
    private var batteryAskedThisSession = false

    // Marshrutga FAQAT bir marta o'tamiz. Android 13+ da bildirishnoma ruxsati dialogidan
    // keyin ham onResume (firstResume'dan keyingi kelish), ham onRequestPermissionsResult
    // oqimni davom ettirishga urinadi — guardsiz goToMainActivity ikki marta chaqirilib,
    // back-stack'da kirish ekranining IKKI nusxasi paydo bo'lardi.
    private var routed = false
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySplashBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Mono eyebrow takes the form "TELEFON HIMOYASI · V {versionName}"
            // (matches the design's Splash subtitle exactly).
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { null } ?: "7.5"
            binding.tvSubtitle.text = getString(R.string.splash_subtitle, versionName)
            binding.tvVersion.text = getString(R.string.splash_version, versionName)

            // Long-press on the (hidden) version pill still opens diagnostics so the
            // back-door survives the redesign. Wired on the eyebrow too because the
            // hidden tvVersion can no longer be tapped.
            val diagOpener = View.OnLongClickListener {
                try {
                    startActivity(Intent(this, DiagnosticsActivity::class.java))
                } catch (e: Exception) {
                    android.util.Log.e("SplashActivity", "Diag failed", e)
                }
                true
            }
            binding.tvVersion.setOnLongClickListener(diagOpener)
            binding.tvSubtitle.setOnLongClickListener(diagOpener)

            // Fade-in: shield → wordmark → eyebrow. Mirrors the design's `fade-in`
            // entrance (CSS @keyframes fadeIn, 280ms).
            safeAnim { AnimationHelper.fadeIn(binding.ivLogo, duration = 800) }
            safeAnim { AnimationHelper.fadeIn(binding.tvAppName, duration = 800, delay = 300) }
            safeAnim { AnimationHelper.fadeIn(binding.tvSubtitle, duration = 800, delay = 600) }

            // Two pulsing rings around the shield (second one offset by 700ms per design).
            safeAnim {
                val pulse = android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.kq_pulse_ring)
                binding.pulseRing1.startAnimation(pulse)
            }
            binding.pulseRing2.postDelayed({
                safeAnim {
                    val pulse = android.view.animation.AnimationUtils
                        .loadAnimation(this, R.anim.kq_pulse_ring)
                    binding.pulseRing2.startAnimation(pulse)
                }
            }, 700)

            // Three load dots, staggered by 150ms per design (`animationDelay: i*0.15s`).
            val dots = listOf(binding.loadDot1, binding.loadDot2, binding.loadDot3)
            dots.forEachIndexed { i, dot ->
                dot.postDelayed({
                    safeAnim {
                        val a = android.view.animation.AnimationUtils
                            .loadAnimation(this, R.anim.kq_load_dot)
                        dot.startAnimation(a)
                    }
                }, (i * 150).toLong())
            }

            // Kill-eslatma bildirishnomasidan kelgan bo'lsa, dialog'ni qayta ochishga
            // ruxsat berish uchun "wasShown" bayrog'ini olib tashlaymiz.
            if (intent?.getBooleanExtra("show_oem_guide", false) == true) {
                OemAutostartGuide.setDismissed(this, false)
                val prefs = getSharedPreferences("kiberqalqon_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("oem_guide_shown_v1", false).apply()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    try {
                        checkPermissions()
                    } catch (e: Throwable) {
                        android.util.Log.e("SplashActivity", "checkPermissions crashed", e)
                        try { goToMainActivity() } catch (_: Throwable) { finish() }
                    }
                }
            }, 1500)
        } catch (e: Throwable) {
            android.util.Log.e("SplashActivity", "onCreate crashed", e)
            try { goToMainActivity() } catch (_: Throwable) { finish() }
        }
    }

    private inline fun safeAnim(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            android.util.Log.w("SplashActivity", "anim failed", e)
        }
    }
    
    private fun checkPermissions() {
        // Tartib: storage → overlay → OEM overlay → battery → notifications → OEM autostart → asosiy.
        // Har bir bosqich rad etilsa keyingisiga o'tamiz, lekin foydalanuvchini
        // ogohlantirib qo'yamiz — har bir ruxsat aniq bir muammoni hal qiladi:
        //   storage      → APK fayllarni topish
        //   overlay      → Android standart "display over other apps"
        //   OEM overlay  → MIUI "Display popup in background" + "Lock screen display"
        //   battery      → standart Android battery optimization
        //   notifs       → Android 13+ bildirishnoma
        //   OEM autostart→ MIUI Security Center → Autostart yoqish
        when {
            !hasStoragePermission() -> requestStoragePermission()
            !hasOverlayPermission() -> requestOverlayPermission()
            shouldPromptOemOverlay() -> maybePromptOemOverlay()
            shouldRequestBattery() -> requestBatteryOptimization()
            shouldRequestLocation() -> requestLocationPermission()
            !hasNotificationPermission() -> requestNotificationPermission()
            shouldShowOemGuide() -> showOemAutostartGuide()
            else -> goToMainActivity()
        }
    }

    private fun shouldPromptOemOverlay(): Boolean {
        val oem = OemAutostartGuide.detect()
        if (!OemAutostartGuide.needsExtraOverlayPermissions(oem)) return false
        val prefs = getSharedPreferences("kiberqalqon_prefs", Context.MODE_PRIVATE)
        return !prefs.getBoolean("oem_overlay_prompted_v1", false)
    }

    /**
     * Xiaomi/Huawei/Oppo/Vivo/Samsung qurilmalarida hali OEM-specific autostart
     * sozlamasi ochilmagan bo'lsa, foydalanuvchiga ko'rsatamiz. Standart Pixel/
     * Nokia/Sony uchun bu bosqich o'tkazib yuboriladi.
     */
    private fun shouldShowOemGuide(): Boolean {
        val oem = OemAutostartGuide.detect()
        if (!OemAutostartGuide.hasOemRestrictions(oem)) return false
        if (OemAutostartGuide.wasShown(this)) return false
        if (OemAutostartGuide.isDismissed(this)) return false
        return true
    }

    private fun showOemAutostartGuide() {
        val oem = OemAutostartGuide.detect()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_oem_extra_setting_title, oem.displayName))
            .setMessage(
                getString(R.string.splash_oem_guide_message) + "\n\n" +
                OemAutostartGuide.instructions(oem)
            )
            .setPositiveButton(getString(R.string.splash_open_setting)) { _, _ ->
                OemAutostartGuide.markShown(this)
                OemAutostartGuide.openAutostartSettings(this, oem)
                // Foydalanuvchi qaytib kelganida onResume'da goToMainActivity
                // chaqiramiz — markShown qo'yganmiz, ikkinchi marta dialog
                // chiqmaydi.
            }
            .setNegativeButton(getString(R.string.splash_later)) { _, _ ->
                OemAutostartGuide.markShown(this)
                checkPermissions()
            }
            .setNeutralButton(getString(R.string.splash_dont_show)) { _, _ ->
                OemAutostartGuide.setDismissed(this, true)
                checkPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun hasBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    /**
     * Batareya optimizatsiyasidan ozod qilishni so'raymizmi. Bir marta so'ralgach
     * (rad etilsa ham) shu sessiyada QAYTA so'ramaymiz — aks holda checkPermissions
     * shu bosqichga qaytib, dialog cheksiz takrorlanardi. Keyingi ishga tushirishda
     * (hali ozod qilinmagan bo'lsa) yana bir marta so'raladi.
     */
    private fun shouldRequestBattery(): Boolean =
        !hasBatteryOptimizationIgnored() && !batteryAskedThisSession

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Joylashuv ruxsatini FAQAT "Jamoatchilik xavfsizligi" opt-in yoqilgan bo'lsa so'raymiz
     * (CloudTelemetry shu bilan darvozalangan — opt-in bermagan foydalanuvchini bezovta qilmaymiz).
     * Bir marta so'ralgach (rad etilsa ham) qayta so'ramaymiz — checkPermissions sikliga tushmasin.
     */
    private fun shouldRequestLocation(): Boolean {
        if (!Config.hasCommunityShareConsent(this)) return false
        if (DeviceLocation.hasPermission(this)) return false
        // Sessiya ichida bir marta — lekin har yangi ishga tushishda qayta so'raladi
        // (ruxsat hali berilmagan bo'lsa), shunda foydalanuvchi qo'lda Sozlamalarga kirmaydi.
        return !locationAskedThisSession
    }

    private fun requestLocationPermission() {
        // Faqat shu sessiya uchun belgilaymiz (loop bo'lmasin) — keyingi runda qayta so'raladi.
        locationAskedThisSession = true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_location_title))
            .setMessage(getString(R.string.splash_location_message))
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    104
                )
            }
            .setNegativeButton(getString(R.string.btn_skip)) { _, _ -> checkPermissions() }
            .setCancelable(false)
            .show()
    }

    private fun requestBatteryOptimization() {
        // Shu sessiyada so'radik — rad etilsa ham qayta ko'rsatmaymiz (loop bo'lmasin).
        batteryAskedThisSession = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            checkPermissions()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_battery_title))
            .setMessage(getString(R.string.splash_battery_message))
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, 102)
                } catch (e: Throwable) {
                    android.util.Log.w("SplashActivity", "battery opt intent failed", e)
                    checkPermissions()
                }
            }
            .setNegativeButton(getString(R.string.btn_skip)) { _, _ -> checkPermissions() }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            goToMainActivity()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            103
        )
    }
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - запрашиваем MANAGE_EXTERNAL_STORAGE
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.splash_storage_title))
                .setMessage(getString(R.string.splash_storage_message))
                .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivityForResult(intent, PERMISSION_REQUEST_CODE)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, PERMISSION_REQUEST_CODE)
                    }
                }
                .setCancelable(false)
                .show()
        } else {
            // Android 10 и ниже - обычные разрешения.
            // ВАЖНО: WRITE_EXTERNAL_STORAGE надо просить ОТДЕЛЬНО — с API 26+ Android
            // больше не авто-гранитит весь permission group. Без него file.delete()
            // молча возвращает false и юзер видит "Faylni o'chirib bo'lmadi".
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }
    
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            goToMainActivity()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_overlay_title))
            .setMessage(getString(R.string.splash_overlay_message))
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                } catch (e: Throwable) {
                    // Ba'zi qurilmalarda (eski Huawei, custom ROM) ACTION_MANAGE_OVERLAY_PERMISSION
                    // umuman yo'q yoki crash beradi. Bu holatda OEM permissions editorga
                    // tushib qolamiz.
                    android.util.Log.w("SplashActivity", "overlay intent failed", e)
                    val oem = OemAutostartGuide.detect()
                    if (OemAutostartGuide.needsExtraOverlayPermissions(oem)) {
                        OemAutostartGuide.openOemAppPermissions(this, oem)
                    } else {
                        // Standart Android'da intent yo'q — full-screen Activity'ga
                        // tayanamiz, davom etamiz.
                        checkPermissions()
                    }
                }
            }
            .setNeutralButton(getString(R.string.btn_skip)) { _, _ ->
                // Foydalanuvchi rad etsa — full-screen intent notification orqali
                // AutoScanActivity baribir ochiladi (overlay shart emas).
                checkPermissions()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Standart Settings.canDrawOverlays olingandan keyin chaqiriladi —
     * MIUI/EMUI/ColorOS'ning qo'shimcha "background popup" va "lock screen
     * display" toggle'lari haqida foydalanuvchini ogohlantiramiz va kerakli
     * ekranni ochamiz. Faqat OEM cheklovi bor qurilmalarda chaqiriladi.
     */
    private fun maybePromptOemOverlay() {
        val oem = OemAutostartGuide.detect()
        if (!OemAutostartGuide.needsExtraOverlayPermissions(oem)) {
            checkPermissions()
            return
        }
        // Bir martagina ko'rsatamiz — keyingi runlarda chiqib bezovta qilmaydi.
        val prefs = getSharedPreferences("kiberqalqon_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("oem_overlay_prompted_v1", false)) {
            checkPermissions()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.splash_oem_overlay_title, oem.displayName))
            .setMessage(
                getString(R.string.splash_oem_overlay_message) + "\n\n" +
                OemAutostartGuide.overlayInstructions(oem)
            )
            .setPositiveButton(getString(R.string.splash_open_setting)) { _, _ ->
                prefs.edit().putBoolean("oem_overlay_prompted_v1", true).apply()
                OemAutostartGuide.openOemAppPermissions(this, oem)
            }
            .setNegativeButton(getString(R.string.splash_later)) { _, _ ->
                prefs.edit().putBoolean("oem_overlay_prompted_v1", true).apply()
                checkPermissions()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Разрешение получено - проверяем overlay
                checkPermissions()
            } else {
                // Разрешение не получено - показываем объяснение
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.permission_needed))
                    .setMessage(getString(R.string.splash_storage_denied_message))
                    .setPositiveButton(getString(R.string.splash_retry)) { _, _ ->
                        requestStoragePermission()
                    }
                    .setNegativeButton(getString(R.string.btn_exit)) { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        } else if (requestCode == 104) {
            // Joylashuv — berilsa GPS yuboriladi, rad etilsa server IP'dan taxminlaydi.
            // Ikki holda ham oqimni davom ettiramiz (keyingi bosqich — bildirishnoma).
            checkPermissions()
        } else if (requestCode == 103) {
            // POST_NOTIFICATIONS — rad etilsa ham davom etamiz, faqat bildirishnoma ishlamaydi.
            // Foydalanuvchi keyinroq Settings'dan o'zi yoqishi mumkin. 104 branch'i kabi
            // checkPermissions'ga qaytamiz (to'g'ridan-to'g'ri goToMainActivity emas) — shunda
            // keyingi bosqich (OEM guide) ham o'tkazib yuborilmaydi va marshrut yagona joydan.
            checkPermissions()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (hasStoragePermission()) {
                    checkPermissions()
                } else {
                    requestStoragePermission()
                }
            }
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                // Overlay rad etilsa ham davom etamiz — popup ishlamasdi, lekin
                // notification fallback orqali foydalanuvchi baribir ogohlantirish oladi.
                checkPermissions()
            }
            102 -> {
                // Battery optimization — rad etilsa ham davom etamiz, lekin worker tezroq o'ldiriladi.
                checkPermissions()
            }
        }
    }

    /** Birinchi onResume — fade-in animatsiyasi paytida true. */
    private var firstResume = true

    override fun onResume() {
        super.onResume()
        // Dastlabki onResume — onCreate keyin keladi, u yerda 1.5s delay bilan
        // checkPermissions'ni planlashtirganmiz. Ikkinchi kelishida (foydalanuvchi
        // tashqi sozlamalar ekranidan qaytsa) checkPermissions'ni qayta chaqiramiz
        // — barcha bayroqlar prefs'ga yozilgan, takror dialog chiqmaydi va flow
        // davom etadi.
        if (firstResume) {
            firstResume = false
            return
        }
        if (!isFinishing && !isDestroyed) {
            try {
                checkPermissions()
            } catch (e: Throwable) {
                android.util.Log.e("SplashActivity", "onResume checkPermissions", e)
            }
        }
    }
    
    private fun goToMainActivity() {
        // Idempotent: bir martadan ortiq marshrutlamaymiz (yuqoridagi `routed` izohiga qarang).
        if (routed || isFinishing) return
        routed = true
        // Маршрут запуска (после редизайна §3):
        //   1. Если юзер ещё не дал согласие на ToS+Privacy → ConsentActivity
        //   2. Если согласие есть + первый запуск → OnboardingActivity
        //   3. Если onboarding пройден но ещё не было первичного скана → InitialScanActivity
        //   4. Иначе → DashboardNewActivity
        val target = when {
            !Config.hasUserConsent(this) -> ConsentActivity::class.java
            Config.isFirstRun(this) -> OnboardingActivity::class.java
            !Config.isInitialScanDone(this) -> InitialScanActivity::class.java
            // Himoya holati ekrani — barcha ruxsat/sozlama yoqilganini bir joyda ko'rsatadi.
            // Bir marta "Davom etish" bosilgach qayta majburlanmaydi (Config.isProtectionAcked).
            !Config.isProtectionAcked(this) -> ProtectionStatusActivity::class.java
            // MAJBURIY ruxsatlardan birortasi keyinchalik o'chirilgan bo'lsa — Dashboard'ga
            // o'tkazmaymiz, qaytadan "Himoya holati" shlagbaumiga yo'naltiramiz. Ruxsatsiz
            // ilova ishlamaydi (fon kuzatuvi / o'chirish / ogohlantirish oynasi ishlamaydi).
            !ProtectionStatusActivity.allCriticalPermissionsGranted(this) ->
                ProtectionStatusActivity::class.java
            else -> DashboardNewActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
