package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kiberqalqon.databinding.ActivityScanResultBinding
import kotlinx.coroutines.*
import java.io.File

/**
 * Virus / xavf topilganda ko'rsatiladigan natija oynasi.
 *
 * Avval bu oyna BITTA virusning (Ajina.Banker) qotirilgan demo ma'lumotini
 * ko'rsatardi — qaysi APK skan qilinganidan qat'i nazar. Endi u haqiqiy
 * ilovaning ruxsatlarini APK'dan o'qib, odam tiliga tushunarli qilib chiqaradi:
 * "Xavfli ruxsatlar" (daraja yorlig'i bilan) + "Boshqa ruxsatlar".
 */
class ScanResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanResultBinding
    private var apkPath: String = ""
    private var installedPackage: String? = null
    private var scanResult: ScanResult? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            // HECH QACHON yolg'on XAVFSIZ: extra yo'q yoki turi mos kelmasa — SHUBHALI.
            // Ilgari bu yerda SAFE turardi, ya'ni EXTRA_VERDICT'siz ochilgan ekran
            // tekshirilmagan fayl uchun yashil "xavfsiz" bannerini ko'rsatardi. Bu loyihaning
            // asosiy qoidasiga zid (ApkScanner ham o'qib bo'lmagan faylni SUSPICIOUS deb beradi).
            verdict = verdict ?: ScanResult.Verdict.SUSPICIOUS,
            reason = intent.getStringExtra(EXTRA_REASON) ?: "",
            details = intent.getStringArrayListExtra(EXTRA_DETAILS) ?: emptyList(),
            dangerousPermissions = intent.getStringArrayListExtra(EXTRA_PERMS) ?: emptyList(),
            malwareSignatures = intent.getStringArrayListExtra(EXTRA_SIGS) ?: emptyList()
        )
        scanResult = res

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Ovozli verdict — Config.isSoundEnabled'da o'chirilgan bo'lsa, jim turadi.
        VoiceVerdict.init(this)
        VoiceVerdict.speak(this, res.verdict)

        bindVerdict(res)
        bindActions(res)
        loadPermissions(res)

        revealVerdict(res.verdict)
    }

    /**
     * Verdict ochilish mikro-animatsiyasi ("Yorug' minimal"): ikonka yengil "pop"
     * (overshoot) bilan paydo bo'ladi; XAVF bo'lsa qo'shimcha ogohlantiruvchi tebranish.
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

    /** Banner — rang, ikonka, verdict yorlig'i va qisqacha xulosa. */
    private fun bindVerdict(res: ScanResult) {
        when (res.verdict) {
            ScanResult.Verdict.SAFE -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq_banner_safe)
                binding.tvVerdict.text = getString(R.string.kq_sev_safe)
                binding.imgVerdictIcon.setImageResource(R.drawable.ic_shield_check)
            }
            ScanResult.Verdict.SUSPICIOUS -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq_banner_warn)
                binding.tvVerdict.text = getString(R.string.kq_sev_high)
                binding.imgVerdictIcon.setImageResource(R.drawable.ic_alert_triangle)
            }
            ScanResult.Verdict.DANGER -> {
                binding.cardResult.setBackgroundResource(R.drawable.kq_banner_danger)
                binding.tvVerdict.text = getString(R.string.kq_sev_crit)
                binding.imgVerdictIcon.setImageResource(R.drawable.ic_virus)
            }
        }

        binding.tvReason.text =
            if (res.reason.isNotBlank()) res.reason else defaultReason(res.verdict)

        val file = File(apkPath)
        // tvFileName boshlang'ich qiymat — keyin APK'dan ilova nomi o'qilsa, ustiga yoziladi.
        binding.tvFileName.text = file.name.ifBlank { getString(R.string.scan_result_title) }
        binding.tvFileSize.text = if (file.exists()) "${file.length() / 1024} KB" else ""
    }

    private fun defaultReason(v: ScanResult.Verdict): String = when (v) {
        ScanResult.Verdict.DANGER -> getString(R.string.reason_danger_default)
        ScanResult.Verdict.SUSPICIOUS -> getString(R.string.suspicious_reason)
        ScanResult.Verdict.SAFE -> getString(R.string.safe_reason)
    }

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
                binding.btnDelete.alpha = 0.5f
                binding.btnDelete.isEnabled = false
            }
        }

        // Do'stlarga ogohlantirish — faqat xavf bo'lganda mantiqiy.
        binding.btnRecheck.setOnClickListener { shareWarning(res) }
        if (res.verdict == ScanResult.Verdict.SAFE) {
            binding.btnRecheck.visibility = View.GONE
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

    private data class PermRow(val raw: String, val label: String, val severity: PermissionCatalog.Severity)
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
            binding.tvPermCount.text = getString(R.string.perm_count, all.size)

            val dangerous = all
                .filter { PermissionCatalog.severity(it) != PermissionCatalog.Severity.NORMAL }
                .map { PermRow(it, PermissionCatalog.label(this@ScanResultActivity, it), PermissionCatalog.severity(it)) }
                .sortedWith(
                    compareBy(
                        { if (it.severity == PermissionCatalog.Severity.CRITICAL) 0 else 1 },
                        { it.label }
                    )
                )

            val others = all
                .filter { PermissionCatalog.severity(it) == PermissionCatalog.Severity.NORMAL }
                .map { PermissionCatalog.shortName(it) }
                .distinct()
                .sorted()

            renderDangerous(dangerous)
            renderOthers(others)
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

    private fun renderDangerous(rows: List<PermRow>) {
        val container = binding.permDangerContainer
        container.removeAllViews()
        if (rows.isEmpty()) {
            binding.tvPermNone.visibility = View.VISIBLE
            return
        }
        binding.tvPermNone.visibility = View.GONE
        val inflater = layoutInflater
        for ((index, row) in rows.withIndex()) {
            val view = inflater.inflate(R.layout.inc_kq_perm_row, container, false)
            view.findViewById<TextView>(R.id.tvPermLabel).text = row.label
            view.findViewById<TextView>(R.id.tvPermRaw).text = PermissionCatalog.shortName(row.raw)
            val dot = view.findViewById<View>(R.id.vPermDot)
            val sev = view.findViewById<TextView>(R.id.tvPermSev)
            if (row.severity == PermissionCatalog.Severity.CRITICAL) {
                dot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_danger))
                sev.setBackgroundResource(R.drawable.kq_sev_crit)
                sev.setTextColor(getColor(R.color.kq_danger_ink))
                sev.text = getString(R.string.perm_sev_critical)
            } else {
                dot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.kq_warn))
                sev.setBackgroundResource(R.drawable.kq_sev_high)
                sev.setTextColor(getColor(R.color.kq_warn_ink))
                sev.text = getString(R.string.perm_sev_warning)
            }
            container.addView(view)
            // Xavfli ruxsatlar ketma-ket, yengil suriladi (stagger reveal).
            AnimationHelper.fadeIn(view, duration = 320, delay = index * 60L)
        }
    }

    private fun renderOthers(others: List<String>) {
        if (others.isEmpty()) {
            binding.cardOthers.visibility = View.GONE
            return
        }
        binding.cardOthers.visibility = View.VISIBLE
        binding.tvPermOthersTitle.text = getString(R.string.perm_section_others, others.size)
        binding.tvPermOthers.text = others.joinToString("\n") { "·  $it" }
    }

    private fun deleteApk() {
        try {
            val deleted = File(apkPath).delete()
            if (deleted) {
                Toast.makeText(this, getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
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
