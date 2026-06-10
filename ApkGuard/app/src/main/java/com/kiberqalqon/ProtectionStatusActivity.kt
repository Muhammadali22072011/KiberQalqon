package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

/**
 * Himoya holati — kirishda barcha ruxsat/sozlamalarni BIR ekranda ko'rsatadi:
 * ✓ yoqilgan / ✗ yo'q / ⚠ qo'lda. Har birining yonida "Yoqish" tugmasi tegishli
 * tizim ekranini ochadi. Foydalanuvchi bir qarashda hammasi yoqilganini ko'radi.
 *
 * MUHIM: bu ekran kirish yo'lida turadi, shuning uchun HECH QACHON yiqilmasligi
 * kerak — onCreate'dagi har qanday xato bo'lsa, to'g'ridan-to'g'ri Dashboard'ga o'tamiz.
 */
class ProtectionStatusActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    // "Davom etish" tugmasi — barcha majburiy ruxsat berilmaguncha ichkariga o'tkazmaydi.
    private var continueBtn: MaterialButton? = null

    // Joylashuv ruxsati shu sessiyada bir marta so'ralganmi (loop bo'lmasligi uchun).
    private var locationAsked = false

    // Joylashuv runtime-so'rovi. Natija kelgach qatorlarni qayta chizamiz (✓/✗ yangilanadi).
    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { renderRows() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
            buildUi()
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Barcha majburiy ruxsat berilgan bo'lsa — orqaga = davom etish.
                    // Aks holda ichkariga O'TKAZMAYMIZ: ilovani fonga tushiramiz
                    // (chiqib ketmaydi, lekin ruxsatsiz Dashboard'ga ham kira olmaydi).
                    if (allCriticalPermissionsGranted(this@ProtectionStatusActivity)) {
                        proceed(ack = true)
                    } else {
                        moveTaskToBack(true)
                    }
                }
            })
        } catch (e: Throwable) {
            android.util.Log.e("ProtStatus", "onCreate crashed", e)
            proceed(ack = true) // hech qachon kirishni bloklamaymiz
        }
    }

    override fun onResume() {
        super.onResume()
        // Foydalanuvchi tizim sozlamalaridan qaytsa — holatni qayta o'qiymiz.
        try { renderRows() } catch (e: Throwable) { android.util.Log.e("ProtStatus", "render", e) }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.kq_bg))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "Himoya holati"
            setTextColor(getColor(R.color.kq_ink))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "To'liq himoya uchun quyidagilar yoqilgan bo'lishi kerak. " +
                "Qizil bo'lsa — yonidagi tugma orqali yoqing."
            setTextColor(getColor(R.color.kq_ink_2))
            textSize = 14f
            setPadding(0, dp(6), 0, dp(16))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        val btn = MaterialButton(this).apply {
            text = "Davom etish"
            textSize = 16f
            isAllCaps = false
            setBackgroundColor(getColor(R.color.kq_primary))
            setTextColor(getColor(R.color.kq_on_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
            setOnClickListener {
                if (allCriticalPermissionsGranted(this@ProtectionStatusActivity)) {
                    proceed(ack = true)
                } else {
                    // Ruxsatsiz davom ettirmaymiz — qaysi biri yetishmayotganini
                    // qizil ✗ bilan ko'rsatamiz va tushuntiramiz.
                    Toast.makeText(
                        this@ProtectionStatusActivity,
                        "Davom etish uchun barcha majburiy ruxsatlarni yoqing (qizil ✗).",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderRows()
                }
            }
        }
        continueBtn = btn
        content.addView(btn)
    }

    /** Tugma ko'rinishini majburiy ruxsatlar holatiga moslaydi (yoqilmagan bo'lsa — xira). */
    private fun refreshContinueButton() {
        val btn = continueBtn ?: return
        val ok = allCriticalPermissionsGranted(this)
        btn.alpha = if (ok) 1f else 0.5f
        btn.text = if (ok) "Davom etish" else "Avval ruxsatlarni yoqing"
    }

    // 3 holat: true=yoqilgan, false=yo'q, null=qo'lda (tekshirib bo'lmaydi, masalan MIUI autostart).
    private data class Row(
        val title: String,
        val desc: String,
        val state: Boolean?,
        val critical: Boolean,
        val onFix: (() -> Unit)?,
    )

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        out.add(
            Row(
                "Barcha fayllarga ruxsat",
                "Telefondagi APK fayllarni tekshirish uchun shart.",
                VersionCompat.hasFileScanAccess(this), true,
            ) { openAllFiles() },
        )
        out.add(
            Row(
                "Batareya cheklovisiz ishlash",
                "Tavsiya etiladi — yopilgandan keyin ham fon'da kuzatishni davom ettiradi. " +
                    "Samsung'da \"Cheklanmagan\" qilsangiz ham bu yerda ✗ qolishi mumkin: " +
                    "majburiy emas, baribir davom etishingiz mumkin.",
                batteryIgnored(), false,
            ) { openBattery() },
        )
        out.add(
            Row(
                "Boshqa oynalar ustida ko'rsatish",
                "Xavf topilganda ogohlantirish OYNASINI ochish uchun shart.",
                VersionCompat.hasOverlayPermission(this), true,
            ) { openOverlay() },
        )
        out.add(
            Row(
                "Bildirishnomalar",
                "Tahdid haqida BILDIRISHNOMA yuborish uchun shart.",
                VersionCompat.hasNotificationPermission(this), true,
            ) { openNotifications() },
        )
        out.add(
            Row(
                "Joylashuv (geolokatsiya)",
                "Hududingizdagi tahdidlar xaritasida ko'rinish uchun. Ixtiyoriy — " +
                    "bermasangiz ham ilova to'liq ishlaydi.",
                DeviceLocation.hasPermission(this), false,
            ) { requestLocation() },
        )
        // MIUI/OEM autostart — holatini tizim bermaydi, shuning uchun "qo'lda" (null).
        val oem = try { OemAutostartGuide.detect() } catch (_: Throwable) { null }
        if (oem != null && OemAutostartGuide.hasOemRestrictions(oem)) {
            out.add(
                Row(
                    "Avtomatik ishga tushirish (${oem.displayName})",
                    "Telefon ilovani o'chirib qo'ymasligi uchun \"Autostart\"ni yoqing.",
                    null, true,
                ) { showAutostartGuide(oem) },
            )
        }
        out.add(
            Row(
                "Doimiy himoya (fon)",
                "Doimiy kuzatuv yoqilgan bo'lsin.",
                Config.isBackgroundEnabled(this), true,
            ) { Config.setBackgroundEnabled(this, true); renderRows() },
        )
        return out
    }

    private fun renderRows() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        for (r in rows()) list.addView(rowCard(r))
        refreshContinueButton()
    }

    private fun rowCard(r: Row): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.kq_bg_elev))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val (mark, color) = when (r.state) {
            true -> "✓" to R.color.kq_safe
            false -> "✗" to R.color.kq_danger
            null -> "⚠" to R.color.kq_warn
        }
        top.addView(TextView(this).apply {
            text = mark
            setTextColor(getColor(color))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(12), 0)
        })
        top.addView(TextView(this).apply {
            text = r.title
            setTextColor(getColor(R.color.kq_ink))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(top)
        card.addView(TextView(this).apply {
            text = r.desc
            setTextColor(getColor(R.color.kq_ink_2))
            textSize = 13f
            setPadding(dp(32), dp(4), 0, 0)
        })

        // ✓ bo'lmasa — "Yoqish" tugmasi (qo'lda bo'lsa ham ochib beramiz).
        if (r.state != true && r.onFix != null) {
            card.addView(MaterialButton(this).apply {
                text = if (r.state == null) "Sozlamani ochish" else "Yoqish"
                textSize = 14f
                isAllCaps = false
                setBackgroundColor(getColor(R.color.kq_bg))
                setTextColor(getColor(R.color.kq_primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8); marginStart = dp(32) }
                setOnClickListener { r.onFix.invoke() }
            })
        }
        return card
    }

    // ---- Holat tekshiruvlari ----------------------------------------------
    private fun batteryIgnored(): Boolean = isBatteryIgnored(this)

    // ---- Sozlamalarni ochish ----------------------------------------------
    private fun openAllFiles() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
    }

    private fun openBattery() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else null
    }

    private fun openOverlay() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        } else null
    }

    private fun openNotifications() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
    }

    /**
     * Joylashuv ruxsatini so'raydi. Birinchi bosishda tizim dialogi chiqadi; agar
     * foydalanuvchi "Boshqa so'ralmasin" tanlagan bo'lsa (rationale=false va avval
     * so'ralgan), to'g'ridan-to'g'ri ilova Sozlamalariga olib boramiz.
     */
    private fun requestLocation() {
        if (DeviceLocation.hasPermission(this)) { renderRows(); return }
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, fine) ||
            ActivityCompat.shouldShowRequestPermissionRationale(this, coarse)
        if (!locationAsked || rationale) {
            locationAsked = true
            locationLauncher.launch(arrayOf(fine, coarse))
        } else {
            safeStart {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
        }
    }

    /**
     * Avtomatik ishga tushirish (OEM autostart) — avval o'zbekcha yo'riqnomani
     * KO'RSATAMIZ, so'ng tegishli OEM ekranini ochamiz. Ekran ochilmasa
     * (openAutostartSettings=false bo'lsa) jim qolmaymiz — foydalanuvchiga qo'lda
     * yo'lni aytamiz. Ilgari xato jim yutilib, tugma "ishlamayotgandek" tuyulardi
     * (foydalanuvchi shikoyati: "avtomatik ishga tushirish xato bilan ishlaydi").
     */
    private fun showAutostartGuide(oem: OemAutostartGuide.Oem) {
        try {
            AlertDialog.Builder(this)
                .setTitle("Avtomatik ishga tushirish (${oem.displayName})")
                .setMessage(OemAutostartGuide.instructions(oem))
                .setPositiveButton("Sozlamani ochish") { _, _ ->
                    val opened = try {
                        OemAutostartGuide.openAutostartSettings(this, oem)
                    } catch (_: Throwable) { false }
                    if (!opened) {
                        Toast.makeText(
                            this,
                            "Avtomatik ochib bo'lmadi. Sozlamalar → Ilovalar → " +
                                "KiberQalqon orqali qo'lda yoqing.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .setNegativeButton("Yopish", null)
                .show()
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "autostart guide failed", e)
        }
    }

    private inline fun safeStart(build: () -> Intent?) {
        try {
            val i = build() ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "open settings failed", e)
        }
    }

    private fun proceed(ack: Boolean) {
        if (ack) try { Config.setProtectionAcked(this, true) } catch (_: Throwable) {}
        // Chek-list to'liq (barcha kritik ruxsatlar berilgan) bo'lib "Davom etish" bosildi — himoya
        // ENDI haqiqatan tayyor. Xizmatni yoqamiz va birinchi marta "Himoyangiz yoqildi" chiqaramiz.
        try { ProtectionActivator.activateIfReady(this) } catch (_: Throwable) {}
        try {
            startActivity(Intent(this, DashboardNewActivity::class.java))
        } catch (_: Throwable) {}
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Barcha MAJBURIY (tizim orqali tekshirib bo'ladigan) ruxsatlar berilganmi.
         *
         * MUHIM: uchta narsa bu yerga KIRMAYDI (majburiy shart EMAS):
         *  - OEM autostart (MIUI/EMUI...) — uni dasturiy yo'l bilan tekshirib bo'lmaydi,
         *    majburiy qilsak foydalanuvchi abadiy "davom eta olmaydigan" holatga tushardi.
         *  - Joylashuv — maxfiylik siyosatiga ko'ra IXTIYORIY (usiz ham ilova to'liq ishlaydi).
         *  - Batareya cheklovisiz — Samsung/One UI'da foydalanuvchi ilovani "Cheklanmagan"
         *    qilsa ham isIgnoringBatteryOptimizations() ko'pincha false qaytaradi (Samsung
         *    "Unrestricted"ni Doze whitelist'ga qo'shmaydi). Majburiy qilsak — Samsung
         *    foydalanuvchilari KIRA OLMAY qoladi (real shikoyat). Shu sabab tavsiya, shart emas.
         * Uchchalasi ham faqat holat/yo'l-yo'riq (⚠ / ✗) sifatida ko'rsatiladi.
         *
         * Majburiy ruxsatlar va ular nimani ta'minlaydi:
         *  - Barcha fayllarga ruxsat → APK fayllarni topish/o'chirish (Telegram/WhatsApp papkalari)
         *  - Overlay (oyna ustida)   → tahdid OYNASI chiqishi
         *  - Bildirishnoma           → tahdid BILDIRISHNOMASI chiqishi
         *  - Fon himoyasi yoqilgan   → doimiy kuzatuv (Config bayrog'i)
         */
        fun allCriticalPermissionsGranted(ctx: Context): Boolean {
            return VersionCompat.hasFileScanAccess(ctx) &&
                VersionCompat.hasOverlayPermission(ctx) &&
                VersionCompat.hasNotificationPermission(ctx) &&
                Config.isBackgroundEnabled(ctx)
        }

        private fun isBatteryIgnored(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            return try {
                (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
            } catch (_: Throwable) { true }
        }
    }
}
