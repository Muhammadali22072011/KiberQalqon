package com.uzguard

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Единственный способ, которым обычное приложение может УДАЛИТЬ файл из чужой
 * песочницы /Android/data/<pkg>/ на Android 11+ без root — это Shizuku.
 *
 * Почему это работает (доказано на живом Samsung A56 / Android 16):
 *   • /Android/data/org.telegram.messenger/files/... принадлежит uid Telegram,
 *     права drwxrws--- (владелец + группа ext_data_rw). Наш uid туда не влезет
 *     ДАЖЕ с MANAGE_EXTERNAL_STORAGE — это аппаратная песочница.
 *   • Shizuku запускает процессы под uid=shell (2000). Shell входит в группу
 *     1078(ext_data_rw) → имеет rwx на этих папках. `adb shell rm <path>` там
 *     реально удаляет (проверено: touch/rm → WRITE_OK/RM_OK).
 *   • Значит Shizuku.newProcess(["rm", ...]) удаляет вирус, которого само
 *     приложение коснуться не может.
 *
 * Честные ограничения:
 *   • Требует, чтобы пользователь ОДИН РАЗ поставил приложение Shizuku и запустил
 *     его (беспроводная отладка на Android 11+, без ПК). Мы не можем это сделать
 *     за него — только направить (см. [ShizukuSetup]).
 *   • Без запущенного Shizuku все методы возвращают false/GONE — приложение
 *     спокойно откатывается к честному "копия удалена, оригинал в Telegram".
 *
 * Класс полностью защищён try/catch: если биндер Shizuku мёртв, любой вызов
 * тихо вернёт false, а не уронит приложение.
 */
object ShizukuDeleter {

    private const val TAG = "ShizukuDeleter"

    /** Код запроса разрешения Shizuku (произвольный, лишь бы стабильный). */
    const val PERMISSION_REQUEST_CODE = 0x5A12

    /**
     * Жёсткий потолок ожидания rm. Реальное удаление одного APK через shell — <200мс;
     * если процесс/биндер завис дольше — сдаёмся (не держим поток дольше). Значение
     * специально ниже ANR-порога (5с), т.к. FileDeleter.delete может вызываться с main.
     */
    private const val TIMEOUT_MS = 2500L

    /** Жив ли биндер Shizuku (приложение установлено И сервис запущен). */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** Есть ли у нас разрешение Shizuku. Pre-v11 Shizuku не поддерживаем (устарел). */
    fun hasPermission(): Boolean = try {
        if (!isBinderAlive()) false
        else if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Готов ли Shizuku реально удалять прямо сейчас. */
    fun canUse(): Boolean = isBinderAlive() && hasPermission()

    /** Запросить разрешение Shizuku (результат ловит [ShizukuSetup] listener). */
    fun requestPermission() {
        try {
            if (isBinderAlive() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /**
     * Удаляет файл под uid=shell через Shizuku. Возвращает true ТОЛЬКО если после
     * `rm` файла реально нет (проверяем тем же shell — наш uid его не увидит).
     *
     * @return true — файл физически удалён; false — Shizuku недоступен/нет прав/не удалось.
     */
    fun deleteViaShizuku(path: String): Boolean {
        if (!canUse()) return false
        return try {
            // Экранируем путь для sh: оборачиваем в одинарные кавычки, а каждую
            // одинарную кавычку внутри — в '\'' (классический безопасный приём).
            val q = "'" + path.replace("'", "'\\''") + "'"
            // Одна команда: удалить и сразу проверить существование его же глазами shell.
            val script = "rm -f $q; if [ -e $q ]; then echo EX; else echo OK; fi"
            val proc = newProcess(arrayOf("sh", "-c", script)) ?: return false

            // Чтение stdout + waitFor блокирующие → выполняем в рабочем потоке с потолком
            // TIMEOUT_MS, чтобы зависший биндер Shizuku не устроил ANR (delete может идти с main).
            val holder = arrayOf("")
            val worker = Thread {
                try {
                    holder[0] = proc.inputStream.bufferedReader().readText().trim()
                } catch (_: Throwable) { /* ignore */ }
                try {
                    proc.waitFor()
                } catch (_: Throwable) { /* ignore */ }
            }.apply { isDaemon = true }
            worker.start()
            worker.join(TIMEOUT_MS)
            if (worker.isAlive) {
                try { proc.destroy() } catch (_: Throwable) { /* ignore */ }
                Log.w(TAG, "shizuku delete timed out for '$path'")
                return false
            }

            val out = holder[0]
            val ok = out.contains("OK") && !out.contains("EX")
            Log.d(TAG, "shizuku delete '$path' -> out='$out' ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "deleteViaShizuku failed", t)
            false
        }
    }

    /**
     * Shizuku.newProcess() помечен @hide в shizuku-api, но доступен рефлексией —
     * это официально задокументированный способ выполнять shell-команды.
     * Возвращает [Process] (ShizukuRemoteProcess его наследует) или null.
     */
    private fun newProcess(cmd: Array<String>): Process? {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            Log.w(TAG, "newProcess reflection failed", t)
            null
        }
    }
}
