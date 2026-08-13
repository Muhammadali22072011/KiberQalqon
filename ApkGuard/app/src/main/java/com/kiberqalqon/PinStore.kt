package com.uzguard

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ====== HIMOYA QULFI PIN SAQLAGICHI ======
 *
 * 4 xonali PIN'ni tuzli SHA-256 hash sifatida "uzguard_pin" prefs'da saqlaydi. XOM PIN
 * HECH QACHON saqlanmaydi — faqat tasodifiy tuz (per-install, PIN o'rnatilganda yaratiladi)
 * + hash. Tekshirishda hash qayta hisoblanadi va doimiy-vaqtli taqqoslanadi.
 *
 * MUHIM: bu — kriptografik "seif" EMAS (PIN entropiyasi 4 xonali). Vazifasi — ilovaga
 * tasodifiy kirishni to'sish. Ilovani tizim sozlamalaridan majburan to'xtatish/o'chirish
 * PIN'ni chetlab o'tishi mumkin (PinLockActivity buni halol aytadi).
 */
object PinStore {

    private const val PREFS = "uzguard_pin"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCK_UNTIL = "pin_lock_until"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** PIN o'rnatilganmi (hash + tuz mavjudmi). */
    fun isSet(ctx: Context): Boolean {
        val p = prefs(ctx)
        return !p.getString(KEY_HASH, null).isNullOrBlank() &&
            !p.getString(KEY_SALT, null).isNullOrBlank()
    }

    /** Yangi PIN o'rnatadi — yangi tasodifiy tuz yaratib, tuz+PIN hash'ini saqlaydi. */
    fun set(ctx: Context, pin: String) {
        val salt = randomSaltHex()
        val hash = hash(salt, pin)
        prefs(ctx).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash)
            .apply()
    }

    /** Kiritilgan PIN saqlangan hash bilan mos keladimi (doimiy-vaqtli taqqoslash). */
    fun verify(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString(KEY_SALT, null) ?: return false
        val stored = p.getString(KEY_HASH, null) ?: return false
        val candidate = hash(salt, pin)
        return constantTimeEquals(stored, candidate)
    }

    /** PIN'ni butunlay o'chiradi (qulfni o'chirish). */
    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_HASH).remove(KEY_SALT)
            .remove(KEY_FAILS).remove(KEY_LOCK_UNTIL)
            .apply()
    }

    // ── Noto'g'ri urinishlarga qarshi backoff ──
    // 4 xonali PIN'da (10k kombinatsiya) cheksiz urinish = brute-force uchun ochiq eshik edi.
    // 5 xatodan keyin 30s qulf, har keyingi xato bilan ikki baravar (max 8 daqiqa).

    /** Qulf tugashigacha qolgan millisekundlar (0 = qulf yo'q). */
    fun lockedRemainingMs(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Noto'g'ri PIN qayd etiladi; kerak bo'lsa qulf muddati uzaytiriladi. */
    fun recordFail(ctx: Context) {
        val p = prefs(ctx)
        val fails = p.getInt(KEY_FAILS, 0) + 1
        val e = p.edit().putInt(KEY_FAILS, fails)
        if (fails >= 5) {
            val steps = (fails - 5).coerceAtMost(4)
            val lockMs = 30_000L shl steps  // 30s, 60s, 2min, 4min, 8min (cap)
            e.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lockMs)
        }
        e.apply()
    }

    /** To'g'ri PIN — hisoblagich va qulf tozalanadi. */
    fun resetFails(ctx: Context) {
        prefs(ctx).edit().remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
    }

    // ── Yordamchilar ──

    private fun randomSaltHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(saltHex: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(saltHex.toByteArray(Charsets.UTF_8))
        md.update(pin.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Uzunlik/mazmun sizib chiqmasligi uchun doimiy-vaqtli satr taqqoslash. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
