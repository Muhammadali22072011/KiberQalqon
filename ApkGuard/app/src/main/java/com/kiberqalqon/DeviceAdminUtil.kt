package com.uzguard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Zararli ilovalar (Ajina kabi banker'lar) ko'pincha «Qurilma administratori» huquqini
 * oladi — shunda Android ularni oddiy uninstall bilan O'CHIRTIRMAYDI. Bu yordamchi:
 *  • isActiveAdmin — paket faol administratormi (uninstall'dan oldin tekshiramiz);
 *  • openDeviceAdminSettings — administratorlar ro'yxati ekranini ochadi (foydalanuvchi
 *    virusdan admin huquqini o'chiradi, so'ng uni o'chirsa bo'ladi).
 *
 * Boshqa ilovaning admin huquqini DASTURIY o'chirib bo'lmaydi (faqat egasi yoki foydalanuvchi),
 * shuning uchun foydalanuvchini tizim ekraniga yo'naltiramiz.
 */
object DeviceAdminUtil {

    private const val TAG = "DeviceAdminUtil"

    fun isActiveAdmin(ctx: Context, pkg: String?): Boolean {
        val p = pkg?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            dpm.activeAdmins?.any { it.packageName == p } == true
        } catch (t: Throwable) {
            Log.w(TAG, "isActiveAdmin failed", t)
            false
        }
    }

    fun openDeviceAdminSettings(ctx: Context) {
        val candidates = listOf(
            Intent("android.app.action.DEVICE_ADMIN_SETTINGS"),
            Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            } catch (_: Throwable) { /* keyingisini sinaymiz */ }
        }
    }
}
