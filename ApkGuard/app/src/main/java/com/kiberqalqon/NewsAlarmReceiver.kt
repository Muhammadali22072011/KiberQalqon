package com.uzguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager (setAndAllowWhileIdle) uyg'otganda — ekran O'CHIQ / Doze paytida ham —
 * panel e'lonlarini tekshiradi va yangisi bo'lsa bildirishnoma (tovush bilan) chiqaradi.
 *
 * NEGA kerak: foreground [ProtectionService] PROTSESS'ni tirik tutadi, lekin CPU'ni emas.
 * Ekran o'chib qurilma uxlaganda servisning poll-loop'i muzlaydi (delay() to'xtaydi,
 * tarmoq kesiladi) — natijada yangilik "ekran o'chiq bo'lsa kelmaydi". Bu receiver esa
 * AlarmManager orqali qurilmani qisqa vaqtga uyg'otadi (Doze'da ham), tarmoq oynasida
 * [NewsNotifier.checkAndNotify] ni bajaradi va keyingi uyg'otishni qayta rejalashtiradi
 * (o'z-o'zini tiklaydigan zanjir). FCM/Google YO'Q — oflayn/maxfiylik pozitsiyasi saqlanadi.
 *
 * Alarm tizimda saqlanadi: protsess o'ldirilgan bo'lsa ham fire bo'lganda OS uni qayta
 * ko'taradi (manifest-receiver). Reboot alarmni tozalaydi → BootReceiver qayta rejalashtiradi.
 */
class NewsAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        // goAsync: broadcast tugagach ham protsessni qisqa vaqt tirik tutadi (tarmoq + rasm
        // yuklash uchun). Ish boshqa thread'da — onReceive main thread'ni bloklamasligi shart.
        val pending = goAsync()
        Thread {
            try {
                NewsNotifier.checkAndNotify(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "news check failed", t)
            } finally {
                // Zanjirni davom ettiramiz (o'chirilgan bo'lsa scheduleNext o'zi bekor qiladi).
                try { NewsNotifier.scheduleNext(ctx) } catch (_: Throwable) {}
                try { pending.finish() } catch (_: Throwable) {}
            }
        }.start()
    }

    companion object {
        private const val TAG = "NewsAlarmReceiver"
    }
}
