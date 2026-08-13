package com.uzguard

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * Jonli o'rnatish qalqoni — uchinchi rubej "hech kim virusni o'rnatmasin"ning yuragi.
 *
 * Tizimning APK o'rnatuvchisi ("O'rnatasizmi?") oynasini kuzatadi (config faqat o'rnatuvchi
 * paketlar bilan cheklangan). Agar o'rnatilayotgan narsa UzGuard tomonidan TASDIQLANMAGAN
 * va yaqinda DANGER deb topilgan bo'lsa — avtomatik "Bekor" bosadi (GLOBAL_ACTION_BACK,
 * zaxira sifatida "Bekor" tugmasini bosish) va foydalanuvchini ogohlantiradi.
 *
 * MUHIM cheklovlar (halol):
 *  • Xizmatni YOQISH foydalanuvchidan qo'lda harakat talab qiladi (Android 13+ "cheklangan
 *    sozlamalar"). Biz uni majburan yoqa olmaymiz — faqat yo'l ko'rsatamiz.
 *  • Bu poyga (race): foydalanuvchi biz ulgurmasdan "O'rnatish" bossa — o'tib ketishi mumkin;
 *    o'sha holatni PackageInstallReceiver (PACKAGE_ADDED → snos) ushlaydi.
 *  • Soxta-bloklamaslik uchun faqat (a) ma'lum DANGER yorlig'i, yoki (b) yaqinda DANGER bo'lib
 *    HALI tasdiqlanmagan holatda bloklaymiz. O'z paketimiz / ishonchli do'kon yangilanishi
 *    hech qachon bloklanmaydi.
 */
class InstallShieldService : AccessibilityService() {

    private var lastActionAt = 0L
    private var lastScanAt = 0L
    private var lastBlockedLabel: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        try {
            if (!Config.isInstallShieldEnabled(this)) return
            val src = e.packageName?.toString() ?: return
            if (src !in INSTALLERS) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastActionAt < DEBOUNCE_MS) return  // shu oynani allaqachon bekor qildik
            // BATAREYA/QIZISH himoyasi: o'rnatuvchi oynasi typeWindowContentChanged'ni tez-tez
            // otadi — har safar daraxtni aylanmaymiz. ~700ms da bir martadan ko'p emas.
            if (now - lastScanAt < SCAN_DEBOUNCE_MS) return
            lastScanAt = now

            val root = rootInActiveWindow ?: return
            // root va uning bolalari tizim resurslari — ishlatib bo'lgach BO'SHATAMIZ
            // (eski Android'da xotira oqib ketmasin/qizimasin; API 33+ da recycle no-op).
            try {
                val texts = ArrayList<String>(24)
                collectTexts(root, texts, 0)

                // Bu haqiqatan o'rnatish-tasdiq oynasimi? (Install/Cancel turidagi tugma bor)
                if (texts.none { looksLikeInstallWord(it) }) return

                val label = guessAppLabel(texts)
                if (label != null && isOwnAppLabel(label)) return  // o'zimizni o'rnatish/yangilash

                if (!shouldBlock(label)) return

                // BLOK: avval id-bog'liq bo'lmagan eng ishonchli yo'l — orqaga.
                lastActionAt = now
                lastBlockedLabel = label
                performGlobalAction(GLOBAL_ACTION_BACK)
                // Zaxira: "Bekor" tugmasini topib bosamiz (ba'zi OEM'larda BACK yetmasligi mumkin).
                clickCancel(root)
                notifyBlocked(label)
                Log.w(TAG, "Blocked install dialog (installer=$src, label=$label)")
            } finally {
                try { root.recycle() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed", t)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    /**
     * Bloklash qarori. Oynadan ko'pincha faqat ilova YORLIG'I o'qiladi (paket nomi emas),
     * shuning uchun ikki signal:
     *  1) Aniq: yorliq UzGuard tomonidan DANGER deb belgilangan (InstallApproval).
     *  2) Vaqt-oynasi: yaqinda biror narsa DANGER bo'ldi VA shundan beri hech narsa
     *     tasdiqlanmadi → bu o'sha virusni o'rnatish urinishi (foydalanuvchi ogohlantirishdan
     *     keyin baribir o'rnatmoqchi). Tasdiq yaqinda bo'lsa — bloklamaymiz (soxta-blok yo'q).
     */
    private fun shouldBlock(label: String?): Boolean {
        // 1) Aniq: shu yorliq UzGuard tomonidan DANGER deb belgilangan.
        if (label != null && InstallApproval.isDanger(this, label)) return true
        // 2) Vaqt-oynasi FAQAT yorliqni umuman o'qiy olmaganimizda (label == null) ishlaydi.
        //    Agar oynadan haqiqiy ilova nomini o'qigan bo'lsak-u u DANGER emas bo'lsa —
        //    yaqinda boshqa narsa DANGER bo'lgani uchun BEGONA (masalan, qonuniy o'yin yoki
        //    do'kon yangilanishi) o'rnatishini bloklamaymiz (soxta-blok yo'q). Bu holatni
        //    baribir PackageInstallReceiver (PACKAGE_ADDED → snos) ushlab qoladi.
        if (label != null) return false
        return InstallApproval.hasRecentDanger(this, RECENT_DANGER_MS) &&
            !InstallApproval.hasRecentApproval(this, RECENT_OK_MS)
    }

    private fun isOwnAppLabel(label: String): Boolean {
        // XAVFSIZLIK: o'z ilovamizni FAQAT ANIQ yorliq bo'yicha tanaymiz — substring EMAS.
        // Ilgari contains("qalqon"/"uzguard") ishlatilardi; soxta "UzGuard Pro" yoki
        // "Kiber Qalqon Update" nomli virus shu tekshiruvdan o'tib, qalqon uni bekor
        // qilmasdi (impersonatsiya bypass'i). Endi normallashtirilgan aniq tenglik.
        val n = normalizeLabel(label)
        if (n.isEmpty()) return false
        if (n in OWN_LABELS) return true
        // Lokalizatsiya/rebrend uchun: haqiqiy o'rnatilgan ilova yorlig'i bilan solishtiramiz.
        val self = try { normalizeLabel(getString(R.string.app_name)) } catch (_: Throwable) { "" }
        return self.isNotEmpty() && n == self
    }

    /** Yorliqni taqqoslash uchun normallashtiramiz (past registr, bo'sh joylarsiz). */
    private fun normalizeLabel(s: String): String =
        s.trim().lowercase().filter { !it.isWhitespace() }

    /** Daraxtni yengil aylanib, ko'rinadigan matnlarni yig'amiz (chuqurlik/soni cheklangan). */
    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTexts(child, out, depth + 1)
            // Bolani ishlatib bo'ldik — bo'shatamiz (root'ni emas, uni chaqiruvchi bo'shatadi).
            try { child?.recycle() } catch (_: Throwable) {}
            if (out.size >= MAX_NODES) return
        }
    }

    /** Oynadagi ilova nomini taxmin qilamiz — tugma/bo'sh-gap so'zlari emas eng birinchi matn. */
    private fun guessAppLabel(texts: List<String>): String? =
        texts.firstOrNull { t ->
            t.length in 2..40 &&
                !looksLikeInstallWord(t) &&
                !looksLikeCancelWord(t) &&
                !BOILERPLATE.any { t.lowercase().contains(it) }
        }

    private fun clickCancel(root: AccessibilityNodeInfo?) {
        if (root == null) return
        try {
            // 1) view-id bo'yicha (AOSP: button2 = Cancel). Topilgan node'lar har birini bir
            //    marta bo'shatamiz (ikki marta emas — eski Android'da ikkilamchi recycle crash).
            for (id in CANCEL_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                try {
                    val hit = nodes.firstOrNull { it.isClickable }
                    if (hit != null) { hit.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
                } finally {
                    nodes.forEach { try { it.recycle() } catch (_: Throwable) {} }
                }
            }
            // 2) matn bo'yicha (Cancel / Bekor / Отмена ...).
            for (w in CANCEL_WORDS) {
                val nodes = root.findAccessibilityNodeInfosByText(w) ?: continue
                try {
                    for (n in nodes) {
                        val clickable = generateSequence(n) { it.parent }.take(4).firstOrNull { it.isClickable }
                        if (clickable != null) { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
                    }
                } finally {
                    nodes.forEach { try { it.recycle() } catch (_: Throwable) {} }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clickCancel failed", t)
        }
    }

    private fun looksLikeInstallWord(t: String): Boolean {
        val s = t.lowercase()
        return INSTALL_WORDS.any { s == it || s.contains(it) }
    }

    private fun looksLikeCancelWord(t: String): Boolean {
        val s = t.lowercase()
        return CANCEL_WORDS.any { s.equals(it, ignoreCase = true) || s.contains(it.lowercase()) }
    }

    private fun notifyBlocked(label: String?) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Himoya", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val body = if (!label.isNullOrBlank())
                getString(R.string.install_blocked_notif_body, label)
            else getString(R.string.install_blocked_notif_body_generic)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.install_blocked_notif_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyBlocked failed", t)
        }
    }

    companion object {
        private const val TAG = "InstallShield"
        private const val CHANNEL = "uzguard_shield"
        private const val NOTIF_ID = 920_311
        private const val DEBOUNCE_MS = 1500L
        private const val SCAN_DEBOUNCE_MS = 700L
        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 80
        // Vaqt-oynasi: skan DANGER bergach foydalanuvchi virusni o'rnatishga ~shu vaqt ichida
        // urinadi. Tasdiq oynasi qisqaroq (faqat shu o'rnatishni o'tkazish).
        private const val RECENT_DANGER_MS = 5L * 60 * 1000
        private const val RECENT_OK_MS = 90L * 1000

        private val INSTALLERS = setOf(
            "com.samsung.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.huawei.packageinstaller",
            "com.vivo.packageinstaller",
            "com.oppo.packageinstaller",
            "com.coloros.packageinstaller",
        )
        // O'z ilovamizning ANIQ yorliqlari (normallashtirilgan: past registr, bo'sh joysiz).
        // Substring emas — aniq tenglik uchun. Joriy: "UZGUARD"; tarixiy brendlar ham.
        private val OWN_LABELS = setOf("uzguard", "kiberqalqon", "anorqalqon")
        private val INSTALL_WORDS = setOf("install", "o'rnatish", "ornatish", "установить", "o‘rnatish")
        private val CANCEL_WORDS = listOf("Cancel", "Bekor", "Bekor qilish", "Отмена", "Отменить", "Yopish", "Закрыть")
        private val CANCEL_IDS = listOf("android:id/button2", "com.android.packageinstaller:id/cancel_button")
        private val BOILERPLATE = listOf(
            "do you want", "o'rnatmoqchimisiz", "ornatmoqchimisiz", "хотите установить",
            "play protect", "blocked", "google", "update", "yangilash", "обновить", "version"
        )
    }
}
