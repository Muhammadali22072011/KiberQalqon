package com.uzguard

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ====== SIDELOAD AUDIT — "Play'dan tashqari o'rnatilganlar" ======
 *
 * Ishonchli do'kondan (Play/Galaxy/RuStore...) EMAS, boshqa manbadan (Telegram/brauzer/
 * fayl menejeri) o'rnatilgan barcha ilovalarni bir ro'yxatda ko'rsatadi — kim o'rnatgani
 * (installer) bilan. Aynan shu manbadan viruslar keladi; foydalanuvchi bu ro'yxatni ko'rib
 * begona/eslamаgan ilovalarni topib o'chiradi. Yuqorida — masofaviy boshqaruv ilovalari
 * (AnyDesk/TeamViewer), ular alohida ajratib ko'rsatiladi (firibgarlik vektori).
 *
 * Kod bilan qurilgan UI (XML'siz) — DiagnosticsActivity uslubida, resurs yuklanmasa ham ishlaydi.
 */
class SideloadAuditActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = LocaleHelper.apply(this)

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(color(R.color.kq_bg, "#FBF7EF"))
            isFillViewport = true
        }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, dp(32))
        }
        scroll.addView(container)
        setContentView(scroll)

        title = "Play'dan tashqari ilovalar"

        container.addView(header("Play'dan tashqari o'rnatilganlar"))
        container.addView(sub(
            "Ishonchli do'kondan tashqari (Telegram/brauzer/fayl) o'rnatilgan ilovalar. " +
            "Aynan bu manbadan viruslar keladi — eslamаgan ilovani bosib o'chiring."
        ))
        container.addView(loadingRow())

        // Ilovalar ro'yxati og'ir — fon oqimida yig'amiz.
        Thread {
            val rows = buildRows(ctx)
            runOnUiThread { render(rows) }
        }.start()
    }

    private data class Row(
        val pkg: String,
        val label: String,
        val installer: String,
        val remoteBrand: String?,
    )

    private fun buildRows(ctx: android.content.Context): List<Row> {
        val pm = ctx.packageManager
        val packages = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
        val out = ArrayList<Row>()
        for (p in packages) {
            try {
                val app = p.applicationInfo ?: continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val updatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystem && !updatedSystem) continue
                val pkg = p.packageName ?: continue
                if (pkg == ctx.packageName || pkg == "${ctx.packageName}.debug") continue
                if (ApkScanner.isFromTrustedStore(ctx, pkg)) continue  // faqat sideload
                val label = try { app.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
                out.add(Row(pkg, label, installerLabel(ctx, pkg), RemoteAccessDetector.KNOWN[pkg]))
            } catch (_: Throwable) {}
        }
        // Masofaviy-boshqaruv ilovalari doim tepada, keyin alifbo bo'yicha.
        return out.sortedWith(compareByDescending<Row> { it.remoteBrand != null }.thenBy { it.label.lowercase() })
    }

    private fun render(rows: List<Row>) {
        // "loading" qatorini olib tashlaymiz (header + sub dan keyingi hammasi).
        while (container.childCount > 2) container.removeViewAt(2)

        val remote = rows.filter { it.remoteBrand != null }
        if (remote.isNotEmpty()) {
            container.addView(sectionTitle("⚠ Masofaviy boshqaruv ilovalari", color(R.color.kq_danger, "#E0432F")))
            container.addView(sub(
                "Bu ilovalar orqali boshqa odam telefoningizni ko'radi va boshqaradi. " +
                "Agar buni \"bank xodimi\" so'ragan bo'lsa — bu FIRIBGARLIK. O'zingiz ishlatmasangiz o'chiring."
            ))
            remote.forEach { container.addView(row(it, danger = true)) }
        }

        val normal = rows.filter { it.remoteBrand == null }
        container.addView(sectionTitle("Boshqa sideload ilovalar (${normal.size})", color(R.color.kq_ink, "#2A2622")))
        if (normal.isEmpty() && remote.isEmpty()) {
            container.addView(sub("✅ Play'dan tashqari o'rnatilgan ilova topilmadi. Barakalla!"))
        }
        normal.forEach { container.addView(row(it, danger = false)) }
    }

    private fun row(r: Row, danger: Boolean): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14)
            setPadding(p, dp(12), p, dp(12))
            setBackgroundColor(color(R.color.kq_bg_elev, "#FFFFFF"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            isClickable = true
            setOnClickListener { openAppInfo(r.pkg) }
        }
        card.addView(TextView(ctx).apply {
            text = (if (danger) "🖥 " else "") + r.label
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (danger) color(R.color.kq_danger, "#E0432F") else color(R.color.kq_ink, "#2A2622"))
        })
        card.addView(TextView(ctx).apply {
            text = r.pkg
            textSize = 12f
            setTextColor(color(R.color.kq_ink_3, "#8A8175"))
        })
        card.addView(TextView(ctx).apply {
            text = if (r.remoteBrand != null) "Masofaviy boshqaruv • manba: ${r.installer}"
                   else "Manba: ${r.installer}  •  bosib o'chiring"
            textSize = 12f
            setTextColor(color(R.color.kq_ink_3, "#8A8175"))
        })
        return card
    }

    private fun installerLabel(ctx: android.content.Context, pkg: String): String {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
            }
        } catch (_: Throwable) { null }
        return when (installer) {
            null, "" -> "Noma'lum (sideload)"
            "com.android.vending" -> "Play Market"
            "com.google.android.packageinstaller",
            "com.android.packageinstaller" -> "Qo'lda o'rnatilgan (fayl/APK)"
            "org.telegram.messenger" -> "Telegram"
            "com.whatsapp" -> "WhatsApp"
            "com.android.chrome" -> "Chrome"
            else -> installer
        }
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (_: Throwable) {}
    }

    // ── UI yordamchilari ──
    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.kq_ink, "#2A2622"))
        setPadding(0, dp(4), 0, dp(6))
    }
    private fun sectionTitle(t: String, c: Int) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(c)
        setPadding(0, dp(18), 0, dp(4))
    }
    private fun sub(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(color(R.color.kq_ink_3, "#8A8175"))
        setPadding(0, 0, 0, dp(4))
    }
    private fun loadingRow() = TextView(this).apply {
        text = "Yuklanmoqda…"; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(color(R.color.kq_ink_3, "#8A8175")); setPadding(0, dp(24), 0, 0)
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int, fallback: String): Int =
        try { getColor(res) } catch (_: Throwable) { Color.parseColor(fallback) }
}
