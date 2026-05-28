package com.kiberqalqon

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
        var encrypted = 0
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
                        encrypted++
                        if (sample.size < 5 && nameLen in 1..512) {
                            val nameBytes = ByteArray(nameLen)
                            raf.seek(pos + 30)
                            if (raf.read(nameBytes) == nameLen) {
                                sample.add(String(nameBytes, Charsets.UTF_8))
                            }
                        }
                    }

                    val advance = 30L + nameLen + extraLen + compSize
                    if (advance <= 0) break
                    pos += advance
                }

                // === 2) Central directory scan ===
                // EOCD bo'shliq ichida (max 64KB tail-da) joylashgan. CD offset uni o'qib olamiz.
                val tailSize = minOf(len, 65_557L).toInt()  // EOCD + 64KB max comment
                val tailStart = (len - tailSize).coerceAtLeast(0L)
                raf.seek(tailStart)
                val tail = ByteArray(tailSize)
                raf.readFully(tail)
                val eocdOff = findLastSubarray(tail, EOCD_SIG)
                if (eocdOff >= 0) {
                    // EOCD layout: sig(4) ds_no(2) ds_cd(2) entries_this(2) entries_total(2)
                    //              cd_size(4) cd_offset(4) comment_len(2)
                    val cdSize = readUInt32LE(tail, eocdOff + 12)
                    val cdOffset = readUInt32LE(tail, eocdOff + 16)
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
                            encrypted++  // CD-only marker — alohida hisoblanadi
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
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ZIP scan failed", e)
        }
        return Findings(
            encryptedCount = encrypted,
            totalEntries = total,
            sampleNames = sample,
        )
    }

    private fun readUInt32LE(buf: ByteArray, off: Int): Long {
        val b0 = buf[off].toLong() and 0xFF
        val b1 = buf[off + 1].toLong() and 0xFF
        val b2 = buf[off + 2].toLong() and 0xFF
        val b3 = buf[off + 3].toLong() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun findLastSubarray(buf: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || buf.size < needle.size) return -1
        for (i in (buf.size - needle.size) downTo 0) {
            var ok = true
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
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
