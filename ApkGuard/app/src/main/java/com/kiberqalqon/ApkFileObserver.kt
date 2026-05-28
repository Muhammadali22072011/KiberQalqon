package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Следит за папкой Downloads и автоматически запускает сканирование
 * когда появляется новый APK файл
 */
class ApkFileObserver(
    private val context: Context,
    path: File
) : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {

    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "ApkFileObserver"
    private val watchPath = path.absolutePath

    override fun onEvent(event: Int, path: String?) {
        try {
            if (path == null) {
                Log.d(TAG, "Event with null path")
                return
            }
            
            Log.d(TAG, "File event: $event, path: $path")
            
            // Проверяем что это APK файл
            if (!path.endsWith(".apk", ignoreCase = true)) {
                Log.d(TAG, "Not an APK file: $path")
                return
            }
            
            Log.d(TAG, "New APK detected: $path")
            
            // Ждём 2 секунды чтобы файл полностью скачался
            handler.postDelayed({
                try {
                    val file = File(watchPath, path)
                    Log.d(TAG, "Checking file: ${file.absolutePath}")
                    
                    if (file.exists()) {
                        Log.d(TAG, "File exists, size: ${file.length()}")
                        if (file.length() > 0) {
                            Log.d(TAG, "Launching auto scan for: ${file.name}")
                            launchAutoScan(file)
                        } else {
                            Log.d(TAG, "File is empty")
                        }
                    } else {
                        Log.d(TAG, "File does not exist")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed handler", e)
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onEvent", e)
        }
    }

    private fun launchAutoScan(file: File) {
        try {
            TelemetryReporter.reportDownloadDetected(context, file.absolutePath, file.length())
        } catch (e: Throwable) {
            Log.w(TAG, "telemetry download_detected failed", e)
        }
        try {
            Log.d(TAG, "Creating intent for AutoScanActivity")
            val intent = Intent(context, AutoScanActivity::class.java).apply {
                putExtra("apk_path", file.absolutePath)
                putExtra("apk_name", file.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            Log.d(TAG, "Starting AutoScanActivity")
            context.startActivity(intent)
            Log.d(TAG, "AutoScanActivity started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AutoScanActivity", e)
        }
    }
}
