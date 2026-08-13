package com.uzguard

import android.util.Log
import java.security.MessageDigest
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

    // Ikkinchi-bosqich zond (second-stage probe) uchun XOR kaliti — shu malware oilasida
    // ishlatiladigan ma'lum kalit (ObfuscatedSignatures.XOR_KEY bilan bir xil). 0x00 = passthrough (raw).
    private const val XOR_KEY_5A = 0x5A
    private const val PROBE_CAP = 20L * 1024 * 1024   // ≤20MB — zond faqat shu chegaragacha ishlaydi

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
        /**
         * Ikkinchi-bosqich zondi (XOR 0x5A / passthrough / Base64) dekodlaganda ICHIDA haqiqiy
         * APK/DEX/ELF magic topilgan payload nomlari. Bular "shubhali konteyner"'dan qat'iy
         * yashirin-APK/DEX/ELF DANGER signaliga KO'TARILADI (ApkScanner reason: "dropper:xor_payload").
         */
        val xorPayloads: List<String> = emptyList(),
        /**
         * Dekodlangan ichki payload'larning SHA-256'lari (lowercase hex) — [ApkScanner] ularni
         * mavjud [MaliciousHashes]/[ThreatDb] IOC bazasidan o'tkazadi (ichki-hash mosligi = DANGER).
         */
        val innerHashes: List<String> = emptyList(),
    )

    private enum class InnerKind { APK, DEX, ELF }
    private data class Probe(val kind: InnerKind, val sha256: String?)

    fun analyze(apkPath: String): Findings {
        val hiddenApks = mutableListOf<String>()
        val hiddenDex = mutableListOf<String>()
        val hiddenElf = mutableListOf<String>()
        val soOutsideLib = mutableListOf<String>()
        val encryptedPayloads = mutableListOf<String>()
        val xorPayloads = mutableListOf<String>()
        val innerHashes = mutableListOf<String>()

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
                                    // Ikkinchi-bosqich zond: XOR 0x5A / passthrough / Base64 dekodlab,
                                    // ichida haqiqiy APK/DEX/ELF magic bo'lsa — "shubhali konteyner"'ni
                                    // qat'iy yashirin-payload DANGER signaliga ko'taramiz.
                                    probeSecondStage(zip, entry)?.let { probe ->
                                        when (probe.kind) {
                                            InnerKind.APK -> if (name !in hiddenApks) hiddenApks.add(name)
                                            InnerKind.DEX -> if (name !in hiddenDex) hiddenDex.add(name)
                                            InnerKind.ELF -> if (name !in hiddenElf) hiddenElf.add(name)
                                        }
                                        if (name !in xorPayloads) xorPayloads.add(name)
                                        probe.sha256?.let { if (it !in innerHashes) innerHashes.add(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Dropper analysis failed", e)
        }

        // encryptedPayloads = TASDIQLANMAGAN yuqori-entropiya (XOR-zond ichidan haqiqiy magic
        // TOPMAGAN) — maslahat signal. Vaznini 60→18 ga tushirdik: legit ilovalarda ML-model /
        // shifrlangan config / siqilgan asset ko'p bo'lib, 2 ta topilsa 120 ball bilan yakka
        // o'zi threshold'dan o'tib DANGER berardi (Telegram FP). Haqiqiy XOR-dropper baribir
        // xorPayloads orqali hiddenDex/hiddenApk (40 ball, TIER-1 hard) ga ko'tariladi.
        val score = (hiddenApks.size + hiddenDex.size + hiddenElf.size + soOutsideLib.size) * 40 +
                encryptedPayloads.size * 18

        return Findings(
            score = score,
            hiddenApks = hiddenApks,
            hiddenDex = hiddenDex,
            hiddenElf = hiddenElf,
            soOutsideLib = soOutsideLib,
            encryptedPayloads = encryptedPayloads,
            xorPayloads = xorPayloads,
            innerHashes = innerHashes,
        )
    }

    /**
     * CHEAP ikkinchi-bosqich zond. FAQAT allaqachon yuqori-entropiyali (shifrlangan) deb belgilangan
     * payload uchun chaqiriladi. Hard cap'lar: entry ≤ [PROBE_CAP] (20MB), rekursiya YO'Q (1 daraja),
     * O(payload). Uchta transformni sinaydi: XOR 0x5A, passthrough (XOR 0x00 = xom), Base64-decode.
     * Dekodlangan birinchi baytlarda APK(PK\x03\x04)/DEX("dex\n")/ELF magic bo'lsa — [Probe] qaytaradi
     * (kind + to'liq dekodlangan payload SHA-256). Aks holda null. Hech qachon throw qilmaydi.
     */
    private fun probeSecondStage(zip: ZipFile, entry: java.util.zip.ZipEntry): Probe? {
        val size = entry.size
        if (size <= 4 || size > PROBE_CAP) return null
        return try {
            val raw = readAllBounded(zip, entry) ?: return null
            if (raw.size < 4) return null
            val headLen = minOf(8, raw.size)

            // 1) XOR 0x5A — DEOBFUSKATSIYA. Bosh baytlarni arzon transformlab magic tekshiramiz;
            //    mos bo'lsagina to'liq (bir o'tishli) XOR + SHA-256 hisoblaymiz. Dekodlangan magic
            //    mosligi yuqori ishonchli (tasodif ~1/2^32) — legit yuqori-entropiya asset'da bo'lmaydi.
            innerKindOf(xorBytes(raw, XOR_KEY_5A, headLen))?.let { kind ->
                return Probe(kind, sha256Hex(xorBytes(raw, XOR_KEY_5A)))
            }
            // 2) Base64-decode — payload ASCII base64 bo'lsa. Binar/yaroqsiz base64 → exception → null.
            val decoded = try {
                android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                null
            }
            if (decoded != null && decoded.size >= 4) {
                innerKindOf(decoded.copyOf(minOf(8, decoded.size)))?.let { kind ->
                    return Probe(kind, sha256Hex(decoded))
                }
            }
            // 3) Passthrough (XOR 0x00 = xom) — xom magic. DEX/ELF xom magic asosiy `when`da ushlanadi
            //    (bu yerga yetmaydi). APK (PK..) xom magic esa legit data-zip (masalan tzdata distro.zip)
            //    bo'lishi mumkin — shuning uchun asosiy yo'ldagi AYNAN SHU looksLikeEmbeddedApk guard'ini
            //    qo'llaymiz (ichida AndroidManifest.xml/classes.dex bo'lsagina haqiqiy embedded APK).
            //    Aks holda legit data-zip noto'g'ri qat'iy DANGER bo'lardi (FP).
            innerKindOf(raw.copyOf(headLen))?.let { kind ->
                if (kind != InnerKind.APK || looksLikeEmbeddedApk(zip, entry)) {
                    return Probe(kind, sha256Hex(raw))
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** Entry'ni ≤[PROBE_CAP] baytgacha to'liq o'qiydi. Cap'dan katta/o'lchamsiz → null. */
    private fun readAllBounded(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray? {
        val size = entry.size
        if (size <= 0L || size > PROBE_CAP) return null
        val target = size.toInt()
        val buf = ByteArray(target)
        var off = 0
        return try {
            zip.getInputStream(entry).use { input ->
                while (off < target) {
                    val n = input.read(buf, off, target - off)
                    if (n <= 0) break
                    off += n
                }
            }
            if (off < 4) null else if (off == target) buf else buf.copyOf(off)
        } catch (_: Throwable) {
            null
        }
    }

    /** [src]'ning dastlabki [count] baytini [key] bilan XOR qiladi (yangi massiv qaytaradi). */
    private fun xorBytes(src: ByteArray, key: Int, count: Int = src.size): ByteArray {
        val n = minOf(count, src.size).coerceAtLeast(0)
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (src[i].toInt() xor key).toByte()
        return out
    }

    private fun innerKindOf(head: ByteArray): InnerKind? = when {
        head.startsWith(APK_MAGIC) -> InnerKind.APK
        head.startsWith(DEX_MAGIC) -> InnerKind.DEX
        head.startsWith(ELF_MAGIC) -> InnerKind.ELF
        else -> null
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
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
            ".mp3", ".mp4", ".m4a", ".m4v", ".ogg", ".opus", ".webm", ".aac", ".flac", ".wav",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".heic", ".bmp", ".ico",
            ".ttf", ".otf", ".woff", ".woff2",
            // Standart siqish konteynerlari — shifr EMAS, tabiiy yuqori entropiya.
            // ".gzip" avval yo'q edi (faqat ".gz") → Telegram assets/codelng.gzip (til
            // paketi) "shifrlangan payload" deb noto'g'ri belgilanardi (FP).
            ".zip", ".7z", ".gz", ".gzip", ".tgz", ".xz", ".bz2", ".br", ".zst", ".lz4",
            // ML-model / neyroset BINAR formatlari — o'z magic'i bor, payload yashira olmaydi;
            // legit ilovalarda tabiiy yuqori entropiyali. DIQQAT: ".dat"/".bin"/".model" ATAYIN
            // bu yerda YO'Q — ular ichida XOR-shifrlangan APK/DEX bo'lishi mumkin (vudgi tipidagi
            // dropper), shuning uchun entropiya+XOR-zond tekshiruvida qoladi.
            ".tflite", ".onnx",
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
                // DEFLATE-siqilgan entry uchun getInputStream InflaterInputStream qaytaradi:
                // birinchi read() inflater biror bayt chiqarishi bilan qaytadi va so'ralgandan
                // KAM bayt berishi mumkin (1-3 bayt). Bitta read()'ga tayanib < 4'da null qaytarish
                // yashiringan APK/DEX/ELF payload'ni butunlay o'tkazib yuborardi. Shu bois EOF yoki
                // kamida buf to'lguncha (8 bayt) sikl bilan o'qiymiz.
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                if (total < 4) null else buf
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
