package com.kiberqalqon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверяем определение sandboxed-папок Android/data/<pkg>/.
 * Это критично: для этих файлов нужно показать юзеру понятное сообщение
 * вместо пустого "не получилось".
 *
 * Тестируем [FileDeleter.sandboxOwnerFromPath] (чистый разбор строки), а НЕ
 * sandboxOwner(File): последний (а) гейтится по Build.VERSION.SDK_INT, который в
 * JVM-юнит-тесте всегда 0, и (б) на Windows нормализует "/storage/..." в
 * "C:\storage\..." — оба фактора ломали тест вне Android.
 */
class FileDeleterTest {

    @Test
    fun sandboxOwner_returnsTelegramForAndroidDataPath() {
        assertEquals(
            "org.telegram.messenger",
            FileDeleter.sandboxOwnerFromPath("/storage/emulated/0/Android/data/org.telegram.messenger/files/virus.apk")
        )
    }

    @Test
    fun sandboxOwner_returnsWhatsAppForAndroidDataPath() {
        assertEquals(
            "com.whatsapp",
            FileDeleter.sandboxOwnerFromPath("/storage/emulated/0/Android/data/com.whatsapp/files/X.apk")
        )
    }

    @Test
    fun sandboxOwner_nullForPublicDownload() {
        assertNull(FileDeleter.sandboxOwnerFromPath("/storage/emulated/0/Download/x.apk"))
    }

    @Test
    fun sandboxOwner_nullForAndroidMedia() {
        // /Android/media/<pkg>/ — НЕ sandboxed (media — особый случай в Android, доступен).
        assertNull(FileDeleter.sandboxOwnerFromPath("/storage/emulated/0/Android/media/org.telegram.messenger/file.apk"))
    }
}
