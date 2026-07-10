package com.uzguard

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * ====== UYG'OTUVCHI SIGNAL (ALARM SIREN) ======
 *
 * Xavfli (DANGER) tahdid tunda (telefon uxlab yotgan — ekran o'chiq yoki qulf) topilsa,
 * ALARM ovoz oqimida (STREAM_ALARM) MAKSIMAL balandlikda sirena chaladi + kuchli tebranish.
 * ALARM oqimi telefon "jim/tebranish" rejimida ham chalinadi — shuning uchun foydalanuvchi
 * uxlab yotsa ham uyg'onadi. Foydalanuvchi telefonni OCHIQ ishlatayotgan bo'lsa — sirena
 * CHALINMAYDI (oddiy tovushli bildirishnoma yetarli, bezovta qilmaymiz).
 *
 * To'xtatish: foydalanuvchi AutoScanActivity / ScanResultActivity / Karantin ekranini ochsa
 * (ya'ni uyg'ondi) [stop] chaqiriladi; aks holda [durationMs] (60s) dan keyin o'zi to'xtaydi.
 * Jarayon nobud bo'lsa saqlangan ALARM balandligini [recover] (App.onCreate) tiklaydi.
 */
object AlarmSiren {

    private const val PREFS = "uzguard_siren"
    private const val KEY_SAVED_VOL = "saved_alarm_vol"

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var stopRunnable: Runnable? = null
    private var appCtx: Context? = null

    /**
     * Sirenani chaladi — FAQAT sozlama yoqilgan bo'lsa VA telefon uxlab yotgan bo'lsa
     * (ekran o'chiq yoki qulflangan). Bodrom (ekran ochiq+qulfsiz) bo'lsa — hech narsa qilmaydi.
     */
    @Synchronized
    fun blast(context: Context, durationMs: Long = 60_000L) {
        val ctx = context.applicationContext
        if (!Config.isLoudAlarmEnabled(ctx)) return

        // Uxlayaptimi? — ekran interaktiv EMAS yoki keyguard qulflangan.
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = try { pm?.isInteractive ?: true } catch (_: Throwable) { true }
        val locked = try { km?.isKeyguardLocked ?: false } catch (_: Throwable) { false }
        val sleeping = !interactive || locked
        if (!sleeping) return

        stop()  // avvalgi sirena qolmasin
        appCtx = ctx

        try {
            // ALARM oqimi balandligini vaqtincha MAKSIMALga chiqaramiz (avvalgisini saqlab).
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // Faqat saqlangan qiymat YO'Q bo'lsa yozamiz — takror blast asl (past) qiymatni
                // maksimal bilan almashtirib yubormasin.
                if (!prefs.contains(KEY_SAVED_VOL)) {
                    prefs.edit().putInt(KEY_SAVED_VOL,
                        am.getStreamVolume(AudioManager.STREAM_ALARM)).apply()
                }
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
            }

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                player = MediaPlayer().apply {
                    setDataSource(ctx, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    setVolume(1f, 1f)
                    setOnErrorListener { _, _, _ -> stop(); true }
                    prepare()
                    start()
                }
            }

            vibrate(ctx)

            // Belgilangan vaqtdan keyin o'zi to'xtaydi (foydalanuvchi uyg'onmasa ham).
            stopRunnable = Runnable { stop() }.also { handler.postDelayed(it, durationMs) }
        } catch (_: Throwable) {
            stop()
        }
    }

    /** Sirenani to'xtatadi va ALARM balandligini tiklaydi. Har joydan xavfsiz chaqirsa bo'ladi. */
    @Synchronized
    fun stop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
        appCtx?.let { restoreVolume(it) }
    }

    /**
     * Jarayon sirena chalinayotganda nobud bo'lsa (o'zgartirilgan ALARM balandligi qolib ketadi) —
     * App.onCreate'dan chaqiriladi: saqlangan qiymat bo'lsa tiklaydi.
     */
    fun recover(context: Context) {
        restoreVolume(context.applicationContext)
    }

    private fun restoreVolume(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SAVED_VOL)) return
            val saved = prefs.getInt(KEY_SAVED_VOL, -1)
            prefs.edit().remove(KEY_SAVED_VOL).apply()
            if (saved < 0) return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        } catch (_: Throwable) {}
    }

    private fun vibrate(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            vibrator = vib
            // Kuchli takrorlanuvchi naqsh (0 = boshidan takrorla).
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (_: Throwable) {}
    }
}
