package com.uzguard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.uzguard.databinding.ActivityQuarantineV4Binding
import com.uzguard.databinding.ItemKq4QuarantineEntryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UX-01: Karantin ekrani. Avval avto-karantin/avto-o'chirishda foydalanuvchiga har joyda
 * "7 kun ichida tiklash mumkin" deb va'da berilardi, lekin Quarantine.restore()/list()/purge()
 * HECH QAYERDAN chaqirilmasdi — ekran ham, Telegram-komanda ham yo'q edi, ya'ni va'da yolg'on edi
 * (xato avto-o'chirilgan legit fayl abadiy yo'qolardi). Bu ekran shu va'dani haqiqatga aylantiradi:
 * karantindagi fayllar ro'yxati + "Tiklash" (restore) va "O'chirish" (purge).
 *
 * v4 dizayn (design_v4_extracted/screens2.jsx → Quarantine): layout
 * activity_quarantine_v4.xml, qatorlar item_kq4_quarantine_entry.xml.
 * Logika o'zgarmagan: Quarantine.list/restore/purge + tasdiqlash dialoglari.
 */
class QuarantineActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var binding: ActivityQuarantineV4Binding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ThemeHelper.applyAccent(this) } catch (_: Throwable) {}
        binding = ActivityQuarantineV4Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnQuarBack.setOnClickListener { finish() }
        binding.btnQuarClearAll.setOnClickListener { confirmClearAll() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        // Activity yo'q qilinganda davom etayotgan coroutine'larni bekor qilamiz:
        // aks holda scope o'lik Activity/binding'ni ushlab (leak) reload()/Toast'ni
        // yo'q qilingan ekranga chaqiradi.
        scope.cancel()
        super.onDestroy()
    }

    private fun reload() {
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                try { Quarantine.list(this@QuarantineActivity) } catch (_: Throwable) { emptyList() }
            }
            binding.quarList.removeAllViews()
            val empty = entries.isEmpty()
            binding.cardQuarEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.cardQuarList.visibility = if (empty) View.GONE else View.VISIBLE
            binding.btnQuarClearAll.visibility = if (empty) View.GONE else View.VISIBLE
            entries.forEachIndexed { i, e ->
                binding.quarList.addView(entryView(e, isLast = i == entries.lastIndex))
            }
        }
    }

    private fun entryView(e: Quarantine.Entry, isLast: Boolean): View {
        val item = ItemKq4QuarantineEntryBinding.inflate(
            LayoutInflater.from(this), binding.quarList, false,
        )
        item.tvQuarName.text = e.originalName
        item.tvQuarMeta.text = getString(R.string.kq4_quar_meta, sourceOf(e), whenOf(e))
        item.btnQuarRestore.setOnClickListener { confirmRestore(e) }
        item.btnQuarDelete.setOnClickListener { confirmPurge(e) }
        item.quarDivider.visibility = if (isLast) View.GONE else View.VISIBLE
        return item.root
    }

    /** "{откуда}" — asl fayl yo'lidan odamga tushunarli manba nomi. */
    private fun sourceOf(e: Quarantine.Entry): String {
        val p = e.originalPath.lowercase(Locale.ROOT)
        return when {
            p.contains("telegram") -> getString(R.string.kq4_quar_src_telegram)
            p.contains("whatsapp") -> getString(R.string.kq4_quar_src_whatsapp)
            p.contains("download") -> getString(R.string.kq4_quar_src_downloads)
            else -> getString(R.string.kq4_quar_src_storage)
        }
    }

    /** "{когда}" — "Bugun, 14:32" / "Kecha, 19:40" / "N kun oldin". */
    private fun whenOf(e: Quarantine.Entry): String {
        val ts = e.quarantinedAt
        if (ts <= 0L) return ""
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(ts)) / DAY_MS)
            .toInt().coerceAtLeast(0)
        return when (days) {
            0 -> getString(R.string.kq4_quar_today, time)
            1 -> getString(R.string.kq4_quar_yesterday, time)
            else -> getString(R.string.kq4_quar_days_ago, days)
        }
    }

    private fun startOfDay(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun confirmRestore(e: Quarantine.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_quar_restore)
            .setMessage(getString(R.string.kq4_quar_restore_msg, e.originalName))
            .setPositiveButton(R.string.kq4_quar_restore) { _, _ ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Quarantine.restore(this@QuarantineActivity, e.token) }
                    val msg = when (r) {
                        is Quarantine.Result.Ok -> getString(R.string.kq4_quar_restored, e.originalName)
                        is Quarantine.Result.Failed -> getString(R.string.kq4_quar_restore_fail, r.message)
                    }
                    Toast.makeText(this@QuarantineActivity, msg, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .setNegativeButton(R.string.kq4_quar_cancel, null)
            .show()
    }

    private fun confirmPurge(e: Quarantine.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_quar_purge_title)
            .setMessage(getString(R.string.kq4_quar_purge_msg, e.originalName))
            .setPositiveButton(R.string.kq4_quar_delete) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { Quarantine.purge(this@QuarantineActivity, e.token) }
                    reload()
                }
            }
            .setNegativeButton(R.string.kq4_quar_cancel, null)
            .show()
    }

    /** "Karantinni butunlay tozalash" — barcha yozuvlarni mavjud purge() bilan o'chiradi. */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kq4_quar_clear_all)
            .setMessage(R.string.kq4_quar_clear_all_msg)
            .setPositiveButton(R.string.kq4_quar_delete) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val all = try { Quarantine.list(this@QuarantineActivity) } catch (_: Throwable) { emptyList() }
                        for (e in all) {
                            try { Quarantine.purge(this@QuarantineActivity, e.token) } catch (_: Throwable) {}
                        }
                    }
                    reload()
                }
            }
            .setNegativeButton(R.string.kq4_quar_cancel, null)
            .show()
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
