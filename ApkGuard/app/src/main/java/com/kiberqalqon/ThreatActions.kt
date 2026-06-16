package com.uzguard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Registry token → APK action для inline-кнопок Telegram'а.
 *
 * Когда CommunityReportClient шлёт threat report, к нему цепляются кнопки
 * (🗑 Delete / 🔄 Rescan / ℹ️ Info) с `callback_data` вида `del:<token>`.
 * Telegram callback_data имеет лимит 64 байта — полный путь APK туда не влезает,
 * SHA-256 (64 hex) занимает ровно 64 байта, тоже не оставляет места для префикса.
 * Поэтому берём первые 12 hex от sha256(path) — это 12 байт, коллизия фактически
 * исключена при наших объёмах.
 *
 * Записи персистятся в SharedPreferences с TTL 7 дней — иначе кнопки в старых
 * сообщениях TG перестают работать после перезапуска процесса.
 */
object ThreatActions {

    private const val PREFS = "uzguard_threat_actions"
    private const val KEY_ENTRIES = "entries_v1"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 дней

    data class Entry(
        val token: String,
        val apkPath: String,
        val apkName: String,
        val verdict: String,
        val createdAt: Long
    )

    /** Регистрируем action для apk-пути, возвращаем сгенерированный token. */
    fun register(ctx: Context, apkPath: String, apkName: String, verdict: String): String {
        val token = tokenOf(apkPath)
        val entry = Entry(token, apkPath, apkName, verdict, System.currentTimeMillis())
        val all = loadAll(ctx).toMutableList()
        all.removeAll { it.token == token }  // replace if exists
        all.add(entry)
        // Prune expired entries в том же save'е.
        val cutoff = System.currentTimeMillis() - TTL_MS
        val fresh = all.filter { it.createdAt >= cutoff }
        saveAll(ctx, fresh)
        return token
    }

    fun lookup(ctx: Context, token: String): Entry? {
        val cutoff = System.currentTimeMillis() - TTL_MS
        return loadAll(ctx).firstOrNull { it.token == token && it.createdAt >= cutoff }
    }

    /** После успешного действия можно убрать запись. */
    fun remove(ctx: Context, token: String) {
        val updated = loadAll(ctx).filter { it.token != token }
        saveAll(ctx, updated)
    }

    private fun tokenOf(apkPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(apkPath.toByteArray(Charsets.UTF_8))
        return hash.take(6).joinToString("") { "%02x".format(it) }  // 12 hex chars
    }

    private fun loadAll(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Entry(
                            token = o.optString("token"),
                            apkPath = o.optString("apkPath"),
                            apkName = o.optString("apkName"),
                            verdict = o.optString("verdict"),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("token", e.token)
                    put("apkPath", e.apkPath)
                    put("apkName", e.apkName)
                    put("verdict", e.verdict)
                    put("createdAt", e.createdAt)
                }
            )
        }
        prefs(ctx).edit { putString(KEY_ENTRIES, arr.toString()) }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
