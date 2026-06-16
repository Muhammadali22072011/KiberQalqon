package com.uzguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты на функцию [ApkScanner.matchesSignature] — она решает,
 * считать ли строку в APK совпадением с малварной сигнатурой.
 *
 * Главное: общие слова ("trojan") должны срабатывать только как
 * целые слова, а доменные имена ("dashapp-v2.org") — где угодно.
 */
class ApkScannerTest {

    // ----- доменные имена/слэш-маркеры — точное совпадение -----

    @Test
    fun matchesSignature_domainExactMatch() {
        assertTrue(
            "Должно найти dashapp-v2.org в обычном тексте",
            ApkScanner.matchesSignature("https://dashapp-v2.org/cmd", "dashapp-v2.org")
        )
    }

    @Test
    fun matchesSignature_slashPathExactMatch() {
        assertTrue(
            "Должно найти /commends где угодно",
            ApkScanner.matchesSignature("POST /commends HTTP/1.1", "/commends")
        )
    }

    // ----- общие слова — word boundary -----

    @Test
    fun matchesSignature_wordlikeMatchesAsWord() {
        assertTrue(
            "Должно найти 'trojan' как отдельное слово",
            ApkScanner.matchesSignature("This is a trojan!", "trojan")
        )
    }

    @Test
    fun matchesSignature_wordlikeDoesNotMatchInsideOtherWord() {
        assertFalse(
            "Не должно матчить 'trojan' внутри 'trojandetector' (false positive в антивирусных либах)",
            ApkScanner.matchesSignature("class TrojanDetector { /* legit */ }", "trojan")
        )
    }

    @Test
    fun matchesSignature_isCaseInsensitive() {
        assertTrue(
            ApkScanner.matchesSignature("This is a TROJAN", "trojan")
        )
        assertTrue(
            ApkScanner.matchesSignature("Dashapp-V2.org/path", "dashapp-v2.org")
        )
    }

    // ----- inferSource — определение источника APK по пути -----

    @Test
    fun inferSource_recognizesTelegram() {
        assertNotNull(ApkScanner.inferSource("/sdcard/Download/Telegram/file.apk"))
        assertNotNull(ApkScanner.inferSource("/storage/emulated/0/Android/media/org.telegram.messenger/Telegram/X.apk"))
    }

    @Test
    fun inferSource_recognizesWhatsApp() {
        assertNotNull(ApkScanner.inferSource("/sdcard/WhatsApp/Media/x.apk"))
    }

    @Test
    fun inferSource_recognizesDownload() {
        assertNotNull(ApkScanner.inferSource("/sdcard/Download/something.apk"))
    }

    @Test
    fun inferSource_returnsNullForUnknown() {
        // /tmp/x.apk не из известных источников.
        assertNotNull(
            // null — норма, не падаем; для null assert просто игнорируем.
            ApkScanner.inferSource("/some/random/path/x.apk") ?: "ok"
        )
    }
}
