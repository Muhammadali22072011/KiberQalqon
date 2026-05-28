package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Периодическая проверка APK файлов в фоне
 * Запускается каждые 15 минут через WorkManager
 * Надёжнее чем FileObserver!
 */
class PeriodicCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val TAG = "PeriodicCheckWorker"

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "🔍 Начинаю периодическую проверку...")
            
            // Проверяем что защита включена
            if (!Config.isBackgroundEnabled(applicationContext)) {
                Log.d(TAG, "⏭️ Защита выключена, пропускаю")
                return Result.success()
            }
            
            // Ищем все APK файлы
            val apks = FullPhoneScan.findAllApkFiles(applicationContext)
            Log.d(TAG, "✅ Найдено APK: ${apks.size}")
            
            // Получаем список уже проверенных файлов.
            // SharedPreferences.getStringSet() возвращает immutable Set — нельзя добавлять напрямую,
            // иначе .add() кинет UnsupportedOperationException и worker крашится.
            val prefs = applicationContext.getSharedPreferences("kiberqalqon_checked", Context.MODE_PRIVATE)
            val checkedFiles = HashSet<String>().apply {
                addAll(prefs.getStringSet("checked_paths", emptySet()) ?: emptySet())
            }

            var newThreats = 0
            
            // Проверяем только новые файлы
            apks.forEach { apk ->
                if (!checkedFiles.contains(apk.path)) {
                    Log.d(TAG, "🔍 Проверяю новый файл: ${apk.name}")
                    
                    try {
                        val result = ApkScanner.scan(applicationContext, apk.path)

                        // Добавляем в список проверенных
                        checkedFiles.add(apk.path)

                        // "Yangi yuklab olingan" = oxirgi 30 daqiqada o'zgargan fayl.
                        // Real-time FileObserver Telegram'ning /Android/media/... papkasini
                        // ko'pincha ushlamaydi → bu worker yagona fallback. Avval u FAQAT
                        // DANGER uchun oyna ko'rsatardi: foydalanuvchi xavfsiz/shubhali
                        // APK yuklasa — HECH NIMA chiqmasdi ("oyna chiqmadi"). Endi yangi
                        // yuklab olingan har qanday APK foydalanuvchiga ko'rsatiladi (tap →
                        // AutoScanActivity: tekshirish natijasi + o'chirish/o'rnatish).
                        // Eski (allaqachon turgan) fayllar bezovta qilmaydi — faqat DANGER
                        // har doim ko'rsatiladi. checked_paths dedup spam'ni oldini oladi.
                        val recentlyDownloaded =
                            (System.currentTimeMillis() - apk.file.lastModified()) < 30 * 60 * 1000L

                        when (result.verdict) {
                            ScanResult.Verdict.DANGER -> {
                                Log.d(TAG, "🔴 ОПАСНЫЙ файл: ${apk.name}")
                                newThreats++
                                showDangerAlert(apk)
                            }
                            ScanResult.Verdict.SUSPICIOUS -> {
                                Log.d(TAG, "🟠 ПОДОЗРИТЕЛЬНЫЙ файл: ${apk.name}")
                                if (recentlyDownloaded) {
                                    NotificationHelper.showFoundApkNotification(
                                        applicationContext, apk.file, result.verdict, result.reason
                                    )
                                }
                            }
                            ScanResult.Verdict.SAFE -> {
                                Log.d(TAG, "🟢 Безопасный файл: ${apk.name}")
                                if (recentlyDownloaded) {
                                    NotificationHelper.showFoundApkNotification(
                                        applicationContext, apk.file, result.verdict, result.reason
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка проверки: ${apk.name}", e)
                    }
                }
            }
            
            // Сохраняем список проверенных. Ограничиваем размер, чтобы prefs не разрастались.
            val capped = if (checkedFiles.size > 5000) {
                checkedFiles.take(5000).toHashSet()
            } else checkedFiles
            prefs.edit().putStringSet("checked_paths", capped).apply()

            // Statistics ApkScanner.scan() ichida Statistics object orqali sanab boriladi
            // (har bir SCAN bittadan incrementScanned). Bu yerda apks.size qo'shish ikki
            // marta sanab qo'yardi — olib tashlandi.

            // Avto-yangilash yoqilgan bo'lsa — Config.markDatabaseUpdated bilan timestamp
            // yangilanadi. Bu Sozlamalar ekrаnida "Oxirgi yangilanish" sifatida ko'rinadi.
            if (Config.isAutoUpdateEnabled(applicationContext)) {
                Config.markDatabaseUpdated(applicationContext)
            }

            Log.d(TAG, "✅ Проверка завершена. Новых угроз: $newThreats")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка периодической проверки", e)
            Result.failure()
        }
    }
    
    /**
     * Показать окно об опасном файле
     */
    private fun showDangerAlert(apk: ApkItem) {
        try {
            val intent = Intent(applicationContext, AutoScanActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("apk_path", apk.path)
                putExtra("apk_name", apk.name)
            }
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка показа окна", e)
            // Если не получилось - показываем уведомление
            NotificationHelper.showScanNotification(applicationContext, apk.file)
        }
    }
}
