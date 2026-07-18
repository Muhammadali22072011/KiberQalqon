package com.uzguard

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальный обработчик неперехваченных исключений.
 *
 * Записывает stacktrace в два места (что доступнее в данный момент):
 *  1) files/crash_log.txt в каталоге приложения (всегда доступно)
 *  2) /sdcard/Download/uzguard_crash.txt — если есть права (легко открыть через файл-менеджер)
 *
 * Потом ПРОБРАСЫВАЕТ исключение в дефолтный handler, чтобы Android корректно показал
 * "произошёл сбой" и не было ANR.
 */
object CrashHandler {

    private const val TAG = "UzGuardCrash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(app, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to save crash log", e)
            }
            // Пробрасываем дальше — Android покажет диалог "приложение остановлено"
            // и процесс корректно завершится. Без этого — ANR-зомби.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Crash отправляется в 2 места:
     *  1) Личный бот юзера (TelemetryReporter) — если юзер настроил его в DIAGNOSTIKA
     *  2) Dev community bot (CommunityReportClient) — если юзер opt-in на community sharing
     */
    private fun reportToTelegram(ctx: Context, text: String) {
        try {
            TelemetryReporter.report(ctx, "CRASH", text.take(3000))
        } catch (_: Throwable) { /* nothing more to do */ }
        try {
            CommunityReportClient.reportCrash(ctx, text)
        } catch (_: Throwable) { /* nothing more to do */ }
    }

    private fun save(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            pw.println("=== UzGuard CRASH @ $ts ===")
            pw.println("Thread: ${thread.name}")
            pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            pw.println("Device:  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("Package: ${ctx.packageName}")
            try {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                pw.println("Version: ${info.versionName} (${info.versionCode})")
            } catch (_: Exception) { /* ignore */ }
            pw.println()
            throwable.printStackTrace(pw)
        }
        val text = sw.toString()
        Log.e(TAG, text)
        reportToTelegram(ctx, text)

        // 1) Внутренний кэш — всегда работает.
        try {
            val internal = File(ctx.filesDir, "crash_log.txt")
            internal.appendText(text + "\n\n")
        } catch (_: Throwable) { /* ignore */ }

        // 2) Папка Downloads — открывается из любого файл-менеджера.
        // На API 29+ запись в произвольную папку запрещена, но Downloads через MediaStore
        // обычно работает. Пробуем простой File API — если без прав упадёт, ничего страшного.
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null) {
                val out = File(downloads, "uzguard_crash.txt")
                out.appendText(text + "\n\n")
            }
        } catch (_: Throwable) { /* ignore */ }
    }
}
