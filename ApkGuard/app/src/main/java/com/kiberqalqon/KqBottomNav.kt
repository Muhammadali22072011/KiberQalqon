package com.uzguard

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Единая нижняя навигация. Включается в каждый главный экран через
 * `<include layout="@layout/view_kq_bottom_nav" />`.
 *
 * Использование:
 * ```
 * KqBottomNav.attach(this, KqBottomNav.Tab.HOME)  // в onCreate()
 * ```
 *
 * Активная вкладка получает kq_primary_soft pill + kq_primary иконку/лейбл.
 * Остальные вкладки — серая иконка/лейбл, без pill. Тап на не-активной вкладке
 * переходит на соответствующий Activity. Активная вкладка тапается в no-op
 * (или вызывает recreate() — но это создаёт лишние Activity в стеке, поэтому не делаем).
 */
object KqBottomNav {

    enum class Tab { HOME, SCAN, STATS, SETTINGS }

    fun attach(activity: Activity, active: Tab) {
        val homePill = activity.findViewById<LinearLayout>(R.id.navHomePill) ?: return
        val scanPill = activity.findViewById<LinearLayout>(R.id.navScanPill)
        val statsPill = activity.findViewById<LinearLayout>(R.id.navStatsPill)
        val settingsPill = activity.findViewById<LinearLayout>(R.id.navSettingsPill)

        val homeIcon = activity.findViewById<ImageView>(R.id.navHomeIcon)
        val scanIcon = activity.findViewById<ImageView>(R.id.navScanIcon)
        val statsIcon = activity.findViewById<ImageView>(R.id.navStatsIcon)
        val settingsIcon = activity.findViewById<ImageView>(R.id.navSettingsIcon)

        val homeLabel = activity.findViewById<TextView>(R.id.navHomeLabel)
        val scanLabel = activity.findViewById<TextView>(R.id.navScanLabel)
        val statsLabel = activity.findViewById<TextView>(R.id.navStatsLabel)
        val settingsLabel = activity.findViewById<TextView>(R.id.navSettingsLabel)

        // Reset all tabs to inactive.
        listOf(homePill, scanPill, statsPill, settingsPill).forEach { pill ->
            pill?.setBackgroundResource(0)
        }
        val ink3 = activity.getColor(R.color.kq_ink_3)
        listOf(homeIcon, scanIcon, statsIcon, settingsIcon).forEach { it?.setColorFilter(ink3) }
        listOf(homeLabel, scanLabel, statsLabel, settingsLabel).forEach { it?.setTextColor(ink3) }
        listOf(homeLabel, scanLabel, statsLabel, settingsLabel).forEach { it?.setTypeface(null, android.graphics.Typeface.NORMAL) }

        // Highlight active.
        val (activePill, activeIcon, activeLabel) = when (active) {
            Tab.HOME -> Triple(homePill, homeIcon, homeLabel)
            Tab.SCAN -> Triple(scanPill, scanIcon, scanLabel)
            Tab.STATS -> Triple(statsPill, statsIcon, statsLabel)
            Tab.SETTINGS -> Triple(settingsPill, settingsIcon, settingsLabel)
        }
        activePill?.setBackgroundResource(R.drawable.kq_nav_active_pill)
        val primary = activity.getColor(R.color.kq_primary)
        activeIcon?.setColorFilter(primary)
        activeLabel?.setTextColor(primary)
        activeLabel?.setTypeface(null, android.graphics.Typeface.BOLD)

        // Wire click → navigation.
        wireClick(activity, R.id.navHome, active, Tab.HOME, DashboardNewActivity::class.java)
        wireClick(activity, R.id.navScan, active, Tab.SCAN, MainActivity::class.java)
        wireClick(activity, R.id.navStats, active, Tab.STATS, ScanHistoryActivity::class.java)
        wireClick(activity, R.id.navSettings, active, Tab.SETTINGS, SettingsActivity::class.java)
    }

    private fun wireClick(
        activity: Activity,
        viewId: Int,
        current: Tab,
        target: Tab,
        targetCls: Class<*>,
    ) {
        val view: View = activity.findViewById(viewId) ?: return
        if (current == target) {
            view.setOnClickListener(null)
            view.isClickable = false
        } else {
            view.setOnClickListener {
                activity.startActivity(
                    Intent(activity, targetCls).apply {
                        // Не дублируем Activity в стеке если юзер ходит home/stats/home/stats.
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                activity.overridePendingTransition(0, 0)
            }
        }
    }
}
