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
 * Splash Screen с логотипом и запросом разрешений.
 *
 * RUXSATLAR (just-in-time): Splash FAQAT fayllarga kirishni so'raydi (boshlang'ich skan
 * uchun shart). Qolgan barcha ruxsatlar (overlay, bildirishnoma, batareya, joylashuv,
 * OEM autostart) BITTA ekranda — `ProtectionStatusActivity` ro'yxatida — yoqiladi.
 * Ilgarigi 7 bosqichli "marafon" shu sababdan olib tashlandi.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val PERMISSION_REQUEST_CODE = 100

    // Marshrutga FAQAT bir marta o'tamiz. onResume (firstResume'dan keyingi kelish) ham,
    // onRequestPermissionsResult ham oqimni davom ettirishga urinadi — guardsiz
    // goToMainActivity ikki marta chaqirilib, back-stack'da kirish ekranining IKKI nusxasi
    // paydo bo'lardi.
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
        // YANGI (just-in-time): Splash'da FAQAT eng zarur ruxsat — fayllarga kirish —
        // so'raladi (usiz boshlang'ich skan umuman ishlamaydi). Qolgan hammasi
        // (overlay, bildirishnoma, batareya, JOYLASHUV, OEM autostart) BITTA ekranda —
        // ProtectionStatusActivity ro'yxatida — yoqiladi. Shu bilan ilk ishga tushishda
        // ilgarigi 7 ta tizim ekrani orqali "marafon" yo'qoladi va dublikat so'rovlar ketadi
        // (o'sha ruxsatlar keyin yana ro'yxatda so'ralardi).
        //
        // Joylashuv (geolokatsiya) SAQLANADI — u o'sha ro'yxatda IXTIYORIY qator sifatida
        // (hududdagi tahdidlar xaritasi uchun) so'raladi; majburiy emas.
        if (!hasStoragePermission()) {
            requestStoragePermission()
        } else {
            goToMainActivity()
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Ruxsat berildi — marshrutni davom ettiramiz.
                checkPermissions()
            } else {
                // Ruxsat berilmadi — tushuntirib, qayta urinish / chiqish taklif qilamiz.
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
        }
    }

    /** Birinchi onResume — fade-in animatsiyasi paytida true. */
    private var firstResume = true

    override fun onResume() {
        super.onResume()
        // Dastlabki onResume — onCreate keyin keladi, u yerda 1.5s delay bilan
        // checkPermissions'ni planlashtirganmiz. Ikkinchi kelishida (foydalanuvchi
        // tashqi sozlamalar ekranidan qaytsa) checkPermissions'ni qayta chaqiramiz
        // — takror dialog chiqmaydi va flow davom etadi.
        if (firstResume) {
            firstResume = false
            return
        }
        if (!isFinishing && !isDestroyed) {
            // Foydalanuvchi "Barcha fayllarga ruxsat" ekranidan ruxsat berib qaytgan bo'lishi mumkin —
            // shu paytda himoyani yoqamiz va birinchi marta "Himoyangiz yoqildi" chiqaramiz (ruxsatdan
            // OLDIN emas — endi haqiqiy). Ruxsat hali yo'q bo'lsa activateIfReady no-op.
            try { ProtectionActivator.activateIfReady(this) } catch (_: Throwable) {}
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
            // MAJBURIY TIZIM ruxsatlaridan birortasi keyinchalik o'chirilgan bo'lsa — Dashboard'ga
            // o'tkazmaymiz, qaytadan "Himoya holati" shlagbaumiga yo'naltiramiz. Ruxsatsiz
            // ilova ishlamaydi (fon kuzatuvi / o'chirish / ogohlantirish oynasi ishlamaydi).
            //
            // DIQQAT: bu yerda ataylab `criticalSystemPermissionsGranted` — `allCritical…` EMAS.
            // Ikkinchisiga `Config.isBackgroundEnabled` tumbleri ham kiradi, va uni o'chirgan
            // foydalanuvchi ilovaga umuman kira olmay qolardi (izohga qarang).
            !ProtectionStatusActivity.criticalSystemPermissionsGranted(this) ->
                ProtectionStatusActivity::class.java
            else -> DashboardNewActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
