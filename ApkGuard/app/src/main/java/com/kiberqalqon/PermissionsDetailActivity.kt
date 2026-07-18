package com.uzguard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.uzguard.databinding.ActivityPermissionsDetailBinding

/**
 * v4 dizayn «Ruxsatlar» ekrani (design_v4_extracted/screens3.jsx → Permissions):
 * skan natijasidan ochiladi, APK so'ragan HAQIQIY ruxsatlarni 3 guruhda
 * (KRITIK / DIQQAT / ODDIY) sodda tilda tushuntiradi.
 *
 * Bu faqat KO'RSATISH (UI) — skaner verdiktiga ta'sir qilmaydi.
 */
class PermissionsDetailActivity : AppCompatActivity() {

    /** Ko'rsatish darajasi (UI guruhlash) — skaner verdiktidan mustaqil. */
    enum class Sev { CRIT, WARN, NORMAL }

    private lateinit var binding: ActivityPermissionsDetailBinding

    companion object {
        const val EXTRA_APK_NAME = "apk_name"
        const val EXTRA_PERMISSIONS = "permissions"

        fun intent(context: Context, apkName: String, permissions: ArrayList<String>): Intent =
            Intent(context, PermissionsDetailActivity::class.java).apply {
                putExtra(EXTRA_APK_NAME, apkName)
                putStringArrayListExtra(EXTRA_PERMISSIONS, permissions)
            }

        // Dizayn bo'yicha aniq guruhlash (qisqa nomlar bo'yicha).
        private val CRIT_SHORT = setOf(
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS",
            "BIND_ACCESSIBILITY_SERVICE", "SYSTEM_ALERT_WINDOW"
        )
        private val WARN_SHORT = setOf(
            "READ_PHONE_STATE", "READ_CONTACTS", "READ_CALL_LOG",
            "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES", "CAMERA", "RECORD_AUDIO"
        )
        private val NORMAL_SHORT = setOf(
            "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE",
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"
        )

        /**
         * Ruxsatni UI darajasiga ajratadi: avval dizayndagi aniq ro'yxatlar,
         * qolganlari uchun PermissionCatalog darajasi.
         */
        fun classify(permission: String): Sev {
            val short = permission.substringAfterLast('.').uppercase()
            return when {
                short in CRIT_SHORT -> Sev.CRIT
                short in WARN_SHORT -> Sev.WARN
                short in NORMAL_SHORT -> Sev.NORMAL
                else -> when (PermissionCatalog.severity(permission)) {
                    PermissionCatalog.Severity.CRITICAL -> Sev.CRIT
                    PermissionCatalog.Severity.WARNING -> Sev.WARN
                    PermissionCatalog.Severity.NORMAL -> Sev.NORMAL
                }
            }
        }
    }

    /** Bitta ruxsatning inson tilidagi ko'rinishi: nom + izoh + ikonka. */
    private data class PermUi(val titleRes: Int, val subRes: Int, val iconRes: Int)

    /** data.jsx PERMS dagi tushuntirishlar — qisqa nom bo'yicha. */
    private val permUi: Map<String, PermUi> = mapOf(
        "READ_SMS" to PermUi(R.string.kq4_scanres_perm_read_sms, R.string.kq4_scanres_perm_read_sms_s, R.drawable.ic4_message),
        "RECEIVE_SMS" to PermUi(R.string.kq4_scanres_perm_recv_sms, R.string.kq4_scanres_perm_recv_sms_s, R.drawable.ic4_message),
        "SEND_SMS" to PermUi(R.string.kq4_scanres_perm_send_sms, R.string.kq4_scanres_perm_send_sms_s, R.drawable.ic4_message),
        "BIND_ACCESSIBILITY_SERVICE" to PermUi(R.string.kq4_scanres_perm_a11y, R.string.kq4_scanres_perm_a11y_s, R.drawable.ic4_eye),
        "SYSTEM_ALERT_WINDOW" to PermUi(R.string.kq4_scanres_perm_overlay, R.string.kq4_scanres_perm_overlay_s, R.drawable.ic4_alert),
        "READ_PHONE_STATE" to PermUi(R.string.kq4_scanres_perm_phone_state, R.string.kq4_scanres_perm_phone_state_s, R.drawable.ic4_phone),
        "READ_CONTACTS" to PermUi(R.string.kq4_scanres_perm_contacts, R.string.kq4_scanres_perm_contacts_s, R.drawable.ic4_user),
        "READ_CALL_LOG" to PermUi(R.string.kq4_scanres_perm_call_log, R.string.kq4_scanres_perm_call_log_s, R.drawable.ic4_phone),
        "REQUEST_INSTALL_PACKAGES" to PermUi(R.string.kq4_scanres_perm_install, R.string.kq4_scanres_perm_install_s, R.drawable.ic4_lock),
        "QUERY_ALL_PACKAGES" to PermUi(R.string.kq4_scanres_perm_query_all, R.string.kq4_scanres_perm_query_all_s, R.drawable.ic4_layers),
        "CAMERA" to PermUi(R.string.kq4_scanres_perm_camera, R.string.kq4_scanres_perm_camera_s, R.drawable.ic4_eye),
        "RECORD_AUDIO" to PermUi(R.string.kq4_scanres_perm_mic, R.string.kq4_scanres_perm_mic_s, R.drawable.ic4_eye),
        "INTERNET" to PermUi(R.string.kq4_scanres_perm_internet, R.string.kq4_scanres_perm_internet_s, R.drawable.ic4_globe),
        "ACCESS_NETWORK_STATE" to PermUi(R.string.kq4_scanres_perm_network, R.string.kq4_scanres_perm_network_s, R.drawable.ic4_wifi),
        "ACCESS_WIFI_STATE" to PermUi(R.string.kq4_scanres_perm_network, R.string.kq4_scanres_perm_network_s, R.drawable.ic4_wifi),
        "READ_EXTERNAL_STORAGE" to PermUi(R.string.kq4_scanres_perm_storage, R.string.kq4_scanres_perm_storage_s, R.drawable.ic4_folder),
        "WRITE_EXTERNAL_STORAGE" to PermUi(R.string.kq4_scanres_perm_storage, R.string.kq4_scanres_perm_storage_s, R.drawable.ic4_folder),
        "READ_MEDIA_IMAGES" to PermUi(R.string.kq4_scanres_perm_storage, R.string.kq4_scanres_perm_storage_s, R.drawable.ic4_folder),
        "READ_MEDIA_VIDEO" to PermUi(R.string.kq4_scanres_perm_storage, R.string.kq4_scanres_perm_storage_s, R.drawable.ic4_folder),
        "READ_MEDIA_AUDIO" to PermUi(R.string.kq4_scanres_perm_storage, R.string.kq4_scanres_perm_storage_s, R.drawable.ic4_folder)
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ScanResult kabi maxfiy ekran — skrinshotni bloklaymiz.
        try {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Throwable) { /* muhim emas */ }
        ThemeHelper.applyAccent(this)
        binding = ActivityPermissionsDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvApkName.text = intent.getStringExtra(EXTRA_APK_NAME) ?: ""
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBackBottom.setOnClickListener { finish() }

        val all = (intent.getStringArrayListExtra(EXTRA_PERMISSIONS) ?: arrayListOf()).distinct()
        val crit = all.filter { classify(it) == Sev.CRIT }
        val warn = all.filter { classify(it) == Sev.WARN }
        val normal = all.filter { classify(it) == Sev.NORMAL }

        // Qizil ogohlantirish — faqat haqiqatan kritik ruxsat bo'lsa.
        binding.cardSummary.visibility = if (crit.isEmpty()) View.GONE else View.VISIBLE

        bindGroup(binding.groupCrit, binding.critContainer, crit, Sev.CRIT)
        bindGroup(binding.groupWarn, binding.warnContainer, warn, Sev.WARN)
        bindGroup(binding.groupNormal, binding.normalContainer, normal, Sev.NORMAL)
    }

    private fun bindGroup(group: View, container: LinearLayout, perms: List<String>, sev: Sev) {
        if (perms.isEmpty()) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        container.removeAllViews()

        val avBg = when (sev) {
            Sev.CRIT -> R.drawable.kq4_av_danger
            Sev.WARN -> R.drawable.kq4_av_warn
            Sev.NORMAL -> R.drawable.kq4_av_neutral
        }
        val tint = ColorStateList.valueOf(
            getColor(
                when (sev) {
                    Sev.CRIT -> R.color.kq_danger
                    Sev.WARN -> R.color.kq_warn
                    Sev.NORMAL -> R.color.kq_ink_3
                }
            )
        )
        val genericSubRes = when (sev) {
            Sev.CRIT -> R.string.kq4_scanres_perm_generic_crit
            Sev.WARN -> R.string.kq4_scanres_perm_generic_warn
            Sev.NORMAL -> R.string.kq4_scanres_perm_generic_normal
        }

        val inflater = layoutInflater
        perms.forEachIndexed { index, perm ->
            val view = inflater.inflate(R.layout.inc_kq_perm_row, container, false)
            val short = perm.substringAfterLast('.').uppercase()
            val ui = permUi[short]

            view.findViewById<FrameLayout>(R.id.permAv).setBackgroundResource(avBg)
            val icon = view.findViewById<ImageView>(R.id.permIcon)
            icon.setImageResource(ui?.iconRes ?: R.drawable.ic4_key)
            icon.imageTintList = tint

            view.findViewById<TextView>(R.id.tvPermLabel).text =
                if (ui != null) getString(ui.titleRes)
                else PermissionCatalog.label(this, perm)

            val sub = view.findViewById<TextView>(R.id.tvPermSub)
            sub.visibility = View.VISIBLE
            sub.text = if (ui != null) getString(ui.subRes) else getString(genericSubRes)

            view.findViewById<View>(R.id.permDivider).visibility =
                if (index == perms.lastIndex) View.GONE else View.VISIBLE

            container.addView(view)
            AnimationHelper.fadeIn(view, duration = 320, delay = index * 50L)
        }
    }
}
