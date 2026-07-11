package com.uzguard

import android.util.Log
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.ln

/**
 * Эвристический анализ .so-библиотек внутри APK.
 *
 * Что ищем:
 *  1) Импорты подозрительных libc/Android-функций: dlopen, system, execve, ptrace, JNI_OnLoad+execve.
 *  2) High entropy секции — признак шифрованного payload, который грузится в рантайме.
 *  3) Совпадение с известными bad-string'ами (URL C2, packer-маркеры).
 *
 * Главное: чтение по чанкам, без распаковки всего .so в память (минимум 45КБ × 4 ABI).
 */
object NativeLibAnalyzer {

    private const val TAG = "NativeLibAnalyzer"
    private const val MAX_SO_SIZE = 5L * 1024 * 1024  // 5 MB на 1 lib
    private const val SAMPLE_SIZE = 256 * 1024        // первые 256 KB сэмпла достаточно

    /**
     * СИЛЬНЫЕ импорты — реально характерны для reverse-shell'ов, дропперов, анти-дебага.
     * Легитимные приложения почти никогда не запускают shell и не трейсят свой процесс.
     *
     * ВАЖНО: dlopen/dlsym/mprotect/JNI_OnLoad УБРАНЫ отсюда. Они есть в КАЖДОЙ native
     * библиотеке (Flutter, React Native, Unity, OpenSSL, Chrome, GMS, Instagram...).
     * Раньше "JNI_OnLoad + dlopen + dlsym" давало 3 хита → instant DANGER на любом
     * приложении с native-кодом. Это была главная причина false-positive'ов.
     */
    private val STRONG_IMPORTS = listOf(
        "execve", "execvp", "execlp",  // запуск shell-команд
        "/system/bin/sh", "/bin/sh",
        "/system/bin/su", "/data/local/tmp",
        "ptrace",                      // анти-дебаг / process injection
        "/proc/self/maps",             // self-inspection (anti-Frida/anti-debug)
    )

    /**
     * Известные безопасные .so — крупные движки/SDK, дающие шум при анализе.
     *
     * ВАЖНО (безопасность): имя .so внутри APK ПОЛНОСТЬЮ контролируется атакующим,
     * поэтому это НЕ повод пропускать анализ. Dropper мог бы переименовать свой
     * packed reverse-shell в libflutter.so и обойти детектор. Теперь мы анализируем
     * КАЖДУЮ .so, а этот список используется лишь для подавления СЛАБОЙ ветки
     * (2+ импорта без энтропии) у настоящих движков. Сильная комбинация
     * (execve/sh/ptrace + высокая энтропия) флагуется независимо от имени.
     */
    private val SAFE_LIB_NAMES = listOf(
        "libflutter.so", "libreactnativejni.so", "libhermes.so", "libjsc.so",
        "libv8", "libunity.so", "libil2cpp.so", "libmonochrome.so", "libchrome.so",
        "libwebviewchromium.so", "libcrashlytics", "libtensorflow", "libpytorch",
        "libfb.so", "libfolly", "libcronet", "libmmkv.so", "libtool-checker.so",
    )

    data class Findings(
        val suspiciousLibs: List<String>,
        val reasons: List<String>,
        /**
         * Очковый вклад в общий verdict-score. NB: native-находка БОЛЬШЕ не даёт
         * мгновенный DANGER (это была причина FP) — она лишь добавляет к total score,
         * которому нужно подтверждение от других сигналов.
         */
        val score: Int = 0
    )

    fun analyze(apkPath: String): Findings {
        val suspicious = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var score = 0

        try {
            ZipFile(apkPath).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".so", ignoreCase = true)) continue
                    // #32: avval >5MB .so'lar BUTUNLAY o'tkazib yuborilardi — dropper payload'ni
                    //      katta .so ichiga joylab, importlar+entropy tahlilidan qochishi mumkin edi.
                    // FIX: endi faqat birinchi 256KB emas, .so'ning bosh/o'rta/oxir oynalari
                    //      (MAX_SO_SIZE gacha) tekshiriladi — payload'ni 256KB'dan keyinga
                    //      joylab qochib bo'lmaydi. Har bir oyna bo'yicha entropiya alohida
                    //      hisoblanadi (max olinadi), importlar esa birlashtiriladi.
                    if (entry.isDirectory || entry.size <= 0) continue

                    val baseName = entry.name.substringAfterLast('/').lowercase()
                    // Имя .so подделываемо → НЕ пропускаем анализ. Используем только для
                    // подавления слабой ветки у настоящих движков (см. SAFE_LIB_NAMES).
                    val knownEngine = SAFE_LIB_NAMES.any { baseName == it || baseName.startsWith(it) }

                    val total = entry.size.coerceAtMost(MAX_SO_SIZE)
                    val windowLen = SAMPLE_SIZE.toLong()
                    // Bosh + oxir (+ o'rta, agar fayl yetarlicha katta bo'lsa) oynalari.
                    val offsets = linkedSetOf(0L)
                    if (total > windowLen) offsets.add((total - windowLen).coerceAtLeast(0L))
                    if (total > windowLen * 3) offsets.add((total / 2 - windowLen / 2).coerceAtLeast(0L))

                    val allHits = linkedSetOf<String>()
                    var maxEnt = 0.0
                    var readAny = false
                    for (ofs in offsets) {
                        val len = (total - ofs).coerceAtMost(windowLen).toInt()
                        if (len <= 0) continue
                        val data = readWindow(zip, entry, ofs, len) ?: continue
                        readAny = true
                        val text = String(data, Charsets.ISO_8859_1)
                        STRONG_IMPORTS.filterTo(allHits) { text.contains(it) }
                        val e = approximateEntropy(data)
                        if (e > maxEnt) maxEnt = e
                    }
                    if (!readAny) continue
                    val hits = allHits.toList()
                    val ent = maxEnt
                    val highEntropy = ent > 7.6   // 8.0 = равномерный шум (зашифровано/упаковано)

                    // Флаг ТОЛЬКО при реально подозрительной комбинации:
                    //   • 1+ сильный импорт (execve/ptrace/sh) + высокая энтропия (упакованный shell-runner)
                    //   • ИЛИ 2+ разных сильных импорта (shell-exec + анти-дебаг вместе)
                    // Легитимный OpenSSL/Glide/Flutter сюда не попадает: у них нет execve/ptrace,
                    // а dlopen/mprotect мы вообще не считаем.
                    // Сильную ветку (execve/sh + энтропия) флагуем ДАЖЕ для движковых имён —
                    // настоящий движок не бывает packed'ом с shell-exec; так закрываем bypass
                    // через переименование payload'а в libflutter.so и т.п.
                    when {
                        hits.isNotEmpty() && highEntropy -> {
                            suspicious.add(entry.name)
                            score += 45
                            reasons.add(
                                "Native ${entry.name}: shubhali import [${hits.joinToString(",")}] + yuqori entropiya (${"%.2f".format(ent)})"
                            )
                        }
                        hits.size >= 2 && !knownEngine -> {
                            suspicious.add(entry.name)
                            score += 40
                            reasons.add(
                                "Native ${entry.name}: shubhali importlar [${hits.joinToString(",")}]"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
        }
        // Бюджетируем вклад: даже несколько .so не должны в одиночку = DANGER.
        return Findings(suspicious, reasons, score.coerceAtMost(60))
    }

    /**
     * .so ichidan [offset]'dan boshlab [length] baytlik oyna o'qiydi.
     * ZIP oqimida seek yo'q, shuning uchun offset'gacha skip qilamiz (deflate bo'lsa ham
     * MAX_SO_SIZE bilan cheklangan). Xotira faqat bitta oyna hajmida bo'ladi.
     */
    private fun readWindow(zip: ZipFile, entry: ZipEntry, offset: Long, length: Int): ByteArray? {
        return try {
            zip.getInputStream(entry).use { input ->
                var toSkip = offset
                while (toSkip > 0) {
                    val s = input.skip(toSkip)
                    if (s > 0) {
                        toSkip -= s
                    } else {
                        // skip 0 qaytarsa — o'qib tashlab yuboramiz.
                        val chunk = ByteArray(minOf(toSkip, 8192L).toInt())
                        val r = input.read(chunk)
                        if (r <= 0) return null
                        toSkip -= r
                    }
                }
                val buf = ByteArray(length)
                var off = 0
                while (off < length) {
                    val n = input.read(buf, off, length - off)
                    if (n <= 0) break
                    off += n
                }
                when {
                    off == 0 -> null
                    off == length -> buf
                    else -> buf.copyOf(off)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Шенноновская энтропия на байтах, в битах. 0 = всё одинаково, 8 = равномерно случайно. */
    private fun approximateEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in data) counts[b.toInt() and 0xFF]++
        val n = data.size.toDouble()
        var h = 0.0
        for (c in counts) {
            if (c == 0) continue
            val p = c / n
            h -= p * (ln(p) / LN2)
        }
        return h
    }

    private val LN2 = ln(2.0)
}
