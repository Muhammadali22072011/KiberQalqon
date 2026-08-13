package com.uzguard

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
import com.uzguard.databinding.ActivitySplashBinding

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
            // v4: gradient fon ?attr/kqPrimary/2 dan quriladi — foydalanuvchi tanlagan
            // aksent splash'da ham to'g'ri ko'rinishi uchun temani inflate'dan OLDIN qo'yamiz.
            try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
            binding = ActivitySplashBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // v4 dizayn: mono tagline endi doimiy "MILLIY KIBER HIMOYA" (layoutda).
            // Versiya faqat yashirin tvVersion'da qoladi (long-press diagnostika).
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { null } ?: "7.5"
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

            playEntrance()

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    try {
                        // Ruxsat allaqachon bor bo'lsa — chiroyli chiqish animatsiyasi
                        // bilan marshrutlaymiz; bo'lmasa dialogni animatsiyasiz ko'rsatamiz.
                        if (hasStoragePermission()) playExitThenRoute() else checkPermissions()
                    } catch (e: Throwable) {
                        android.util.Log.e("SplashActivity", "checkPermissions crashed", e)
                        try { goToMainActivity() } catch (_: Throwable) { finish() }
                    }
                }
            }, 2000)
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

    /**
     * v4 «Milliy Kiber Himoya» kirish xoreografiyasi (screens1.jsx → Splash):
     *   0ms    LogoDisc yumshoq overshoot bilan kiradi (scale .8 → 1)
     *   0ms    pulsar halqa №1 (scale+alpha, cheksiz); №2 +800ms (dizayn: delay .8s)
     *   250ms  "UZGUARD" wordmark pastdan ko'tariladi
     *   450ms  mono "MILLIY KIBER HIMOYA" pastdan ko'tariladi
     *   600ms  3 ta load-nuqta (stagger 160ms — dizayn: i*.16s)
     */
    private fun playEntrance() {
        // LogoDisc: yumshoq bounce-in (dizaynning fade'iga yaqin, biroz jonliroq).
        safeAnim {
            binding.logoDisc.alpha = 0f
            binding.logoDisc.scaleX = 0.8f
            binding.logoDisc.scaleY = 0.8f
            binding.logoDisc.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(600)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }

        // Pulsar halqalar (ikkinchisi 800ms kechikish bilan — dizayndagidek).
        safeAnim {
            binding.pulseRing1.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.kq_pulse_ring)
            )
        }
        binding.pulseRing2.postDelayed({
            safeAnim {
                binding.pulseRing2.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.kq_pulse_ring)
                )
            }
        }, 800)

        // Wordmark: pastdan yumshoq ko'tarilib kiradi.
        safeAnim {
            binding.tvAppName.translationY = 24f
            binding.tvAppName.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(250).setDuration(550)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // Mono tagline: biroz keyinroq.
        safeAnim {
            binding.tvSubtitle.translationY = 16f
            binding.tvSubtitle.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(450).setDuration(550)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // Load-nuqtalar (stagger 160ms — dizayn: animationDelay i*.16s).
        val dots = listOf(binding.loadDot1, binding.loadDot2, binding.loadDot3)
        dots.forEachIndexed { i, dot ->
            dot.postDelayed({
                safeAnim {
                    dot.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(this, R.anim.kq_load_dot)
                    )
                }
            }, (600 + i * 160).toLong())
        }
    }

    /**
     * Chiqish: markaziy kolonna biroz kattalashib so'nadi, ornament/nuqtalar so'nadi,
     * so'ng marshrut. FAQAT ruxsat allaqachon bor bo'lganda chaqiriladi — dialog
     * ko'rsatiladigan yo'lda ekran joyida qoladi.
     */
    private fun playExitThenRoute() {
        if (routed || isFinishing) return
        safeAnim {
            binding.contentColumn.animate()
                .alpha(0f).scaleX(1.08f).scaleY(1.08f)
                .setDuration(240)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
            binding.starsBox.animate().alpha(0f).setDuration(240).start()
            binding.loadDots.animate().alpha(0f).setDuration(240).start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try { checkPermissions() } catch (_: Throwable) { goToMainActivity() }
        }, 250)
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

    // UX-14 fix: tashqi sozlamalardan grantsiz qaytishda onActivityResult ham,
    // onResume ham dialog ochishga urinardi — ikkita ustma-ust, yopib bo'lmas dialog
    // paydo bo'lardi. Bitta jonli dialogni kuzatamiz: ochiq bo'lsa qayta ochmaymiz.
    private var permissionDialog: AlertDialog? = null

    private fun requestStoragePermission() {
        if (permissionDialog?.isShowing == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - запрашиваем MANAGE_EXTERNAL_STORAGE.
            // UX-14 fix: "Chiqish" tugmasi qo'shildi — ilgari Android 11+ da rad etgan
            // foydalanuvchi uchun chiqish yo'li yo'q edi.
            permissionDialog = AlertDialog.Builder(this)
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
                .setNegativeButton(getString(R.string.btn_exit)) { _, _ -> finish() }
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
                if (permissionDialog?.isShowing == true) return
                permissionDialog = AlertDialog.Builder(this)
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
                }
                // Grant yo'q bo'lsa BU YERDA dialog ochmaymiz — keyin keladigan onResume
                // baribir checkPermissions'ni chaqiradi (UX-14: ikki dialog fix'i).
            }
        }
    }

    override fun onDestroy() {
        // Window leak bo'lmasin — activity yopilayotganda ochiq dialogni yopamiz.
        try { permissionDialog?.dismiss() } catch (_: Throwable) {}
        permissionDialog = null
        super.onDestroy()
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
        // Marshrut zinapoyasi — StartRouter'da (bitta manba). Ilgari shu `when` bu yerda,
        // Consent'da va Onboarding'da alohida-alohida yozilgan edi va ular bir-biridan
        // farq qila boshlagandi; yangi shlagbaum (Telegram ro'yxati) qo'shilganda esa
        // ularning biri uni chetlab o'tib yuborardi.
        startActivity(Intent(this, StartRouter.next(this)))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
