package com.uzguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Setevaya informatsiya dlya admin-paneli (LIChNYJ Telegram bot).
 *
 * Eto ne community sharing. Dannye idut TOL'KO v lichnyy bot pol'zovatelya
 * (token + chat_id kotoryye on sam vvel v DIAGNOSTIKA). Eto ego sobstvennoye
 * ustroystvo — eto kak TeamViewer dlya svoego telefona.
 */
object NetworkInfo {

    private const val TAG = "NetworkInfo"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** Local IPv4 — bystro, bez seti. Nichego ne trebuet krome INTERNET permission. */
    fun localIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // 169.254.* — link-local, propustim
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "localIp failed", e); null
        }
    }

    /** Tip seti: WIFI / CELLULAR / ETHERNET / NONE. */
    fun connectionType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "?"
            val net = cm.activeNetwork ?: return "NONE"
            val caps = cm.getNetworkCapabilities(net) ?: return "NONE"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "connType failed", e); "?"
        }
    }

    /** External/public IP — sinhronnyy. Vyzyvaem TOL'KO iz background thread. */
    fun externalIp(): String? {
        // Ispol'zuem ipify.org — prostoy plain-text endpoint, bez kakogo-libo trackinga.
        return try {
            val req = Request.Builder()
                .url("https://api.ipify.org")
                .header("User-Agent", "UzGuard/admin")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.trim()?.takeIf { it.isNotBlank() && it.length <= 45 }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "externalIp failed", e); null
        }
    }
}
