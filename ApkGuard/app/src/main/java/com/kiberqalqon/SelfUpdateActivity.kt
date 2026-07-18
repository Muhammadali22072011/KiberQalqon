package com.uzguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Yangilanish bildirishnomasidan ochiladigan KO'RINMAS (translucent) tasdiq oynasi:
 * «Yangi versiya — yangilansinmi?» → fonda [SelfUpdate.downloadVerifyInstall] →
 * tekshiruvdan o'tsa tizim o'rnatuvchisi ochiladi. Sof [Activity] + framework dialog
 * (translucent mavzuda AppCompat qulaydi — LinkGuardActivity bilan bir xil sabab).
 */
class SelfUpdateActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = RemoteConfig.updateInfo(this)
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            Toast.makeText(this, R.string.kq4_update_already, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_update_dialog_title)
            .setMessage(getString(R.string.kq4_update_dialog_msg, info.versionCode))
            .setPositiveButton(R.string.kq4_update_do) { _, _ -> startDownload(info) }
            .setNegativeButton(R.string.kq4_btn_later) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startDownload(info: SelfUpdate.Info) {
        Toast.makeText(applicationContext, R.string.kq4_update_downloading, Toast.LENGTH_LONG).show()
        val app = applicationContext
        // Yuklab olish daqiqagacha cho'zilishi mumkin — applicationContext bilan ishlaymiz,
        // activity darhol yopiladi (foydalanuvchi kutib o'tirmaydi, natija Toast bilan keladi).
        Thread {
            val errRes = SelfUpdate.downloadVerifyInstall(app, info)
            if (errRes != null) {
                Handler(Looper.getMainLooper()).post {
                    try { Toast.makeText(app, errRes, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
                }
            }
        }.start()
        finish()
    }
}
