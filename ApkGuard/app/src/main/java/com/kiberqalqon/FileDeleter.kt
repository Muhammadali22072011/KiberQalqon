package com.uzguard

import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Удаление APK с правильной обработкой ограничений Android.
 *
 * Реальная картина Android-удаления:
 *
 *  1) /sdcard/Download, /sdcard/DCIM и т.п. — удаляются если есть MANAGE_EXTERNAL_STORAGE.
 *  2) /sdcard/Android/media/<pkg>/ — тоже удаляются (media — особый случай).
 *  3) /sdcard/Android/data/<pkg>/ — НЕДОСТУПНЫ никому кроме <pkg>, ДАЖЕ с MANAGE_EXTERNAL_STORAGE.
 *     Это аппаратная защита Android 11+. Единственный путь — SAF tree URI grant,
 *     где юзер вручную выбирает эту папку через системный пикер.
 *  4) Файлы созданные нами (cacheDir/filesDir) — всегда удаляются обычным file.delete().
 */
object FileDeleter {

    private const val TAG = "FileDeleter"

    sealed class Result {
        /** Удалено сразу. */
        object Deleted : Result()
        /** Нужен системный диалог "разрешить удалить файл X?" (MediaStore путь). */
        data class NeedsUserConsent(val sender: IntentSender) : Result()
        /** Нет разрешения "Доступ ко всем файлам" — открыть настройки. */
        object NeedsManageStorage : Result()
        /** Файл в /Android/data/<pkg>/ — может удалить только владелец. */
        data class SandboxedByOwner(val ownerPackage: String) : Result()
        /** Не получилось — причина в [message]. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Главная точка входа. Пытается удалить файл всеми доступными способами.
     */
    fun delete(activity: Activity, filePath: String): Result {
        val file = File(filePath)

        // ЗАЩИТА ОТ СУИЦИДА: никогда не удаляем сам UzGuard.
        if (SelfGuard.isOwnApk(activity, filePath)) {
            Log.w(TAG, "Refusing to delete self APK: $filePath")
            return Result.Failed("Bu UzGuardning o'zi — himoyachini o'chirish taqiqlangan.")
        }

        // Файл в /Android/data/<pkg>/ — особая зона, в неё нельзя пробиться никаким разрешением.
        // Единственный пользовательский способ — удалить через само приложение-владельца.
        //
        // ВАЖНО (#delete-false-success): эта проверка ДОЛЖНА быть ВЫШЕ `!file.exists()`.
        // На Android 11+ мы не можем даже stat'нуть чужую песочницу, поэтому
        // file.exists()==false здесь НЕ значит «файл удалён» — вредонос всё ещё лежит
        // в хранилище приложения-владельца (Telegram и т.п.), просто невидим нам.
        // Раньше ранний `return Deleted` врал «o'chirildi» на выжившем вирусе.
        sandboxOwner(file)?.let { owner ->
            Log.w(TAG, "File belongs to sandboxed dir of $owner: $filePath")
            // Настоящий обход песочницы /Android/data: если пользователь настроил
            // Shizuku (uid=shell в группе ext_data_rw) — удаляем файл под ним.
            // Это ЕДИНСТВЕННЫЙ способ реально стереть файл владельца без root.
            // Не настроен → возвращаем SandboxedByOwner (UI предложит включить Shizuku).
            if (ShizukuDeleter.deleteViaShizuku(filePath)) {
                Log.d(TAG, "Deleted sandboxed file via Shizuku: $filePath")
                return Result.Deleted
            }
            return Result.SandboxedByOwner(owner)
        }

        // Путь, который мы реально видим, и его уже нет → действительно удалён.
        if (!file.exists()) return Result.Deleted

        // 1) Если есть MANAGE_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE — пробуем сразу прямое удаление.
        if (hasFullStorage()) {
            try {
                if (file.delete()) {
                    Log.d(TAG, "Direct delete with full storage OK: $filePath")
                    return Result.Deleted
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Direct delete denied with full storage", e)
            }
        }

        // 2) Простое file.delete() для cacheDir/filesDir и для Android <= 10.
        try {
            if (file.delete()) {
                Log.d(TAG, "Direct delete OK: $filePath")
                return Result.Deleted
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct delete denied", e)
        }

        // 3) Android 11+ без MANAGE_EXTERNAL_STORAGE — отправляем юзера в настройки разрешения.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasFullStorage()) {
            return Result.NeedsManageStorage
        }

        // 4) Ищем URI в MediaStore и пробуем через него (Android 10 + RecoverableSecurityException).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findInMediaStore(activity, file)
            if (uri != null) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestDeleteR(activity, uri)
                } else {
                    tryDeleteQ(activity, uri)
                }
            }
        }

        return Result.Failed("Faylni o'chirib bo'lmadi. Qo'lda o'chiring fayl menejeri orqali.")
    }

    /**
     * True если у нас есть полный доступ к хранилищу — поверка через VersionCompat,
     * чтобы вся логика версий была в одном месте.
     */
    fun hasFullStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else true
    }

    /**
     * Если файл в /Android/data/<pkg>/ — возвращает имя владельца пакета. Иначе null.
     * Аппаратная песочница появилась только на Android 11 (API 30) — до этого папка
     * нормально читается/удаляется с WRITE_EXTERNAL_STORAGE, поэтому ниже R мы её
     * не считаем sandbox'ом и даём обычному file.delete() сработать.
     */
    @androidx.annotation.VisibleForTesting
    internal fun sandboxOwner(file: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return sandboxOwnerFromPath(file.absolutePath)
    }

    /**
     * Чистый разбор пути: вернёт <pkg> для .../Android/data/<pkg>/..., иначе null.
     * Без SDK-гейта и без File-нормализации — поэтому стабильно тестируется на любой
     * ОС (на Windows `File("/x").absolutePath` подставил бы диск и обратные слэши).
     */
    @androidx.annotation.VisibleForTesting
    internal fun sandboxOwnerFromPath(rawPath: String): String? {
        val path = rawPath.replace('\\', '/')
        val marker = "/Android/data/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        val after = path.substring(idx + marker.length)
        val slash = after.indexOf('/')
        return if (slash > 0) after.substring(0, slash) else after
    }

    /** Ищем uri файла в MediaStore через скан Downloads и Files. */
    private fun findInMediaStore(context: Context, file: File): Uri? {
        val name = file.name
        val absPath = file.absolutePath

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            findByName(context, downloadsUri, MediaStore.Downloads._ID, name, absPath)?.let { return it }
        }

        val filesUri = MediaStore.Files.getContentUri("external")
        return findByName(context, filesUri, MediaStore.Files.FileColumns._ID, name, absPath)
    }

    private fun findByName(
        context: Context,
        collection: Uri,
        idColumn: String,
        name: String,
        absPath: String
    ): Uri? = try {
        val projection = arrayOf(idColumn, MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        context.contentResolver.query(collection, projection, selection, arrayOf(name), null)?.use { c ->
            val idIdx = c.getColumnIndex(idColumn)
            val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                val path = if (dataIdx >= 0) c.getString(dataIdx) else null
                // #33: avval `path == null` ham mos deb hisoblanardi — Android 11+ da DATA
                // ko'pincha null bo'lib, AYNAN SHU NOMDAGI BOSHQA fayl (boshqa papkada)
                // o'chirilishi mumkin edi. Endi faqat HAQIQIY yo'l mosligida o'chiramiz.
                if (path != null && path.equals(absPath, ignoreCase = false)) {
                    val id = c.getLong(idIdx)
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore query failed", e)
        null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestDeleteR(activity: Activity, uri: Uri): Result {
        return try {
            val pi: PendingIntent = MediaStore.createDeleteRequest(
                activity.contentResolver,
                listOf(uri)
            )
            Result.NeedsUserConsent(pi.intentSender)
        } catch (e: Exception) {
            Log.e(TAG, "createDeleteRequest failed", e)
            Result.Failed("Ruxsat so'rab bo'lmadi: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun tryDeleteQ(activity: Activity, uri: Uri): Result {
        return try {
            val rows = activity.contentResolver.delete(uri, null, null)
            if (rows > 0) Result.Deleted
            else Result.Failed("MediaStore faylni o'chirmadi (rows=0).")
        } catch (e: RecoverableSecurityException) {
            Result.NeedsUserConsent(e.userAction.actionIntent.intentSender)
        } catch (e: SecurityException) {
            Log.e(TAG, "delete denied", e)
            Result.Failed("Faylni o'chirish uchun ruxsat yo'q.")
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
            Result.Failed("O'chirishda xatolik: ${e.message}")
        }
    }
}
