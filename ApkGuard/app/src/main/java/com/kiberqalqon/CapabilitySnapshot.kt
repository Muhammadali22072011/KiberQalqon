package com.uzguard

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Har paket uchun "imkoniyat suratini" (capability snapshot) saqlaydi va YANGILANISHdan keyin
 * XAVFLI o'sishlarni aniqlaydi. Maqsad: sideload orqali kelgan "zararsiz" yangilanish keyin
 * yashirincha SMS o'qish / Accessibility / device-admin / notification-listener huquqlarini
 * qo'shib olsa — foydalanuvchini ogohlantirish.
 *
 * Surat: { dangerousPerms: Set<String>, hasA11yService, hasDeviceAdmin, hasNotifListener, targetSdk }
 * PackageManager'dan olinadi (GET_PERMISSIONS | GET_SERVICES | GET_RECEIVERS).
 *
 * Integrator PackageInstallReceiver'da ACTION_PACKAGE_REPLACED bo'yicha [diffOnReplace] ni,
 * shuningdek InstalledAppsRescanWorker'da "catch-up" (surat mavjud bo'lmaganlar uchun [snapshot])
 * chaqiradi. Hammasi fail-soft — hech qachon skan/receiver'ni buzmaydi.
 *
 * ESLATMA: PACKAGE_REPLACED'da sertifikat O'ZGARA OLMAYDI (Android buni rad etadi) — shuning uchun
 * bu yerda cert tekshirilmaydi; faqat e'lon qilingan imkoniyatlar farqi.
 */
object CapabilitySnapshot {

    private const val TAG = "CapabilitySnapshot"
    private const val PREFS = "uzguard_capsnap"

    // BIND_* ruxsatlari <service>/<receiver> android:permission da e'lon qilinadi.
    private const val PERM_A11Y = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val PERM_NOTIF = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    private const val PERM_ADMIN = "android.permission.BIND_DEVICE_ADMIN"

    // Kuzatiladigan "xavfli" ruxsatlar — o'sish (gain) bo'lsa foydalanuvchiga ko'rsatiladi.
    // Uzbekcha izoh bilan (foydalanuvchiga tushunarli delta matni).
    private val DANGEROUS_PERM_LABELS: Map<String, String> = linkedMapOf(
        "android.permission.READ_SMS" to "SMS o'qish huquqini oldi",
        "android.permission.RECEIVE_SMS" to "SMS qabul qilish huquqini oldi",
        "android.permission.SEND_SMS" to "SMS yuborish huquqini oldi",
        "android.permission.READ_CALL_LOG" to "Qo'ng'iroqlar tarixini o'qish huquqini oldi",
        "android.permission.WRITE_CALL_LOG" to "Qo'ng'iroqlar tarixini o'zgartirish huquqini oldi",
        "android.permission.CALL_PHONE" to "Qo'ng'iroq qilish huquqini oldi",
        "android.permission.READ_CONTACTS" to "Kontaktlarni o'qish huquqini oldi",
        "android.permission.WRITE_CONTACTS" to "Kontaktlarni o'zgartirish huquqini oldi",
        "android.permission.ACCESS_FINE_LOCATION" to "Aniq joylashuv huquqini oldi",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Fonda joylashuv huquqini oldi",
        "android.permission.CAMERA" to "Kamera huquqini oldi",
        "android.permission.RECORD_AUDIO" to "Mikrofon huquqini oldi",
        "android.permission.SYSTEM_ALERT_WINDOW" to "Ustiga oyna chizish (overlay) huquqini oldi",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Boshqa ilovalarni o'rnatish huquqini oldi",
        "android.permission.QUERY_ALL_PACKAGES" to "Barcha ilovalar ro'yxatini ko'rish huquqini oldi",
    )

    private data class Caps(
        val dangerousPerms: Set<String>,
        val hasA11yService: Boolean,
        val hasDeviceAdmin: Boolean,
        val hasNotifListener: Boolean,
        val targetSdk: Int,
    )

    /** O'rnatilgan [pkg] ning joriy imkoniyat suratini saqlaydi. Fail-soft. */
    fun snapshot(ctx: Context, pkg: String) {
        try {
            val caps = readCaps(ctx, pkg) ?: return
            store(ctx, pkg, caps)
        } catch (e: Throwable) {
            Log.w(TAG, "snapshot failed for $pkg", e)
        }
    }

    /**
     * Yangilanishdan KEYIN chaqiriladi: saqlangan suratni joriy holat bilan solishtiradi va FAQAT
     * XAVFLI o'sishlar uchun o'zbekcha delta ro'yxatini qaytaradi (yangi xavfli ruxsat, yangi
     * accessibility/device-admin/notification-listener komponenti, targetSdk PASAYISHI). So'ng yangi
     * suratni qayta saqlaydi. Avvalgi surat bo'lmasa yoki xavfli o'sish bo'lmasa — bo'sh ro'yxat.
     */
    fun diffOnReplace(ctx: Context, pkg: String): List<String> {
        return try {
            val current = readCaps(ctx, pkg) ?: return emptyList()
            val prior = load(ctx, pkg)
            // Avvalgi surat qanday bo'lsa ham, keyingi safar uchun joriy holatni qayta saqlaymiz.
            store(ctx, pkg, current)
            if (prior == null) return emptyList()  // taqqoslash uchun asos yo'q

            val deltas = ArrayList<String>()
            // 1) Yangi xavfli ruxsatlar (avval yo'q edi, endi bor).
            for (perm in current.dangerousPerms) {
                if (perm !in prior.dangerousPerms) {
                    deltas.add(DANGEROUS_PERM_LABELS[perm] ?: "Yangi xavfli ruxsat oldi: ${perm.substringAfterLast('.')}")
                }
            }
            // 2) Yangi kuchli komponentlar.
            if (current.hasA11yService && !prior.hasA11yService)
                deltas.add("Maxsus imkoniyatlar (Accessibility) xizmatini qo'shdi — bank ilovalarini boshqarishi mumkin")
            if (current.hasDeviceAdmin && !prior.hasDeviceAdmin)
                deltas.add("Qurilma administratori huquqini qo'shdi")
            if (current.hasNotifListener && !prior.hasNotifListener)
                deltas.add("Bildirishnomalarni o'qish xizmatini qo'shdi — SMS/OTP kodlarni ko'rishi mumkin")
            // 3) targetSdk PASAYISHI — eski Android ruxsat modelidan foydalanish urinishi.
            if (current.targetSdk in 1 until prior.targetSdk)
                deltas.add("Yangilanish eskiroq Android versiyasiga moslashtirilgan (targetSdk ${prior.targetSdk}→${current.targetSdk})")

            deltas
        } catch (e: Throwable) {
            Log.w(TAG, "diffOnReplace failed for $pkg", e)
            emptyList()
        }
    }

    private fun readCaps(ctx: Context, pkg: String): Caps? {
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(
                pkg,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
            )
            val dangerous = HashSet<String>()
            info.requestedPermissions?.forEach { p ->
                if (p in DANGEROUS_PERM_LABELS) dangerous.add(p)
            }
            val services = info.services
            val receivers = info.receivers
            val a11y = services?.any { it.permission == PERM_A11Y } == true
            val notif = services?.any { it.permission == PERM_NOTIF } == true
            val admin = receivers?.any { it.permission == PERM_ADMIN } == true
            val targetSdk = info.applicationInfo?.targetSdkVersion ?: 0
            Caps(dangerous, a11y, admin, notif, targetSdk)
        } catch (_: Throwable) {
            null
        }
    }

    private fun store(ctx: Context, pkg: String, caps: Caps) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val perms = JSONArray()
        for (p in caps.dangerousPerms) perms.put(p)
        val o = JSONObject()
            .put("perms", perms)
            .put("a11y", caps.hasA11yService)
            .put("admin", caps.hasDeviceAdmin)
            .put("notif", caps.hasNotifListener)
            .put("tsdk", caps.targetSdk)
        sp.edit().putString(pkg, o.toString()).apply()
    }

    private fun load(ctx: Context, pkg: String): Caps? {
        return try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = sp.getString(pkg, null) ?: return null
            val o = JSONObject(raw)
            val perms = HashSet<String>()
            o.optJSONArray("perms")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotEmpty()) perms.add(p)
                }
            }
            Caps(
                dangerousPerms = perms,
                hasA11yService = o.optBoolean("a11y", false),
                hasDeviceAdmin = o.optBoolean("admin", false),
                hasNotifListener = o.optBoolean("notif", false),
                targetSdk = o.optInt("tsdk", 0),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
