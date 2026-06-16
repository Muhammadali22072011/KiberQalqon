package com.uzguard

import android.util.Log
import java.io.RandomAccessFile

/**
 * APK ZIP-entry encryption flag (general-purpose bit 0) is set to mark an
 * entry as password-protected. Android's PackageManager IGNORES this flag —
 * it loads classes.dex / AndroidManifest.xml just fine even when the bit is
 * set. But Java's [java.util.zip.ZipFile] refuses to read encrypted entries.
 *
 * This asymmetry is the **classic AV-evasion trick** of Uzbek banker malware
 * (Ajina.Banker, "TAKLIFNOMA" family). Every analyzer in [ApkScanner] that
 * uses ZipFile (Manifest, DEX patterns, Dropper, ObfuscatedSignatures,
 * NativeLib) is silently neutered — entries throw on getInputStream → caught
 * → empty findings → score stays low → verdict = SAFE.
 *
 * Detection here is **direct byte scan** of the ZIP local file headers, NOT
 * via ZipFile. We look for the GP-flag bit-0 set on any entry. Legitimate
 * APKs NEVER use this flag — gradle/aapt2 never set it.
 *
 * Output is binary (any encrypted entry → DANGER) plus the count, so the
 * verdict reason can be precise.
 */
object ZipEncryptionDetector {

    private const val TAG = "ZipEncDetector"

    // Local file header signature: PK\003\004 (little-endian 0x04034b50).
    private val LFH_SIG = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    // Central directory file header signature: PK\001\002 (little-endian 0x02014b50).
    // MUHIM: Ba'zi virus'lar (TAKLIFNOMA = uzbekchill.com) faqat CENTRAL DIRECTORY'da
    // encryption flag o'rnatadi, lokal header'da emas. Java ZipFile CD'dan o'qiydi va
    // tashlab yuboradi; Android libziparchive lokal header'dan o'qib o'rnatadi.
    // Asimmetriya — to'liq antivirus chetlab o'tish hiylasi.
    private val CDH_SIG = byteArrayOf(0x50, 0x4B, 0x01, 0x02)

    // End of central directory record signature: PK\005\006
    private val EOCD_SIG = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    data class Findings(
        val encryptedCount: Int,
        val totalEntries: Int,
        val sampleNames: List<String>,
    ) {
        val hasEncrypted: Boolean get() = encryptedCount > 0
        val fractionEncrypted: Double
            get() = if (totalEntries == 0) 0.0 else encryptedCount.toDouble() / totalEntries
    }

    /**
     * Scans BOTH local file headers AND central directory headers for entries
     * with the GP-flag bit 0 (encryption) set. Java's ZipFile reads from CD,
     * Android's libziparchive reads from LFH — bugun ko'p AV-evasion virus'lari
     * faqat CD'da flag o'rnatib, asimmetriyani ekspluatatsiya qiladi.
     * Bounded by [maxHeaders] to keep runtime predictable on huge APKs.
     */
    fun analyze(apkPath: String, maxHeaders: Int = 5000): Findings {
        // #28/#30: LFH va CD bir XIL entry'ni ikki marta sanab, fractionEncrypted > 100%
        // ko'rsatardi. Endi alohida sanaymiz va max'ini olamiz (LFH-faqat yoki CD-faqat
        // flag bo'lsa ham musbat qoladi — aniqlash buzilmaydi).
        var lfhEncrypted = 0
        var cdEncrypted = 0
        var total = 0
        val sample = mutableListOf<String>()
        try {
            RandomAccessFile(apkPath, "r").use { raf ->
                val len = raf.length()

                // === 1) Local file headers (sequential scan from start) ===
                var pos = 0L
                val sig = ByteArray(4)
                while (pos + 30 < len && total < maxHeaders) {
                    raf.seek(pos)
                    if (raf.read(sig) != 4) break
                    if (!sig.contentEquals(LFH_SIG)) break  // CD reached or malformed
                    total++

                    raf.seek(pos + 6)
                    val gpFlag = raf.readUnsignedShort16LE()
                    raf.seek(pos + 18)
                    val compSize = raf.readUnsignedInt32LE()
                    raf.seek(pos + 26)
                    val nameLen = raf.readUnsignedShort16LE()
                    val extraLen = raf.readUnsignedShort16LE()

                    if ((gpFlag and 0x0001) != 0) {
                        lfhEncrypted++
                        if (sample.size < 5 && nameLen in 1..512) {
                            val nameBytes = ByteArray(nameLen)
                            raf.seek(pos + 30)
                            if (raf.read(nameBytes) == nameLen) {
                                sample.add(String(nameBytes, Charsets.UTF_8))
                            }
                        }
                    }

                    // DET-03: GP-bit-3 (data descriptor) bo'lsa compSize LFH'da 0 — oddiy advance
                    // data ichiga sakraydi, keyingi imzo LFH_SIG bo'lmay sikl uzilardi va keyingi
                    // (ehtimol flag'li) entry'lar o'tkazib yuborilardi. Bu holda keyingi LFH/CDH
                    // imzosini oldinga qidiramiz (CDH topilsa — LFH bosqichi tabiiy tugaydi).
                    val hasDataDescriptor = (gpFlag and 0x0008) != 0
                    val advance = 30L + nameLen + extraLen + compSize
                    if (hasDataDescriptor || compSize == 0L || advance <= 30L) {
                        val next = findNextHeaderSignature(raf, pos + 30L, len)
                        if (next < 0) break
                        pos = next
                    } else {
                        pos += advance
                    }
                }

                // === 2) Central directory scan ===
                // EOCD bo'shliq ichida (max 64KB tail-da). #15: hujumchi ZIP-comment ichiga
                // SOXTA ikkinchi EOCD qo'yib, CD-skanini haqiqiy (flag'li) CD'dan chetga
                // burishi mumkin (findLast eng oxirgi EOCD'ni topadi). Shuning uchun EOCD
                // nomzodlarini oxiridan boshlab kezamiz va FAQAT cd_offset'i haqiqiy CDH_SIG
                // (PK\x01\x02) ga ishora qiladigan EOCD'ni qabul qilamiz.
                val tailSize = minOf(len, 65_557L).toInt()  // EOCD + 64KB max comment
                val tailStart = (len - tailSize).coerceAtLeast(0L)
                raf.seek(tailStart)
                val tail = ByteArray(tailSize)
                raf.readFully(tail)
                val eocdCandidates = findAllSubarray(tail, EOCD_SIG)
                for (ci in eocdCandidates.indices.reversed()) {
                    val eocdOff = eocdCandidates[ci]
                    // EOCD layout: sig(4) ds_no(2) ds_cd(2) entries_this(2) entries_total(2)
                    //              cd_size(4) cd_offset(4) comment_len(2)
                    if (eocdOff + 20 > tail.size) continue
                    val cdSize = readUInt32LE(tail, eocdOff + 12)
                    val cdOffset = readUInt32LE(tail, eocdOff + 16)
                    if (cdOffset < 0 || cdOffset + 4 > len) continue
                    // Validatsiya: cd_offset haqiqiy CDH'ga ishora qilishi shart (spoof'ni rad etadi).
                    raf.seek(cdOffset)
                    if (raf.read(sig) != 4 || !sig.contentEquals(CDH_SIG)) continue

                    var cdPos = cdOffset
                    var cdScanned = 0
                    val cdEnd = (cdOffset + cdSize).coerceAtMost(len)
                    while (cdPos + 46 < cdEnd && cdScanned < maxHeaders) {
                        raf.seek(cdPos)
                        if (raf.read(sig) != 4) break
                        if (!sig.contentEquals(CDH_SIG)) break
                        cdScanned++
                        raf.seek(cdPos + 8)
                        val gpFlag = raf.readUnsignedShort16LE()
                        raf.seek(cdPos + 28)
                        val nameLen = raf.readUnsignedShort16LE()
                        val extraLen = raf.readUnsignedShort16LE()
                        val commentLen = raf.readUnsignedShort16LE()

                        if ((gpFlag and 0x0001) != 0) {
                            cdEncrypted++  // CD-only marker — alohida hisoblanadi
                            if (sample.size < 5 && nameLen in 1..512) {
                                val nameBytes = ByteArray(nameLen)
                                raf.seek(cdPos + 46)
                                if (raf.read(nameBytes) == nameLen) {
                                    val nm = String(nameBytes, Charsets.UTF_8)
                                    if (nm !in sample) sample.add(nm)
                                }
                            }
                        }
                        cdPos += 46L + nameLen + extraLen + commentLen
                    }
                    // CD'dagi total — eng ishonchli "fayllar soni" o'lchami.
                    if (cdScanned > total) total = cdScanned
                    break  // valid EOCD topildi — qidiruvni to'xtatamiz
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ZIP scan failed", e)
        }
        return Findings(
            encryptedCount = maxOf(lfhEncrypted, cdEncrypted),
            totalEntries = total,
            sampleNames = sample,
        )
    }

    /**
     * [from] dan boshlab keyingi LFH (PK\x03\x04) yoki CDH (PK\x01\x02) imzosini qidiradi.
     * Data-descriptor entry'lardan keyin LFH-skanini davom ettirish uchun (DET-03). -1 = topilmadi.
     */
    private fun findNextHeaderSignature(raf: RandomAccessFile, from: Long, len: Long): Long {
        var p = from.coerceAtLeast(0L)
        val chunk = ByteArray(8192)
        while (p + 4 <= len) {
            raf.seek(p)
            val n = raf.read(chunk)
            if (n < 4) break
            var i = 0
            while (i <= n - 4) {
                if (chunk[i] == 0x50.toByte() && chunk[i + 1] == 0x4B.toByte()) {
                    val c2 = chunk[i + 2]; val c3 = chunk[i + 3]
                    if ((c2 == 0x03.toByte() && c3 == 0x04.toByte()) ||
                        (c2 == 0x01.toByte() && c3 == 0x02.toByte())) {
                        return p + i
                    }
                }
                i++
            }
            // Imzo chunk chegarasida bo'linib qolmasligi uchun 3 bayt ustma-ust qoldiramiz.
            p += (n - 3).coerceAtLeast(1)
        }
        return -1
    }

    private fun readUInt32LE(buf: ByteArray, off: Int): Long {
        val b0 = buf[off].toLong() and 0xFF
        val b1 = buf[off + 1].toLong() and 0xFF
        val b2 = buf[off + 2].toLong() and 0xFF
        val b3 = buf[off + 3].toLong() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    /** Barcha mos joylashuvlar (o'sish tartibida). #15 spoof tekshiruvi uchun. */
    private fun findAllSubarray(buf: ByteArray, needle: ByteArray): List<Int> {
        if (needle.isEmpty() || buf.size < needle.size) return emptyList()
        val out = ArrayList<Int>()
        val last = buf.size - needle.size
        var i = 0
        while (i <= last) {
            var ok = true
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) out.add(i)
            i++
        }
        return out
    }

    private fun RandomAccessFile.readUnsignedShort16LE(): Int {
        val lo = read() and 0xFF
        val hi = read() and 0xFF
        return (hi shl 8) or lo
    }

    private fun RandomAccessFile.readUnsignedInt32LE(): Long {
        val b0 = read().toLong() and 0xFF
        val b1 = read().toLong() and 0xFF
        val b2 = read().toLong() and 0xFF
        val b3 = read().toLong() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}
