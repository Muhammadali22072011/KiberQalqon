package com.uzguard

import com.uzguard.NewApkDetector.PathStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Покрываем логику "какие APK появились ВНОВЬ" во всех граничных случаях, которые
 * раньше ломали real-time защиту (окно не всплывало). Чистый JVM-тест: NewApkDetector
 * не трогает Android API (Build.VERSION.SDK_INT в юнит-тестах = 0).
 *
 * NOW — фиксированный "текущий момент" для детерминизма. Файлы датируем относительно него.
 */
class NewApkDetectorTest {

    private val NOW = 1_000_000_000L
    private val OLD = NOW - 60_000L          // стабильный (минута назад)
    private val FRESH = NOW - 500L           // ещё докачивается (<2s назад)

    // ── Сидирование: файлы, лежавшие до старта защиты, окон НЕ дают ──────────────

    @Test
    fun seed_marksExistingAsSeen_soNoWindowForOldFiles() {
        val seen = HashSet<String>()
        val existing = listOf(
            PathStamp("/sd/Download/old1.apk", OLD),
            PathStamp("/sd/Telegram/old2.apk", OLD),
        )
        NewApkDetector.seed(existing, seen)

        // Тот же список на следующем опросе — ничего нового.
        val res = NewApkDetector.pickNew(existing, seen, NOW)
        assertTrue("файлы, бывшие при старте, не считаются новыми", res.isEmpty())
    }

    // ── ГЛАВНЫЙ баг: backdated mtime (Telegram ставит дату отправителя) ──────────

    @Test
    fun pickNew_detectsFileWithBackdatedMtime() {
        val seen = HashSet<String>()
        // mtime в 2001 году — далеко в прошлом (как у пересланного через Telegram файла).
        val ancient = NOW - 700L * 24 * 3600 * 1000
        val res = NewApkDetector.pickNew(
            listOf(PathStamp("/sd/Telegram/virus.apk", ancient)),
            seen, NOW
        )
        assertEquals(listOf("/sd/Telegram/virus.apk"), res)
        assertTrue(seen.contains("/sd/Telegram/virus.apk"))
    }

    // ── Стабильность: свежий (возможно докачивающийся) файл откладывается ────────

    @Test
    fun pickNew_defersFreshFile_andDoesNotMarkSeen() {
        val seen = HashSet<String>()
        val res = NewApkDetector.pickNew(
            listOf(PathStamp("/sd/Download/half.apk", FRESH)),
            seen, NOW
        )
        assertTrue("свежий файл не показываем сразу", res.isEmpty())
        assertFalse("свежий файл НЕ помечен seen — чтобы поймать на след. опросе", seen.contains("/sd/Download/half.apk"))
    }

    @Test
    fun pickNew_catchesPreviouslyFreshFileOnceStable() {
        val seen = HashSet<String>()
        val path = "/sd/Download/dl.apk"

        // Опрос 1: файл только что появился (свежий) → отложен.
        val first = NewApkDetector.pickNew(listOf(PathStamp(path, NOW - 500L)), seen, NOW)
        assertTrue(first.isEmpty())

        // Опрос 2 (через ~15s): тот же файл теперь стабилен → пойман.
        val later = NOW + 15_000L
        val second = NewApkDetector.pickNew(listOf(PathStamp(path, NOW - 500L)), seen, later)
        assertEquals(listOf(path), second)
    }

    // ── Уже виденный путь не показывается повторно ───────────────────────────────

    @Test
    fun pickNew_ignoresAlreadySeenPath() {
        val seen = hashSetOf("/sd/Download/seen.apk")
        val res = NewApkDetector.pickNew(
            listOf(PathStamp("/sd/Download/seen.apk", OLD)),
            seen, NOW
        )
        assertTrue(res.isEmpty())
    }

    @Test
    fun pickNew_isIdempotent_secondCallReturnsNothing() {
        val seen = HashSet<String>()
        val list = listOf(PathStamp("/sd/Download/a.apk", OLD))
        assertEquals(1, NewApkDetector.pickNew(list, seen, NOW).size)
        assertTrue("повторный опрос того же файла — пусто", NewApkDetector.pickNew(list, seen, NOW).isEmpty())
    }

    // ── Несколько новых сразу ────────────────────────────────────────────────────

    @Test
    fun pickNew_returnsAllNewStableFiles() {
        val seen = HashSet<String>()
        val res = NewApkDetector.pickNew(
            listOf(
                PathStamp("/sd/Telegram/a.apk", OLD),
                PathStamp("/sd/WhatsApp/b.apk", OLD),
                PathStamp("/sd/Download/c.apk", OLD),
            ),
            seen, NOW
        )
        assertEquals(setOf("/sd/Telegram/a.apk", "/sd/WhatsApp/b.apk", "/sd/Download/c.apk"), res.toSet())
        assertEquals(3, seen.size)
    }

    // ── Смешанный случай: новый-стабильный + свежий + виденный ───────────────────

    @Test
    fun pickNew_mixed_returnsOnlyStableNew() {
        val seen = hashSetOf("/sd/Download/seen.apk")
        val res = NewApkDetector.pickNew(
            listOf(
                PathStamp("/sd/Download/seen.apk", OLD),   // уже виден
                PathStamp("/sd/Telegram/new.apk", OLD),    // новый стабильный → да
                PathStamp("/sd/Download/fresh.apk", FRESH) // свежий → отложить
            ),
            seen, NOW
        )
        assertEquals(listOf("/sd/Telegram/new.apk"), res)
        assertFalse("свежий не помечен seen", seen.contains("/sd/Download/fresh.apk"))
        assertTrue(seen.contains("/sd/Telegram/new.apk"))
    }

    // ── Пустой список / границы ──────────────────────────────────────────────────

    @Test
    fun pickNew_emptyList_returnsEmpty_noCrash() {
        assertTrue(NewApkDetector.pickNew(emptyList(), HashSet(), NOW).isEmpty())
    }

    @Test
    fun pickNew_exactlyAtGuardBoundary_isStable() {
        val seen = HashSet<String>()
        // now - mtime == FRESH_GUARD_MS → НЕ "< guard" → считается стабильным.
        val res = NewApkDetector.pickNew(
            listOf(PathStamp("/sd/Download/edge.apk", NOW - NewApkDetector.FRESH_GUARD_MS)),
            seen, NOW
        )
        assertEquals(listOf("/sd/Download/edge.apk"), res)
    }
}
