package com.uzguard

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Skan natijasini ovoz bilan e'lon qiladi (TextToSpeech).
 *
 * - Uzbek lokali mavjud bo'lsa — uni ishlatadi.
 * - Aks holda RU yoki sukut bo'yicha tilga tushadi (transliteratsiya o'zbekcha,
 *   rus tovushida o'qiladi — ko'p Android qurilmalarida shu kombinatsiya
 *   ishlaydi).
 * - Config.isSoundEnabled = false bo'lsa, hech narsa qilmaydi.
 * - Har bir Activity TTS engine'ini onCreate'da `init`, onDestroy'da `shutdown`
 *   qilishi kerak — chunki TTS resource process bo'ylab faqat bittagina
 *   tutiladi.
 */
object VoiceVerdict {

    private const val TAG = "VoiceVerdict"
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    /** Init tugagunicha kelgan so'rovni keyin o'qish uchun saqlaymiz. */
    @Volatile private var pendingSpeech: String? = null

    /** Yangi Activity ochilganda chaqiring. Idempotent — qayta chaqirish bezarar. */
    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status")
                return@TextToSpeech
            }
            // Tilni eng yaxshi mosini tanlaymiz.
            val engine = tts ?: return@TextToSpeech
            val locale = pickBestLocale(engine)
            if (locale != null) {
                engine.language = locale
            }
            engine.setSpeechRate(0.95f)
            engine.setPitch(1.0f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS error: $utteranceId")
                }
            })
            ready = true
            pendingSpeech?.let {
                pendingSpeech = null
                actuallySpeak(it)
            }
        }
    }

    /** Activity yo'q bo'lganda chaqiring — TTS resursini bo'shatadi. */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown failed", t)
        }
        tts = null
        ready = false
        pendingSpeech = null
    }

    /** Verdict bo'yicha mos jumlani aytadi. Context faqat sukut sozlamasini tekshirish uchun. */
    fun speak(context: Context, verdict: ScanResult.Verdict) {
        if (!Config.isSoundEnabled(context)) return
        val phrase = phraseFor(verdict)
        if (!ready) {
            pendingSpeech = phrase
            init(context)
            return
        }
        actuallySpeak(phrase)
    }

    /** Erkin matn aytish (masalan: "5 ta xavfli APK topildi"). */
    fun speak(context: Context, text: String) {
        if (!Config.isSoundEnabled(context)) return
        if (!ready) {
            pendingSpeech = text
            init(context)
            return
        }
        actuallySpeak(text)
    }

    private fun actuallySpeak(text: String) {
        val engine = tts ?: return
        val id = "kq_" + System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    private fun phraseFor(verdict: ScanResult.Verdict): String = when (verdict) {
        ScanResult.Verdict.SAFE ->
            "Tekshiruv yakunlandi. Telefoningiz xavfsiz."
        ScanResult.Verdict.SUSPICIOUS ->
            "Diqqat! Shubhali fayl topildi. Batafsil ko'ring."
        ScanResult.Verdict.DANGER ->
            "Ogohlantirish! Xavfli fayl aniqlandi. Darhol o'chiring."
    }

    /** Eng mos lokal: uz_UZ > uz > ru_RU > default. */
    private fun pickBestLocale(engine: TextToSpeech): Locale? {
        val candidates = listOf(
            Locale("uz", "UZ"),
            Locale("uz"),
            Locale("ru", "RU"),
            Locale.getDefault(),
        )
        for (loc in candidates) {
            try {
                val res = engine.isLanguageAvailable(loc)
                if (res == TextToSpeech.LANG_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    return loc
                }
            } catch (_: Throwable) {
                // continue
            }
        }
        return null
    }
}
