package com.uzguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Qurilmaning aniq GPS koordinatasini o'qiydi — markaziy himoya xaritasi uchun.
 *
 * Maxfiylik:
 *   - FAQAT foydalanuvchi joylashuv ruxsatini bergan bo'lsa ishlaydi (aks holda null).
 *   - Fon (background) joylashuv ISHLATILMAYDI: koordinata faqat ilova ishlatilayotganda
 *     (skan / register) o'qiladi. Doimiy kuzatuv yo'q.
 *   - Google Play Services TALAB QILINMAYDI — AOSP LocationManager (GMS'siz qurilmalarda ham ishlaydi).
 *   - Koordinata 6 xona aniqlikka yumaloqlanadi (~0.1 m) — keraksiz ortiqcha aniqlik bermaslik uchun.
 *
 * MUHIM (xato tuzatildi): ilk o'rnatishdan keyin getLastKnownLocation() deyarli HAR DOIM null
 * qaytaradi — hech bir ilova hali joylashuv so'ramagan bo'lsa OS keshida nuqta bo'lmaydi.
 * Shuning uchun kesh bo'sh/eskirган bo'lsa, providerlardan BIR MARTALIK aktiv fix so'raymiz.
 */
object DeviceLocation {
    private const val TAG = "DeviceLocation"

    // Kesh fix shu muddatdan yangi bo'lsa — aktiv GPS yoqmasdan uni ishlatamiz (batareya tejaymiz).
    private const val FRESH_ENOUGH_MS = 10L * 60 * 1000   // 10 daqiqa
    // Bir martalik aktiv fix uchun maksimal kutish (fon oqimini shu muddatdan ko'p ushlamaymiz).
    private const val ACTIVE_TIMEOUT_MS = 8_000L
    // 12 soatlik throttle oynasida ko'chishni tekshirishda ishlatiladigan eng katta kesh yoshi.
    // Bundan eski kesh "joriy nuqta" sifatida ishlatilmaydi (#27 — kunlar oldingi nuqta yozilib qolmasin).
    const val IN_WINDOW_MAX_AGE_MS = 60L * 60 * 1000      // 1 soat

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Float?)

    fun hasPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Eng so'nggi ma'lum joylashuv (non-blocking, kesh). Aktiv GPS so'rovini bloklab kutmaydi.
     * Kesh bo'lmasa null — bu holda currentFix() dan foydalaning.
     */
    fun lastKnown(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return freshestLastKnown(lm)?.toFix()
    }

    /**
     * lastKnown() kabi, lekin kesh nuqta [maxAgeMs] dan eski bo'lsa null qaytaradi (#27).
     * Eskirgan keshni "joriy joylashuv" deb ishlatib, xaritaga kunlar oldingi nuqtani
     * yozib qo'yishning oldini oladi.
     */
    fun lastKnownFresh(ctx: Context, maxAgeMs: Long): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = freshestLastKnown(lm) ?: return null
        if (System.currentTimeMillis() - loc.time > maxAgeMs) return null
        return loc.toFix()
    }

    /**
     * Joriy joylashuvni qaytaradi: avval kesh (yetarlicha yangi bo'lsa), aks holda
     * providerlardan bir martalik aktiv fix so'raydi (timeout bilan), so'ng eski keshga qaytadi.
     *
     * BLOKLAYDI (timeoutMs gacha) — FAQAT fon (IO) oqimidan chaqiring, asosiy oqimdan EMAS.
     */
    fun currentFix(ctx: Context, timeoutMs: Long = ACTIVE_TIMEOUT_MS): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val cached = freshestLastKnown(lm)
        if (cached != null && System.currentTimeMillis() - cached.time <= FRESH_ENOUGH_MS) {
            return cached.toFix()
        }
        val active = requestActive(lm, timeoutMs)
        return (active ?: cached)?.toFix()
    }

    // ---- internal ----------------------------------------------------------

    private fun freshestLastKnown(lm: LocationManager): Location? = try {
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        best
    } catch (e: Throwable) {
        Log.w(TAG, "lastKnown failed", e); null
    }

    /** Yoqilgan providerlar — tezligi/batareyasi bo'yicha tartiblangan (fused → network → gps). */
    private fun enabledProviders(lm: LocationManager): List<String> {
        val out = ArrayList<String>(3)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
            ) out.add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) out.add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) out.add(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) { /* provider mavjud emas — e'tiborsiz */ }
        return out
    }

    /**
     * Yoqilgan providerlardan bir martalik aktiv fix so'raydi; birinchi kelgan nuqta yutadi.
     * Joylashuv xizmati o'chiq bo'lsa providerlar bo'sh bo'ladi → null qaytadi.
     */
    @Suppress("MissingPermission") // hasPermission() currentFix() boshida tekshirilgan
    private fun requestActive(lm: LocationManager, timeoutMs: Long): Location? {
        val providers = enabledProviders(lm)
        if (providers.isEmpty()) return null

        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signals = ArrayList<CancellationSignal>(providers.size)
            val executor = Executor { it.run() }
            try {
                for (p in providers) {
                    val cs = CancellationSignal()
                    signals.add(cs)
                    lm.getCurrentLocation(p, cs, executor) { loc ->
                        // getCurrentLocation timeout bo'lsa null yuborishi mumkin — faqat haqiqiy nuqtani olamiz.
                        if (loc != null && result.compareAndSet(null, loc)) latch.countDown()
                    }
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                Log.w(TAG, "getCurrentLocation failed", e)
            } finally {
                signals.forEach { it.cancel() }
            }
        } else {
            // API 24–29: requestLocationUpdates bilan bir martalik (birinchi nuqtadan keyin removeUpdates).
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (result.compareAndSet(null, loc)) latch.countDown()
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                // API 29-gacha abstract bo'lgan — eski qurilmada AbstractMethodError bo'lmasligi uchun beramiz.
                @Deprecated("API 29 da deprecated; eski qurilmada abstract bo'lgani uchun berilishi shart.")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                val looper = Looper.getMainLooper()
                for (p in providers) {
                    try {
                        lm.requestLocationUpdates(p, 0L, 0f, listener, looper)
                    } catch (e: Throwable) {
                        Log.w(TAG, "requestLocationUpdates($p) failed", e)
                    }
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                Log.w(TAG, "active fix failed", e)
            } finally {
                try { lm.removeUpdates(listener) } catch (_: Throwable) {}
            }
        }
        return result.get()
    }

    private fun Location.toFix(): Fix =
        Fix(round6(latitude), round6(longitude), if (hasAccuracy()) accuracy else null)

    private fun round6(d: Double): Double = Math.round(d * 1e6) / 1e6
}
