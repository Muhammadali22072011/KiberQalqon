package com.kiberqalqon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Boshqaruv paneli — o'sha veb-panelni ilova ICHIDA WebView orqali ochadi, ya'ni
 * kompyuterdagi panel bilan AYNAN bir xil (bitta kod bazasi, ikkala joyda bir xil).
 *
 * Admin login+parol bilan kiradi: ko'rish + eksport + e'lon. Egasi esa master kalit
 * bilan. Bu sirlar APK ICHIDA YO'Q — foydalanuvchi qo'lda kiritadi (panelda), shuning
 * uchun "flotni APK siri bilan dump qilib bo'lmaydi" qoidasi buzilmaydi.
 *
 * FLAG_SECURE — skrinshot/ekran yozuvi bloklanadi (maxfiy panel).
 */
class AdminPanelActivity : AppCompatActivity() {

    private var web: WebView? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val base = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (base.isBlank() || !base.startsWith("https://")) {
            setContentView(TextView(this).apply {
                text = "Server hali sozlanmagan (CLOUD_BASE_URL bo'sh). " +
                    "Panelni ko'rish uchun cloud manzili kerak."
                gravity = Gravity.CENTER
                setPadding(dpx(32), dpx(96), dpx(32), dpx(32))
            })
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Yuqori panel: ilovaga (antivirusga) qaytish tugmasi.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.kq_bg_elev))
            setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
        }
        bar.addView(TextView(this).apply {
            text = "←  Ilovaga qaytish"
            textSize = 16f
            setTextColor(getColor(R.color.kq_ink))
            isClickable = true
            setOnClickListener { finish() }
        })
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val w = WebView(this)
        web = w
        root.addView(
            w,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        w.settings.apply {
            javaScriptEnabled = true       // SPA (React) ishlashi uchun
            domStorageEnabled = true       // sessiya tokeni sessionStorage'da saqlanadi
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        w.webChromeClient = WebChromeClient()
        val baseHost = Uri.parse(base).host
        w.webViewClient = object : WebViewClient() {
            // Faqat o'z panelimiz ichida qolamiz; tashqi havola — tashqi brauzerda.
            // Xost (host) bo'yicha solishtiramiz, satr prefiksi bo'yicha EMAS — aks holda
            // "panel.kiberqalqon.app.evil.com" kabi soxta xost ham startsWith'ni qanoatlantirib,
            // ishonchli panel ichida ochilib ketardi.
            private fun isInternal(uri: Uri): Boolean {
                if (uri.scheme != "https") return false
                val host = uri.host ?: return false
                val bh = baseHost ?: return false
                return host == bh || host.endsWith(".$bh")
            }

            private fun handle(uri: Uri): Boolean {
                if (isInternal(uri)) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Throwable) {
                    true
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return true
                return handle(uri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                val uri = url?.let { Uri.parse(it) } ?: return true
                return handle(uri)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(this@AdminPanelActivity, "Ulanish xatosi", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Eksport (fayl yuklab olish) — tashqi yuklab oluvchiga uzatamiz.
        w.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Throwable) {
                Toast.makeText(this, "Yuklab bo'lmadi", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ww = web
                if (ww != null && ww.canGoBack()) ww.goBack() else finish()
            }
        })

        w.loadUrl("$base/login")
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
