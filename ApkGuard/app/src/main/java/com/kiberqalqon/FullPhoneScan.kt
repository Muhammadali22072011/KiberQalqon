package com.kiberqalqon

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Полное сканирование ВСЕГО телефона - рекурсивно по всем папкам
 */
object FullPhoneScan {
    private const val TAG = "FullPhoneScan"
    private const val MAX_DEPTH = 5 // Максимальная глубина рекурсии
    private const val MAX_FILES = 100 // Максимум файлов для безопасности
    
    /**
     * Найти ВСЕ APK файлы на телефоне
     */
    fun findAllApkFiles(context: Context): List<ApkItem> {
        val result = mutableListOf<ApkItem>()
        val seenPaths = mutableSetOf<String>()
        
        try {
            val storage = Environment.getExternalStorageDirectory()
            Log.d(TAG, "🔍 Начинаю полное сканирование: ${storage.absolutePath}")
            
            // Рекурсивно сканируем весь телефон
            scanDirectoryRecursive(storage, result, seenPaths, 0)
            
            Log.d(TAG, "✅ Найдено ${result.size} APK файлов")
            
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
        depth: Int
    ) {
        // Проверки безопасности
        if (depth > MAX_DEPTH) {
            Log.d(TAG, "⏭️ Пропускаю (глубина): ${dir.name}")
            return
        }
        
        if (result.size >= MAX_FILES) {
            Log.d(TAG, "⏹️ Достигнут лимит файлов: $MAX_FILES")
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
                if (result.size >= MAX_FILES) break
                
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
                            Log.d(TAG, "✅ Найден APK: ${file.name} в ${dir.name}")
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки отдельных файлов
                }
            }
            
            // Потом рекурсивно обрабатываем подпапки
            for (file in files) {
                if (result.size >= MAX_FILES) break
                
                try {
                    if (file.isDirectory) {
                        scanDirectoryRecursive(file, result, seenPaths, depth + 1)
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
