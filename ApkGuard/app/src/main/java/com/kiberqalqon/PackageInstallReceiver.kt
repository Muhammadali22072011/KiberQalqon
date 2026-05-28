package com.kiberqalqon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Слушает события установки пакетов в системе.
 *
 * После установки APK (даже если юзер проигнорировал наше предупреждение,
 * или установил с sideload без открытия через нас), мы СКАНИРУЕМ только что
 * установленное приложение и, если оно опасное, поднимаем уведомление с
 * предложением удалить.
 *
 * Это последняя линия обороны: лучше предупредить после установки, чем не вовсе.
 *
 * ВАЖНО: ACTION_PACKAGE_ADDED нельзя зарегистрировать в манифесте на API 26+ для
 * broadcast-приёма из системы (системные приёмники этим ограничением не задеты).
 * Поэтому регистрируем динамически из App.onCreate() — пока процесс жив,
 * приёмник работает. Этого достаточно, когда наш WorkManager поднимает процесс
 * каждые 15 минут.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        // Ne treboem skanirovat' samogo sebya, no event vse-ravno otpravim
        // (osobenno PACKAGE_FULLY_REMOVED — chtoby viden bylo, esli kto-to
        // pytaetsya unintall'nut' KiberQalqon).
        val isOwn = pkg == context.packageName || pkg == "${context.packageName}.debug"
        val ctx = context.applicationContext

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (isReplacing) {
                    // Eto budet otdel'no obrabotano cherez ACTION_PACKAGE_REPLACED.
                    return
                }
                if (isOwn) return
                Log.d(TAG, "Package installed: $pkg")
                scope.launch { scanInstalledPackage(ctx, pkg) }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (isOwn) return
                Log.d(TAG, "Package replaced: $pkg")
                scope.launch { rescanReplacedPackage(ctx, pkg) }
            }
            Intent.ACTION_PACKAGE_FULLY_REMOVED, Intent.ACTION_PACKAGE_REMOVED -> {
                // REMOVED prikhodit s EXTRA_REPLACING=true vo vremya update — skipaem.
                if (isReplacing) return
                Log.d(TAG, "Package uninstalled: $pkg (own=$isOwn)")
                try {
                    TelemetryReporter.reportPackageUninstalled(ctx, pkg)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to report uninstall", e)
                }
            }
        }
    }

    private fun rescanReplacedPackage(context: Context, pkg: String) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val apkPath = info.sourceDir
            val label = info.loadLabel(pm).toString()
            val verdict = if (apkPath != null && java.io.File(apkPath).exists()) {
                try { ApkScanner.scan(context, apkPath).verdict.name } catch (_: Throwable) { "?" }
            } else "?"
            TelemetryReporter.reportPackageReplaced(context, pkg, label, verdict)
        } catch (e: Exception) {
            Log.w(TAG, "rescanReplacedPackage failed for $pkg", e)
            // Vse-ravno otpravim event — tol'ko bez verdikta.
            TelemetryReporter.reportPackageReplaced(context, pkg, pkg, "?")
        }
    }

    private fun scanInstalledPackage(context: Context, pkg: String) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val apkPath = info.sourceDir ?: return
            if (!File(apkPath).exists()) {
                Log.w(TAG, "sourceDir doesn't exist: $apkPath")
                return
            }

            val result = ApkScanner.scan(context, apkPath)
            val label = info.loadLabel(pm).toString()
            // Отправляем событие установки в Telegram.
            TelemetryReporter.report(
                context, "INSTALL",
                "Yangi ilova o'rnatildi:\n" +
                "📦 $label ($pkg)\n" +
                "Xulosa: ${TelemetryReporter.verdictUz(result.verdict.name)}\n" +
                "Sabab: ${result.reason}"
            )
            when (result.verdict) {
                ScanResult.Verdict.DANGER -> {
                    Log.w(TAG, "Installed DANGER package: $pkg — $result")
                    // Avval popup ochishga urinamiz (faqat oldingi planda yoki overlay ruxsati bo'lsa).
                    // Notification full-screen-intent bilan har holda chiqadi — fon'da bo'lsa lock
                    // screen ustida ko'rinadi va telefon ochilsa avtomatik uninstall dialogiga olib boradi.
                    if (ImprovedApkFileObserver.canLaunchActivityFromBackground(context)) {
                        try {
                            val intent = Intent(context, AutoScanActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("apk_path", apkPath)
                                putExtra("apk_name", "$label.apk")
                                putExtra("installed_pkg", pkg)
                            }
                            context.startActivity(intent)
                        } catch (e: Throwable) {
                            Log.w(TAG, "popup launch failed", e)
                        }
                    }
                    NotificationHelper.showInstalledDangerNotification(context, pkg, label, result)
                }
                ScanResult.Verdict.SUSPICIOUS -> {
                    Log.w(TAG, "Installed SUSPICIOUS package: $pkg — $result")
                    NotificationHelper.showInstalledSuspiciousNotification(context, pkg, label, result)
                }
                ScanResult.Verdict.SAFE -> {
                    Log.d(TAG, "Installed safe package: $pkg")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanInstalledPackage failed for $pkg", e)
        }
    }

    companion object {
        private const val TAG = "PackageInstallReceiver"
    }
}
