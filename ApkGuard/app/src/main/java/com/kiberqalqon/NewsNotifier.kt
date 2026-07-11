package com.uzguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.AlarmManagerCompat

/**
 * Panel ("Yangiliklar") yangi e'lon joylaganda — qurilmaga BILDIRISHNOMA (rasm bilan).
 *
 * Firebase/FCM ATAYLAB YO'Q: UzGuard butunlay Google-xizmatlarisiz (oflayn + maxfiylik).
 * Buning o'rniga telefon allaqachon /api/news'ni davriy o'qiydi ([NewsStore]); bu klass
 * shu o'qishga "yangi keldi → push" qo'shadi. Server tomonida HECH NARSA o'zgarmaydi.
 *
 * Chaqiriladi: [GuardWorker] davriy (≈15 daq) fon-skanidan va [App.onCreate] (ilova ochilganda) —
 * ikkalasi ham IO thread'da. Ko'p joydan chaqirilsa ham xavfsiz: ichki 30 daqiqalik
 * urinish-throttle bor.
 *
 * Spamga qarshi himoya:
 *   • BIRINCHI marta — butun mavjud backlog "ko'rilgan" deb belgilanadi, push YO'Q
 *     (o'rnatish bilan 5 ta eski e'lon birdan kelib spam qilmasin).
 *   • keyin faqat YANGI (ko'rilmagan) id'lar uchun push.
 *   • bitta yugurishda maksimum [MAX_NOTIFY_PER_RUN] ta (backlog to'planib qolsa ham).
 *   • [MAX_AGE_MS]'dan eski e'lonlar push qilinmaydi (faqat ko'rilgan deb belgilanadi).
 *
 * Tarmoq/rasm bloklaydi — IO thread'dan chaqirilsin. Hech qachon throw qilmaydi.
 */
object NewsNotifier {

    private const val TAG = "NewsNotifier"
    private const val PREFS = "uzguard_news_notify"
    private const val KEY_SEEN_IDS = "seen_ids"        // push qilingan/ko'rilgan e'lon id'lari
    private const val KEY_SEEDED = "seeded_v1"         // birinchi marta backlog seed qilindimi
    private const val KEY_LAST_CHECK = "last_check_ts" // urinish-throttle

    // 3 daq: ProtectionService loop'idan chaqirilganda yangilik ~3 daqiqada keladi
    // (FCMsiz "deyarli real-vaqt"). NewsStore tarmoq-throttle'i (2 daq) bundan kichik —
    // shu sabab bu intervalda refresh haqiqatan yangi e'lonni tarmoqdan oladi.
    private const val CHECK_INTERVAL_MS = 3L * 60 * 1000
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000    // 7 kundan eski e'lonni push qilmaymiz
    private const val MAX_NOTIFY_PER_RUN = 3                   // bir yugurishda ko'pi bilan 3 ta
    private const val MAX_SEEN = 500                           // saqlanadigan id'lar chegarasi

    /** IO thread'dan chaqirilsin (NewsStore.refresh + NewsImages.load bloklaydi). */
    fun checkAndNotify(ctx: Context) {
        try {
            if (!Config.isNewsNotificationEnabled(ctx)) return

            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = sp.getLong(KEY_LAST_CHECK, 0L)
            // Soat orqaga surilsa (now < last) throttle abadiy qolmasin.
            if (now in last..(last + CHECK_INTERVAL_MS)) return
            sp.edit().putLong(KEY_LAST_CHECK, now).apply()

            // Piggyback: shu mavjud tekshiruv-tsikliga (ekran ochiq ~3 daq, Doze ~40 daq) egasidan
            // kelgan masofaviy buyruq/bayroq (message/flag/rescan) pollini ilamiz. YANGI taymer
            // QO'SHILMAYDI (loyiha isish tarixiga sezgir) — mavjud uyg'onishni ulashamiz, shu sabab
            // admin xabari App.onCreate + 6 soatlik Heartbeat o'rniga deyarli real-vaqtda keladi.
            try { CloudTelemetry.pollCommands(ctx) } catch (_: Throwable) {}

            // Yangi ro'yxat: avval tarmoqdan (NewsStore keshini ham yangilaydi), bo'lmasa
            // (throttle/oflayn) keshdan. NewsNotifier 30 daq > NewsStore 10 daq throttle,
            // shu sabab odatda refresh haqiqatan tarmoqdan oladi.
            val items = NewsStore.refresh(ctx) ?: NewsStore.loadCached(ctx)
            if (items.isEmpty()) return

            val ids = items.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
            if (ids.isEmpty()) return

            val seen = HashSet<String>().apply {
                addAll(sp.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet())
            }

            // BIRINCHI marta: hammasi "ko'rilgan", push YO'Q (eski backlog spam qilmasin).
            if (!sp.getBoolean(KEY_SEEDED, false)) {
                sp.edit()
                    .putStringSet(KEY_SEEN_IDS, ids.toHashSet())
                    .putBoolean(KEY_SEEDED, true)
                    .apply()
                Log.i(TAG, "seed: ${ids.size} mavjud e'lon belgilandi (push yo'q)")
                return
            }

            // Yangi (ko'rilmagan) + juda eski bo'lmagan e'lonlar.
            val fresh = items.filter {
                it.id.isNotBlank() &&
                    !seen.contains(it.id) &&
                    (it.createdAt == 0L || now - it.createdAt <= MAX_AGE_MS)
            }

            if (fresh.isNotEmpty()) {
                // Eng yangi avval; backlog to'planib qolsa anti-spam cheklov.
                val toNotify = fresh.sortedByDescending { it.createdAt }.take(MAX_NOTIFY_PER_RUN)
                for (item in toNotify) {
                    val bmp = item.imageUrl?.let { runCatching { NewsImages.load(it) }.getOrNull() }
                    try {
                        NotificationHelper.showNewsNotification(ctx, item, bmp)
                    } catch (e: Throwable) {
                        Log.w(TAG, "news notification failed: ${item.id}", e)
                    }
                }
                Log.i(TAG, "push: ${toNotify.size} yangi e'lon (jami yangi: ${fresh.size})")
            }

            // Hozirgi feed'dagi BARCHA id'larni ko'rilgan deb belgilaymiz (push qilingan
            // bo'lsin, anti-spam tufayli o'tkazib yuborilgan bo'lsin — qayta push bo'lmasin).
            persistSeen(sp, seen, ids)
        } catch (e: Throwable) {
            Log.w(TAG, "checkAndNotify failed", e)
        }
    }

    /**
     * Ko'rilgan id'larni saqlaydi. Hozirgi feed id'lari BIRINCHI qo'yiladi (LinkedHashSet
     * tartibi), shu sabab [MAX_SEEN] da kesilsa ham feed'da hali turgan id hech qachon
     * tushib qolmaydi (faqat feed'dan chiqib ketgan eski id'lar kesiladi — ular baribir
     * qaytmaydi). Demak qayta-push imkonsiz.
     */
    private fun persistSeen(
        sp: android.content.SharedPreferences,
        oldSeen: Set<String>,
        currentIds: List<String>
    ) {
        val merged = LinkedHashSet<String>()
        merged.addAll(currentIds)
        merged.addAll(oldSeen)
        val capped = if (merged.size > MAX_SEEN) merged.take(MAX_SEEN).toHashSet() else merged
        sp.edit().putStringSet(KEY_SEEN_IDS, capped).apply()
    }

    // ── Ekran O'CHIQ / Doze paytida yetkazish (AlarmManager) ──────────────────────
    //
    // MUAMMO: foreground ProtectionService PROTSESS'ni tirik tutadi, lekin CPU'ni emas.
    // Ekran o'chib qurilma uxlaganda loop'dagi delay() muzlaydi va tarmoq kesiladi —
    // shu sabab yangilik "ekran o'chiq bo'lsa kelmaydi". WorkManager ham Doze'da
    // kechiktiriladi. YagonaFCMsiz ishonchli yo'l — AlarmManager.setAndAllowWhileIdle:
    // u Doze'da ham ishlaydi (OS ~9 daqiqada birga cheklaydi) va qisqa tarmoq+wakelock
    // oynasi beradi. Inexact — SCHEDULE_EXACT_ALARM ruxsati SHART EMAS.
    // ~40 daq: ekran O'CHIQ / Doze paytida YANGILIKNI yetkazishning yagona yo'li. Avval 12 daq
    // edi — telefon stolda yotganda soatiga ~5 marta Doze'dan uyg'onib (RTC_WAKEUP + tarmoq)
    // qizirdi va batareya yerardi. 40 daq'ga uzaytirdik: idle uyg'onish ~3 barobar kamayadi
    // (kamroq qizish/batareya), e'lon esa ekran o'chiq bo'lganda eng kechi ~40 daqiqada keladi.
    // Ekran OCHIQ bo'lsa ProtectionService loop'i baribir ~3 daqiqada yetkazadi (deyarli real-vaqt).
    private const val ALARM_INTERVAL_MS = 40L * 60 * 1000   // ~40 daq (idle/Doze yetkazish)
    private const val ALARM_REQUEST = 7311
    private const val ALARM_ACTION = "com.uzguard.NEWS_CHECK"

    /**
     * Keyingi uyg'otishni rejalashtiradi. [NewsAlarmReceiver] har fire'da buni qayta
     * chaqiradi (o'z-o'zini tiklaydigan zanjir; protsess o'lsa ham alarm tizimda qoladi
     * va fire bo'lganda protsessni qayta ko'taradi). O'chirilgan bo'lsa — bekor qiladi.
     * App.onCreate, BootReceiver va Sozlamalar toggle'idan chaqiriladi.
     */
    fun scheduleNext(ctx: Context) {
        try {
            if (!Config.isNewsNotificationEnabled(ctx)) { cancel(ctx); return }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
            AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, triggerAt, alarmPi(ctx))
        } catch (e: Throwable) {
            Log.w(TAG, "scheduleNext failed", e)
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPi(ctx))
        } catch (_: Throwable) {}
    }

    private fun alarmPi(ctx: Context): PendingIntent {
        val intent = Intent(ctx.applicationContext, NewsAlarmReceiver::class.java).setAction(ALARM_ACTION)
        return PendingIntent.getBroadcast(
            ctx.applicationContext, ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
