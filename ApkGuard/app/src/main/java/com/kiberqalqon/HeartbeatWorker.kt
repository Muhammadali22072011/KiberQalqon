package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Raz v 6 chasov shlet "tirikman" v Telegram. Cherez heartbeat my vidim,
 * chto:
 *   - telefon zazhzhen i v seti
 *   - process KiberQalqon ne ubit OS'om
 *   - batareya/storage ne v kritichnom sostoyanii
 *
 * Esli heartbeat ne pridet 12+ chasov — eto sam po sebe signal (telefon vyklyuchen
 * ili KiberQalqon ubit). Bonusom: razbudim GuardWorker zaodno.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            if (!TelemetryReporter.isConfigured(ctx)) {
                return Result.success()
            }
            val battery = batteryPercent(ctx)
            val freeMb = freeStorageMb()
            val uptimeMin = SystemClock.elapsedRealtime() / 1000 / 60
            TelemetryReporter.reportHeartbeat(ctx, battery, freeMb, uptimeMin)

            // Pri kriticheski malen'kom storage — otdel'nyj warn.
            if (freeMb in 1..500) {
                TelemetryReporter.reportStorageWarn(ctx, freeMb)
            }

            // Diff-state checks: shlem event TOL'KO esli sostoyanie pomenyalos'.
            checkUnknownSources(ctx)
            checkBatteryOptimization(ctx)
            checkCriticalPermissions(ctx)

            Result.success()
        } catch (e: Throwable) {
            Log.w(TAG, "heartbeat failed", e)
            Result.success()  // ne perekhochem retry — sleduyushchij period sam pridet.
        }
    }

    private fun batteryPercent(ctx: Context): Int {
        return try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (_: Throwable) { -1 }
    }

    private fun checkUnknownSources(ctx: Context) {
        try {
            val current: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Per-app flag. Check if any app holds it — proksi cherez nashego.
                // Tochnogo API obhodit' vse app nety, no chasto eto pokazatelnyj signal.
                ctx.packageManager.canRequestPackageInstalls()
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            }
            val prefs = ctx.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE)
            val prev = prefs.getInt(KEY_UNKNOWN_SOURCES, -1)
            val now = if (current) 1 else 0
            if (prev != -1 && prev != now) {
                TelemetryReporter.reportUnknownSources(ctx, current)
            }
            if (prev != now) prefs.edit().putInt(KEY_UNKNOWN_SOURCES, now).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "checkUnknownSources", e)
        }
    }

    private fun checkBatteryOptimization(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else true
            val prefs = ctx.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE)
            val prev = prefs.getInt(KEY_BATTERY_WHITELIST, -1)
            val now = if (ignored) 1 else 0
            if (prev != -1 && prev != now) {
                TelemetryReporter.reportBatteryOptimization(ctx, ignored)
            }
            if (prev != now) prefs.edit().putInt(KEY_BATTERY_WHITELIST, now).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "checkBatteryOptimization", e)
        }
    }

    private fun checkCriticalPermissions(ctx: Context) {
        val critical = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED"
        )
        try {
            val prefs = ctx.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE)
            for (perm in critical) {
                val granted = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
                val key = "perm_" + perm.substringAfterLast('.')
                val prev = prefs.getInt(key, -1)
                val now = if (granted) 1 else 0
                // Soobshchaem TOL'KO ob otzyve (poteryali ranee imeli ee).
                if (prev == 1 && now == 0) {
                    TelemetryReporter.reportPermissionRevoked(ctx, perm)
                }
                if (prev != now) prefs.edit().putInt(key, now).apply()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "checkCriticalPermissions", e)
        }
    }

    private fun freeStorageMb(): Long {
        return try {
            val root = Environment.getDataDirectory()
            val stat = android.os.StatFs(root.path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            freeBytes / 1024 / 1024
        } catch (_: Throwable) { -1 }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "kiberqalqon_heartbeat"
        private const val PREFS_STATE = "kiberqalqon_state_diff"
        private const val KEY_UNKNOWN_SOURCES = "unknown_sources"
        private const val KEY_BATTERY_WHITELIST = "battery_whitelist"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)  // ne srazu — pust' app start ne dublirovalsya s heartbeat
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
