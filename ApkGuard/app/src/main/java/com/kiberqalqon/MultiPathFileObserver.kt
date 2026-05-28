package com.kiberqalqon

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Мониторинг нескольких папок одновременно
 * Следит за: Downloads, Telegram, WhatsApp, Bluetooth и т.д.
 */
class MultiPathFileObserver(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "MultiPathObserver"
    private val observers = mutableListOf<ImprovedApkFileObserver>()
    
    /**
     * Запустить мониторинг всех папок + корня рекурсивно
     */
    fun startWatching() {
        try {
            val storage = Environment.getExternalStorageDirectory()

            // Дедупликация по canonical path: DIRECTORY_DOWNLOADS == File(storage,"Download")
            // на большинстве устройств — раньше создавалось 2 observer'а на одну папку
            // и каждый APK прилетал в Telegram-канал дважды.
            val watchPaths = mutableListOf<File>()

            watchPaths.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            watchPaths.add(File(storage, "Download"))
            watchPaths.add(File(storage, "Telegram"))
            watchPaths.add(File(storage, "WhatsApp"))
            watchPaths.add(File(storage, "Bluetooth"))
            watchPaths.add(File(storage, "DCIM"))
            watchPaths.add(File(storage, "Documents"))

            // Android 11+ scoped storage: WhatsApp va Telegram endi
            // /Android/media/<pkg>/ ga yuklaydi. Eski jadval'da bu papkalar yo'q edi —
            // shuning uchun ko'pchilik Telegram'dan kelgan APK'lar FileObserver'ni
            // umuman ishga tushirmasdi. Endi qo'shamiz; FileObserver bu papkalarda
            // ishlamasligi mumkin (ba'zi qurilmalarda), shuning uchun 15 daq periodik
            // GuardWorker MediaStore orqali ham qidiradi — qo'shimcha himoya qatlami.
            val mediaRoot = File(storage, "Android/media")
            watchPaths.add(File(mediaRoot, "org.telegram.messenger/Telegram"))
            watchPaths.add(File(mediaRoot, "org.telegram.messenger/Telegram/Telegram Documents"))
            watchPaths.add(File(mediaRoot, "com.whatsapp/WhatsApp"))
            watchPaths.add(File(mediaRoot, "com.whatsapp/WhatsApp/Media"))
            watchPaths.add(File(mediaRoot, "com.whatsapp/WhatsApp/Media/WhatsApp Documents"))
            watchPaths.add(File(mediaRoot, "com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents"))

            addSubfolders(File(storage, "Telegram"), watchPaths, 2)
            addSubfolders(File(storage, "WhatsApp"), watchPaths, 2)
            addSubfolders(File(mediaRoot, "org.telegram.messenger"), watchPaths, 3)
            addSubfolders(File(mediaRoot, "com.whatsapp"), watchPaths, 3)

            watchPaths.add(storage)

            val seen = HashSet<String>()
            watchPaths.forEach { dir ->
                try {
                    if (!dir.exists() || !dir.isDirectory) return@forEach
                    val key = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                    if (!seen.add(key)) {
                        Log.d(TAG, "skip dup: $key")
                        return@forEach
                    }
                    val observer = ImprovedApkFileObserver(context, dir, scope)
                    observer.startWatching()
                    observers.add(observer)
                    Log.d(TAG, "✅ Watching: $key")
                } catch (e: Exception) {
                    Log.e(TAG, "Error watching: ${dir.absolutePath}", e)
                }
            }

            Log.d(TAG, "✅ Started watching ${observers.size} directories")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting observers", e)
        }
    }
    
    /**
     * Добавить все подпапки рекурсивно
     */
    private fun addSubfolders(dir: File, list: MutableList<File>, maxDepth: Int, currentDepth: Int = 0) {
        if (currentDepth >= maxDepth || !dir.exists() || !dir.isDirectory) return
        
        try {
            dir.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory) {
                    list.add(subDir)
                    addSubfolders(subDir, list, maxDepth, currentDepth + 1)
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки
        }
    }
    
    /**
     * Остановить мониторинг
     */
    fun stopWatching() {
        try {
            observers.forEach { it.stopWatching() }
            observers.clear()
            Log.d(TAG, "✅ Stopped all observers")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping observers", e)
        }
    }
}
