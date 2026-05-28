package com.kiberqalqon

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Tonki wrapper poverh Telegram Bot API.
 * Ne hranit token/chat_id — chitaet iz SharedPreferences pri kazhdom vyzove,
 * chtoby pereklyuchenie nastroek prim e nyalos' srazu.
 *
 * Ispol'zuetsya:
 *  - TelemetryReporter (poslednie ostavlyaem dlya prostogo plain-text reporta)
 *  - CommandRouter (otvety na inline-buttons)
 *  - ApkScanner (zagruzka samogo APK fayla)
 *  - TelegramCommandPoller (getUpdates)
 *
 * Vse zaprosy IO — vyzyvayte iz Worker'a ili Dispatchers.IO.
 */
object TelegramBot {

    private const val TAG = "TelegramBot"
    private const val PREFS = "kiberqalqon_telemetry"
    private const val KEY_TOKEN = "tg_bot_token"
    private const val KEY_CHAT_ID = "tg_chat_id"
    private const val KEY_ENABLED = "tg_enabled"
    private const val KEY_UPDATE_OFFSET = "tg_update_offset"
    private const val KEY_LISTEN = "tg_listen_commands"
    private const val KEY_SEND_APK = "tg_send_apk"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)  // sendDocument mojhet byt' bol'shoy
            .readTimeout(35, TimeUnit.SECONDS)   // getUpdates long-polling
            .build()
    }

    // ============================================================
    //   PUBLIC API
    // ============================================================

    fun isConfigured(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean(KEY_ENABLED, false) &&
                !p.getString(KEY_TOKEN, null).isNullOrBlank() &&
                !p.getString(KEY_CHAT_ID, null).isNullOrBlank()
    }

    fun isListenEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LISTEN, false) && isConfigured(ctx)

    fun isSendApkEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SEND_APK, false) && isConfigured(ctx)

    fun setListenEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LISTEN, on).apply()
    }

    fun setSendApkEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SEND_APK, on).apply()
    }

    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, null)?.trim().orEmpty()
    fun chatId(ctx: Context): String = prefs(ctx).getString(KEY_CHAT_ID, null)?.trim().orEmpty()

    // ============================================================
    //   sendMessage / editMessage / answerCallback
    // ============================================================

    /**
     * Markdown V1 reserved belgilarini escape qiladi. Foydalanuvchi nazoratidagi
     * matnni (apk nomi, paket, exception xabari) `*bold*` yoki `` `code` `` ichiga
     * qo'yishdan oldin shu funksiyani chaqiring — aks holda Telegram "can't parse
     * entities" qaytaradi va xabar yo'qoladi.
     */
    fun mdEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '_', '*', '`', '[' -> { sb.append('\\'); sb.append(c) }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Otpravlyaem tekst (s optional inline-klaviaturoj). Vozvrashaem message_id ili null. */
    fun sendMessage(
        ctx: Context,
        text: String,
        keyboard: InlineKeyboard? = null,
        chatIdOverride: String? = null
    ): Long? {
        val token = token(ctx); if (token.isEmpty()) return null
        val chat = chatIdOverride ?: chatId(ctx); if (chat.isEmpty()) return null

        val body = JSONObject().apply {
            put("chat_id", chat)
            put("text", text.take(4000))
            put("parse_mode", "Markdown")
            put("disable_web_page_preview", true)
            keyboard?.let { put("reply_markup", it.toJson()) }
        }
        val result = postJson(token, "sendMessage", body)
            ?: return retryPlainSend(token, chat, text, keyboard)
        return result.optJSONObject("result")?.optLong("message_id")
    }

    /**
     * Fallback: agar Markdown parse muvaffaqiyatsiz bo'lsa (foydalanuvchi nazoratidagi
     * matnda escape qilinmagan `*` yoki `` ` `` bo'lsa), xabarni plain text bilan
     * qayta yuboramiz — buyruq yoki ogohlantirish butunlay yo'qolib ketmasligi uchun.
     */
    private fun retryPlainSend(
        token: String,
        chat: String,
        text: String,
        keyboard: InlineKeyboard?
    ): Long? {
        val body = JSONObject().apply {
            put("chat_id", chat)
            put("text", stripMarkdown(text).take(4000))
            put("disable_web_page_preview", true)
            keyboard?.let { put("reply_markup", it.toJson()) }
        }
        return postJson(token, "sendMessage", body)?.optJSONObject("result")?.optLong("message_id")
    }

    private fun stripMarkdown(s: String): String =
        s.replace("`", "").replace("*", "").replace("_", "")

    fun editMessageText(
        ctx: Context,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboard? = null
    ): Boolean {
        val token = token(ctx); if (token.isEmpty()) return false
        val chat = chatId(ctx); if (chat.isEmpty()) return false

        val body = JSONObject().apply {
            put("chat_id", chat)
            put("message_id", messageId)
            put("text", text.take(4000))
            put("parse_mode", "Markdown")
            put("disable_web_page_preview", true)
            keyboard?.let { put("reply_markup", it.toJson()) }
        }
        if (postJson(token, "editMessageText", body) != null) return true
        // Markdown parse fallback — plain text bilan urinib ko'ramiz.
        val plainBody = JSONObject().apply {
            put("chat_id", chat)
            put("message_id", messageId)
            put("text", stripMarkdown(text).take(4000))
            put("disable_web_page_preview", true)
            keyboard?.let { put("reply_markup", it.toJson()) }
        }
        return postJson(token, "editMessageText", plainBody) != null
    }

    /** Bystryj otvet na tap po inline-knopke (chtoby ubrat' "часики" v UI). */
    fun answerCallbackQuery(ctx: Context, callbackId: String, text: String? = null) {
        val token = token(ctx); if (token.isEmpty()) return
        val body = JSONObject().apply {
            put("callback_query_id", callbackId)
            text?.let { put("text", it.take(200)) }
        }
        postJson(token, "answerCallbackQuery", body)
    }

    /** Otpravka fayla (APK, log, dump). Vozvraschaem true esli ok. */
    fun sendDocument(
        ctx: Context,
        file: File,
        caption: String? = null,
        chatIdOverride: String? = null
    ): Boolean {
        val token = token(ctx); if (token.isEmpty()) return false
        val chat = chatIdOverride ?: chatId(ctx); if (chat.isEmpty()) return false
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "sendDocument: file unreadable ${file.absolutePath}")
            return false
        }
        // Telegram limit dlya botov — 50 MB
        if (file.length() > 50L * 1024 * 1024) {
            Log.w(TAG, "sendDocument: file too big (${file.length()}b) — skip")
            return false
        }

        return try {
            val mediaType = "application/vnd.android.package-archive".toMediaTypeOrNull()
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chat)
                .apply { if (caption != null) addFormDataPart("caption", caption.take(1000)) }
                .addFormDataPart(
                    "document",
                    file.name,
                    file.asRequestBody(mediaType)
                )
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendDocument")
                .post(multipart)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendDocument failed ${resp.code}: ${resp.body?.string()?.take(300)}")
                    false
                } else true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendDocument exception", e)
            false
        }
    }

    // ============================================================
    //   getUpdates (long polling)
    // ============================================================

    /**
     * Tyanem updates s ofletom. Pust' offset hranitsya v prefs, chtoby ne propustit'
     * sobytiya posle perezagruzki processa.
     *
     * Whitelist: pri parse my CHECKAEM chto chat.id sovpadayet s nashim — drugie chaty igror.
     */
    fun getUpdates(ctx: Context, longPollSec: Int = 25): List<TelegramUpdate> {
        val token = token(ctx); if (token.isEmpty()) return emptyList()
        val ownChat = chatId(ctx); if (ownChat.isEmpty()) return emptyList()
        val offset = prefs(ctx).getLong(KEY_UPDATE_OFFSET, 0L)

        val url = "https://api.telegram.org/bot$token/getUpdates" +
                "?timeout=$longPollSec" +
                "&allowed_updates=" + URLEncoder.encode("[\"message\",\"callback_query\"]", "UTF-8") +
                (if (offset > 0) "&offset=$offset" else "")

        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getUpdates ${resp.code}")
                    return emptyList()
                }
                val raw = resp.body?.string() ?: return emptyList()
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) return emptyList()
                val arr = json.optJSONArray("result") ?: return emptyList()

                val out = mutableListOf<TelegramUpdate>()
                var maxId = offset
                for (i in 0 until arr.length()) {
                    val u = arr.optJSONObject(i) ?: continue
                    val updateId = u.optLong("update_id", 0L)
                    if (updateId > 0 && updateId >= maxId) maxId = updateId + 1
                    val parsed = TelegramUpdate.parse(u, ownChat) ?: continue
                    out.add(parsed)
                }
                // Soxranyaem offset chtoby v sleduyushij raz ne zabrat' eti zhe updates.
                if (maxId != offset) {
                    prefs(ctx).edit().putLong(KEY_UPDATE_OFFSET, maxId).apply()
                }
                out
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getUpdates exception", e)
            emptyList()
        }
    }

    // ============================================================
    //   internal
    // ============================================================

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun postJson(token: String, method: String, body: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/$method")
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$method failed ${resp.code}: ${raw?.take(300)}")
                    return null
                }
                if (raw == null) null else JSONObject(raw)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$method exception", e)
            null
        }
    }
}

// ============================================================
//   MODELS — inline klaviatura
// ============================================================

/** Knopka inline-klaviatury. callback_data peredayotsya nazad pri tape. */
data class InlineButton(val text: String, val callbackData: String)

/** Inline klaviatura: matrica knopok (kazhdyy spisok — odna stroka). */
data class InlineKeyboard(val rows: List<List<InlineButton>>) {
    fun toJson(): JSONObject {
        val outer = JSONArray()
        for (row in rows) {
            val inner = JSONArray()
            for (btn in row) {
                inner.put(JSONObject().apply {
                    put("text", btn.text)
                    put("callback_data", btn.callbackData)
                })
            }
            outer.put(inner)
        }
        return JSONObject().apply { put("inline_keyboard", outer) }
    }
}

// ============================================================
//   MODELS — vhodyaschie update
// ============================================================

sealed class TelegramUpdate {
    /** Polzovatel' napisal tekst v gruppu — naprimer /start ili "skanir". */
    data class TextMessage(
        val updateId: Long,
        val chatId: String,
        val messageId: Long,
        val text: String,
        val fromUsername: String?
    ) : TelegramUpdate()

    /** Polzovatel' nazhal inline-knopku. */
    data class Callback(
        val updateId: Long,
        val chatId: String,
        val messageId: Long?,
        val callbackId: String,
        val data: String,
        val fromUsername: String?
    ) : TelegramUpdate()

    companion object {
        /**
         * Parse + whitelist: vozvraschaem update tol'ko esli chat.id == ownChat.
         * Vse drugie chaty (vklyuchaya privatnye sluchajno-pisavshie userov) igror.
         */
        fun parse(obj: JSONObject, ownChat: String): TelegramUpdate? {
            val updateId = obj.optLong("update_id", 0L)
            val msg = obj.optJSONObject("message")
            if (msg != null) {
                val chatId = msg.optJSONObject("chat")?.optLong("id")?.toString() ?: return null
                if (chatId != ownChat) return null
                val text = msg.optString("text", "").trim()
                if (text.isEmpty()) return null
                return TextMessage(
                    updateId = updateId,
                    chatId = chatId,
                    messageId = msg.optLong("message_id", 0L),
                    text = text,
                    fromUsername = msg.optJSONObject("from")?.optString("username", "")?.ifBlank { null }
                )
            }
            val cb = obj.optJSONObject("callback_query")
            if (cb != null) {
                val cbMsg = cb.optJSONObject("message")
                val chatId = cbMsg?.optJSONObject("chat")?.optLong("id")?.toString() ?: return null
                if (chatId != ownChat) return null
                return Callback(
                    updateId = updateId,
                    chatId = chatId,
                    messageId = cbMsg.optLong("message_id", 0L).takeIf { it > 0 },
                    callbackId = cb.optString("id"),
                    data = cb.optString("data", ""),
                    fromUsername = cb.optJSONObject("from")?.optString("username", "")?.ifBlank { null }
                )
            }
            return null
        }
    }
}
