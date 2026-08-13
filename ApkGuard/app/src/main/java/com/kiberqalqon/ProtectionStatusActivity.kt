package com.uzguard

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
import com.uzguard.databinding.ActivityProtectionV4Binding
import com.uzguard.databinding.ItemKq4ProtectionRowBinding

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

    // Bildirishnoma shu sessiyada bir marta runtime-so'ralganmi (loop bo'lmasligi uchun).
    private var notifAsked = false

    // Bildirishnoma (POST_NOTIFICATIONS) runtime-so'rovi — Android 13+. Avval Sozlamalarga
    // yo'naltirardik (faqat deep-link); natijada ruxsat hech qachon SO'RALMAS edi va
    // fallback bildirishnoma jim ishlamasdi. Endi haqiqiy tizim dialogini ko'rsatamiz.
    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
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
        onWizardLauncherResult()
    }

    // «Hammasini yoqish» sehrgari: bildirishnoma + joylashuvni BITTA tizim dialogida so'raydi
    // (alohida notif/location launcher'lardan farqli — bir tapda ikkalasi ham so'raladi).
    private val wizardPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { renderRows(); onWizardLauncherResult() }

    // ---- «Hammasini yoqish» sehrgari holati -------------------------------
    // Android bitta tap bilan 9 ta ruxsatni BERA OLMAYDI (har bir maxsus ruxsat alohida
    // tizim ekranini ochadi). Shuning uchun bitta tugma ularni KETMA-KET so'raydi:
    // runtime ruxsatlar bitta dialogda, qolganlari har biri o'z ekranida (foydalanuvchi
    // qaytgach — onResume keyingisini ochadi).
    private var wizardActive = false
    private var wizardOutstanding: WizKind? = null // SETTINGS → onResume kutadi, LAUNCHER → callback kutadi
    private val wizardDone = HashSet<String>()

    // Haqiqiy tashqi ekrandan qaytishni bildiradi. onStop'da true bo'ladi; SETTINGS
    // qadamini onResume faqat shu bayroq yoqilganda pump qiladi (keyin tozalaymiz).
    // LAUNCHER callback'i o'sha resume tsiklida SETTINGS qadamini sinxron ochsa —
    // oraliqda onStop bo'lmaydi, shuning uchun onResume ikki marta o'tkazib yubormaydi.
    private var wentBackground = false

    private enum class WizKind { INSTANT, SETTINGS, LAUNCHER }
    private data class WizStep(
        val id: String,
        val pending: () -> Boolean,
        val run: () -> WizKind,
    )

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
            b.btnProtContinue.setOnClickListener { onContinueClicked() }
            b.btnProtEnableAll.setOnClickListener { startWizard() }

            // "Jarayon o'ldirildi" bildirishnomasidan kelindi — OEM autostart yo'riqnomasi
            // + tegishli ekran. (Bildirishnoma avval shu ekranga olib kelib, olib tashlangan
            // "Avtomatik ishga tushirish" qatorini va'da qilardi — foydalanuvchi tupikda qolardi.)
            // removeExtra: rotatsiya/recreate'da dialog qayta-qayta chiqmasin.
            if (intent?.getBooleanExtra(EXTRA_OPEN_AUTOSTART, false) == true) {
                intent.removeExtra(EXTRA_OPEN_AUTOSTART)
                if (savedInstanceState == null) showOemAutostartGuide()
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
        // Sehrgar tizim EKRANINI kutayotgan bo'lsa (SETTINGS) — qaytib kelindi, keyingisini ochamiz.
        // TUZATISH (2026-08-13, "3-4 ruxsatdan keyin to'xtaydi"): avval pump FAQAT wentBackground
        // (onStop) yoqilganda bo'lardi. Lekin ko'p Samsung/OEM sozlama ekrani yoki ruxsat dialogi
        // onStop CHAQIRMAYDI (faqat onPause) → wentBackground false qolib, SETTINGS qadami HECH
        // QACHON pump bo'lmasdi va sehrgar qotib qolardi. Endi ekranga har qaytilganda kutilayotgan
        // SETTINGS qadami bo'lsa — davom etamiz. Re-entrantlikdan wizardOutstanding himoya qiladi
        // (null qilib olib, keyin pumpWizard yangisini qo'yadi). LAUNCHER qadamini bu yerda
        // tegmaymiz — uni callback yopadi.
        wentBackground = false
        if (wizardActive && wizardOutstanding == WizKind.SETTINGS) {
            wizardOutstanding = null
            try { pumpWizard() } catch (e: Throwable) { android.util.Log.w("ProtStatus", "wizard resume", e) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Ekran fon'ga tushdi (tizim sozlama/dialogi ochildi yoki ilova almashtirildi) —
        // keyingi onResume'da bu HAQIQIY qaytish deb hisoblanadi (sehrgar SETTINGS pump'i uchun).
        wentBackground = true
    }

    /** Tugma ko'rinishini majburiy ruxsatlar holatiga moslaydi (yoqilmagan bo'lsa — xira). */
    private fun refreshContinueButton() {
        val btn = binding?.btnProtContinue ?: return
        // Tugma "tayyor" ko'rinishi: majburiy ruxsatlar + avto-oyna ruxsatlari (overlay/to'liq-ekran)
        // ham berilganda. Avto-oyna ruxsatlari yetishsa ham tugma bosiladi — onContinueClicked
        // ogohlantirish ko'rsatadi (qattiq bloklamaymiz, lockout bo'lmasin).
        val ok = allCriticalPermissionsGranted(this) && allWindowPermsGranted(this)
        btn.alpha = if (ok) 1f else 0.5f
        btn.setText(if (ok) R.string.kq4_continue else R.string.kq4_prot_continue_locked)
    }

    /**
     * "Davom etish" bosilganda: majburiy (fayl/bildirishnoma/fon) ruxsatlarsiz UMUMAN
     * o'tkazmaymiz — ular har bir telefonda beriladi va ularsiz ilova ishlamaydi. Avto-oyna
     * ruxsatlari (overlay + to'liq-ekran) yetishmasa — ogohlantirib, foydalanuvchi xohlasa
     * baribir o'tkazamiz (ba'zi ROM'larda bu ruxsatlarni umuman berib bo'lmaydi → aks holda
     * onboarding'da abadiy qotib qolardi, bu ilgari real shikoyat bo'lgan).
     */
    private fun onContinueClicked() {
        when {
            !allCriticalPermissionsGranted(this) -> {
                Toast.makeText(this, R.string.kq4_prot_continue_toast, Toast.LENGTH_LONG).show()
                renderRows()
            }
            !allWindowPermsGranted(this) -> showWindowPermWarning()
            else -> proceed(ack = true)
        }
    }

    /**
     * Avto-oyna ruxsatlari yetishmaganda: yoqishni qattiq tavsiya qilamiz, lekin bloklamaymiz.
     *
     * MUHIM (tuzatish): ilgari "Yoqish" tugmasi faqat renderRows() chaqirardi — ya'ni HECH NARSA
     * QILMASDI, foydalanuvchi ruxsatni qanday berishni bilmay qolardi va "skip" bosib o'tib ketardi.
     * Natijada Android 14+ (Samsung A56 va h.k.) qurilmalarda to'liq-ekran ruxsati hech qachon
     * berilmas, virus OYNASI o'zi ochilmasdi (asosiy shikoyat: "oyna chiqmaydi"). Endi "Yoqish" —
     * sehrgarni ishga tushiradi: u overlay + to'liq-ekran ruxsatlarini ketma-ket tizim ekranlarida
     * so'raydi. "Skip" baribir o'tkazadi (ba'zi ROM'da bu ruxsatlarni berib bo'lmaydi — lockout yo'q).
     */
    private fun showWindowPermWarning() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.kq4_prot_window_warn_title)
                .setMessage(R.string.kq4_prot_window_warn_msg)
                .setPositiveButton(R.string.kq4_prot_window_warn_enable) { _, _ -> startWizard() }
                .setNegativeButton(R.string.kq4_prot_window_warn_skip) { _, _ -> proceed(ack = true) }
                .show()
        } catch (_: Throwable) {
            proceed(ack = true)
        }
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
        // To'liq ekranli ogohlantirish (USE_FULL_SCREEN_INTENT) — Android 14+ (API 34) bu ruxsatni
        // oddiy ilovalardan oldi. Ruxsatsiz: qulflangan/o'chiq ekranda virus OYNASI o'zi ochilmaydi
        // (faqat oddiy bildirishnoma). Yangi qurilmalarda (Samsung A56 va h.k.) asosiy sabab — shu.
        // Boolean qator (ring'ga kiradi), lekin gate'ni bloklamaydi (ba'zi ROM'da berilmasligi mumkin).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out.add(
                Row(
                    R.drawable.ic4_alert,
                    getString(R.string.kq4_prot_row_fsi_t),
                    getString(R.string.kq4_prot_row_fsi_s),
                    VersionCompat.canUseFullScreenIntent(this), false,
                ) { openFullScreenIntentSettings() },
            )
        }
        // MIUI/EMUI/ColorOS: standart overlay yetarli emas — "Fonda popup oynasi" alohida
        // tugmasi kerak (default O'CHIQ). Tizim holatini bermaydi → qo'lda (state=null).
        val oemPopup = try { OemAutostartGuide.detect() } catch (_: Throwable) { null }
        if (oemPopup != null && OemAutostartGuide.needsExtraOverlayPermissions(oemPopup)) {
            out.add(
                Row(
                    R.drawable.ic4_alert,
                    getString(R.string.kq4_prot_row_oempopup_t),
                    getString(R.string.kq4_prot_row_oempopup_s),
                    null, false,
                ) { showOemPopupGuide(oemPopup) },
            )
        }
        out.add(
            Row(
                R.drawable.ic4_bell,
                getString(R.string.kq4_notifications),
                getString(R.string.kq4_prot_row_notif_s),
                VersionCompat.hasNotificationPermission(this), true,
            ) { requestNotifications() },
        )
        // Anti-fishing listener'iga bildirishnoma-KIRISH (notification access). Bu ruxsat
        // ILGARI HECH QAYERDA so'ralmasdi — funksiya barcha qurilmalarda jim o'lik edi.
        // TAVSIYA (gate'ni bloklamaydi); faqat direct flavor'da (Play'da listener yo'q).
        if (BuildConfig.NOTIF_LISTENER && Config.isPhishingBlockerEnabled(this)) {
            out.add(
                Row(
                    R.drawable.ic4_message,
                    getString(R.string.kq4_prot_row_notiflisten_t),
                    getString(R.string.kq4_prot_row_notiflisten_s),
                    isNotifListenerGranted(), false,
                ) { openNotificationListenerSettings() },
            )
        }
        out.add(
            Row(
                R.drawable.ic4_globe,
                getString(R.string.kq4_prot_row_loc_t),
                getString(R.string.kq4_prot_row_loc_s),
                DeviceLocation.hasPermission(this), false,
            ) { requestLocation() },
        )
        // «Avtomatik ishga tushirish (MIUI/OEM)» qatori egasi xohishi bilan OLIB TASHLANDI
        // (2026-06-16) — bu ruxsatning holatini tizim bermaydi (null) va u faqat qo'lda
        // yoqilardi. OEM «fonda popup» qatori (showOemPopupGuide) o'z joyida qoladi.
        out.add(
            Row(
                R.drawable.ic4_shield,
                getString(R.string.kq4_prot_row_bg_t),
                getString(R.string.kq4_prot_row_bg_s),
                Config.isBackgroundEnabled(this), true,
            ) { Config.setBackgroundEnabled(this, true); renderRows() },
        )
        // Havola qalqoni — UzGuard standart havola ochuvchimi (TAVSIYA, majburiy emas:
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
        // O'rnatish himoyasi — UzGuard APK fayllar uchun standart ochuvchi (proxodnaya):
        // har bir APK avval tekshiriladi. TAVSIYA (gate'ni bloklamaydi).
        if (Config.isInstallProtectionEnabled(this)) {
            out.add(
                Row(
                    R.drawable.ic4_folder,
                    getString(R.string.install_default_t),
                    getString(R.string.install_default_s),
                    InstallProtectionGuide.isDefaultApkHandler(this), false,
                ) { showDefaultApkGuide() },
            )
        }
        // Jonli o'rnatish qalqoni (Accessibility) — zararli o'rnatishni avtomatik bekor qiladi.
        // TAVSIYA: Android 13+ "cheklangan sozlamalar" tufayli ba'zi ROM'da yoqish ko'p qadamli.
        if (Config.isInstallShieldEnabled(this)) {
            out.add(
                Row(
                    R.drawable.ic4_shield,
                    getString(R.string.install_shield_t),
                    getString(R.string.install_shield_s),
                    InstallProtectionGuide.isShieldServiceEnabled(this), false,
                ) { showInstallShieldGuide() },
            )
        }
        // Noma'lum manbalar — Telegram/WhatsApp/brauzer "noma'lum ilovalarni o'rnatish"i
        // o'chirilsa, o'sha ilovadan APK umuman o'rnatib bo'lmaydi. Tizim holatni bermaydi
        // (boshqa ilova appop'i) → state=null, yo'l-yo'riq qatori.
        if (Config.isInstallProtectionEnabled(this) &&
            InstallProtectionGuide.unknownSourceCandidates(this).isNotEmpty()
        ) {
            out.add(
                Row(
                    R.drawable.ic4_lock,
                    getString(R.string.unknown_sources_t),
                    getString(R.string.unknown_sources_s),
                    null, false,
                ) { showUnknownSourcesGuide() },
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

        // «Hammasini yoqish» — faqat hali yoqilmagan (sehrgar so'ray oladigan) ruxsat
        // qolganda ko'rsatamiz; hammasi yoqilgach yashiramiz (faqat «Davom etish» qoladi).
        val anyPending = try { wizardSteps().any { it.pending() } } catch (_: Throwable) { false }
        b.btnProtEnableAll.visibility = if (anyPending) View.VISIBLE else View.GONE

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

    /** Anti-fishing listener'iga bildirishnoma-kirish berilganmi. */
    private fun isNotifListenerGranted(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
    } catch (_: Throwable) { false }

    private fun openNotificationListenerSettings() = safeStart {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
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

    // ---- «Hammasini yoqish» sehrgari --------------------------------------
    // BITTA tugma → barcha (tekshirib bo'ladigan) ruxsatlarni ketma-ket yoqadi.
    // OEM autostart/popup qatorlari bu yerda YO'Q: ularning holatini tizim bermaydi
    // va ularni faqat qo'lda berish mumkin — shuning uchun ular qatorda yo'l-yo'riq
    // sifatida qoladi (sehrgar abadiy tsiklga tushmasligi uchun).
    private fun wizardSteps(): List<WizStep> {
        val s = ArrayList<WizStep>()
        // 1) Fon himoyasi — bir zumda (Config bayrog'i, tizim ekrani kerak emas).
        s.add(WizStep("bg", { !Config.isBackgroundEnabled(this) }) {
            Config.setBackgroundEnabled(this, true); WizKind.INSTANT
        })
        // 2) Bildirishnoma + joylashuv — BITTA tizim dialogi.
        s.add(WizStep("runtime", {
            !VersionCompat.hasNotificationPermission(this) || !DeviceLocation.hasPermission(this)
        }) {
            val req = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !VersionCompat.hasNotificationPermission(this)
            ) {
                req.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!DeviceLocation.hasPermission(this)) {
                req.add(Manifest.permission.ACCESS_FINE_LOCATION)
                req.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (req.isEmpty()) {
                WizKind.INSTANT
            } else {
                wizardPermLauncher.launch(req.toTypedArray()); WizKind.LAUNCHER
            }
        })
        // 3) Fayllarga kirish (MANAGE_EXTERNAL_STORAGE / READ) — tizim ekrani.
        s.add(WizStep("files", { !VersionCompat.hasFileScanAccess(this) }) {
            toastStep(R.string.kq4_prot_row_files_t)
            if (openAllFiles()) WizKind.SETTINGS else WizKind.INSTANT
        })
        // 4) Batareya cheklovisiz ishlash — tizim ekrani.
        s.add(WizStep("battery", { !batteryIgnored() }) {
            toastStep(R.string.kq4_prot_row_battery_t)
            if (openBattery()) WizKind.SETTINGS else WizKind.INSTANT
        })
        // 5) Oynalar ustida ko'rsatish (overlay) — tizim ekrani.
        s.add(WizStep("overlay", { !VersionCompat.hasOverlayPermission(this) }) {
            toastStep(R.string.kq4_prot_row_overlay_t)
            if (openOverlay()) WizKind.SETTINGS else WizKind.INSTANT
        })
        // 6) To'liq ekranli ogohlantirish (Android 14+) — tizim ekrani.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            s.add(WizStep("fsi", { !VersionCompat.canUseFullScreenIntent(this) }) {
                toastStep(R.string.kq4_prot_row_fsi_t)
                if (openFullScreenIntentSettings()) WizKind.SETTINGS else WizKind.INSTANT
            })
        }
        // 7) Havola qalqoni (standart ilova) — yoqilgan bo'lsa, tizim ekrani.
        if (Config.isLinkGuardEnabled(this)) {
            s.add(WizStep("linkguard", { !LinkForwarder.isDefaultLinkHandler(this) }) {
                toastStep(R.string.kq4_prot_row_linkguard_t)
                if (openDefaultApps()) WizKind.SETTINGS else WizKind.INSTANT
            })
        }
        // 8) O'rnatish himoyasi — UzGuard'ni APK uchun standart qilish (tizim ekrani).
        if (Config.isInstallProtectionEnabled(this)) {
            s.add(WizStep("installdefault", { !InstallProtectionGuide.isDefaultApkHandler(this) }) {
                toastStep(R.string.install_default_t); InstallProtectionGuide.promptSetDefaultApk(this); WizKind.SETTINGS
            })
        }
        // 9) Jonli o'rnatish qalqoni — Accessibility sozlamalari (tizim ekrani).
        if (Config.isInstallShieldEnabled(this)) {
            s.add(WizStep("installshield", { !InstallProtectionGuide.isShieldServiceEnabled(this) }) {
                toastStep(R.string.install_shield_t); InstallProtectionGuide.openAccessibilitySettings(this); WizKind.SETTINGS
            })
        }
        // 9b) Anti-fishing bildirishnoma-kirishi — tizim ekrani (holati tekshiriladi).
        if (BuildConfig.NOTIF_LISTENER && Config.isPhishingBlockerEnabled(this)) {
            s.add(WizStep("notiflisten", { !isNotifListenerGranted() }) {
                toastStep(R.string.kq4_prot_row_notiflisten_t)
                if (openNotificationListenerSettings()) WizKind.SETTINGS else WizKind.INSTANT
            })
        }
        // 10) Internet himoyasi (VPN C2-filtri) — ruxsat bo'lsa darhol, bo'lmasa tasdiq oynasi.
        s.add(WizStep("vpn", { !isVpnReady() }) {
            val prep = try { VpnFilterService.prepareIntent(this) } catch (_: Throwable) { null }
            if (prep == null) {
                try {
                    Config.setVpnFilterEnabled(this, true); VpnFilterService.start(this)
                } catch (_: Throwable) {}
                WizKind.INSTANT
            } else {
                vpnLauncher.launch(prep); WizKind.LAUNCHER
            }
        })
        return s
    }

    /** Sehrgarni boshlaydi: holatni tozalab, birinchi yoqilmagan ruxsatni so'raydi. */
    private fun startWizard() {
        if (wizardActive) return
        wizardDone.clear()
        wizardOutstanding = null
        wizardActive = true
        try { Toast.makeText(this, R.string.kq4_prot_wizard_start, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        pumpWizard()
    }

    /**
     * Navbatdagi yoqilmagan ruxsatni topib so'raydi. Bir vaqtda FAQAT bitta amal
     * "kutuvda" bo'ladi (wizardOutstanding) — shu sabab tizim ekrani/dialogidan
     * qaytishda ikki marta o'tib ketmaymiz. INSTANT amal darhol keyingisiga o'tadi.
     */
    private fun pumpWizard() {
        if (!wizardActive || wizardOutstanding != null) return
        val next = try {
            wizardSteps().firstOrNull { it.id !in wizardDone && it.pending() }
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "wizard scan", e); null
        }
        if (next == null) { finishWizard(); return }
        wizardDone.add(next.id)
        val kind = try { next.run() } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "wizard step ${next.id}", e); WizKind.INSTANT
        }
        when (kind) {
            WizKind.INSTANT -> pumpWizard() // darhol keyingisiga
            WizKind.SETTINGS, WizKind.LAUNCHER -> wizardOutstanding = kind // qaytishni kutamiz
        }
    }

    /** runtime/VPN dialog yopilgach (callback) — sehrgarni davom ettiramiz. */
    private fun onWizardLauncherResult() {
        if (wizardActive && wizardOutstanding == WizKind.LAUNCHER) {
            wizardOutstanding = null
            try { pumpWizard() } catch (e: Throwable) { android.util.Log.w("ProtStatus", "wizard cb", e) }
        }
    }

    private fun finishWizard() {
        wizardActive = false
        wizardOutstanding = null
        renderRows()
        val ok = try {
            allCriticalPermissionsGranted(this) && allWindowPermsGranted(this)
        } catch (_: Throwable) { false }
        try {
            Toast.makeText(
                this,
                if (ok) R.string.kq4_prot_wizard_done_ok else R.string.kq4_prot_wizard_done_partial,
                Toast.LENGTH_LONG,
            ).show()
        } catch (_: Throwable) {}
    }

    /** Tizim ekrani ochilishidan oldin "nimani yoqish" kerakligini Toast bilan aytamiz. */
    private fun toastStep(titleRes: Int) {
        try {
            Toast.makeText(
                this,
                getString(R.string.kq4_prot_wizard_open, getString(titleRes)),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: Throwable) {}
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

    /**
     * "To'liq ekranli bildirishnoma" tizim ekrani — Android 14+ (API 34) da virus oynasi
     * qulflangan ekranda o'zi ochilishi uchun zarur ruxsat shu yerdan beriladi.
     */
    private fun openFullScreenIntentSettings() = safeStart {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
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
     * Bildirishnoma ruxsatini so'raydi (joylashuv qatori bilan bir xil idioma). Android 13+ da
     * avval HAQIQIY tizim dialogini ko'rsatamiz; "boshqa so'ralmasin" tanlangan (rationale=false
     * va avval so'ralgan) bo'lsa — Sozlamalarga. Bildirishnoma — tahdid ogohlantirishining
     * UNIVERSAL kanali (overlay/FSI bo'lmaganda ham keladi), shuning uchun uni real so'rashimiz SHART.
     */
    private fun requestNotifications() {
        if (VersionCompat.hasNotificationPermission(this)) { renderRows(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            if (!notifAsked || rationale) {
                notifAsked = true
                notifLauncher.launch(perm)
                return
            }
        }
        // API < 33 (install-time ruxsat) yoki doimiy rad — tizim sozlamalariga.
        openNotifications()
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
     * MIUI/EMUI/ColorOS "fonda popup oynasi" + "lock ekranda ko'rsatish" ruxsatlari.
     * Standart [Settings.canDrawOverlays] bu telefonlarda YETARLI EMAS — fon'dan oyna ochish
     * uchun alohida vendor tugmasi kerak (default o'chiq). Avval o'zbekcha yo'riqnoma, so'ng
     * OemAutostartGuide tegishli "Other permissions" ekranini ochadi. Ochilmasa — qo'lda
     * yo'lni Toast bilan aytamiz.
     */
    /**
     * OEM autostart yo'riqnomasi + "Ochish" → OemAutostartGuide.openAutostartSettings.
     * Kill-detected bildirishnomasidan chaqiriladi (EXTRA_OPEN_AUTOSTART).
     */
    private fun showOemAutostartGuide() {
        try {
            val oem = OemAutostartGuide.detect()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notif_kill_title))
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

    private fun showOemPopupGuide(oem: OemAutostartGuide.Oem) {
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.kq4_prot_row_oempopup_t))
                .setMessage(OemAutostartGuide.overlayInstructions(oem))
                .setPositiveButton(R.string.kq4_prot_autostart_open) { _, _ ->
                    val opened = try {
                        OemAutostartGuide.openOemAppPermissions(this, oem)
                    } catch (_: Throwable) { false }
                    if (!opened) {
                        Toast.makeText(this, R.string.kq4_prot_autostart_fail, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.kq4_prot_autostart_close, null)
                .show()
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "oem popup guide failed", e)
        }
    }

    /**
     * Jonli o'rnatish qalqonini yoqish yo'riqnomasi. Avval nima uchun kerakligini va
     * Android 13+ "cheklangan sozlamalar" qadamini tushuntiramiz, so'ng Maxsus imkoniyatlar
     * (Accessibility) ekranini ochamiz (u yerda foydalanuvchi UzGuard'ni yoqadi).
     */
    private fun showInstallShieldGuide() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.install_shield_guide_title)
                .setMessage(R.string.install_shield_guide_msg)
                .setPositiveButton(R.string.kq4_prot_autostart_open) { _, _ ->
                    InstallProtectionGuide.openAccessibilitySettings(this)
                }
                .setNegativeButton(R.string.kq4_prot_autostart_close, null)
                .show()
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "install shield guide failed", e)
            InstallProtectionGuide.openAccessibilitySettings(this)
        }
    }

    /**
     * "Noma'lum manbalarni o'chirish" — qurilmada o'rnatilgan xavfli manbalar (Telegram,
     * WhatsApp, brauzer) ro'yxatini ko'rsatadi; tanlansa — o'sha ilovaning "Noma'lum
     * ilovalarni o'rnatish" toggle ekranini ochamiz (foydalanuvchi o'chiradi).
     */
    /**
     * UzGuard'ni APK uchun standart ilova qilish — ENG QULAY yo'l. Tushuntirib, bitta tugma
     * bilan tizimning "Qaysi ilova bilan ochish?" oynasini ochamiz (APK qidirish shart emas).
     * U yerda foydalanuvchi «UzGuard» + «Doimo»ni bossa — har bir APK avval bizda tekshiriladi.
     */
    private fun showDefaultApkGuide() {
        if (InstallProtectionGuide.isDefaultApkHandler(this)) {
            try { Toast.makeText(this, R.string.set_default_apk_done, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            renderRows()
            return
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_default_apk_prompt_title)
                .setMessage(R.string.set_default_apk_prompt_msg)
                .setPositiveButton(R.string.set_default_apk_btn) { _, _ ->
                    if (!InstallProtectionGuide.triggerSetDefaultApk(this)) {
                        InstallProtectionGuide.promptSetDefaultApk(this)
                    }
                }
                .setNegativeButton(R.string.kq4_prot_autostart_close, null)
                .show()
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "default apk guide failed", e)
            InstallProtectionGuide.triggerSetDefaultApk(this)
        }
    }

    private fun showUnknownSourcesGuide() {
        val apps = try {
            InstallProtectionGuide.unknownSourceCandidates(this)
        } catch (_: Throwable) { emptyList() }
        if (apps.isEmpty()) {
            try { Toast.makeText(this, R.string.unknown_sources_none, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            return
        }
        try {
            val labels = apps.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.unknown_sources_dialog_title)
                .setItems(labels) { _, which ->
                    InstallProtectionGuide.openUnknownSourceFor(this, apps[which].pkg)
                }
                .setNegativeButton(R.string.kq4_prot_autostart_close, null)
                .show()
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "unknown sources guide failed", e)
        }
    }

    // Ekran HAQIQATAN ochilganini qaytaradi. Sehrgar (pumpWizard) shu natijaga qarab
    // hal qiladi: ochilmasa (null intent yoki xato), SETTINGS qadamni kutib qotib
    // qolmasdan darhol keyingisiga o'tadi (WizKind.INSTANT). Aks holda foydalanuvchi
    // "hech narsa chiqmadi" holatida abadiy kutardi — asosiy shikoyat: "3-4 ruxsatdan
    // keyin oyna chiqmay to'xtab qoladi".
    private inline fun safeStart(build: () -> Intent?): Boolean {
        return try {
            val i = build() ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) == null) return false
            startActivity(i)
            true
        } catch (e: Throwable) {
            android.util.Log.w("ProtStatus", "open settings failed", e)
            false
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
        /** Kill-detected bildirishnomasi: darhol OEM autostart yo'riqnomasini ochish. */
        const val EXTRA_OPEN_AUTOSTART = "open_autostart"

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

        /**
         * Avto-oyna (AutoScanActivity) fon'dan o'zi chiqishi uchun kerakli, TEKSHIRIB BO'LADIGAN
         * ruxsatlar: overlay (Android 10+ BAL exemption) + Android 14+ da to'liq-ekran intent.
         * Bular A56/Redmi'da beriladi. MUHIM: bu allCriticalPermissionsGranted'ga QO'SHILMAYDI —
         * aks holda berib bo'lmaydigan ROM'da SplashActivity har safar gate'ga qaytarib tsiklga
         * tushirardi. O'rniga "Davom etish" tugmasida ogohlantirish bilan qattiq tavsiya qilamiz.
         */
        fun allWindowPermsGranted(ctx: Context): Boolean {
            // API < 29 (Q): fon'dan startActivity cheklovsiz — overlay shart EMAS (J4/Android 8-9 da
            // oyna baribir chiqadi), shuning uchun overlay'ni faqat 29+ da talab qilamiz; aks holda
            // eski telefonda ishlab turgani holda ortiqcha "avto-oyna o'chirilgan" ogohlantirish chiqardi.
            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                VersionCompat.hasOverlayPermission(ctx)
            return overlayOk && VersionCompat.canUseFullScreenIntent(ctx)
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
