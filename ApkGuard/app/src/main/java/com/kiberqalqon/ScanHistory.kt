package com.uzguard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Лёгкий лог сканирований — без БД. Хранится в SharedPreferences как JSON.
 *
 * Ограничен 200 записями (LRU-эвикция): больше не нужно, размер prefs контролируем.
 * Если в будущем добавим экспорт/синхронизацию — перенесём на Room.
 */
object ScanHistory {

    private const val PREFS = "uzguard_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200

    data class Entry(
        val timestamp: Long,
        val apkName: String,
        val apkPath: String,
        val verdict: ScanResult.Verdict,
        val reason: String,
        /** Текстовые ключи, доступные для локализации в UI (например, "perm_internet"). */
        val explanationKeys: List<String>,
        /** Источник: telegram/whatsapp/download/share/scan. */
        val source: String?
    )

    fun add(context: Context, entry: Entry) {
        // Атомарный read-modify-write: параллельные сканы (batch/GuardWorker + ручной)
        // иначе перетирают историю друг друга и теряют записи (в т.ч. реальный DANGER).
        synchronized(ScanHistory) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = readArray(prefs.getString(KEY, null))
            val obj = JSONObject().apply {
                put("ts", entry.timestamp)
                put("name", entry.apkName)
                put("path", entry.apkPath)
                put("verdict", entry.verdict.name)
                put("reason", entry.reason)
                put("explanation", JSONArray(entry.explanationKeys))
                put("source", entry.source ?: "")
            }
            // Новые записи в начало; LRU-эвикция в хвосте.
            val newArr = JSONArray()
            newArr.put(obj)
            for (i in 0 until arr.length()) {
                if (newArr.length() >= MAX_ENTRIES) break
                newArr.put(arr.getJSONObject(i))
            }
            // commit() под локом — окно записи детерминировано.
            prefs.edit(commit = true) { putString(KEY, newArr.toString()) }
        }
    }

    fun all(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = readArray(prefs.getString(KEY, null))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val explanation = mutableListOf<String>()
                o.optJSONArray("explanation")?.let { ja ->
                    for (j in 0 until ja.length()) explanation.add(ja.optString(j))
                }
                val verdict = try {
                    ScanResult.Verdict.valueOf(o.optString("verdict", "SAFE"))
                } catch (_: Exception) {
                    ScanResult.Verdict.SAFE
                }
                add(
                    Entry(
                        timestamp = o.optLong("ts", 0),
                        apkName = o.optString("name", "?"),
                        apkPath = o.optString("path", ""),
                        verdict = verdict,
                        reason = o.optString("reason", ""),
                        explanationKeys = explanation,
                        source = o.optString("source", "").ifBlank { null }
                    )
                )
            }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }

    private fun readArray(json: String?): JSONArray {
        if (json.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(json)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /**
     * Превращает scan result в человекочитаемые объяснения "почему именно".
     * Сохраняем КЛЮЧИ, не текст — чтобы в UI подставлялся правильный язык (uz/ru).
     */
    fun explanationKeysFor(result: ScanResult): List<String> {
        val keys = mutableListOf<String>()
        for (perm in result.dangerousPermissions) {
            keys.add("perm:" + perm.substringAfterLast("."))
        }
        for (sig in result.malwareSignatures) {
            keys.add("sig:$sig")
        }
        return keys
    }
}
