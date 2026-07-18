package com.uzguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Slushaet sistemnye sostoyaniya kotorye mogut ukazyvat' na ugrozu ili krazhu:
 *
 *  - SIM_STATE_CHANGED — vor sunul druguyu SIM-kartu posle krazhi (klassicheskiy anti-theft).
 *    Compare serial s tem chto sohranili pri pervom zapuske.
 *
 *  - AIRPLANE_MODE_CHANGED — vor srazu vklyuchaet aviarezhim, chtoby otorvat'
 *    telefon ot servisov.
 *
 *  - PHONE_STATE — ne podpisany (trebuet READ_PHONE_STATE, ne hochem
 *    dobavlyat' ego v manifest bez yavnoy nuzhdy).
 *
 * Registracziya dinamicheskaya iz App.onCreate.
 */
class SystemStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val ctx = context.applicationContext

        when (intent.action) {
            "android.intent.action.SIM_STATE_CHANGED" -> handleSim(ctx)
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                try {
                    TelemetryReporter.reportAirplaneMode(ctx, on)
                } catch (e: Throwable) {
                    Log.w(TAG, "airplane telemetry failed", e)
                }
            }
        }
    }

    private fun handleSim(ctx: Context) {
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            // simSerialNumber pal v Android 10+ — bez READ_PHONE_STATE vernet ""/null.
            // No simOperator i subscriberId dolzhny rabotat' kak proxy. Dlya nashih
            // tseley dostatochno operatorNumeric (MCC+MNC) — kogda SIM smenilsya,
            // chasto i operator menyaetsya.
            val newSerial: String? = try {
                tm?.simOperator?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) { null }

            val prefs = simPrefs(ctx)
            val oldSerial = prefs.getString(KEY_LAST_SIM, null)

            if (oldSerial == null) {
                // Pervyy zapusk — prosto zapomnim.
                if (!newSerial.isNullOrBlank()) {
                    prefs.edit().putString(KEY_LAST_SIM, newSerial).apply()
                }
                return
            }
            if (newSerial != null && newSerial != oldSerial) {
                TelemetryReporter.reportSimChanged(ctx, oldSerial, newSerial)
                prefs.edit().putString(KEY_LAST_SIM, newSerial).apply()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "handleSim failed", e)
        }
    }

    companion object {
        private const val TAG = "SystemStateReceiver"
        private const val PREFS = "uzguard_sysstate"
        private const val KEY_LAST_SIM = "last_sim_operator"
        private fun simPrefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
