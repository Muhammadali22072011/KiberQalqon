package com.kiberqalqon

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Egasi uchun: joriy soatlik maxfiy kodni ko'rsatish (maxfiy kirishning 2-qulfi).
 *
 * Kodni server ROLE_CODE_SECRET + joriy soatdan hisoblaydi (stateless). Uni faqat
 * egasi ko'ra oladi — chunki /api/role/code admin sirini (x-admin-secret) talab
 * qiladi. Bu sir APK ichida YO'Q (xavfsizlik qoidasi): egasi o'z telefonida bir
 * marta kiritadi (SecretAccess.ownerSecret — faqat shu qurilmada saqlanadi).
 *
 * Egasi shu kodni ko'rib, ishonchli xodimga (operatorga) beradi; operator esa uni
 * SecretAccessActivity'da kiritadi (RoleAccessClient.login serverda tekshiradi).
 */
object OwnerCodeClient {

    private const val TAG = "OwnerCode"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        /** Server joriy kodni qaytardi. */
        data class Success(val code: String, val secondsLeft: Int) : Result()
        /** Admin siri noto'g'ri (401) — egasi qayta kiritishi kerak. */
        object BadSecret : Result()
        /** Server ROLE_CODE_SECRET'siz sozlangan (500) yoki boshqa server xatosi. */
        data class ServerError(val reason: String) : Result()
        /** Cloud build'da sozlanmagan (CLOUD_BASE_URL bo'sh). */
        object NotConfigured : Result()
        /** Tarmoq/ulanish xatosi. */
        data class NetworkError(val reason: String) : Result()
    }

    /**
     * @param adminSecret egasi kiritgan admin siri (xom ADMIN_SECRET yoki sessiya tokeni)
     * @param onResult    main thread'da chaqiriladi
     */
    fun fetch(adminSecret: String, onResult: (Result) -> Unit) {
        val base = baseUrl()
        val secret = adminSecret.trim()
        if (base == null) {
            runMain { onResult(Result.NotConfigured) }
            return
        }
        if (secret.isEmpty()) {
            runMain { onResult(Result.BadSecret) }
            return
        }

        scope.launch {
            val result = try {
                val req = Request.Builder()
                    .url("$base/api/role/code")
                    .header("x-admin-secret", secret)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    parse(resp.code, resp.body?.string().orEmpty())
                }
            } catch (e: Throwable) {
                Log.w(TAG, "code fetch failed", e)
                Result.NetworkError(e.javaClass.simpleName)
            }
            runMain { onResult(result) }
        }
    }

    private fun parse(httpCode: Int, text: String): Result {
        if (httpCode == 401) return Result.BadSecret
        val json = try { JSONObject(text) } catch (_: Throwable) { null }
            ?: return if (httpCode == 404) {
                Result.NetworkError("endpoint yo'q (404) — backend deploy qilinmagan")
            } else {
                Result.NetworkError("HTTP $httpCode")
            }
        if (!json.optBoolean("ok", false)) {
            return Result.ServerError(json.optString("error", "server xatosi"))
        }
        val code = json.optString("code", "")
        if (code.isBlank()) return Result.ServerError("kod bo'sh")
        return Result.Success(code, json.optInt("seconds_left", 0))
    }

    private fun baseUrl(): String? {
        val u = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (u.isBlank() || !u.startsWith("https://")) return null
        return u
    }

    private fun runMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
