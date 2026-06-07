package com.kiberqalqon

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Карантин: вместо прямого `file.delete()` мы переносим подозрительный APK в
 * внутреннюю папку KiberQalqon, переименовываем (расширение .apk → .quar),
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
 *  • Auto-purge: при каждом quarantine() удаляем записи старше 7 дней.
 *
 * Что делать с системно-установленным APK: карантин неприменим (apk в /data/app/
 * мы не можем переместить). Для таких случаев Quarantine.fromPackage() копирует
 * sourceDir в карантин ПЕРЕД тем, как мы предложим юзеру удалить пакет.
 */
object Quarantine {

    private const val TAG = "Quarantine"
    private const val PREFS = "kiberqalqon_quarantine"
    private const val KEY_ENTRIES = "entries_v1"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(
        val token: String,
        val originalPath: String,
        val originalName: String,
        val quarantinedAt: Long,
        val verdict: String,
        val reason: String,
        val sizeBytes: Long
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

        // Самозащита: KiberQalqon в карантин не идёт.
        if (SelfGuard.isOwnApk(context, originalFile.absolutePath)) {
            return Result.Failed("KiberQalqon o'zini karantinga qo'ya olmaydi")
        }

        purgeExpired(context)

        val token = generateToken(originalFile.absolutePath)
        val quarDir = quarantineDir(context)
        val payloadFile = File(quarDir, "$token.quar")
        val metaFile = File(quarDir, "$token.json")

        try {
            originalFile.copyTo(payloadFile, overwrite = true)
        } catch (e: Throwable) {
            Log.w(TAG, "copy to quarantine failed", e)
            return Result.Failed("Karantinga ko'chirib bo'lmadi: ${e.message}")
        }

        val entry = Entry(
            token = token,
            originalPath = originalFile.absolutePath,
            originalName = originalFile.name,
            quarantinedAt = System.currentTimeMillis(),
            verdict = verdict,
            reason = reason.take(500),
            sizeBytes = payloadFile.length()
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
            payloadFile.copyTo(target, overwrite = true)
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
    }

    private fun jsonToEntry(o: JSONObject): Entry = Entry(
        token = o.optString("token"),
        originalPath = o.optString("originalPath"),
        originalName = o.optString("originalName"),
        quarantinedAt = o.optLong("quarantinedAt", 0L),
        verdict = o.optString("verdict"),
        reason = o.optString("reason"),
        sizeBytes = o.optLong("sizeBytes", 0L)
    )
}
