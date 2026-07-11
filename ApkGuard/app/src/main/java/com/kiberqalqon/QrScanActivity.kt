package com.uzguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.uzguard.databinding.ActivityQrScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * QR-kod xavfsizlik skaneri — v4 «Milliy Kiber Himoya» dizayni.
 *
 * CameraX PreviewView + ImageAnalysis kadrlarini ZXing MultiFormatReader bilan
 * (FAQAT QR_CODE) Y-yorug'lik tekisligidan o'qiydi. QR matni havola bo'lsa —
 * LinkCheckActivity ga "url" extra bilan uzatadi (B1 ekrani tahdidni tekshiradi);
 * havola bo'lmasa — matnni neytral ko'rinishda ko'rsatadi. Kamera onDestroy'da
 * to'liq bo'shatiladi, analyzer Executor ham yopiladi.
 *
 * Kamera ruxsati — JUST-IN-TIME: faqat shu ekran ochilganda so'raladi
 * (AutoScanActivity.writePermissionLauncher idiomasi). Doimiy rad etilsa →
 * ilova sozlamalariga chuqur havola.
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding

    /** QR kadrlarini fonda dekodlash uchun bitta ish ipi — onDestroy'da yopiladi. */
    private var analysisExecutor: ExecutorService? = null

    /** Faol kamera provayderi — bind/unbind va to'liq bo'shatish uchun. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Kamera ishga tushish jarayonida (provider future hali hal bo'lmagan) — qo'sh-startni oldini oladi. */
    private var cameraStarting = false

    /** Bir QR ikki marta ishlov bermasligi uchun — birinchi muvaffaqiyatdan keyin to'xtaymiz. */
    @Volatile private var handled = false

    /** Kamera apparati bormi — onCreate'da bir marta aniqlanadi. */
    private var hasCameraHardware = false

    /**
     * "Guruh kodini qaytar" rejimi — GroupJoinActivity shu ekranni kod olish uchun ochadi.
     * Yoqilgan bo'lsa: QR ichidan guruh kodini ajratib, natija sifatida qaytaramiz (havola
     * tekshiruviga o'tmaymiz).
     */
    private val returnJoinCode by lazy { intent.getBooleanExtra(EXTRA_RETURN_JOIN_CODE, false) }

    /**
     * ZXing reader — FAQAT QR_CODE formati. Reader thread-safe emas, shuning uchun
     * faqat analysisExecutor ipida (analyzeFrame ichida) ishlatiladi.
     */
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /**
     * Runtime CAMERA so'rovi — AutoScanActivity.writePermissionLauncher idiomasi.
     * Berilsa → kamerani ishga tushiramiz; rad etilsa → tushuntirish + sozlamalar havolasi.
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCameraState()
            startCamera()
        } else {
            // Foydalanuvchi rad etdi. "Ruxsat berish" tugmasini sozlamalar havolasiga aylantiramiz —
            // doimiy rad ("qayta so'rama") holatida ham qo'lda yoqishi mumkin.
            showPermissionState(deniedOnce = true)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnResultClose.setOnClickListener { finish() }
        binding.btnPermGrant.setOnClickListener { onPermGrantClicked() }

        hasCameraHardware = try {
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        } catch (e: Throwable) {
            Log.w(TAG, "camera feature check failed", e)
            true // ehtiyot uchun — apparatni keyin CameraX tekshiradi
        }

        if (!hasCameraHardware) {
            showNoCameraState()
            return
        }

        if (hasCameraPermission()) {
            showCameraState()
            startCamera()
        } else {
            showPermissionState(deniedOnce = false)
        }
    }

    /**
     * Sozlamalardan qaytganda (doimiy rad → ilova sozlamalari → CAMERA yoqildi → Orqaga)
     * ruxsat tashqaridan berilgan bo'lsa, kamerani SHU YERDA ishga tushiramiz. Aks holda
     * foydalanuvchi ruxsat ekranida tiqilib qolardi (onResume yo'qligi tufayli).
     * Guard'lar: kamera apparati bor, hali QR o'qilmagan (handled=false — natija ekranini
     * ustidan yozib yubormaslik), kamera ishlamayotgan (cameraProvider==null), ruxsat bor.
     */
    override fun onResume() {
        super.onResume()
        if (hasCameraHardware && !handled && cameraProvider == null && !cameraStarting && hasCameraPermission()) {
            showCameraState()
            startCamera()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** "Ruxsat berish" tugmasi: birinchi marta — runtime so'rov; rad etilgan bo'lsa — sozlamalar. */
    private fun onPermGrantClicked() {
        if (binding.btnPermGrant.tag == TAG_OPEN_SETTINGS) {
            openAppSettings()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "open app settings failed", e)
        }
    }

    // ───────────────────────── Holatlar (ko'rinish) ─────────────────────────

    private fun showCameraState() {
        binding.cameraGroup.visibility = View.VISIBLE
        binding.permGroup.visibility = View.GONE
        binding.noCameraGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.GONE
    }

    private fun showPermissionState(deniedOnce: Boolean) {
        binding.cameraGroup.visibility = View.GONE
        binding.permGroup.visibility = View.VISIBLE
        binding.noCameraGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.GONE
        if (deniedOnce) {
            binding.tvPermBody.text = getString(R.string.kq4_qr_perm_denied)
            binding.btnPermGrant.text = getString(R.string.kq4_qr_perm_open_settings)
            binding.btnPermGrant.tag = TAG_OPEN_SETTINGS
        } else {
            binding.tvPermBody.text = getString(R.string.kq4_qr_perm_body)
            binding.btnPermGrant.text = getString(R.string.kq4_qr_perm_grant)
            binding.btnPermGrant.tag = null
        }
    }

    private fun showNoCameraState() {
        binding.cameraGroup.visibility = View.GONE
        binding.permGroup.visibility = View.GONE
        binding.noCameraGroup.visibility = View.VISIBLE
        binding.resultGroup.visibility = View.GONE
    }

    private fun showDecodedTextState(text: String) {
        binding.tvDecodedText.text = text
        binding.cameraGroup.visibility = View.GONE
        binding.permGroup.visibility = View.GONE
        binding.noCameraGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.VISIBLE
    }

    // ───────────────────────── CameraX + ZXing ─────────────────────────

    private fun startCamera() {
        // Allaqachon ishlamoqda yoki ishga tushmoqda — qayta urinmaymiz (onCreate+onResime qo'sh-start).
        if (cameraProvider != null || cameraStarting) return
        if (analysisExecutor == null) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }
        val providerFuture = try {
            ProcessCameraProvider.getInstance(this)
        } catch (e: Throwable) {
            Log.e(TAG, "getInstance failed", e)
            showNoCameraState()
            return
        }
        cameraStarting = true
        providerFuture.addListener({
            cameraStarting = false
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (e: Throwable) {
                Log.e(TAG, "camera bind failed", e)
                // Kameraga ulanib bo'lmadi (band/yo'q) — soxta "ishladi" ko'rsatmaymiz.
                showNoCameraState()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val exec = analysisExecutor
        if (exec != null) {
            analysis.setAnalyzer(exec) { proxy -> analyzeFrame(proxy) }
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Throwable) {
            // Orqa kamera yo'q — old kameraga urinib ko'ramiz.
            Log.w(TAG, "back camera bind failed, trying front", e)
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e2: Throwable) {
                Log.e(TAG, "front camera bind failed too", e2)
                showNoCameraState()
            }
        }
    }

    /**
     * Bitta kamera kadrini ZXing bilan dekodlaydi. Y (yorug'lik) tekisligini to'g'ridan-to'g'ri
     * PlanarYUVLuminanceSource ga beramiz — RGB konvertatsiyasi shart emas, tez. QR topilsa
     * (handled bayrog'i bilan bir martalik) — main ipda yo'naltiramiz.
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (handled) {
            proxy.close()
            return
        }
        try {
            val text = decodeQr(proxy)
            if (text != null && !text.isBlank()) {
                if (!handled) {
                    handled = true
                    runOnUiThread { onQrDecoded(text.trim()) }
                }
            }
        } catch (e: Throwable) {
            // Dekod xatosi — oddiy holat (har kadrda QR yo'q). E'tiborsiz, keyingi kadr.
            Log.v(TAG, "frame decode skipped", e)
        } finally {
            proxy.close()
        }
    }

    private fun decodeQr(proxy: ImageProxy): String? {
        val plane = proxy.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val width = proxy.width
        val height = proxy.height
        if (width <= 0 || height <= 0) return null

        val source = PlanarYUVLuminanceSource(
            data, plane.rowStride, height,
            0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (e: Throwable) {
            // Burilgan/teskari kodlar uchun yana invert qilib ko'ramiz.
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (e2: Throwable) {
                null
            } finally {
                reader.reset()
            }
        } finally {
            reader.reset()
        }
    }

    /**
     * QR matni o'qildi. Havola bo'lsa → LinkCheckActivity "url" extra bilan (B1 tahdid tekshiruvi)
     * va shu ekranni yopamiz; aks holda — neytral matn ko'rinishi (kq4_qr_not_url).
     */
    private fun onQrDecoded(text: String) {
        // Guruh rejimi: QR ichidan kodni ajratib qaytaramiz (GroupJoinActivity oladi).
        if (returnJoinCode) {
            val code = extractJoinCode(text)
            releaseCamera()
            if (code != null) {
                setResult(RESULT_OK, Intent().putExtra(RESULT_JOIN_CODE, code))
                finish()
            } else {
                // Guruh QR emas — o'qilgan matnni ko'rsatamiz (foydalanuvchi yopib qayta urinadi).
                showDecodedTextState(text)
            }
            return
        }
        // Kamerani darhol bo'shatamiz — natija ko'rsatilmoqda, skan to'xtadi.
        releaseCamera()
        if (looksLikeUrl(text)) {
            try {
                val intent = Intent(this, LinkCheckActivity::class.java).apply {
                    putExtra("url", text)
                }
                startActivity(intent)
                finish()
                return
            } catch (e: Throwable) {
                Log.e(TAG, "launch LinkCheckActivity failed", e)
                // Yo'naltirib bo'lmadi — hech bo'lmaganda matnni ko'rsatamiz.
            }
        }
        showDecodedTextState(text)
    }

    /**
     * Matn havolami? https?:// bilan boshlansa — ha. Aks holda nuqta/host belgilari
     * bor-yo'qligini tekshiramiz (masalan "payme.uz", "click.uz/pay"). Bo'sh joy yoki
     * nuqta yo'q matn (oddiy yozuv) — havola emas.
     */
    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        // Bo'sh joyli matn — havola emas (URL'da bo'sh joy bo'lmaydi).
        if (t.any { it.isWhitespace() }) return false
        // Sxemali boshqa URI'lar (tel:, mailto:, bitcoin: ...) — havola emas, neytral matn.
        // DIQQAT: host:port (masalan "evil.com:8443/login") sxema EMAS — ':' dan oldingi
        // qismda nuqta bo'lsa, bu host, sxema emas; shuning uchun rad etmaymiz, davom etamiz.
        if (lower.contains("://")) return false
        val colon = lower.indexOf(':')
        if (colon > 0) {
            val scheme = lower.substring(0, colon)
            // Haqiqiy URI sxemasi = nuqtasiz token (host.tld emas). Nuqta bo'lsa — host:port deb qaraymiz.
            if (!scheme.contains('.') && scheme.matches(Regex("^[a-z][a-z0-9+-]*$"))) return false
        }
        // host qismi: yo'l (/), so'rov (?) va port (:) belgilaridan tozalaymiz.
        val hostPart = t.substringBefore('/').substringBefore('?').substringBefore(':')
        // IPv4 host (masalan "185.220.101.5/pay") — TLD harfli emas, lekin bu ham havola.
        val octets = hostPart.split('.')
        if (octets.size == 4 &&
            octets.all { o -> o.isNotEmpty() && o.all { it.isDigit() } && (o.toIntOrNull() ?: -1) in 0..255 }
        ) {
            return true
        }
        // host.tld ko'rinishi: kamida bitta nuqta, oxirgi qism harfli (TLD).
        val dot = hostPart.lastIndexOf('.')
        if (dot <= 0 || dot >= hostPart.length - 1) return false
        val tld = hostPart.substring(dot + 1)
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    /**
     * QR matnidan guruh KODini ajratadi. Ikki shakl:
     *   • "uzguard://join?code=NAVOIY7" (chuqur havola) → code parametri
     *   • yalang'och kod "NAVOIY7" (harf+raqam, 4..16) → o'zi
     * Aks holda null (bu guruh QR emas).
     */
    private fun extractJoinCode(text: String): String? {
        val t = text.trim()
        val lower = t.lowercase()
        if (lower.startsWith("uzguard://join")) {
            val c = try { Uri.parse(t).getQueryParameter("code") } catch (e: Throwable) { null }
            val code = c?.trim()?.uppercase()
            if (code != null && code.matches(Regex("^[A-Z0-9]{4,16}$"))) return code
            return null
        }
        val up = t.uppercase()
        return if (up.matches(Regex("^[A-Z0-9]{4,16}$"))) up else null
    }

    // ───────────────────────── Hayotiy sikl ─────────────────────────

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "unbindAll failed", e)
        }
        cameraProvider = null
        cameraStarting = false
    }

    override fun onDestroy() {
        releaseCamera()
        try {
            analysisExecutor?.shutdown()
        } catch (e: Throwable) {
            Log.w(TAG, "executor shutdown failed", e)
        }
        analysisExecutor = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QrScanActivity"
        private const val TAG_OPEN_SETTINGS = "open_settings"

        /** GroupJoinActivity beradi: QR'ni guruh kodi sifatida qaytar (havolaga o'tma). */
        const val EXTRA_RETURN_JOIN_CODE = "return_join_code"
        /** Natija Intent'idagi kod kaliti (setResult). */
        const val RESULT_JOIN_CODE = "join_code"
    }
}
