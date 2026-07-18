package com.uzguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import rikka.shizuku.Shizuku

/**
 * UI-помощник для Shizuku: определение установки, открытие приложения/магазина,
 * запрос разрешения и диалог "включить настоящее удаление".
 *
 * Разделение обязанностей: [ShizukuDeleter] — само удаление (без UI),
 * [ShizukuSetup] — всё, что связано с направлением пользователя.
 */
object ShizukuSetup {

    private const val TAG = "ShizukuSetup"

    /** Пакет официального приложения Shizuku. */
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val main = Handler(Looper.getMainLooper())

    /**
     * Текущий слушатель результата разрешения. Shizuku хранит слушателей в статическом
     * списке до явного remove — если пользователь ушёл, не ответив на диалог разрешения,
     * анонимный слушатель (а через него Activity+binding) утёк бы навсегда. Держим ОДНУ
     * ссылку и снимаем предыдущего перед добавлением нового → максимум один «висячий».
     */
    private var pendingListener: Shizuku.OnRequestPermissionResultListener? = null

    /** Установлено ли приложение Shizuku. */
    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) != null
    } catch (_: Throwable) {
        false
    }

    /** Открыть Shizuku (если стоит) или страницу установки (магазин / браузер). */
    fun openShizukuOrStore(ctx: Context) {
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
                return
            }
        } catch (_: Throwable) { /* fall through to store */ }

        // Не стоит — ведём в магазин; если магазина нет, в GitHub-релизы.
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(market)
        } catch (_: Throwable) {
            try {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "openShizukuOrStore failed", t)
            }
        }
    }

    /**
     * Диалог "включить настоящее удаление через Shizuku". Вызывается из ветки
     * SandboxedByOwner, когда файл в /Android/data и Shizuku ещё не готов.
     *
     * Ведёт пользователя по состоянию:
     *   • Shizuku не установлен → кнопка "Установить Shizuku".
     *   • Установлен, но не запущен → кнопка "Открыть Shizuku" (там он его стартует).
     *   • Запущен, но нет разрешения → запрашиваем разрешение, по выдаче — удаляем.
     *   • Готов → сразу удаляем.
     *
     * @param onDeleted вызывается на main-потоке с true, если файл реально удалён.
     */
    fun promptRealDelete(activity: Activity, path: String, onDeleted: (Boolean) -> Unit) {
        // Уже готов — просто удаляем в фоне.
        if (ShizukuDeleter.canUse()) {
            deleteAsync(path, onDeleted)
            return
        }

        val installed = isInstalled(activity)
        val binderAlive = ShizukuDeleter.isBinderAlive()

        val (msgRes, btnRes) = when {
            !installed -> R.string.shizuku_msg_install to R.string.shizuku_btn_install
            !binderAlive -> R.string.shizuku_msg_start to R.string.shizuku_btn_open
            else -> R.string.shizuku_msg_permission to R.string.shizuku_btn_grant
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.shizuku_title))
            .setMessage(activity.getString(msgRes))
            .setPositiveButton(activity.getString(btnRes)) { _, _ ->
                when {
                    !installed || !binderAlive -> openShizukuOrStore(activity)
                    else -> requestPermissionThenDelete(activity, path, onDeleted)
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    /** Запрашивает разрешение Shizuku и, как только оно выдано, удаляет файл. */
    private fun requestPermissionThenDelete(
        activity: Activity,
        path: String,
        onDeleted: (Boolean) -> Unit
    ) {
        // Снимаем предыдущего висячего слушателя (если пользователь ранее не ответил на диалог).
        pendingListener?.let {
            try { Shizuku.removeRequestPermissionResultListener(it) } catch (_: Throwable) { /* ignore */ }
            pendingListener = null
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != ShizukuDeleter.PERMISSION_REQUEST_CODE) return
                try {
                    Shizuku.removeRequestPermissionResultListener(this)
                } catch (_: Throwable) { /* ignore */ }
                if (pendingListener === this) pendingListener = null
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    deleteAsync(path, onDeleted)
                } else {
                    main.post { onDeleted(false) }
                }
            }
        }
        try {
            pendingListener = listener
            Shizuku.addRequestPermissionResultListener(listener)
            ShizukuDeleter.requestPermission()
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermissionThenDelete failed", t)
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (_: Throwable) { /* ignore */ }
            if (pendingListener === listener) pendingListener = null
            main.post { onDeleted(false) }
        }
    }

    /** Удаление — биндер-вызов, поэтому уводим с main-потока. */
    private fun deleteAsync(path: String, onDeleted: (Boolean) -> Unit) {
        Thread {
            val ok = ShizukuDeleter.deleteViaShizuku(path)
            main.post { onDeleted(ok) }
        }.apply { isDaemon = true }.start()
    }
}
