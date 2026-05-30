package com.kiberqalqon

/**
 * Чистая (без Android API) логика "какие APK появились ВНОВЬ с прошлого опроса".
 * Вынесена из [ProtectionService.startFastScanLoop] чтобы её можно было покрыть
 * JVM-юнит-тестами во всех граничных случаях (см. NewApkDetectorTest).
 *
 * Ключевое правило (исправление бага): новизна определяется по ПУТИ, а НЕ по
 * "времени изменения". Telegram/WhatsApp/Bluetooth сохраняют принятый файл с mtime
 * отправителя (часто в прошлом) — поэтому max-mtime watermark пропускал такие файлы
 * целиком. mtime используется ТОЛЬКО как guard стабильности (файл с mtime≈now ещё
 * может докачиваться — откладываем до следующего опроса), но никогда для решения
 * "новый ли он".
 */
object NewApkDetector {

    /** Файл, изменённый менее чем за это время до now, считаем ещё не докачанным. */
    const val FRESH_GUARD_MS = 2_000L

    data class PathStamp(val path: String, val lastModified: Long)

    /**
     * Помечает всё, что есть СЕЙЧАС, как уже виденное — вызывается один раз при старте,
     * чтобы не показывать окна для файлов, лежавших на устройстве до запуска защиты.
     */
    fun seed(current: List<PathStamp>, seen: MutableSet<String>) {
        for (ps in current) seen.add(ps.path)
    }

    /**
     * Возвращает пути, которые действительно НОВЫЕ, и добавляет их в [seen].
     * Путь считается новым ⇔ его нет в [seen] И он "стабилен"
     * (now - mtime ≥ [freshGuardMs]; backdated mtime → в прошлом → всегда стабилен).
     *
     * "Свежий" (возможно докачивающийся) файл НЕ добавляется в [seen] — он будет
     * повторно проверен на следующем опросе, когда докачается.
     */
    fun pickNew(
        current: List<PathStamp>,
        seen: MutableSet<String>,
        now: Long,
        freshGuardMs: Long = FRESH_GUARD_MS,
    ): List<String> {
        val newOnes = ArrayList<String>()
        for (ps in current) {
            if (seen.contains(ps.path)) continue
            if (now - ps.lastModified < freshGuardMs) continue // ещё пишется — отложить
            seen.add(ps.path)
            newOnes.add(ps.path)
        }
        return newOnes
    }
}
