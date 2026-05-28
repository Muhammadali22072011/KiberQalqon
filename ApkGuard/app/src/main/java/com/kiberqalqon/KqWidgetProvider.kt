package com.kiberqalqon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * Bosh ekran widget'i — KiberQalqon himoyasi holatini va oxirgi skan vaqtini
 * tezkor ko'rsatadi.
 *
 * Ikkita asosiy ko'rsatkich:
 *  - Status nuqtasi rangi: yashil (xavf yo'q), sariq (shubhali), qizil (xavfli)
 *  - "Oxirgi skan" qatori: ScanHistory'dagi eng so'nggi yozuv vaqti
 *
 * Action'lar:
 *  - widget ustiga tap → DashboardNewActivity
 *  - "Skan" tugmasi → MainActivity (tezkor skan)
 *
 * Yangilanishlar:
 *  - System onUpdate (har boshlanishida)
 *  - Skan tugagach `KqWidgetProvider.refreshAll(context)` chaqirilganda
 *  - System enabled/disabled hodisalarida
 *
 * updatePeriodMillis=0 (xml/kq_widget_info.xml) — periodic update yo'q.
 * Batareya tejaymiz, faqat real hodisalarda yangilanadi.
 */
class KqWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id)
        }
    }

    private fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        // Har qanday runtime xato (Config/SharedPreferences/RemoteViews) onUpdate'ni
        // uzib, system'da "Problem loading widget" chiqaradi — shuni butun tanani
        // o'rab oldindan to'sib qo'yamiz: xato bo'lsa widget oxirgi holatida qoladi.
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_kq_status)
            val state = computeState(context)

            views.setTextViewText(R.id.widgetStatus, state.statusText)
            views.setTextViewText(R.id.widgetLastScan, state.lastScanText)
            views.setInt(R.id.widgetStatusDot, "setBackgroundResource", state.dotDrawable)

            // Widget ustiga tap → Dashboard ochiladi.
            val openIntent = Intent(context, SplashActivity::class.java).apply {
                // Splash o'zi consent/onboarding'ni tekshirib, kerakli ekranga yuboradi.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context,
                REQ_OPEN,
                openIntent,
                pendingIntentFlags(),
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openPi)

            // "Skan" tugmasi → MainActivity (tezkor skan ekrani).
            val scanIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_WIDGET, true)
            }
            val scanPi = PendingIntent.getActivity(
                context,
                REQ_SCAN,
                scanIntent,
                pendingIntentFlags(),
            )
            views.setOnClickPendingIntent(R.id.widgetScanBtn, scanPi)

            manager.updateAppWidget(widgetId, views)
        } catch (t: Throwable) {
            android.util.Log.w("KqWidget", "updateOne($widgetId) failed", t)
        }
    }

    private data class WidgetState(
        val statusText: String,
        val lastScanText: String,
        val dotDrawable: Int,
    )

    private fun computeState(context: Context): WidgetState {
        // ScanHistory'ning so'nggi 30 ta yozuvini ko'rib, eng yomon verdict'ni
        // topamiz — agar so'nggi 24 soatda DANGER bo'lsa, widget'da qizil nuqta.
        val history = try {
            ScanHistory.all(context)
        } catch (_: Throwable) { emptyList() }

        val recent = history.take(30)
        val now = System.currentTimeMillis()
        val dayAgo = now - 24L * 60 * 60 * 1000

        var worst: ScanResult.Verdict = ScanResult.Verdict.SAFE
        var threatCount = 0
        for (e in recent) {
            if (e.timestamp < dayAgo) continue
            if (e.verdict == ScanResult.Verdict.DANGER) {
                worst = ScanResult.Verdict.DANGER
                threatCount++
            } else if (e.verdict == ScanResult.Verdict.SUSPICIOUS &&
                worst != ScanResult.Verdict.DANGER) {
                worst = ScanResult.Verdict.SUSPICIOUS
                threatCount++
            }
        }

        val statusText = when (worst) {
            ScanResult.Verdict.SAFE ->
                if (Config.isBackgroundEnabled(context)) context.getString(R.string.widget_status_protected_bg)
                else context.getString(R.string.status_protected)
            ScanResult.Verdict.SUSPICIOUS -> context.getString(R.string.widget_suspicious_count, threatCount)
            ScanResult.Verdict.DANGER -> context.getString(R.string.widget_danger_count, threatCount)
        }
        val dot = when (worst) {
            ScanResult.Verdict.SAFE -> R.drawable.kq_widget_dot_safe
            ScanResult.Verdict.SUSPICIOUS -> R.drawable.kq_widget_dot_warn
            ScanResult.Verdict.DANGER -> R.drawable.kq_widget_dot_danger
        }

        val lastScanText = recent.firstOrNull()?.let {
            context.getString(R.string.widget_last_scan, humanAgo(context, now - it.timestamp))
        } ?: context.getString(R.string.widget_last_scan_never)

        return WidgetState(statusText, lastScanText, dot)
    }

    private fun humanAgo(context: Context, deltaMs: Long): String {
        val sec = deltaMs / 1000
        return when {
            sec < 60 -> context.getString(R.string.widget_ago_now)
            sec < 3600 -> context.getString(R.string.widget_ago_min, (sec / 60).toInt())
            sec < 86400 -> context.getString(R.string.widget_ago_hour, (sec / 3600).toInt())
            else -> context.getString(R.string.widget_ago_day, (sec / 86400).toInt())
        }
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        private const val REQ_OPEN = 1001
        private const val REQ_SCAN = 1002
        const val EXTRA_FROM_WIDGET = "from_widget"

        /**
         * Skan tugagach yoki konfiguratsiya o'zgargach chaqiriladi —
         * barcha widget instans'larni qaytadan chizadi.
         */
        fun refreshAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val component = ComponentName(context, KqWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component) ?: return
                if (ids.isEmpty()) return
                val intent = Intent(context, KqWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            } catch (t: Throwable) {
                android.util.Log.w("KqWidget", "refreshAll failed", t)
            }
        }
    }
}
