package com.kiberqalqon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Doimiy himoya foreground service'i.
 *
 * Status bar'da har doim ko'rinib turuvchi bildirishnoma:
 * "🛡️ KIBER QALQON faol · Telefoningiz himoyalangan"
 *
 * Bu — foydalanuvchiga eng kuchli signal: "himoya ishlayapti". Hech qanday
 * sozlama o'zgartirmasdan, ilovani o'rnatib ochish bilan darhol shu yozuv
 * ekranda paydo bo'ladi.
 *
 * Qo'shimcha imtiyozlar:
 *  - foreground service Android'da "important" prioritet oladi — OS uni
 *    oddiy fon ilovalarga qaraganda kamroq o'ldiradi
 *  - Xiaomi/Huawei kabi agressiv qurilmalarda WorkManager'ni "uxlatib" qo'ysa
 *    ham, foreground notification turgan ekan, jarayon ham tirik qoladi
 *  - foydalanuvchi bildirishnomani bossa Dashboard ochiladi
 *
 * Boshlanish: `ProtectionService.start(context)` — App.onCreate'da chaqiriladi.
 * To'xtatish: foydalanuvchi sozlamada o'chirsa, `stop(context)`.
 */
class ProtectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: foreground service type kerak. KiberQalqon antivirus
                // bo'lgani uchun SPECIAL_USE eng yaqin kategoriya (DATA_SYNC ham bo'ladi
                // lekin biz hech narsa sync qilmaymiz — special_use to'g'riroq).
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Foreground start ba'zi qurilmalarda rad etiladi (masalan Android 12+
            // background dan startForegroundService chaqirilgan bo'lsa). Bu holatda
            // crash qilmasdan oddiy service sifatida davom etamiz — notification
            // bo'lmaydi, lekin process tirik qoladi.
            android.util.Log.w(TAG, "startForeground failed", t)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service o'lgan bo'lsa, OS qayta tiklaydi (START_STICKY tufayli).
    }

    companion object {
        private const val TAG = "ProtectionService"
        private const val CHANNEL_ID = "kq_protection_status"
        private const val CHANNEL_NAME = "Himoya holati"
        const val NOTIFICATION_ID = 1010

        /** Service'ni ishga tushiradi. Idempotent — qayta chaqirish bezarar. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, ProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "start failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ProtectionService::class.java))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "stop failed", t)
            }
        }

        /** Status matnini yangilash (scan tugagach yoki sozlama o'zgargach). */
        fun refresh(context: Context) {
            try {
                ensureChannel(context)
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(NOTIFICATION_ID, buildNotification(context))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "refresh failed", t)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // IMPORTANCE_LOW — tovush yo'q, faqat status bar'da kichik ikona.
            // Foydalanuvchi "qoldiradigan" doimiy yozuv, bezovta qilmaslik kerak.
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "KiberQalqon doimiy himoya holati"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): android.app.Notification {
            // Bildirishnoma ustiga bosish — Dashboard ochiladi (Splash orqali,
            // u consent/initial-scan flag'larini o'zi tekshiradi).
            val openIntent = Intent(context, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                pendingIntentFlags(),
            )

            val statusText = computeStatusText(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("KIBER QALQON faol")
                .setContentText(statusText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)

            return builder.build()
        }

        private fun computeStatusText(context: Context): String {
            // ScanHistory'ning so'nggi 24 soatidagi eng yomon verdict'ga qarab matn.
            val now = System.currentTimeMillis()
            val dayAgo = now - 24L * 60 * 60 * 1000
            val recent = try {
                ScanHistory.all(context).take(30).filter { it.timestamp >= dayAgo }
            } catch (_: Throwable) { emptyList() }

            val danger = recent.count { it.verdict == ScanResult.Verdict.DANGER }
            val suspicious = recent.count { it.verdict == ScanResult.Verdict.SUSPICIOUS }

            return when {
                danger > 0 -> "$danger ta xavfli fayl topildi — bosib ko'ring"
                suspicious > 0 -> "$suspicious ta shubhali fayl bor — bosib ko'ring"
                else -> "Telefoningiz himoyalangan · 24/7"
            }
        }

        private fun pendingIntentFlags(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        }
    }
}
