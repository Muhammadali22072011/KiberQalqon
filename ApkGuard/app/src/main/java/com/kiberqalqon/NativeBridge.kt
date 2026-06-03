package com.kiberqalqon

import android.util.Log

/**
 * libkqguard.so'ga yagona kirish nuqtasi.
 *
 * Native qatlam faqat ikkita ishni qiladi (ikkalasi ham Kotlin'da oson patch
 * qilinadigan, lekin nativeda qiyin): imzo tekshiruvi + anti-debug. Maxfiy SIR
 * bu yerda YO'Q — tarmoq sirlari Shield orqali [Secrets]'da.
 *
 * HAR BIR chaqiruvning Kotlin fallback'i bor: agar .so yuklanmasa (test JVM, ABI
 * mos kelmaydigan qurilma, yoki umuman NDK'siz qurilgan build), [isLoaded] false
 * bo'ladi va chaqiruvchilar (SecurityGuard) eski to'liq Kotlin yo'liga tushadi.
 * Shu sabab ilova .so bilan ham, .so'siz ham quriladi va himoyalangan qoladi.
 *
 * Test/Debug: pure-JVM unit testlar System.loadLibrary'ni yuklay olmaydi →
 * UnsatisfiedLinkError ushlanadi, loaded=false. SecurityGuard debug'da baribir
 * butunlay o'tkazib yuboriladi, shuning uchun dev oqimiga ta'sir yo'q.
 */
object NativeBridge {

    private const val TAG = "NativeBridge"

    @Volatile private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("kqguard")
            nPing()                       // ramzlar haqiqatan bog'langanini tasdiqlaymiz
        } catch (t: Throwable) {
            Log.e(TAG, "kqguard unavailable", t)   // Log.e R8'da saqlanadi
            false
        }
    }

    /** Idempotent: obyektga tegish init blokini ishga tushiradi. App.onCreate'dan chaqiriladi. */
    fun init() { /* obyektga murojaat → init bloki bajariladi */ }

    fun isLoaded(): Boolean = loaded

    // ---- native metodlar (JNI_OnLoad'da RegisterNatives bilan bog'lanadi) ----
    private external fun nPing(): Boolean
    private external fun nAntiDebug(): Boolean
    private external fun nSigInvalid(hexUpper: String): Boolean

    /**
     * Debugger ulanganmi (native TracerPid). Lib yo'q bo'lsa false (qoqilmagan) —
     * debug/test'da .so yo'q va SecurityGuard ham debug'da o'chiq. Fail-open.
     */
    fun antiDebugTripped(): Boolean =
        if (loaded) try { nAntiDebug() } catch (_: Throwable) { false } else false

    /**
     * Native imzo tekshiruvi. [hexUpper] = SHA-256(cert DER) ning UPPERCASE hex'i
     * (SecurityGuard.isSignatureInvalid bilan AYNAN bir xil baytlardan). true = NOTO'G'RI.
     * Lib yo'q / xato bo'lsa true (himoya tomon) — chaqiruvchi `isLoaded()` bilan gate
     * qilib, lib yo'qida Kotlin const fallback'iga tushadi.
     */
    fun signatureInvalid(hexUpper: String): Boolean =
        if (loaded) try { nSigInvalid(hexUpper) } catch (_: Throwable) { true } else true
}
