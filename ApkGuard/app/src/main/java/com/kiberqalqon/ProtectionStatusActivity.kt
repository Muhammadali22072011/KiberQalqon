package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kiberqalqon.databinding.ActivityProtectionV4Binding
import com.kiberqalqon.databinding.ItemKq4ProtectionRowBinding

/**
 * Himoya holati — kirishda barcha ruxsat/sozlamalarni BIR ekranda ko'rsatadi:
 * ✓ yoqilgan / yo'q. Har birining yonida "Yoqish" tugmasi tegishli
 * tizim ekranini ochadi. Foydalanuvchi bir qarashda hammasi yoqilganini ko'radi.
 *
 * v4 dizayn (design_v4_extracted/screens2.jsx → Protection): layout
 * activity_protection_v4.xml (halqa + hisob), qatorlar item_kq4_protection_row.xml.
 * Logika o'zgarmagan: ruxsat tekshiruvlari, tizim sozlamalari intentlari,
 * onboarding-gate ("Davom etish" majburiy ruxsatlarsiz o'tkazmaydi).
 *
 * MUHIM: bu ekran kirish yo'lida turadi, shuning uchun HECH QACHON yiqilmasligi
 * kerak — onCreate'dagi har qanday xato bo'lsa, to'g'ridan-to'g'ri Dashboard'ga o'tamiz.
 */
class ProtectionStatusActivity : AppCompatActivity() {

    private var binding: ActivityProtectionV4Binding? = null

    // Joylashuv ruxsati shu sessiyada bir marta so'ralganmi (loop bo'lmasligi uchun).
    private var locationAsked = false

    // Joylashuv runtime-so'rovi. Natija kelgach qatorlarni qayta chizamiz (✓/✗ yangilanadi).
    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { renderRows() }

    // VPN ruxsat oynasi (VpnService.prepare) — tasdiq bo'lsa C2-filtr doimiy yoqiladi
    // (App.onCreate har ishga tushishda o'zi qayta ko'taradi, qo'shimcha tap kerak emas).
    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                Config.setVpnFilterEnabled(this, true)
                VpnFilterService.start(this)
            } catch (_: Throwable) {}
        }
        renderRows()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
            val b = ActivityProtectionV4Binding.inflate(layoutInflater)
            binding = b
            setContentView(b.root)
            b.ringProt.strokeWidthDp = 11f

            b.btnProtBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            b.btnProtContinue.setOnClickListener {
                if (allCriticalPermissionsGranted(this)) {
                    proceed(ack = true)
                } else {
                    // Ruxsatsiz davom ettirmaymiz — qaysi biri yetishmayotganini
                    // "Yoqish" tugmali qatorlar ko'rsatadi.
                    Toast.makeText(this, R.string.kq4_prot_continue_toast, Toast.LENGTH_LONG).show()
                    renderRows()
                }
            }

            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Ekran ilova ICHIDAN ochilgan (onboarding allaqachon o'tilgan,
                    // Dashboard/Sozlamalar'dan holatni ko'rish uchun kirilgan) —
                    // oddiy "orqaga": ostidagi ekran joyida turibdi, faqat yopamiz.
                    // Aks holda v4 ko'rinadigan "orqaga" strelkasi yo Dashboard
                    // dublikatini yaratardi, yo moveTaskToBack bilan ilovani
                    // "jimgina yo'q qilib" yuborardi (crash'dek ko'rinadi).
                    if (isProtectionAckedSafe()) {
                        finish()
                        return
                    }
                    // Onboarding-gate: barcha majburiy ruxsat berilgan bo'lsa —
                    // orqaga = davom etish. Aks holda ichkariga O'TKAZMAYMIZ:
                    // ilovani fonga tushiramiz (chiqib ketmaydi, lekin ruxsatsiz
                    // Dashboard'ga ham kira olmaydi).
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

    /** Tugma ko'rinishini majburiy ruxsatlar holatiga moslaydi (yoqilmagan bo'lsa — xira). */
    private fun refreshContinueButton() {
        val btn = binding?.btnProtContinue ?: return
        val ok = allCriticalPermissionsGranted(this)
        btn.alpha = if (ok) 1f else 0.5f
        btn.setText(if (ok) R.string.kq4_continue else R.string.kq4_prot_continue_locked)
    }

    // 3 holat: true=yoqilgan, false=yo'q, null=qo'lda (tekshirib bo'lmaydi, masalan MIUI autostart).
    private data class Row(
        val iconRes: Int,
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
                R.drawable.ic4_folder,
                getString(R.string.kq4_prot_row_files_t),
                getString(R.string.kq4_prot_row_files_s),
                VersionCompat.hasFileScanAccess(this), true,
            ) { openAllFiles() },
        )
        out.add(
            Row(
                R.drawable.ic4_clock,
                getString(R.string.kq4_prot_row_battery_t),
                getString(R.string.kq4_prot_row_battery_s),
                batteryIgnored(), false,
            ) { openBattery() },
        )
        out.add(
            Row(
                R.drawable.ic4_alert,
                getString(R.string.kq4_prot_row_overlay_t),
                getString(R.string.kq4_prot_row_overlay_s),
                VersionCompat.hasOverlayPermission(this), false,
            ) { openOverlay() },
        )
        out.add(
            Row(
                R.drawable.ic4_bell,
                getString(R.string.kq4_notifications),
                getString(R.string.kq4_prot_row_notif_s),
                VersionCompat.hasNotificationPermission(this), true,
            ) { openNotifications() },
        )
        out.add(
            Row(
                R.drawable.ic4_globe,
                getString(R.string.kq4_prot_row_loc_t),
                getString(R.string.kq4_prot_row_loc_s),
                DeviceLocation.hasPermission(this), false,
            ) { requestLocation() },
        )
        // MIUI/OEM autostart — holatini tizim bermaydi, shuning uchun "qo'lda" (null).
        val oem = try { OemAutostartGuide.detect() } catch (_: Throwable) { null }
        if (oem != null && OemAutostartGuide.hasOemRestrictions(oem)) {
            out.add(
                Row(
                    R.drawable.ic4_refresh,
                    getString(R.string.kq4_prot_row_autostart_t, oem.displayName),
                    getString(R.string.kq4_prot_row_autostart_s),
                    null, true,
                ) { showAutostartGuide(oem) },
            )
        }
        out.add(
            Row(
                R.drawable.ic4_shield,
                getString(R.string.kq4_prot_row_bg_t),
                getString(R.string.kq4_prot_row_bg_s),
                Config.isBackgroundEnabled(this), true,
            ) { Config.setBackgroundEnabled(this, true); renderRows() },
        )
        // Havola qalqoni — KiberQalqon standart havola ochuvchimi (TAVSIYA, majburiy emas:
        // Telegram ichki brauzeri baribir o'tib ketadi, shu sabab gate'ni bloklamaymiz).
        if (Config.isLinkGuardEnabled(this)) {
            out.add(
                Row(
                    R.drawable.ic4_link,
                    getString(R.string.kq4_prot_row_linkguard_t),
                    getString(R.string.kq4_prot_row_linkguard_s),
                    LinkForwarder.isDefaultLinkHandler(this), false,
                ) { openDefaultApps() },
            )
        }
        // Internet himoyasi (DNS C2-filtri) — bir marta tasdiqlangach App.onCreate doim o'zi
        // ko'taradi. TAVSIYA (majburiy emas): VPN tasdiqsiz ham asosiy himoya to'liq ishlaydi.
        out.add(
            Row(
                R.drawable.ic4_wifi,
                getString(R.string.kq4_prot_row_vpn_t),
                getString(R.string.kq4_prot_row_vpn_s),
                isVpnReady(), false,
            ) { enableVpn() },
        )
        return out
    }

    private fun renderRows() {
        val b = binding ?: return
        val all = rows()
        b.protList.removeAllViews()
        all.forEachIndexed { i, r -> b.protList.addView(rowView(b, r, isLast = i == all.lastIndex)) }

        // Hero: halqa + "{ok} / {total} ruxsat berilgan".
        // MUHIM: faqat TEKSHIRIB BO'LADIGAN qatorlar hisoblanadi (state != null).
        // OEM autostart (MIUI/EMUI/Oppo/Vivo) qatori state=null — tizim holatini
        // bermaydi, shuning uchun u hisobga kirsa halqa HECH QACHON 100% bo'lmasdi
        // ("6/7" abadiy). Qator ro'yxatda yo'l-yo'riq sifatida qoladi, lekin
        // hisob/halqaga kirmaydi (dizayndagi okCount/items.length ham faqat
        // boolean `ok` qatorlar ustida ishlaydi).
        val checkable = all.filter { it.state != null }
        val ok = checkable.count { it.state == true }
        val total = checkable.size
        val full = ok == total
        val color = getColor(if (full) R.color.kq_safe else R.color.kq_warn)
        b.ringProt.ringColor = color
        b.ringProt.setValue(if (total == 0) 0f else ok * 100f / total)
        b.imgProtShield.imageTintList = ColorStateList.valueOf(color)
        b.tvProtCount.text = getString(R.string.kq4_prot_count, ok, total)
        b.tvProtHint.setText(if (full) R.string.kq4_prot_all_on else R.string.kq4_prot_enable_one)

        refreshContinueButton()
    }

    /**
     * v4 qator: av 44 (safe/warn) + sarlavha/izoh + o'ngda "Yoniq" tag yoki
     * "Yoqish" tugmasi. Qator ham, tugma ham bosilganda tegishli tizim
     * sozlamasi ochiladi (yoqilmagan bo'lsa).
     */
    private fun rowView(b: ActivityProtectionV4Binding, r: Row, isLast: Boolean): View {
        val item = ItemKq4ProtectionRowBinding.inflate(layoutInflater, b.protList, false)
        val ok = r.state == true

        item.imgProtRowIcon.setImageResource(r.iconRes)
        item.tvProtRowTitle.text = r.title
        item.tvProtRowSub.text = r.desc

        item.avProt.setBackgroundResource(if (ok) R.drawable.kq4_av_safe else R.drawable.kq4_av_warn)
        item.imgProtRowIcon.imageTintList = ColorStateList.valueOf(
            getColor(if (ok) R.color.kq_safe else R.color.kq_warn),
        )

        item.tagProtOn.visibility = if (ok) View.VISIBLE else View.GONE
        item.btnProtEnable.visibility = if (ok) View.GONE else View.VISIBLE

        if (r.onFix != null) {
            item.btnProtEnable.setOnClickListener { r.onFix.invoke() }
            item.rowProt.setOnClickListener { if (r.state != true) r.onFix.invoke() }
        }
        item.divProt.visibility = if (isLast) View.GONE else View.VISIBLE
        return item.root
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

    /** VPN C2-filtri yoqilgan VA tizim ruxsati berilganmi (✓ holati). */
    private fun isVpnReady(): Boolean = try {
        Config.isVpnFilterEnabled(this) && VpnFilterService.prepareIntent(this) == null
    } catch (_: Throwable) { false }

    /** VPN filtrini yoqadi: ruxsat bo'lsa darhol, bo'lmasa tizim tasdiq oynasi. */
    private fun enableVpn() {
        try {
            val prepare = VpnFilterService.prepareIntent(this)
            if (prepare == null) {
                Config.setVpnFilterEnabled(this, true)
                VpnFilterService.start(this)
                renderRows()
            } else {
                vpnLauncher.launch(prepare)
            }
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "vpn enable failed", e)
        }
    }

    /** «Standart ilovalar» ekrani — foydalanuvchi bizni standart havola ochuvchi qiladi. */
    private fun openDefaultApps() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
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
                .setTitle(getString(R.string.kq4_prot_row_autostart_t, oem.displayName))
                .setMessage(OemAutostartGuide.instructions(oem))
                .setPositiveButton(R.string.kq4_prot_autostart_open) { _, _ ->
                    val opened = try {
                        OemAutostartGuide.openAutostartSettings(this, oem)
                    } catch (_: Throwable) { false }
                    if (!opened) {
                        Toast.makeText(this, R.string.kq4_prot_autostart_fail, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.kq4_prot_autostart_close, null)
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

    /** Onboarding o'tilganmi — xavfsiz o'qish (Config xatosi gate'ni buzmasin). */
    private fun isProtectionAckedSafe(): Boolean =
        try { Config.isProtectionAcked(this) } catch (_: Throwable) { false }

    private fun proceed(ack: Boolean) {
        if (ack) try { Config.setProtectionAcked(this, true) } catch (_: Throwable) {}
        // Chek-list to'liq (barcha kritik ruxsatlar berilgan) bo'lib "Davom etish" bosildi — himoya
        // ENDI haqiqatan tayyor. Xizmatni yoqamiz va birinchi marta "Himoyangiz yoqildi" chiqaramiz.
        try { ProtectionActivator.activateIfReady(this) } catch (_: Throwable) {}
        try {
            // CLEAR_TOP|SINGLE_TOP: back-stack'da Dashboard bo'lsa, yangisini
            // YARATMAYMIZ — borini yuqoriga chiqaramiz. Bu ekran Dashboard,
            // Sozlamalar va MainActivity'dan ochiladi; flagsiz har "davom etish"
            // stack'ka yana bitta Dashboard dublikatini qo'shardi.
            startActivity(
                Intent(this, DashboardNewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (_: Throwable) {}
        finish()
    }

    companion object {
        /**
         * Barcha MAJBURIY (tizim orqali tekshirib bo'ladigan) ruxsatlar berilganmi.
         *
         * MUHIM: bu narsalar bu yerga KIRMAYDI (majburiy shart EMAS):
         *  - OEM autostart (MIUI/EMUI...) — uni dasturiy yo'l bilan tekshirib bo'lmaydi,
         *    majburiy qilsak foydalanuvchi abadiy "davom eta olmaydigan" holatga tushardi.
         *  - Joylashuv — maxfiylik siyosatiga ko'ra IXTIYORIY (usiz ham ilova to'liq ishlaydi).
         *  - Batareya cheklovisiz — Samsung/One UI'da foydalanuvchi ilovani "Cheklanmagan"
         *    qilsa ham isIgnoringBatteryOptimizations() ko'pincha false qaytaradi (Samsung
         *    "Unrestricted"ni Doze whitelist'ga qo'shmaydi). Majburiy qilsak — Samsung
         *    foydalanuvchilari KIRA OLMAY qoladi (real shikoyat). Shu sabab tavsiya, shart emas.
         *  - Overlay ("oyna ustida") — ba'zi telefon/proshivkalarda bu ruxsatni umuman yoqib
         *    BO'LMAYDI (ishlab chiqaruvchi cheklovi); majburiy qilsak foydalanuvchi onboarding'da
         *    abadiy qotib qolardi (real shikoyat). Usiz ogohlantirish to'liq-ekranli BILDIRISHNOMA
         *    orqali baribir keladi (canLaunchActivityFromBackground → notification fallback), shu
         *    sabab overlay endi TAVSIYA, shart emas.
         * Hammasi faqat holat/yo'l-yo'riq sifatida ko'rsatiladi, davom etishni bloklamaydi.
         *
         * Majburiy ruxsatlar va ular nimani ta'minlaydi:
         *  - Barcha fayllarga ruxsat → APK fayllarni topish/o'chirish (Telegram/WhatsApp papkalari)
         *  - Bildirishnoma           → tahdid BILDIRISHNOMASI / to'liq-ekranli ogohlantirish chiqishi
         *  - Fon himoyasi yoqilgan   → doimiy kuzatuv (Config bayrog'i)
         */
        fun allCriticalPermissionsGranted(ctx: Context): Boolean {
            return VersionCompat.hasFileScanAccess(ctx) &&
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
