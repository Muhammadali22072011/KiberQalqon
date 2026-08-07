package com.kiberqalqon

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings Tile — "Сканировать сейчас" в шторке Android.
 * Tap → запускает MainActivity и сразу триггерит сканирование.
 *
 * minSdk 24 для TileService совпадает с нашим minSdk, поэтому RequiresApi(N).
 */
@RequiresApi(Build.VERSION_CODES.N)
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.label = getString(R.string.tile_label)
            it.contentDescription = getString(R.string.tile_description)
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Запускаем главный экран. На Android 14+ нужно сначала
        // unlockAndRun, чтобы корректно стартовать Activity с заблокированного экрана.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TRIGGER_SCAN, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
