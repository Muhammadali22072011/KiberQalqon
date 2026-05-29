package com.kiberqalqon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Maxfiy kirishning 4-qulfi: server rolni tasdiqlaydi.
 *
 * Qurilma soatlik kod + login + parolni cloudga yuboradi. Server (egasining
 * backendi) tekshiradi va shu odamga BERILGAN rolni qaytaradi: nomi (tag),
 * huquqlar (permissions) va ko'rsatiladigan komponentlar (components).
 *
 * Cloud sozlanmagan bo'lsa (CLOUD_BASE_URL bo'sh) — NotConfigured qaytaradi,
 * ekran "server hali sozlanmagan" deb ko'rsatadi (forklar/lokal uchun no-op).
 *
 * Maxfiylik: faqat HTTPS. x-device-secret — bu haqiqiy KiberQalqon ilovasidan
 * kelganini bildiradigan qo'pol darvoza (ADMIN_SECRET bu yerda ISHLATILMAYDI).
 */
object RoleAccessClient {

    private const val TAG = "RoleAccess"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

    sealed class Result {
        data class Success(
            val roleName: String,
            val permissions: List<String>,
            val components: List<String>,
        ) : Result()
        /** Server javob berdi, lekin kod/login/parol noto'g'ri yoki rad etildi. */
        data class Failure(val reason: String) : Result()
        /** Cloud build'da sozlanmagan — funksiya o'chiq. */
        object NotConfigured : Result()
        /** Tarmoq/server xatosi (ulanib bo'lmadi). */
        data class NetworkError(val reason: String) : Result()
    }

    /**
     * @param code     soatlik kod (egadan olingan)
     * @param login    xodim logini
     * @param password parol
     * @param onResult main thread'da chaqiriladi
     */
    fun login(
        ctx: Context,
        code: String,
        login: String,
        password: String,
        onResult: (Result) -> Unit,
    ) {
        val base = baseUrl()
        val secret = deviceSecret()
        if (base == null || secret == null) {
            runMain { onResult(Result.NotConfigured) }
            return
        }

        val body = JSONObject().apply {
            put("code", code.trim())
            put("login", login.trim())
            put("password", password)
        }

        scope.launch {
            val result = try {
                val req = Request.Builder()
                    .url("$base/api/role/login")
                    .header("x-device-secret", secret)
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    parse(resp.code, text)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "login failed", e)
                Result.NetworkError(e.javaClass.simpleName)
            }
            runMain { onResult(result) }
        }
    }

    private fun parse(httpCode: Int, text: String): Result {
        // Server kontrakti (kelajakda /api/role/login):
        //   200 { ok:true, role:{ name, permissions:[], components:[] } }
        //   200 { ok:false, error:"..." }  — kod/login/parol noto'g'ri
        val json = try { JSONObject(text) } catch (_: Throwable) { null }
        if (json == null) {
            // 404 — endpoint hali yo'q (deploy qilinmagan). Aniq xabar beramiz.
            return if (httpCode == 404) {
                Result.NetworkError("endpoint yo'q (404) — backend hali deploy qilinmagan")
            } else {
                Result.NetworkError("HTTP $httpCode")
            }
        }
        if (!json.optBoolean("ok", false)) {
            return Result.Failure(json.optString("error", "rad etildi"))
        }
        val role = json.optJSONObject("role")
            ?: return Result.Failure("server javobi noto'g'ri")
        val name = role.optString("name", "Xodim")
        val perms = jsonArrayToList(role.optJSONArray("permissions"))
        val comps = jsonArrayToList(role.optJSONArray("components"))
        return Result.Success(name, perms, comps)
    }

    private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    // ---- Konfiguratsiya (CloudTelemetry bilan bir xil BuildConfig) --------

    private fun baseUrl(): String? {
        val u = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (u.isBlank() || !u.startsWith("https://")) return null
        return u
    }

    private fun deviceSecret(): String? {
        val s = BuildConfig.CLOUD_DEVICE_SECRET.trim()
        return if (s.isBlank()) null else s
    }

    private fun runMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
