package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityScanResultBinding
import kotlinx.coroutines.*
import java.io.File

/**
 * Virus / xavf topilganda ko'rsatiladigan natija oynasi — v4 «Milliy Kiber Himoya»
 * dizayni (screens3.jsx → ScanResult).
 *
 * Tuzilishi: rangli banner (verdict + manba + fayl nomi) → "Bu fayl nima?" →
 * "Bu fayl nima qiladi?" (haqiqiy xavfli topilmalar inson tilida) →
 * "Qayerdan keldi?" (manba ogohlantirishi) → "So'ralgan ruxsatlar" havolasi
 * (PermissionsDetailActivity) → harakatlar (o'chirish / do'stlarni ogohlantirish).
 *
 * Ruxsatlar haqiqiy APK'dan o'qiladi (readApkMeta) — dizayndagi THREATS faqat namuna.
 */
class ScanResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanResultBinding
    private var apkPath: String = ""
    private var installedPackage: String? = null
    private var scanResult: ScanResult? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private enum class SourceKind { TELEGRAM, WHATSAPP, WEB, FOLDER, INSTALLED }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tahdid verdikti maxfiy ekran — skrinshot/ekran yozuvini bloklaymiz (FLAG_SECURE),
        // shunda boshqa ilova yoki overlay tahlilchi natija/IOC'larni ko'chirib ololmaydi.
        try {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Throwable) { /* FLAG_SECURE muhim emas — UI ishlashda davom etadi */ }
        ThemeHelper.applyAccent(this)
        binding = ActivityScanResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: ""
        installedPackage = intent.getStringExtra(EXTRA_PACKAGE)
        val verdict = intent.getSerializableExtra(EXTRA_VERDICT) as? ScanResult.Verdict
        val res = ScanResult(
            verdict = verdict ?: ScanResult.Verdict.SAFE,
            reason = intent.getStringExtra(EXTRA_REASON) ?: "",
            details = intent.getStringArrayListExtra(EXTRA_DETAILS) ?: emptyList(),
            dangerousPermissions = intent.getStringArrayListExtra(EXTRA_PERMS) ?: emptyList(),
            malwareSignatures = intent.getStringArrayListExtra(EXTRA_SIGS) ?: emptyList()
        )
        scanResult = res

        binding.btnBack.setOnClickListener { finish() }

        // Ovozli verdict — Config.isSoundEnabled'da o'chirilgan bo'lsa, jim turadi.
        VoiceVerdict.init(this)
        VoiceVerdict.speak(this, res.verdict)

        bindVerdict(res)
        bindSource(res)
        bindActions(res)
        loadPermissions(res)

        revealVerdict(res.verdict)
    }

    /**
     * Verdict ochilish mikro-animatsiyasi: fayl-ikonka yengil "pop" (overshoot) bilan
     * paydo bo'ladi; XAVF bo'lsa qo'shimcha ogohlantiruvchi tebranish.
     */
    private fun revealVerdict(verdict: ScanResult.Verdict) {
        AnimationHelper.bounce(binding.imgVerdictIcon, duration = 600)
        if (verdict == ScanResult.Verdict.DANGER) {
            binding.imgVerdictIcon.postDelayed(
                { AnimationHelper.shake(binding.imgVerdictIcon) },
                620
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        VoiceVerdict.shutdown()
    }

    /** Banner — fon rangi, verdict-tag, fayl nomi/hajmi, "o'zini ... ko'rsatadi". */
    private fun bindVerdict(res: ScanResult) {
        val dotColor: Int
        when (res.verdict) {
            ScanResult.Verdict.SAFE -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq4_scanres_banner_safe)
                binding.tvVerdict.text = getString(R.string.kq4_safe)
                dotColor = Color.parseColor("#AEF3C9")
                binding.imgWhatIcon.imageTintList =
                    ColorStateList.valueOf(getColor(R.color.kq_safe))
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq4_scanres_banner_warn)
                binding.tvVerdict.text = getString(R.string.kq4_suspicious)
                dotColor = Color.parseColor("#FFE3B3")
                binding.imgWhatIcon.imageTintList =
                    ColorStateList.valueOf(getColor(R.color.kq_warn))
            }
            ScanResult.Verdict.DANGER -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq4_scanresultpermissions_banner_danger)
                binding.tvVerdict.text = getString(R.string.kq4_danger)
                dotColor = Color.parseColor("#FFB3AA")
                binding.imgWhatIcon.imageTintList =
                    ColorStateList.valueOf(getColor(R.color.kq_danger))
            }
        }
        binding.dotVerdict.backgroundTintList = ColorStateList.valueOf(dotColor)

        binding.tvReason.text =
            if (res.reason.isNotBlank()) res.reason else defaultReason(res.verdict)

        val file = File(apkPath)
        // tvFileName boshlang'ich qiymat — keyin APK'dan ilova nomi o'qilsa, ustiga yoziladi.
        binding.tvFileName.text = file.name.ifBlank { getString(R.string.scan_result_title) }
        binding.tvFileSize.text = if (file.exists()) "${file.length() / 1024} KB" else ""

        // "O'zini «...» qilib ko'rsatadi" — fayl nomi niqobidan; SAFE'da ko'rsatilmaydi.
        val like = if (res.verdict == ScanResult.Verdict.SAFE) null else looksLikeLabel(file.name)
        if (like != null) {
            binding.tvLooksLike.visibility = View.VISIBLE
            binding.tvLooksLike.text = getString(R.string.kq4_scanres_lookslike, like)
        } else {
            binding.tvLooksLike.visibility = View.GONE
        }
    }

    private fun defaultReason(v: ScanResult.Verdict): String = when (v) {
        ScanResult.Verdict.DANGER -> getString(R.string.reason_danger_default)
        ScanResult.Verdict.SUSPICIOUS -> getString(R.string.suspicious_reason)
        ScanResult.Verdict.SAFE -> getString(R.string.safe_reason)
    }

    // ─────────────────────── Manba ("Qayerdan keldi?") ───────────────────────

    private fun detectSource(): SourceKind {
        if (!installedPackage.isNullOrBlank() || apkPath.contains("/data/app")) {
            return SourceKind.INSTALLED
        }
        val p = apkPath.lowercase()
        return when {
            p.contains("telegram") -> SourceKind.TELEGRAM
            p.contains("whatsapp") -> SourceKind.WHATSAPP
            p.contains("download") -> SourceKind.WEB
            else -> SourceKind.FOLDER
        }
    }

    /** Fayl nomi kimga o'xshatib yasalgan — sodda niqob-aniqlash (faqat ko'rsatish uchun). */
    private fun looksLikeLabel(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("taklifnoma") -> getString(R.string.kq4_scanres_like_invite)
            Regex("rasm|foto|photo|img|surat").containsMatchIn(n) ->
                getString(R.string.kq4_scanres_like_photos)
            Regex("video|vid[_.]|\\.mp4|\\.mov").containsMatchIn(n) ->
                getString(R.string.kq4_scanres_like_video)
            Regex("\\.pdf|\\.docx?|hujjat|shartnoma").containsMatchIn(n) ->
                getString(R.string.kq4_scanres_like_doc)
            else -> null
        }
    }

    private fun bindSource(res: ScanResult) {
        val kind = detectSource()

        binding.tvSource.text = getString(
            when (kind) {
                SourceKind.TELEGRAM -> R.string.kq4_scanres_src_telegram
                SourceKind.WHATSAPP -> R.string.kq4_scanres_src_whatsapp
                SourceKind.WEB -> R.string.kq4_scanres_src_web
                SourceKind.FOLDER -> R.string.kq4_scanres_src_folder
                SourceKind.INSTALLED -> R.string.kq4_scanres_src_installed
            }
        )

        binding.imgSourceIcon.setImageResource(
            when (kind) {
                SourceKind.TELEGRAM, SourceKind.WHATSAPP -> R.drawable.ic4_message
                SourceKind.WEB -> R.drawable.ic4_globe
                SourceKind.INSTALLED -> R.drawable.ic4_phone
                SourceKind.FOLDER -> R.drawable.ic4_folder
            }
        )

        if (res.verdict == ScanResult.Verdict.SAFE) {
            // SAFE — yumshoq neytral karta, ogohlantirish o'rniga maslahat.
            binding.cardSource.setBackgroundResource(R.drawable.kq4_card_sunken)
            val ink2 = ColorStateList.valueOf(getColor(R.color.kq_ink_2))
            binding.imgSourceIcon.imageTintList = ink2
            binding.tvSourceWarn.setTextColor(getColor(R.color.kq_ink_2))
            binding.tvSourceWarn.text = getString(R.string.kq4_scanres_src_safe_note)
        } else {
            binding.tvSourceWarn.text = getString(
                when (kind) {
                    SourceKind.TELEGRAM -> R.string.kq4_scanres_warn_telegram
                    SourceKind.WHATSAPP -> R.string.kq4_scanres_warn_whatsapp
                    SourceKind.WEB -> R.string.kq4_scanres_warn_web
                    SourceKind.FOLDER -> R.string.kq4_scanres_warn_folder
                    SourceKind.INSTALLED -> R.string.kq4_scanres_warn_installed
                }
            )
        }
    }

    // ─────────────────────── Harakatlar ───────────────────────

    private fun bindActions(res: ScanResult) {
        val pkg = installedPackage
        if (!pkg.isNullOrBlank()) {
            // O'rnatilgan ilova — "o'chirish" = tizimning uninstall oynasi.
            binding.btnDelete.text = getString(R.string.uninstall_app)
            binding.btnDelete.setOnClickListener { uninstall(pkg) }
        } else {
            // Yuklab olingan APK fayl — faylni o'chirish (tasdiq bilan).
            binding.btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_confirm_title))
                    .setMessage(getString(R.string.delete_confirm_message))
                    .setPositiveButton(getString(R.string.delete_apk)) { _, _ -> deleteApk() }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            if (res.verdict == ScanResult.Verdict.SAFE) {
                // SAFE fayl — o'chirish tugmasi o'rniga oddiy "Yopish".
                binding.btnDelete.visibility = View.GONE
            }
        }

        // Do'stlarga ogohlantirish — faqat xavf bo'lganda mantiqiy.
        binding.btnRecheck.setOnClickListener { shareWarning(res) }
        binding.btnShareTop.setOnClickListener { shareWarning(res) }
        if (res.verdict == ScanResult.Verdict.SAFE) {
            binding.btnRecheck.visibility = View.GONE
            binding.btnShareTop.visibility = View.GONE
            binding.btnClose.visibility = View.VISIBLE
            binding.btnClose.setOnClickListener { finish() }
        }
    }

    private fun uninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")))
        } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareWarning(res: ScanResult) {
        val name = File(apkPath).name.ifBlank { binding.tvFileName.text?.toString() ?: "APK" }
        val bodyRes = if (res.verdict == ScanResult.Verdict.SUSPICIOUS)
            R.string.share_text_suspicious else R.string.share_text_danger
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, getString(bodyRes, name))
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────── Ruxsat tahlili ───────────────────────

    private data class ApkMeta(val perms: List<String>, val pkg: String?, val label: String?)

    private fun loadPermissions(res: ScanResult) {
        scope.launch {
            val meta = withContext(Dispatchers.IO) { readApkMeta(res) }

            meta.label?.takeIf { it.isNotBlank() }?.let { binding.tvFileName.text = it }
            if (meta.pkg != null) {
                binding.tvPackageName.visibility = View.VISIBLE
                binding.tvPackageName.text = meta.pkg
            } else {
                binding.tvPackageName.visibility = View.GONE
            }

            val all = meta.perms.distinct()
            renderDoes(all, res)
            renderPermsCard(all, res)
        }
    }

    /** To'liq ruxsat ro'yxati + paket nomi + ilova nomini o'qiydi. */
    private fun readApkMeta(res: ScanResult): ApkMeta {
        val pm = packageManager

        // 1) Yuklab olingan APK fayl (yoki o'rnatilgan ilovaning base.apk yo'li).
        if (apkPath.isNotBlank()) {
            try {
                val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)
                if (info != null) {
                    val label = info.applicationInfo?.let { ai ->
                        ai.sourceDir = apkPath
                        ai.publicSourceDir = apkPath
                        try { ai.loadLabel(pm).toString() } catch (_: Throwable) { null }
                    }
                    val perms = info.requestedPermissions?.toList().orEmpty()
                    if (perms.isNotEmpty() || installedPackage.isNullOrBlank()) {
                        return ApkMeta(
                            perms = perms.ifEmpty { res.dangerousPermissions },
                            pkg = info.packageName ?: installedPackage,
                            label = label
                        )
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("ScanResult", "archive perms failed", e)
            }
        }

        // 2) O'rnatilgan ilova — paket nomi orqali (base.apk o'qib bo'lmaganda ishonchli yo'l).
        val pkg = installedPackage
        if (!pkg.isNullOrBlank()) {
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val label = try { info.applicationInfo?.loadLabel(pm)?.toString() } catch (_: Throwable) { null }
                val perms = info.requestedPermissions?.toList().orEmpty()
                return ApkMeta(perms.ifEmpty { res.dangerousPermissions }, pkg, label)
            } catch (e: Throwable) {
                android.util.Log.w("ScanResult", "installed perms failed", e)
            }
        }

        // 3) Hech narsa o'qilmadi — skanerdan kelgan xavfli ruxsatlarni ko'rsatamiz.
        return ApkMeta(res.dangerousPermissions, pkg, null)
    }

    // ─────────────────────── "Bu fayl nima qiladi?" ───────────────────────

    private data class DoesRow(val iconRes: Int, val textRes: Int)

    /** Haqiqiy topilmalardan (siglar + ruxsatlar) inson tilidagi ro'yxat. */
    private fun buildDoesRows(allPerms: List<String>, res: ScanResult): List<DoesRow> {
        val s = allPerms.map { it.substringAfterLast('.').uppercase() }.toSet()
        val rows = mutableListOf<DoesRow>()
        if (res.malwareSignatures.isNotEmpty()) {
            rows += DoesRow(R.drawable.ic4_alert, R.string.kq4_scanres_does_sig)
        }
        if ("SYSTEM_ALERT_WINDOW" in s) {
            rows += DoesRow(R.drawable.ic4_card, R.string.kq4_scanres_does_overlay)
        }
        if ("READ_SMS" in s || "RECEIVE_SMS" in s) {
            rows += DoesRow(R.drawable.ic4_message, R.string.kq4_scanres_does_sms_read)
        }
        if ("SEND_SMS" in s) {
            rows += DoesRow(R.drawable.ic4_message, R.string.kq4_scanres_does_sms_send)
        }
        if ("BIND_ACCESSIBILITY_SERVICE" in s) {
            rows += DoesRow(R.drawable.ic4_eye, R.string.kq4_scanres_does_screen)
        }
        if ("REQUEST_INSTALL_PACKAGES" in s) {
            rows += DoesRow(R.drawable.ic4_download, R.string.kq4_scanres_does_download)
        }
        if ("BIND_DEVICE_ADMIN" in s) {
            rows += DoesRow(R.drawable.ic4_lock, R.string.kq4_scanres_does_admin)
        }
        if ("READ_CONTACTS" in s) {
            rows += DoesRow(R.drawable.ic4_user, R.string.kq4_scanres_does_contacts)
        }
        if ("READ_CALL_LOG" in s || "CALL_PHONE" in s ||
            "PROCESS_OUTGOING_CALLS" in s || "ANSWER_PHONE_CALLS" in s
        ) {
            rows += DoesRow(R.drawable.ic4_phone, R.string.kq4_scanres_does_calls)
        }
        if ("ACCESS_FINE_LOCATION" in s || "ACCESS_BACKGROUND_LOCATION" in s) {
            rows += DoesRow(R.drawable.ic4_globe, R.string.kq4_scanres_does_location)
        }
        if ("RECORD_AUDIO" in s) {
            rows += DoesRow(R.drawable.ic4_eye, R.string.kq4_scanres_does_mic)
        }
        if ("CAMERA" in s) {
            rows += DoesRow(R.drawable.ic4_eye, R.string.kq4_scanres_does_camera)
        }
        if ("MANAGE_EXTERNAL_STORAGE" in s) {
            rows += DoesRow(R.drawable.ic4_folder, R.string.kq4_scanres_does_files)
        }
        return rows.take(6)
    }

    private fun renderDoes(allPerms: List<String>, res: ScanResult) {
        val container = binding.doesContainer
        container.removeAllViews()

        val rows: List<DoesRow>
        val avBg: Int
        val tint: Int
        if (res.verdict == ScanResult.Verdict.SAFE) {
            rows = listOf(DoesRow(R.drawable.ic4_check_circle, R.string.kq4_scanres_does_safe))
            avBg = R.drawable.kq4_av_safe
            tint = getColor(R.color.kq_safe)
        } else {
            rows = buildDoesRows(allPerms, res).ifEmpty {
                listOf(DoesRow(R.drawable.ic4_alert, R.string.kq4_scanres_does_generic))
            }
            if (res.verdict == ScanResult.Verdict.SUSPICIOUS) {
                avBg = R.drawable.kq4_av_warn
                tint = getColor(R.color.kq_warn)
            } else {
                avBg = R.drawable.kq4_av_danger
                tint = getColor(R.color.kq_danger)
            }
        }

        val inflater = layoutInflater
        rows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.inc_kq_perm_row, container, false)
            view.findViewById<FrameLayout>(R.id.permAv).setBackgroundResource(avBg)
            val icon = view.findViewById<ImageView>(R.id.permIcon)
            icon.setImageResource(row.iconRes)
            icon.imageTintList = ColorStateList.valueOf(tint)
            view.findViewById<TextView>(R.id.tvPermLabel).text = getString(row.textRes)
            view.findViewById<TextView>(R.id.tvPermSub).visibility = View.GONE
            view.findViewById<View>(R.id.permDivider).visibility =
                if (index == rows.lastIndex) View.GONE else View.VISIBLE
            container.addView(view)
            // Satrlar ketma-ket, yengil suriladi (stagger reveal).
            AnimationHelper.fadeIn(view, duration = 320, delay = index * 60L)
        }
    }

    /** "So'ralgan ruxsatlar" havola-kartasi → PermissionsDetailActivity. */
    private fun renderPermsCard(allPerms: List<String>, res: ScanResult) {
        if (allPerms.isEmpty()) {
            binding.cardPerms.visibility = View.GONE
            return
        }
        binding.cardPerms.visibility = View.VISIBLE

        val critCount = allPerms.count {
            PermissionsDetailActivity.classify(it) == PermissionsDetailActivity.Sev.CRIT
        }
        binding.tvPermCount.text = if (critCount > 0) {
            getString(R.string.kq4_scanres_perm_count_danger, allPerms.size, critCount)
        } else {
            getString(R.string.perm_count, allPerms.size)
        }

        if (res.verdict == ScanResult.Verdict.SAFE) {
            binding.avPerms.setBackgroundResource(R.drawable.kq4_av_neutral)
            binding.imgPermsIcon.imageTintList =
                ColorStateList.valueOf(getColor(R.color.kq_ink_2))
        }

        binding.cardPerms.setOnClickListener {
            startActivity(
                PermissionsDetailActivity.intent(
                    this,
                    binding.tvFileName.text?.toString() ?: File(apkPath).name,
                    ArrayList(allPerms)
                )
            )
        }
    }

    private fun deleteApk() {
        try {
            val deleted = File(apkPath).delete()
            if (deleted) {
                Toast.makeText(this, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                // Dizayn: ekranda qolamiz — "Fayl o'chirildi. Telefoningiz xavfsiz." kartasi.
                binding.btnDelete.visibility = View.GONE
                binding.cardDeleted.visibility = View.VISIBLE
                AnimationHelper.fadeIn(binding.cardDeleted, duration = 320)
            } else {
                Toast.makeText(this, getString(R.string.not_deleted), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.not_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_APK_PATH = "apk_path"
        private const val EXTRA_VERDICT = "verdict"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_DETAILS = "details"
        private const val EXTRA_PERMS = "perms"
        private const val EXTRA_SIGS = "sigs"
        private const val EXTRA_PACKAGE = "package"

        fun intent(
            context: android.content.Context,
            path: String,
            result: ScanResult,
            packageName: String? = null
        ): Intent {
            return Intent(context, ScanResultActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, path)
                putExtra(EXTRA_VERDICT, result.verdict)
                putExtra(EXTRA_REASON, result.reason)
                putStringArrayListExtra(EXTRA_DETAILS, ArrayList(result.details))
                putStringArrayListExtra(EXTRA_PERMS, ArrayList(result.dangerousPermissions))
                putStringArrayListExtra(EXTRA_SIGS, ArrayList(result.malwareSignatures))
                if (!packageName.isNullOrBlank()) putExtra(EXTRA_PACKAGE, packageName)
            }
        }
    }
}
