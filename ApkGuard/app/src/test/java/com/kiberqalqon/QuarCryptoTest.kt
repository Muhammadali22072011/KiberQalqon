package com.uzguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Karantin shifrlash yadrosi ([QuarCrypto]) testlari.
 *
 * Asosiy invariantlar:
 *  • encrypt → decrypt = asl baytlar (round-trip).
 *  • Shifrlangan baytlar diskda yaroqli ZIP/APK EMAS (PK magic yo'q).
 *  • CTR uzunlikni o'zgartirmaydi (sizeBytes hisoblari to'g'ri qoladi).
 *  • Har xil token → har xil keystream (IV unikal).
 */
class QuarCryptoTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val tokenA = "aabbccddeeff001122334455" // 12 bayt hex
    private val tokenB = "aabbccddeeff001122334456"

    /** Soxta APK: ZIP magic (PK) + payload. */
    private fun fakeApk(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(4096) { (it % 251).toByte() }

    private fun run(cipher: javax.crypto.Cipher, data: ByteArray): ByteArray = cipher.doFinal(data)

    @Test
    fun `encrypt then decrypt returns original bytes`() {
        val original = fakeApk()
        val encrypted = run(QuarCrypto.cipher(key, tokenA, encrypt = true), original)
        val decrypted = run(QuarCrypto.cipher(key, tokenA, encrypt = false), encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `encrypted payload is not a valid zip`() {
        val original = fakeApk()
        val encrypted = run(QuarCrypto.cipher(key, tokenA, encrypt = true), original)
        // PK\x03\x04 magic yo'qolgan bo'lishi shart — aks holda diskda "tirik" APK qoladi.
        val hasZipMagic = encrypted.size >= 4 &&
                encrypted[0] == 0x50.toByte() && encrypted[1] == 0x4B.toByte() &&
                encrypted[2] == 0x03.toByte() && encrypted[3] == 0x04.toByte()
        assertFalse("shifrlangan .quar ZIP magic bilan boshlanmasligi kerak", hasZipMagic)
        assertFalse(original.contentEquals(encrypted))
    }

    @Test
    fun `ctr keeps length unchanged`() {
        val original = fakeApk()
        val encrypted = run(QuarCrypto.cipher(key, tokenA, encrypt = true), original)
        assertEquals(original.size, encrypted.size)
    }

    @Test
    fun `different tokens produce different ciphertext`() {
        val original = fakeApk()
        val encA = run(QuarCrypto.cipher(key, tokenA, encrypt = true), original)
        val encB = run(QuarCrypto.cipher(key, tokenB, encrypt = true), original)
        assertFalse("bir xil kalit, har xil token → har xil keystream", encA.contentEquals(encB))
    }

    @Test
    fun `hex helpers roundtrip`() {
        val bytes = ByteArray(32) { (it * 11).toByte() }
        assertArrayEquals(bytes, QuarCrypto.hexToBytes(QuarCrypto.bytesToHex(bytes)))
    }

    @Test
    fun `cipher rejects short key`() {
        val short = ByteArray(16)
        assertTrue(runCatching { QuarCrypto.cipher(short, tokenA, encrypt = true) }.isFailure)
    }
}
