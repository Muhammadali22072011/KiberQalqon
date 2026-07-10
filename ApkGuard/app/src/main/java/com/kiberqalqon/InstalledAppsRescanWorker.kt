package com.uzguard

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

        val prefs = ctx.getSharedPreferences("uzguard_rescan", Context.MODE_PRIVATE)
        val rescanned = mutableListOf<Pair<String, String>>()  // (pkg, verdict)
        var newThreats = 0

        // Фильтруем системные приложения, чтобы не молотить впустую.
        // FLAG_SYSTEM или приложения, которые обновлены поверх системных — НЕ скипаем (там
        // как раз бывают атакующие апдейты sideload-нутые поверх системного).
        //
        // Приложения из Play Market / доверенных сторов НЕ пере-сканируем: Play Protect их уже
        // проверил, а ежедневный re-scan установленных base.apk зря греет телефон. Сканируем
        // ТОЛЬКО приложения из неизвестных источников (sideload). Фильтр стоит ДО take(50),
        // чтобы бюджет в 50 пакетов тратился именно на sideload-приложения.
        val userApps = packages.filter { p ->
            val app = p.applicationInfo ?: return@filter false
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !updatedSystem) return@filter false
            !ApkScanner.isFromTrustedStore(ctx, p.packageName)
        }

        for (pkg in userApps.take(50)) {
            try {
                val name = pkg.packageName ?: continue
                // Skip самого UzGuard — для self-skip есть SelfGuard, но дешевле сразу пропустить.
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

        // Masofaviy boshqaruv ilovalari (AnyDesk/TeamViewer/RustDesk...) — firibgarlik vektori.
        // Ular virus EMAS, shuning uchun DANGER qilib belgilamaymiz; faqat YANGI paydo bo'lganini
        // bir marta ogohlantiramiz (dedup — oldingi ko'rilgan paketlar to'plami bilan solishtirib).
        try {
            if (Config.isRemoteAccessAlertEnabled(ctx)) {
                val found = RemoteAccessDetector.installed(ctx)
                val seen = prefs.getStringSet(KEY_REMOTE_SEEN, emptySet()) ?: emptySet()
                val current = found.map { it.pkg }.toSet()
                val fresh = found.filter { it.pkg !in seen }
                prefs.edit().putStringSet(KEY_REMOTE_SEEN, current).apply()
                if (fresh.isNotEmpty()) {
                    NotificationHelper.showRemoteAccessNotification(ctx, fresh.map { it.brand })
                    try {
                        TelemetryReporter.report(
                            ctx, "REMOTE_ACCESS",
                            "🖥 Masofaviy boshqaruv ilovasi topildi:\n" +
                            fresh.joinToString("\n") { "• ${it.brand} (${it.pkg})" } +
                            "\n(Firibgarlik vektori — foydalanuvchi ogohlantirildi)"
                        )
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "remote-access check failed", e)
        }

        Log.d(TAG, "Rescanned ${rescanned.size} apps, new threats: $newThreats")
        return Result.success()
    }

    companion object {
        private const val TAG = "RescanWorker"
        private const val WORK_NAME = "uzguard_rescan_installed"
        // Oldin ko'rilgan masofaviy-boshqaruv paketlari (dedup — takror ogohlantirmaslik uchun).
        private const val KEY_REMOTE_SEEN = "remote_access_seen"

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
