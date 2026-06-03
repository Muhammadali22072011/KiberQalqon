package com.kiberqalqon

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import kotlin.math.abs

/**
 * Обнаружение PHISHING-ICON impersonation:
 *   виртуально-bank APK с иконкой Click/Payme/Telegram, но package = "com.xxx.xxx".
 *
 * Метод: aHash (average hash) — устойчивая к мелким изменениям perceptual hash.
 *
 *  1) Скейлим иконку в 8x8 grayscale.
 *  2) Считаем среднее значение пикселей.
 *  3) Каждый пиксель: 1 если выше среднего, 0 иначе → 64-битный hash.
 *  4) Hamming distance < 10 = "почти та же иконка".
 *
 * Базу легитимных иконок строим в runtime: если на устройстве установлен
 * настоящий Telegram (org.telegram.messenger), берём его иконку, считаем hash,
 * сохраняем как baseline.
 *
 * Если потом приходит APK с package "com.fake.tg" и иконка совпадает с baseline'ом
 * Telegram'а — это PHISHING.
 */
object IconImpersonationDetector {

    private const val TAG = "IconImpersonation"
    private const val SIZE = 8
    private const val HAMMING_THRESHOLD = 10  // 0-64; <10 = очень похоже

    /**
     * Известные легитимные пакеты, чьи иконки часто копируют для фишинга.
     * Берём hash их РЕАЛЬНЫХ иконок если они установлены на устройстве.
     */
    private val PROTECTED_PACKAGES = mapOf(
        "org.telegram.messenger" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.whatsapp" to "WhatsApp",
        // #7: paket nomlari noto'g'ri edi → ikona-bazasi yuklanmasdi, bu banklar fishingi
        //     umuman aniqlanmasdi. Kanonik nomlar (AppReputation/FilenameHeuristic bilan bir xil).
        "uz.click.evo" to "Click",
        "uz.dida.payme" to "Payme",
        "uz.uzcard.uzcard" to "Uzcard",
        "uz.dida.smartbank" to "Smart Bank",
        "uz.mobiuz.android" to "Mobiuz",
        "uz.beeline.odp" to "Beeline UZ",
        "uz.ums.tenge" to "Apelsin",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.android.chrome" to "Chrome",
        "com.google.android.gm" to "Gmail"
    )

    data class Match(
        val mimickedPackage: String,
        val mimickedLabel: String,
        val hammingDistance: Int
    )

    /** Получить aHash иконки APK, который мы сканируем. */
    fun hashOfApkIcon(context: Context, apkPath: String): Long? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
            info.applicationInfo?.let {
                it.sourceDir = apkPath
                it.publicSourceDir = apkPath
                val icon = it.loadIcon(pm) ?: return null
                aHash(drawableToBitmap(icon))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "hashOfApkIcon failed", e)
            null
        }
    }

    /**
     * Проверка: соответствует ли иконка сканируемого APK иконке одного из защищённых пакетов?
     * Защищённый пакет берётся из set-of-installed-on-device + PROTECTED_PACKAGES list.
     * Если APK ПРИКИДЫВАЕТСЯ Telegram'ом, но это НЕ Telegram (package другой) — match.
     */
    fun detect(context: Context, apkPath: String, scannedPackage: String?): Match? {
        val targetHash = hashOfApkIcon(context, apkPath) ?: return null

        val pm = context.packageManager
        for ((legitPkg, label) in PROTECTED_PACKAGES) {
            // Если сканируемый — это сам легитимный пакет, не алерт.
            if (scannedPackage == legitPkg) continue
            try {
                val legitInfo = pm.getApplicationInfo(legitPkg, 0)
                val legitIcon = legitInfo.loadIcon(pm) ?: continue
                val legitHash = aHash(drawableToBitmap(legitIcon))
                val dist = hamming(targetHash, legitHash)
                if (dist <= HAMMING_THRESHOLD) {
                    return Match(legitPkg, label, dist)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Этот защищённый пакет не установлен — пропускаем.
            } catch (e: Throwable) {
                Log.w(TAG, "compare with $legitPkg failed", e)
            }
        }
        return null
    }

    /** aHash: 8x8 grayscale, bit=1 если выше среднего. */
    @androidx.annotation.VisibleForTesting
    internal fun aHash(bmp: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        var sum = 0
        val gray = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val c = scaled.getPixel(x, y)
                // ITU-R BT.601 luminance
                val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                gray[y * SIZE + x] = g
                sum += g
            }
        }
        val avg = sum / (SIZE * SIZE)
        var hash = 0L
        for (i in gray.indices) {
            if (gray[i] >= avg) hash = hash or (1L shl i)
        }
        if (scaled !== bmp) scaled.recycle()
        return hash
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceAtLeast(48)
        val h = drawable.intrinsicHeight.coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }
}
