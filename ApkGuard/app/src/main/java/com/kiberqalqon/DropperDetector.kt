package com.kiberqalqon

import android.util.Log
import java.util.zip.ZipFile

// Поиск ДРОППЕРОВ — APK, который содержит ВНУТРИ ещё один APK / DEX / .so для
// последующего извлечения и подгрузки.
//
// Классические места где прячут payload:
//   - assets/payload.apk и подобные — самое прямолинейное
//   - assets/data.bin или .dat — с magic-байтами APK/DEX/ELF внутри
//   - res/raw/... — то же самое
//   - lib/ за пределами стандартных ABI-подпапок (armeabi-v7a, arm64-v8a, x86, x86_64)
//   - META-INF/payload.dex — крайне подозрительно
//
// Проверяем magic numbers первых байт каждого подозрительного файла:
//   APK/ZIP:  PK\x03\x04   (50 4B 03 04)
//   DEX:      "dex\n"      (64 65 78 0A)
//   ELF .so:  0x7F "ELF"   (7F 45 4C 46)
//
// Score: каждая находка = 40 баллов (очень специфический признак, мало FP).
object DropperDetector {

    private const val TAG = "DropperDetector"

    private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)  // PK..
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A)  // dex\n
    private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)  // \x7FELF

    private val ALLOWED_SO_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "armeabi", "mips", "mips64")

    data class Findings(
        val score: Int,
        val hiddenApks: List<String>,
        val hiddenDex: List<String>,
        val hiddenElf: List<String>,
        val soOutsideLib: List<String>,
        /**
         * Yuqori entropiyali katta assets/raw fayllar — shifrlangan payload belgisi.
         * Masalan: assets/vudgi.json (552KB) Uzbek-dropper.vudgi'da — bu aslida
         * XOR-shifrlangan DEX/APK, runtime'da dekriptlanib yuklanadi.
         */
        val encryptedPayloads: List<String> = emptyList(),
    )

    fun analyze(apkPath: String): Findings {
        val hiddenApks = mutableListOf<String>()
        val hiddenDex = mutableListOf<String>()
        val hiddenElf = mutableListOf<String>()
        val soOutsideLib = mutableListOf<String>()
        val encryptedPayloads = mutableListOf<String>()

        try {
            ZipFile(apkPath).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || entry.size <= 4) continue
                    val name = entry.name

                    // Стандартные DEX в корне — ожидаемы, скипаем.
                    if (isExpectedRootDex(name)) continue
                    // AndroidManifest.xml тоже скипаем (это AXML, не plain XML).
                    if (name == "AndroidManifest.xml") continue
                    // Подписи и стандартные ресурсы — скипаем.
                    if (name.startsWith("META-INF/") && !name.endsWith(".dex") && !name.endsWith(".apk")) continue
                    // ENG-05: avval res/raw/'dan boshqa BARCHA res/ (res/drawable, res/mipmap...) magic
                    // tekshiruvidan OLDIN tashlanardi — payload-DEX/APK/ELF'ni res/drawable/icon.png ga
                    // qo'yib hiddenApks/hiddenDex/hiddenElf (qattiq DANGER signallari)ni butunlay aylanib
                    // o'tish mumkin edi. Endi HAR res/ entry magic-baytlari tekshiriladi (assets/ kabi).
                    // Yuqori-entropy (shifrlangan) evristikasi esa isSuspectEncryptedPayload ichida
                    // baribir faqat assets/ + res/raw/ bilan cheklangan — FP oshmaydi.

                    // .so вне lib/{abi}/ — очень подозрительно
                    if (name.endsWith(".so") && !isInValidLib(name)) {
                        soOutsideLib.add(name)
                        continue
                    }

                    // Проверяем magic первых 4 байт
                    val magic = readMagic(zip, entry) ?: continue
                    when {
                        magic.startsWith(APK_MAGIC) && isPayloadLocation(name) &&
                                looksLikeEmbeddedApk(zip, entry) -> {
                            // PK.. в payload-локации — но ТОЛЬКО если это реально APK/JAR
                            // (содержит AndroidManifest.xml / *.dex) или имя кончается на .apk/.jar.
                            // Иначе обычный data-zip (напр. Samsung tzdata assets/distro.zip:
                            // distro_version/tzdata/icu_tzdata.dat/tzlookup.xml) давал false-positive.
                            hiddenApks.add(name)
                        }
                        magic.startsWith(DEX_MAGIC) -> {
                            // Любой DEX вне корневого classes*.dex — это payload.
                            hiddenDex.add(name)
                        }
                        magic.startsWith(ELF_MAGIC) && !name.endsWith(".so") -> {
                            // ELF под видом .png/.dat — классический dropper trick.
                            hiddenElf.add(name)
                        }
                        else -> {
                            // Shifrlangan payload tekshiruvi. Real hayotda: assets/vudgi.json
                            // (552KB Uzbek-dropper.vudgi'da) — XOR-shifrlangan DEX/APK.
                            // Magic bytes scramblelangan, lekin entropy ~8.0 ga yaqin.
                            // Real JSON/XML/text fayllarda entropy 4.5-5.5 oralig'ida.
                            if (isSuspectEncryptedPayload(name, entry.size)) {
                                if (looksHighEntropy(zip, entry)) {
                                    encryptedPayloads.add(name)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Dropper analysis failed", e)
        }

        val score = (hiddenApks.size + hiddenDex.size + hiddenElf.size + soOutsideLib.size) * 40 +
                encryptedPayloads.size * 60

        return Findings(
            score = score,
            hiddenApks = hiddenApks,
            hiddenDex = hiddenDex,
            hiddenElf = hiddenElf,
            soOutsideLib = soOutsideLib,
            encryptedPayloads = encryptedPayloads,
        )
    }

    /**
     * Fayl katta (>=100KB), media bo'lmagan kengaytma bilan, assets/ yoki res/raw/ da.
     * Real ilovalar bu shartda ko'pchilik fayllarni saqlamaydi.
     */
    private fun isSuspectEncryptedPayload(name: String, size: Long): Boolean {
        // #31: avval 100KB floor mayda XOR-shifrlangan ikkinchi bosqich DEX/loader'larni
        //      o'tkazib yuborardi (minimal dropper stage bir necha KB bo'lishi mumkin).
        //      8KB ga tushirildi — media-kengaytma filtri + >=7.5 entropy testi FP'ni baribir bo'g'adi.
        if (size < 8 * 1024) return false
        if (!name.startsWith("assets/") && !name.startsWith("res/raw/")) return false
        val lower = name.lowercase()
        // Media va shrift fayllarida tabiiy yuqori entropy bo'ladi — chetlab o'tamiz.
        for (ext in arrayOf(
            ".mp3", ".mp4", ".m4a", ".m4v", ".ogg", ".opus", ".webm", ".aac",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".heic",
            ".ttf", ".otf", ".woff", ".woff2",
            ".zip", ".7z", ".gz", ".xz", ".bz2",
            ".so", ".dex", ".apk",  // bularni boshqa tekshiruvlar topadi
            ".pdf",
        )) {
            if (lower.endsWith(ext)) return false
        }
        return true
    }

    /**
     * Shannon entropy bayt bo'yicha. Faylning birinchi 64KB sini o'qiymiz — bu
     * deflate-compressed DEX/APK uchun ham, raw XOR-payload uchun ham vakil.
     * 7.5+ = juda yuqori (shifrlangan/siqilgan); JSON/XML ~4.5, text ~4.0.
     */
    private fun looksHighEntropy(zip: ZipFile, entry: java.util.zip.ZipEntry): Boolean {
        val sample = 64 * 1024
        val readLen = entry.size.coerceAtMost(sample.toLong()).toInt()
        val buf = ByteArray(readLen)
        var off = 0
        return try {
            zip.getInputStream(entry).use { input ->
                while (off < readLen) {
                    val n = input.read(buf, off, readLen - off)
                    if (n <= 0) break
                    off += n
                }
            }
            if (off < 1024) return false  // juda kichik — ishonchli o'lchab bo'lmaydi
            val data = if (off == readLen) buf else buf.copyOf(off)
            shannonByteEntropy(data) >= 7.5
        } catch (_: Throwable) {
            false
        }
    }

    private fun shannonByteEntropy(data: ByteArray): Double {
        val counts = IntArray(256)
        for (b in data) counts[b.toInt() and 0xFF]++
        val n = data.size.toDouble()
        var h = 0.0
        val ln2 = kotlin.math.ln(2.0)
        for (c in counts) {
            if (c == 0) continue
            val p = c / n
            h -= p * (kotlin.math.ln(p) / ln2)
        }
        return h
    }

    private fun isExpectedRootDex(name: String): Boolean {
        if (!name.endsWith(".dex")) return false
        // classes.dex, classes2.dex .. classes9.dex в корне — multidex.
        return name.matches(Regex("^classes\\d*\\.dex$"))
    }

    private fun isInValidLib(name: String): Boolean {
        if (!name.startsWith("lib/")) return false
        val parts = name.split('/')
        if (parts.size < 3) return false  // lib/abi/file.so
        return parts[1] in ALLOWED_SO_ABIS
    }

    private fun isPayloadLocation(name: String): Boolean {
        return name.startsWith("assets/") ||
                name.startsWith("res/") ||   // ENG-05: barcha res/ (faqat res/raw/ emas)
                name.startsWith("META-INF/") ||
                !name.contains("/")  // root-level random file
    }

    /**
     * Embedded zip-entry с APK-magic (PK..) — это реально вложенный APK/JAR, а не
     * обычный data-zip? Проверяем по содержимому: APK ВСЕГДА содержит
     * AndroidManifest.xml и/или classes*.dex. Обычные data-zip (tzdata distro.zip,
     * asset-бандлы игр, .obb-подобные) — нет.
     *
     * Дополнительно: если имя явно .apk/.jar — считаем APK без вскрытия (на случай
     * вложенного ZIP-шифрованного payload, который мы не сможем распарсить).
     */
    private fun looksLikeEmbeddedApk(zip: ZipFile, entry: java.util.zip.ZipEntry): Boolean {
        val lower = entry.name.lowercase()
        if (lower.endsWith(".apk") || lower.endsWith(".jar")) return true
        // Слишком большой вложенный zip не буферизуем целиком — но всё равно стримим заголовки.
        return try {
            zip.getInputStream(entry).use { raw ->
                java.util.zip.ZipInputStream(raw).use { zin ->
                    var inner = zin.nextEntry
                    var guard = 0
                    while (inner != null && guard++ < 4000) {
                        val n = inner.name
                        if (n == "AndroidManifest.xml" || n.endsWith(".dex") ||
                            n.startsWith("classes") && n.endsWith(".dex")) {
                            return true
                        }
                        zin.closeEntry()
                        inner = zin.nextEntry
                    }
                }
            }
            false
        } catch (_: Throwable) {
            // Не читается как zip → это не вложенный APK (или зашифрован — но тогда имя
            // обычно .apk, что мы уже проверили выше). Консервативно: НЕ dropper.
            false
        }
    }

    private fun readMagic(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray? {
        return try {
            zip.getInputStream(entry).use { input ->
                val buf = ByteArray(8)
                val read = input.read(buf)
                if (read < 4) null else buf
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
