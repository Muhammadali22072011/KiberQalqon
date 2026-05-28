package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Единая точка проверки разрешений и возможностей для всех Android (7 → 15).
 *
 * Зачем выделено: на каждом устройстве иначе нужно проверять "есть ли у нас доступ":
 *   Android 7-9   → READ_EXTERNAL_STORAGE
 *   Android 10    → READ_EXTERNAL_STORAGE + requestLegacyExternalStorage (есть в манифесте)
 *   Android 11-12 → MANAGE_EXTERNAL_STORAGE для удаления, READ_EXTERNAL_STORAGE для чтения
 *   Android 13+   → READ_MEDIA_* для чтения, MANAGE_EXTERNAL_STORAGE для удаления
 *
 * Если эти проверки разбросать по Activity — будут баги. Здесь — централизованно.
 */
object VersionCompat {

    /** True если у нас есть доступ к чтению хранилища, в зависимости от версии. */
    fun hasReadStorage(ctx: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: гранулированные media-разрешения.
                // Хватит любого одного (для APK ищем во всех media-категориях).
                hasPerm(ctx, Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPerm(ctx, Manifest.permission.READ_MEDIA_VIDEO) ||
                    hasPerm(ctx, Manifest.permission.READ_MEDIA_AUDIO) ||
                    hasManageStorage()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12: MANAGE_EXTERNAL_STORAGE даёт всё, иначе нужен READ.
                hasManageStorage() || hasPerm(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                // Android 7-10: классический READ_EXTERNAL_STORAGE.
                hasPerm(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * True если можем удалять чужие APK напрямую без диалогов.
     * - Android 7-9: WRITE_EXTERNAL_STORAGE (всегда true по умолчанию для targetSdk<29)
     * - Android 10: WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage (true благодаря манифесту)
     * - Android 11+: только MANAGE_EXTERNAL_STORAGE
     */
    fun canDeleteFreely(ctx: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> hasManageStorage()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // requestLegacyExternalStorage=true → file.delete() работает
                hasPerm(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> hasPerm(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** True если разрешено рисовать поверх других окон (для AutoScanActivity). */
    fun hasOverlayPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(ctx)
        } else true
    }

    /** True если разрешена установка APK от нашего имени (Android 8+). */
    fun canRequestInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    /** True если разрешены уведомления (Android 13+). */
    fun hasNotificationPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)
        } else true
    }

    private fun hasManageStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    private fun hasPerm(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Читаемое имя версии Android для отладки и UI. */
    fun versionName(): String = when (Build.VERSION.SDK_INT) {
        24, 25 -> "Android 7 (Nougat)"
        26, 27 -> "Android 8 (Oreo)"
        28 -> "Android 9 (Pie)"
        29 -> "Android 10"
        30 -> "Android 11"
        31, 32 -> "Android 12"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        else -> "Android API ${Build.VERSION.SDK_INT}"
    }
}
