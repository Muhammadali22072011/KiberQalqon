package com.uzguard

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Полное сканирование ВСЕГО телефона - рекурсивно по всем папкам
 */
object FullPhoneScan {
    private const val TAG = "FullPhoneScan"
    // BG-06: WhatsApp Documents (/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents)
    // /sdcard'dan 6 chuqurlikda — eski MAX_DEPTH=5 unga umuman yetmasdi. 8 ga ko'tardik.
    private const val MAX_DEPTH = 8
    // BG-06: avval MAX_FILES=100 TOPILGAN APK soni edi — 100+ APK'li telefonda (Telegram'da
    // yillab yig'ilgan) qolgani umuman skanlanmasdi. Endi limit TASHRIF BUYURILGAN papka/fayl
    // soni bo'yicha (DoS himoyasi) — topilgan APK soni cheklanmaydi.
    private const val MAX_VISITS = 60_000
    // Obhod ne imel limita po VREMENI — na telefone s ogromnym hranilishem (mnogo media
    // v Telegram/WhatsApp) pervyy skan "zависал" na ekrane bez progressa, poka shli desyatki
    // tysyach faylov. Teper' u obhoda yest' deadline: chto uspeli — to i vernuli.
    private const val DEFAULT_BUDGET_MS = 20_000L

    /**
     * Найти ВСЕ APK файлы на телефоне (в пределах бюджета времени/обхода).
     */
    fun findAllApkFiles(context: Context, budgetMs: Long = DEFAULT_BUDGET_MS): List<ApkItem> {
        val result = mutableListOf<ApkItem>()
        val seenPaths = mutableSetOf<String>()
        val visits = intArrayOf(0)
        val deadline = SystemClock.elapsedRealtime() + budgetMs

        try {
            val storage = Environment.getExternalStorageDirectory()
            Log.d(TAG, "🔍 Начинаю полное сканирование: ${storage.absolutePath}")

            // Рекурсивно сканируем весь телефон
            scanDirectoryRecursive(storage, result, seenPaths, 0, visits, deadline)

            if (visits[0] >= MAX_VISITS) {
                Log.w(TAG, "⚠️ Достигнут лимит обхода ($MAX_VISITS) — скан мог не покрыть всё хранилище")
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "⚠️ Достигнут лимит времени (${budgetMs}ms) — скан мог не покрыть всё хранилище")
            }
            Log.d(TAG, "✅ Найдено ${result.size} APK файлов (обойдено ${visits[0]})")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сканирования", e)
        }

        return result
    }
    
    /**
     * Рекурсивное сканирование папки
     */
    private fun scanDirectoryRecursive(
        dir: File,
        result: MutableList<ApkItem>,
        seenPaths: MutableSet<String>,
        depth: Int,
        visits: IntArray,
        deadline: Long
    ) {
        // Проверки безопасности
        if (depth > MAX_DEPTH) {
            return
        }

        if (visits[0] >= MAX_VISITS) {
            return
        }

        if (SystemClock.elapsedRealtime() > deadline) {
            return
        }

        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return
        }
        
        // Пропускаем системные папки
        val skipFolders = setOf(
            "Android/data",
            "Android/obb",
            ".android_secure",
            ".thumbnails",
            ".cache"
        )
        
        if (skipFolders.any { dir.absolutePath.contains(it) }) {
            return
        }
        
        try {
            val files = dir.listFiles() ?: return

            // Сначала обрабатываем файлы
            for (file in files) {
                if (visits[0] >= MAX_VISITS) break
                if (SystemClock.elapsedRealtime() > deadline) break
                visits[0]++

                try {
                    if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                        val path = file.absolutePath

                        if (!seenPaths.contains(path)) {
                            seenPaths.add(path)

                            val item = ApkItem(
                                file = file,
                                name = file.name,
                                path = path,
                                sizeBytes = file.length()
                            )

                            result.add(item)
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки отдельных файлов
                }
            }

            // Потом рекурсивно обрабатываем подпапки
            for (file in files) {
                if (visits[0] >= MAX_VISITS) break
                if (SystemClock.elapsedRealtime() > deadline) break

                try {
                    if (file.isDirectory) {
                        scanDirectoryRecursive(file, result, seenPaths, depth + 1, visits, deadline)
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки отдельных папок
                }
            }

        } catch (e: SecurityException) {
            Log.d(TAG, "⚠️ Нет доступа: ${dir.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в папке: ${dir.name}", e)
        }
    }
}
