package com.kiberqalqon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Принимает APK из других приложений через ACTION_SEND / ACTION_VIEW.
 *
 * Пример: пользователь в Telegram нажимает на APK → "Поделиться" → KiberQalqon.
 * Или в файловом менеджере → "Открыть с помощью" → KiberQalqon.
 *
 * Эта Activity exported=true, но НЕ показывает UI напрямую — только копирует APK
 * во внутренний кэш и запускает AutoScanActivity (которая exported=false).
 *
 * Зачем посредник: AutoScanActivity получает apk_path и должна верить, что путь
 * указывает на реальный читаемый APK. Если бы она была exported=true, любое
 * приложение могло бы передать произвольный путь и заставить нас "удалить" чужой файл.
 * Здесь же мы сами кладём APK в наш cacheDir → путь под контролем.
 */
// Plain Activity (not AppCompat): manifest theme is platform Theme.Translucent.NoTitleBar — AppCompatActivity requires a Theme.AppCompat descendant and would crash in createSubDecor.
class ShareReceiverActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onDestroy() {
        // Activity yopilsa, davom etayotgan nusxalash coroutine'ini bekor qilamiz —
        // aks holda u Activity'ni (Toast/Intent) ushlab leak qilardi.
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        if (uri == null) {
            Toast.makeText(this, R.string.share_no_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyToCache(uri) }
            // Nusxalash davomida Activity yopilgan bo'lishi mumkin — UI'ga tegmaymiz.
            if (isFinishing || isDestroyed) return@launch
            if (copied == null) {
                Toast.makeText(this@ShareReceiverActivity, R.string.share_copy_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val launch = Intent(this@ShareReceiverActivity, AutoScanActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("apk_path", copied.absolutePath)
                putExtra("apk_name", copied.name)
                putExtra("apk_source", "share")
            }
            startActivity(launch)
            finish()
        }
    }

    private fun copyToCache(uri: Uri): File? {
        return try {
            val cr = contentResolver
            // Имя из ContentResolver query, если возможно. Иначе fallback.
            val name = queryDisplayName(uri) ?: "shared-${System.currentTimeMillis()}.apk"
            // Санитизация — никаких / \ : чтобы файл не выехал из cacheDir.
            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120)
            val outDir = File(cacheDir, "shared").apply { mkdirs() }
            val out = File(outDir, safeName)
            cr.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                        // 100 МБ потолок — иначе можно ловить OOM на больших файлах.
                        if (total > 100L * 1024 * 1024) {
                            Log.w("ShareReceiver", "Shared file too large, aborting copy")
                            return null
                        }
                    }
                }
            } ?: return null
            out
        } catch (e: Exception) {
            Log.e("ShareReceiver", "Copy failed", e)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
