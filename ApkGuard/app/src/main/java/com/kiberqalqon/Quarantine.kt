package com.uzguard

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Карантин: вместо прямого `file.delete()` мы переносим подозрительный APK в
 * внутреннюю папку UzGuard, переименовываем (расширение .apk → .quar),
 * и храним 7 дней. Юзер может восстановить файл из истории, если verdict
 * был ложным.
 *
 * Хранение:
 *   files/quarantine/<token>.quar          — сам байтовый контент
 *   files/quarantine/<token>.json          — метаданные (originalPath, name, ts, verdict)
 *
 * Token = sha256(originalPath + ts).take(12hex) — короткий, для inline-кнопок Telegram'а.
 *
 * Безопасность:
 *  • .quar расширение Android не открывает как APK — даже если юзер случайно тапнет,
 *    система не запустит установку.
 *  • Папка внутренняя (filesDir) → MODE_PRIVATE, другие приложения не достанут.
 *  • v2: содержимое .quar шифруется AES-CTR per-install ключом — на диске лежит
 *    НЕ валидный APK/ZIP (нет magic-байтов), его невозможно установить или
 *    скопировать в обход restore(). Старые незашифрованные записи (enc=0)
 *    восстанавливаются как раньше. CTR не меняет длину → sizeBytes честный.
 *  • Auto-purge: при каждом quarantine() удаляем записи старше 7 дней.
 *
 * Что делать с системно-установленным APK: карантин неприменим (apk в /data/app/
 * мы не можем переместить). Для таких случаев Quarantine.fromPackage() копирует
 * sourceDir в карантин ПЕРЕД тем, как мы предложим юзеру удалить пакет.
 */
object Quarantine {

    private const val TAG = "Quarantine"
    private const val PREFS = "uzguard_quarantine"
    private const val KEY_ENTRIES = "entries_v1"
    private const val KEY_ENC_KEY = "enc_key_v1"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    // encVersion qiymatlari: 0 = eski ochiq nusxa, 1 = AES-CTR shifrlangan.
    private const val ENC_NONE = 0
    private const val ENC_AES_CTR = 1

    data class Entry(
        val token: String,
        val originalPath: String,
        val originalName: String,
        val quarantinedAt: Long,
        val verdict: String,
        val reason: String,
        val sizeBytes: Long,
        val encVersion: Int = ENC_NONE
    )

    sealed class Result {
        data class Ok(val entry: Entry) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Главная точка: перенести файл в карантин.
     * Удаляет оригинал ПОСЛЕ успешного копирования.
     */
    fun quarantine(
        context: Context,
        originalFile: File,
        verdict: String,
        reason: String
    ): Result {
        if (!originalFile.exists() || !originalFile.canRead()) {
            return Result.Failed("Asl fayl yo'q yoki o'qib bo'lmaydi")
        }

        // Самозащита: UzGuard в карантин не идёт.
        if (SelfGuard.isOwnApk(context, originalFile.absolutePath)) {
            return Result.Failed("UzGuard o'zini karantinga qo'ya olmaydi")
        }

        purgeExpired(context)

        val token = generateToken(originalFile.absolutePath)
        val quarDir = quarantineDir(context)
        val payloadFile = File(quarDir, "$token.quar")
        val metaFile = File(quarDir, "$token.json")

        // v2: nusxa AES-CTR bilan shifrlanadi — diskda yaroqli APK qolmasin.
        // Shifrlash kutilmaganda ishlamasa — himoya birinchi o'rinda: ochiq nusxa
        // bilan davom etamiz (karantin baribir asl faylni olib tashlaydi).
        var encVersion = ENC_AES_CTR
        try {
            transformCopy(originalFile, payloadFile, QuarCrypto.cipher(encKey(context), token, encrypt = true))
        } catch (e: Throwable) {
            Log.w(TAG, "encrypt-copy failed, falling back to plain copy", e)
            encVersion = ENC_NONE
            try {
                originalFile.copyTo(payloadFile, overwrite = true)
            } catch (e2: Throwable) {
                Log.w(TAG, "copy to quarantine failed", e2)
                return Result.Failed("Karantinga ko'chirib bo'lmadi: ${e2.message}")
            }
        }

        val entry = Entry(
            token = token,
            originalPath = originalFile.absolutePath,
            originalName = originalFile.name,
            quarantinedAt = System.currentTimeMillis(),
            verdict = verdict,
            reason = reason.take(500),
            sizeBytes = payloadFile.length(),
            encVersion = encVersion
        )

        // Сохраняем мету и в JSON-файл рядом, и в SharedPreferences-индекс (для быстрого list).
        try {
            metaFile.writeText(entryToJson(entry).toString())
        } catch (e: Throwable) {
            Log.w(TAG, "write meta failed", e)
        }
        saveIndex(context, loadIndex(context) + entry)

        // Удаляем оригинал. Карантинная .quar-копия остаётся как бэкап в любом случае.
        // (#8) Если оригинал НЕ удалён и всё ещё на диске — это НЕ успех: реальный
        // вредоносный APK по-прежнему установим. Возвращаем Failed, чтобы вызывающий
        // (GuardWorker) не сказал пользователю «вирус удалён», а попросил удалить вручную.
        val removed = try {
            originalFile.delete()
        } catch (e: Throwable) {
            Log.w(TAG, "original delete threw", e)
            false
        }
        if (!removed && originalFile.exists()) {
            Log.w(TAG, "original delete failed after quarantine: ${originalFile.absolutePath}")
            return Result.Failed("Asl zararli faylni o'chirib bo'lmadi — uni qo'lda o'chiring")
        }

        return Result.Ok(entry)
    }

    /**
     * Восстановление файла из карантина — копируем .quar обратно с оригинальным именем.
     * После восстановления запись из карантина удаляется.
     */
    fun restore(context: Context, token: String): Result {
        val entry = loadIndex(context).firstOrNull { it.token == token }
            ?: return Result.Failed("Karantinda topilmadi")

        val quarDir = quarantineDir(context)
        val payloadFile = File(quarDir, "$token.quar")
        if (!payloadFile.exists()) {
            return Result.Failed("Karantin fayli o'chirilgan")
        }

        val target = File(entry.originalPath)
        try {
            target.parentFile?.mkdirs()
            if (entry.encVersion == ENC_AES_CTR) {
                transformCopy(payloadFile, target, QuarCrypto.cipher(encKey(context), entry.token, encrypt = false))
            } else {
                // Eski (v1) ochiq yozuvlar — avvalgidek oddiy nusxa.
                payloadFile.copyTo(target, overwrite = true)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "restore copy failed", e)
            return Result.Failed("Tiklab bo'lmadi: ${e.message}")
        }

        // Удаляем из карантина
        try { payloadFile.delete() } catch (_: Throwable) {}
        try { File(quarDir, "$token.json").delete() } catch (_: Throwable) {}
        saveIndex(context, loadIndex(context).filter { it.token != token })

        try { TelemetryReporter.reportQuarantineRestore(context, entry.originalName) } catch (_: Throwable) {}

        return Result.Ok(entry)
    }

    /** Полное удаление из карантина (без восстановления). */
    fun purge(context: Context, token: String): Boolean {
        val quarDir = quarantineDir(context)
        val ok = try { File(quarDir, "$token.quar").delete() } catch (_: Throwable) { false }
        try { File(quarDir, "$token.json").delete() } catch (_: Throwable) {}
        saveIndex(context, loadIndex(context).filter { it.token != token })
        return ok
    }

    /** Список всех записей в карантине, новые сверху. */
    fun list(context: Context): List<Entry> {
        purgeExpired(context)
        return loadIndex(context).sortedByDescending { it.quarantinedAt }
    }

    fun lookup(context: Context, token: String): Entry? =
        loadIndex(context).firstOrNull { it.token == token }

    // ============================================================
    //  internal
    // ============================================================

    private fun quarantineDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "quarantine")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Per-install karantin kaliti (32 bayt): birinchi murojaatda SecureRandom bilan
     * yaratiladi va prefs'da hex ko'rinishda saqlanadi. Boshqa ilovalar prefs'ga
     * yeta olmaydi (MODE_PRIVATE); kalit qurilmadan tashqariga hech qachon chiqmaydi.
     */
    private fun encKey(context: Context): ByteArray {
        val cur = prefs(context).getString(KEY_ENC_KEY, null)
        if (cur != null && cur.length == 64) {
            try {
                return QuarCrypto.hexToBytes(cur)
            } catch (_: Throwable) {
                // Buzilgan qiymat — yangisini yaratamiz (eski enc=1 yozuvlar tiklanmay
                // qoladi, lekin bu faqat prefs qo'lda buzilganda bo'ladi).
            }
        }
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs(context).edit { putString(KEY_ENC_KEY, QuarCrypto.bytesToHex(fresh)) }
        return fresh
    }

    /** Oqimli nusxa shifr orqali — katta APK'lar uchun ham xotira-xavfsiz. */
    private fun transformCopy(src: File, dst: File, cipher: javax.crypto.Cipher) {
        src.inputStream().use { input ->
            javax.crypto.CipherOutputStream(dst.outputStream(), cipher).use { out ->
                input.copyTo(out)
            }
        }
    }

    private fun purgeExpired(context: Context) {
        val cutoff = System.currentTimeMillis() - TTL_MS
        val all = loadIndex(context)
        val keep = mutableListOf<Entry>()
        for (entry in all) {
            if (entry.quarantinedAt < cutoff) {
                try { File(quarantineDir(context), "${entry.token}.quar").delete() } catch (_: Throwable) {}
                try { File(quarantineDir(context), "${entry.token}.json").delete() } catch (_: Throwable) {}
            } else {
                keep.add(entry)
            }
        }
        if (keep.size != all.size) saveIndex(context, keep)
    }

    private fun generateToken(path: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val raw = "$path|${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        val hash = md.digest(raw)
        // 12 bayt (96-bit): avval 6 bayt (48-bit) edi — kollizyon avvalgi karantin yozuvini
        // ezib/yashirib qo'yishi mumkin edi (restore token bo'yicha, overwrite bilan).
        return hash.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadIndex(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(jsonToEntry(o))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveIndex(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(entryToJson(e))
        prefs(context).edit { putString(KEY_ENTRIES, arr.toString()) }
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("token", e.token)
        put("originalPath", e.originalPath)
        put("originalName", e.originalName)
        put("quarantinedAt", e.quarantinedAt)
        put("verdict", e.verdict)
        put("reason", e.reason)
        put("sizeBytes", e.sizeBytes)
        put("enc", e.encVersion)
    }

    private fun jsonToEntry(o: JSONObject): Entry = Entry(
        token = o.optString("token"),
        originalPath = o.optString("originalPath"),
        originalName = o.optString("originalName"),
        quarantinedAt = o.optLong("quarantinedAt", 0L),
        verdict = o.optString("verdict"),
        reason = o.optString("reason"),
        sizeBytes = o.optLong("sizeBytes", 0L),
        // Eski yozuvlarda "enc" yo'q → 0 (ochiq) — restore avvalgidek ishlaydi.
        encVersion = o.optInt("enc", ENC_NONE)
    )
}

/**
 * Karantin shifrlash yadrosi — sof JVM (Android importsiz), unit-testlanadi.
 *
 * AES-CTR tanlovi sababi: oqimda ishlaydi, uzunlikni o'zgartirmaydi va padding
 * kerak emas. IV = token (12 bayt, har yozuv uchun unikal) + 4 nol bayt hisoblagich —
 * bir xil kalit bilan ikki yozuv hech qachon bir xil keystream olmaydi.
 */
internal object QuarCrypto {

    fun cipher(key: ByteArray, tokenHex: String, encrypt: Boolean): javax.crypto.Cipher {
        require(key.size == 32) { "kalit 32 bayt bo'lishi kerak" }
        val tokenBytes = hexToBytes(tokenHex)
        require(tokenBytes.size >= 12) { "token kamida 12 bayt bo'lishi kerak" }
        val iv = ByteArray(16)
        for (i in 0 until 12) iv[i] = tokenBytes[i]
        val c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        val mode = if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE
        c.init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return c
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex uzunligi juft bo'lishi kerak" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
