package com.uzguard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **СИНТЕТИЧЕСКИЙ генератор «тестовых вирусов»** — НЕ ВРЕДОНОСНЫЙ КОД.
 *
 * Создаёт обычные ZIP-файлы с расширением .apk, наполненные триггер-строками
 * нашего собственного сканера. Они НЕ устанавливаются как реальные приложения
 * (нет валидного AndroidManifest и подписи), и единственное что делают — это
 * заставляют наш `ApkScanner` сработать на разных слоях обнаружения.
 *
 * Используется в `DiagnosticsActivity` → кнопка "🧪 Test scanner": генерирует
 * 6 samples в cacheDir, прогоняет каждый через `ApkScanner.scan()`, выдаёт отчёт.
 * Можно убедиться что сканер действительно ловит каждый профиль.
 *
 * Профили:
 *   1. Filename-only (RASMLAR (99).apk) — пустой ZIP с подозрительным именем
 *   2. Double extension (VIDEO.01.01.2026.mp4.apk) — двойное расширение + Telegram-pattern
 *   3. Dropper (hidden APK) — APK с APK-magic в assets/payload.apk
 *   4. Dropper (hidden ELF in .png) — ELF magic в assets/icon.png
 *   5. DEX patterns — classes.dex с банкер-маркерами
 *   6. IOC token match — текст с известным C2 доменом (elrxzx.com)
 */
object TestVirusGenerator {

    /** APK ZIP magic = "PK\x03\x04". */
    private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    /** ELF magic = "\x7FELF". */
    private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    /** DEX magic = "dex\n035\0" (035 — версия DEX). */
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)

    data class TestSample(
        val name: String,
        val expectedVerdict: ScanResult.Verdict,
        val profile: String,
        val path: String,
    )

    /** Создаёт все 6 sample APK в `cacheDir/test_virus/`. Возвращает их список. */
    fun generateAll(context: Context): List<TestSample> {
        val dir = File(context.cacheDir, "test_virus").apply {
            // Очищаем предыдущий запуск
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val out = mutableListOf<TestSample>()

        // 1) Filename-only: RASMLAR (99).apk — пустой ZIP с триггер-именем.
        out += build(dir, "RASMLAR (99).apk", ScanResult.Verdict.SUSPICIOUS, "Filename: Telegram-banker template") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        }

        // 2) Double extension + Telegram-pattern.
        out += build(dir, "VIDEO.01.01.2026.mp4.apk", ScanResult.Verdict.SUSPICIOUS, "Filename: double extension + VIDEO.DD.MM.YYYY") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        }

        // 3) Dropper: APK внутри APK.
        out += build(dir, "dropper_hidden_apk.apk", ScanResult.Verdict.DANGER, "Dropper: hidden APK in assets") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            // assets/payload.apk = ZIP-magic + немного байтов чтобы entry.size > 4
            zip.addBytes("assets/payload.apk", APK_MAGIC + ByteArray(100))
        }

        // 4) Dropper: ELF под видом .png (классический trick).
        out += build(dir, "dropper_elf_in_png.apk", ScanResult.Verdict.DANGER, "Dropper: ELF disguised as .png") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            zip.addBytes("assets/icon.png", ELF_MAGIC + ByteArray(100))
        }

        // 5) DEX patterns — classes.dex с банкер-маркерами как substring'ами в байтах.
        //    Реальный DEX это binary format, но наш DexPatternAnalyzer ищет substring'ы
        //    в ISO-8859-1 строке, поэтому правильное содержимое — это просто текст
        //    с триггер-словами, обёрнутый в DEX magic header.
        out += build(dir, "dex_banker_signals.apk", ScanResult.Verdict.DANGER, "DEX: banker API + anti-analysis markers") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            val dexContent = buildString {
                // Несколько high-score паттернов из DexPatternAnalyzer:
                append("Ldalvik/system/DexClassLoader;\n")
                append("Ldalvik/system/InMemoryDexClassLoader;\n")
                append("Landroid/telephony/SmsManager;->sendTextMessage\n")
                append("Landroid/telephony/TelephonyManager;->getDeviceId\n")
                append("android.provider.Telephony.SMS_RECEIVED\n")
                append("abortBroadcast\n")
                append("api.telegram.org/bot\n")
                // Anti-analysis:
                append("frida-server\n")
                append("com.topjohnwu.magisk\n")
                // Banker overlay:
                append("TYPE_APPLICATION_OVERLAY\n")
                append("AccessibilityEvent;->getText\n")
                append("Landroid/media/projection/MediaProjection;\n")
                // Padding до правдоподобного размера
                append("// padding ".repeat(500))
            }
            zip.addBytes("classes.dex", DEX_MAGIC + dexContent.toByteArray())
        }

        // 6) IOC token-hash match: текст с известным C2-доменом из README §10.
        out += build(dir, "ioc_c2_domain.apk", ScanResult.Verdict.DANGER, "IOC: C2 domain (elrxzx.com)") { zip ->
            zip.addText("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            // ObfuscatedSignatures.matchTokenHashes ловит токен `elrxzx.com` в любой ZIP entry.
            zip.addText("assets/config.txt", "server=elrxzx.com\nbackup=ydbllnjd.com\n")
        }

        return out
    }

    /** Скан-сюита: генерируем, прогоняем каждый, формируем отчёт. */
    fun runAndReport(context: Context): String {
        val samples = generateAll(context)
        val sb = StringBuilder()
        sb.appendLine("=== TEST SCANNER REPORT ===")
        sb.appendLine("Samples: ${samples.size}")
        sb.appendLine()

        var passed = 0
        var failed = 0

        for ((i, sample) in samples.withIndex()) {
            sb.appendLine("[${i + 1}] ${sample.name}")
            sb.appendLine("  Profile:  ${sample.profile}")
            sb.appendLine("  Expected: ${sample.expectedVerdict}")

            val result = try {
                // ScanCache.clear ensures each test runs fresh
                ScanCache.clear(context)
                ApkScanner.scan(context, sample.path)
            } catch (e: Throwable) {
                sb.appendLine("  ❌ Scan crashed: ${e.message}")
                failed++
                continue
            }

            sb.appendLine("  Actual:   ${result.verdict}")
            sb.appendLine("  Reason:   ${result.reason.take(80)}")
            if (result.details.isNotEmpty()) {
                sb.appendLine("  Details:")
                for (d in result.details.take(3)) {
                    sb.appendLine("    • ${d.take(80)}")
                }
            }

            val ok = result.verdict == sample.expectedVerdict ||
                // Допускаем «строже чем ожидали» — DANGER при ожидании SUSPICIOUS норм:
                (sample.expectedVerdict == ScanResult.Verdict.SUSPICIOUS &&
                    result.verdict == ScanResult.Verdict.DANGER)

            if (ok) {
                sb.appendLine("  ✅ PASS")
                passed++
            } else {
                sb.appendLine("  ❌ FAIL — verdict mismatch")
                failed++
            }
            sb.appendLine()
        }

        sb.appendLine("───────────────────────────")
        sb.appendLine("Total: ${samples.size}  ✅ $passed  ❌ $failed")
        return sb.toString()
    }

    // ─────────── private helpers ───────────

    private inline fun build(
        dir: File,
        name: String,
        expected: ScanResult.Verdict,
        profile: String,
        build: (ZipOutputStream) -> Unit,
    ): TestSample {
        val f = File(dir, name)
        ZipOutputStream(FileOutputStream(f)).use { zip ->
            build(zip)
        }
        return TestSample(
            name = name,
            expectedVerdict = expected,
            profile = profile,
            path = f.absolutePath,
        )
    }

    private fun ZipOutputStream.addText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.addBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
