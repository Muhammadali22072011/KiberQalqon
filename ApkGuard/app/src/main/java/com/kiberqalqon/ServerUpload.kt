package com.kiberqalqon

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "ServerUpload"
private const val MAX_FILE_SIZE = 50L * 1024 * 1024  // 50 MB

object ServerUpload {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun uploadApk(serverUrl: String, apkFile: File, fileName: String): Boolean {
        if (serverUrl.isBlank()) return false

        try {
            if (!apkFile.exists() || !apkFile.canRead()) {
                Log.w(TAG, "File not accessible: ${apkFile.absolutePath}")
                return false
            }

            if (apkFile.length() > MAX_FILE_SIZE) {
                Log.w(TAG, "File too large: ${apkFile.length()} bytes")
                return false
            }

            // Безопасное построение URL: парсим как HttpUrl и добавляем сегмент.
            // Старая конкатенация ломалась на URL вида "https://srv/?token=..."
            // (получалось "https://srv/?token=/upload" вместо "https://srv/upload?token=...").
            val base = serverUrl.trim().toHttpUrlOrNull()
            if (base == null) {
                Log.w(TAG, "Invalid server URL: $serverUrl")
                return false
            }
            // Принимаем только HTTPS — иначе MITM может перехватить отправляемый APK.
            if (base.scheme != "https") {
                Log.w(TAG, "Non-HTTPS server URL rejected: $serverUrl")
                return false
            }
            val url = base.newBuilder().addPathSegment("upload").build()

            // Защита от injection в имя файла — оставляем только безопасные символы.
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "apk",
                    safeName,
                    apkFile.asRequestBody("application/vnd.android.package-archive".toMediaType())
                )
                .addFormDataPart("name", safeName)
                .build()

            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                if (!ok) {
                    Log.w(TAG, "Upload failed: ${response.code} - ${response.message}")
                } else {
                    Log.d(TAG, "Upload successful: $safeName")
                }
                return ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for $fileName", e)
            return false
        }
    }
}
