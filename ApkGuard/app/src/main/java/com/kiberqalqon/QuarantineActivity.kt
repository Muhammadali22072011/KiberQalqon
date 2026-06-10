package com.kiberqalqon

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UX-01: Karantin ekrani. Avval avto-karantin/avto-o'chirishda foydalanuvchiga har joyda
 * "7 kun ichida tiklash mumkin" deb va'da berilardi, lekin Quarantine.restore()/list()/purge()
 * HECH QAYERDAN chaqirilmasdi — ekran ham, Telegram-komanda ham yo'q edi, ya'ni va'da yolg'on edi
 * (xato avto-o'chirilgan legit fayl abadiy yo'qolardi). Bu ekran shu va'dani haqiqatga aylantiradi:
 * karantindagi fayllar ro'yxati + "Tiklash" (restore) va "Butunlay o'chirish" (purge).
 *
 * Layout DASTURIY (XML'siz) — yangi resurs/binding kerak emas, mustaqil.
 */
class QuarantineActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(20), pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Karantin"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Avtomatik karantinga olingan fayllar. Xato bo'lsa — \"Tiklash\"; ishonchingiz komil bo'lsa — \"Butunlay o'chirish\"."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
            alpha = 0.7f
        })

        emptyView = TextView(this).apply {
            text = "Karantin bo'sh — hech qanday fayl yo'q."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
            alpha = 0.6f
            visibility = View.GONE
        }
        root.addView(emptyView)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(scroll)

        setContentView(root)
        title = "Karantin"
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                try { Quarantine.list(this@QuarantineActivity) } catch (_: Throwable) { emptyList() }
            }
            listContainer.removeAllViews()
            emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            for (e in entries) listContainer.addView(rowFor(e))
        }
    }

    private fun rowFor(e: Quarantine.Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            layoutParams = lp
            setBackgroundColor(0x11000000)
        }
        card.addView(TextView(this).apply {
            text = e.originalName
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            val mb = e.sizeBytes / 1024.0 / 1024.0
            text = "${e.verdict} · ${"%.1f".format(mb)} MB"
            textSize = 12f
            alpha = 0.7f
        })
        if (e.reason.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = e.reason.take(160)
                textSize = 12f
                alpha = 0.7f
                setPadding(0, dp(2), 0, 0)
            })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "Tiklash"
            setOnClickListener { confirmRestore(e) }
        })
        btnRow.addView(Button(this).apply {
            text = "Butunlay o'chirish"
            setOnClickListener { confirmPurge(e) }
        })
        card.addView(btnRow)
        return card
    }

    private fun confirmRestore(e: Quarantine.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Tiklash")
            .setMessage("\"${e.originalName}\" asl joyiga qaytarilsinmi? (Bu fayl xavfli deb belgilangan edi.)")
            .setPositiveButton("Tiklash") { _, _ ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Quarantine.restore(this@QuarantineActivity, e.token) }
                    val msg = when (r) {
                        is Quarantine.Result.Ok -> "Tiklandi: ${e.originalName}"
                        is Quarantine.Result.Failed -> "Tiklab bo'lmadi: ${r.message}"
                    }
                    android.widget.Toast.makeText(this@QuarantineActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun confirmPurge(e: Quarantine.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Butunlay o'chirish")
            .setMessage("\"${e.originalName}\" butunlay o'chirilsinmi? Bu amalni ortga qaytarib bo'lmaydi.")
            .setPositiveButton("O'chirish") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { Quarantine.purge(this@QuarantineActivity, e.token) }
                    reload()
                }
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
