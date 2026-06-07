package com.kiberqalqon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ZipEncryptionDetector] — loyihaning bosh AV-evaziyasi (GP-bit "soxta shifrlash") qo'riqchisi.
 * Bayt-ofset mantig'i regressiyasi jim `encryptedCount=0` qaytarib, false-SAFE berishi mumkin
 * edi (ZIP-evaziya boshqa ZipFile-analizatorlarni neytrallaydi). Shu sabab unit-test bilan
 * qotiramiz: LFH-faqat / CD-faqat / ikkalasi / toza / data-descriptor (DET-03) holatlari.
 */
class ZipEncryptionDetectorTest {

    private val LFH = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val CDH = byteArrayOf(0x50, 0x4B, 0x01, 0x02)

    // ---- yordamchilar ----

    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(e); zos.write(data); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** DEFLATED — ZipOutputStream LFH'da compSize=0 + GP-bit-3 (data descriptor) qo'yadi (DET-03 yo'li). */
    private fun deflatedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun allPositions(buf: ByteArray, sig: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i <= buf.size - sig.size) {
            var ok = true
            for (j in sig.indices) if (buf[i + j] != sig[j]) { ok = false; break }
            if (ok) out.add(i)
            i++
        }
        return out
    }

    /** LFH GP-flag (ofset +6) bit-0 ni o'rnatadi. nth=null → hammasi, aks holda nth-chi LFH. */
    private fun setLfhEncrypted(bytes: ByteArray, nth: Int? = null): ByteArray {
        val out = bytes.copyOf()
        val pos = allPositions(out, LFH)
        val targets = if (nth == null) pos else listOf(pos[nth])
        for (p in targets) out[p + 6] = (out[p + 6].toInt() or 0x01).toByte()
        return out
    }

    /** CDH GP-flag (ofset +8) bit-0 ni o'rnatadi (barcha CD yozuvlari). */
    private fun setCdhEncrypted(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        for (p in allPositions(out, CDH)) out[p + 8] = (out[p + 8].toInt() or 0x01).toByte()
        return out
    }

    private fun analyze(bytes: ByteArray): ZipEncryptionDetector.Findings {
        val f = File.createTempFile("kqzip", ".apk")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return try {
            ZipEncryptionDetector.analyze(f.absolutePath)
        } finally {
            f.delete()
        }
    }

    private fun entries() = arrayOf(
        "AndroidManifest.xml" to "AAAAAAAAAAAAAAAAAAAAAAAA".toByteArray(),
        "classes.dex" to "BBBBBBBBBBBBBBBBBBBBBBBB".toByteArray(),
    )

    // ---- testlar ----

    @Test fun cleanStoredApk_isNotEncrypted() {
        val r = analyze(storedZip(*entries()))
        assertFalse("toza APK shifrlangan deb belgilanmasligi kerak", r.hasEncrypted)
        assertEquals(0, r.encryptedCount)
    }

    @Test fun cleanDeflatedApk_withDataDescriptors_isNotEncrypted() {
        // Zamonaviy APK'lar deyarli hammasi DEFLATED + data descriptor (LFH compSize=0). DET-03
        // oldinga-skani bularni shifrlangan deb XATO belgilamasligi shart.
        val r = analyze(deflatedZip(*entries()))
        assertFalse("oddiy DEFLATED APK false-positive bermasligi kerak", r.hasEncrypted)
    }

    @Test fun lfhOnlyEncryptionFlag_isDetected() {
        val r = analyze(setLfhEncrypted(storedZip(*entries())))
        assertTrue("LFH-faqat GP-bit aniqlanishi kerak", r.hasEncrypted)
    }

    @Test fun cdOnlyEncryptionFlag_isDetected() {
        // TAKLIFNOMA uslubi: flag faqat central directory'da.
        val r = analyze(setCdhEncrypted(storedZip(*entries())))
        assertTrue("CD-faqat GP-bit aniqlanishi kerak", r.hasEncrypted)
    }

    @Test fun bothHeadersEncryptionFlag_isDetected() {
        var b = storedZip(*entries())
        b = setLfhEncrypted(b); b = setCdhEncrypted(b)
        val r = analyze(b)
        assertTrue(r.hasEncrypted)
    }

    @Test fun lateLfhFlagWithDataDescriptors_isDetected_DET03() {
        // DET-03 aniq holati: 1-yozuv toza (lekin data descriptor → LFH compSize=0), 2-yozuv
        // LFH'da bit-0, CD toza. Eski kod 1-yozuvda sikldan uzilib, 2-yozuvni o'tkazib yuborardi
        // (false-SAFE). Oldinga-skan bilan endi tutiladi.
        val z = deflatedZip(*entries())
        val lfhCount = allPositions(z, LFH).size
        // ikkita yozuv → ikkita LFH; aks holda test sharti buzilgan (deflate ichida soxta imzo).
        org.junit.Assume.assumeTrue("test ZIP'da aynan 2 ta LFH bo'lishi kutilgan", lfhCount == 2)
        val r = analyze(setLfhEncrypted(z, nth = 1)) // faqat 2-LFH
        assertTrue("kechki LFH GP-bit (data descriptor bilan) aniqlanishi kerak", r.hasEncrypted)
    }
}
