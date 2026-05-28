package com.kiberqalqon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Раз в сутки повторно сканируем ВСЕ установленные пользовательские приложения.
 *
 * Зачем: blacklist'ы (hashes, certs, signatures) обновляются — но без re-scan
 * уже установленный вирус не попадётся. Этот worker закрывает дыру.
 *
 * Логика:
 *  • PackageManager.getInstalledPackages() — все user-apps (системные пропускаем).
 *  • Для каждого: scan sourceDir. Если verdict стал DANGER/SUSPICIOUS — алертим.
 *  • Бюджет: до 50 пакетов за один запуск (на старых телефонах сканер тяжёлый).
 *  • Throttle: запускается раз в 24 часа.
 *  • Идемпотентность: храним последний verdict в SharedPreferences,
 *    шлём в Telegram ТОЛЬКО при изменении verdict (чтобы не спамить).
 */
class InstalledAppsRescanWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Config.isBackgroundEnabled(ctx)) return Result.success()

        val pm = ctx.packageManager
        val packages = try {
            pm.getInstalledPackages(0)
        } catch (e: Throwable) {
            Log.w(TAG, "getInstalledPackages failed", e)
            return Result.success()
        }

        val prefs = ctx.getSharedPreferences("kiberqalqon_rescan", Context.MODE_PRIVATE)
        val rescanned = mutableListOf<Pair<String, String>>()  // (pkg, verdict)
        var newThreats = 0

        // Фильтруем системные приложения, чтобы не молотить впустую.
        // FLAG_SYSTEM или приложения, которые обновлены поверх системных — НЕ скипаем (там
        // как раз бывают атакующие апдейты sideload-нутые поверх системного).
        val userApps = packages.filter { p ->
            val app = p.applicationInfo ?: return@filter false
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem || updatedSystem
        }

        for (pkg in userApps.take(50)) {
            try {
                val name = pkg.packageName ?: continue
                // Skip самого KiberQalqon — для self-skip есть SelfGuard, но дешевле сразу пропустить.
                if (name == ctx.packageName || name == "${ctx.packageName}.debug") continue

                val app = pkg.applicationInfo ?: continue
                val sourceDir = app.sourceDir ?: continue
                if (!File(sourceDir).exists()) continue

                val result = try {
                    ApkScanner.scan(ctx, sourceDir)
                } catch (e: Throwable) {
                    Log.w(TAG, "rescan failed for $name", e)
                    continue
                }

                val verdict = result.verdict.name
                val prevKey = "verdict_$name"
                val prev = prefs.getString(prevKey, null)
                prefs.edit().putString(prevKey, verdict).apply()
                rescanned.add(name to verdict)

                // Альерт только если verdict ПЕРЕШЁЛ на DANGER (раньше был не-DANGER).
                // Это значит — blacklist обновился и теперь ловит уже-установленную малварь.
                if (verdict == "DANGER" && prev != "DANGER") {
                    newThreats++
                    val label = try { app.loadLabel(pm).toString() } catch (_: Throwable) { name }
                    try {
                        NotificationHelper.showInstalledDangerNotification(ctx, name, label, result)
                    } catch (e: Throwable) {
                        Log.w(TAG, "notification failed", e)
                    }
                    try {
                        TelemetryReporter.report(
                            ctx, "RESCAN",
                            "🆕 O'rnatilgan ilova endi xavfli deb topildi:\n" +
                            "📦 $label ($name)\n" +
                            "Sabab: ${result.reason}\n" +
                            "(Bazaning yangi qoidalari bilan)"
                        )
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.w(TAG, "iteration failed", e)
            }
        }

        Log.d(TAG, "Rescanned ${rescanned.size} apps, new threats: $newThreats")
        return Result.success()
    }

    companion object {
        private const val TAG = "RescanWorker"
        private const val WORK_NAME = "kiberqalqon_rescan_installed"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<InstalledAppsRescanWorker>(
                1, TimeUnit.DAYS
            ).setInitialDelay(2, TimeUnit.HOURS).build()  // первый запуск через 2 часа после установки

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
