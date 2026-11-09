package bluebit.fitcloud.native

import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class P6SessionCipherTest {

    /** Hardware-verified identity used throughout the P6 research. */
    private val identity = P6Identity(userId = "26544037", macAddress = "90:EB:99:D6:5F:99")

    @Test
    fun `crypt is self inverse for assorted lengths and sequence ids`() {
        val cipher = P6KeyDerivation.derive(identity)!!
        val random = Random(0xF0D3)
        for (length in intArrayOf(1, 5, 15, 16, 17, 32, 37, 100, 1024)) {
            for (sequenceId in intArrayOf(0, 1, 2, 0xA5A5)) {
                val payload = ByteArray(length).also { random.nextBytes(it) }
                val encrypted = cipher.crypt(payload, sequenceId)
                val decrypted = cipher.crypt(encrypted, sequenceId)
                assertEquals(
                    payload.toList(),
                    decrypted.toList(),
                    "propagated length=$length sn=$sequenceId",
                )
            }
        }
    }

    /**
     * The keystream is exactly one AES-256 block: encrypting a 16-byte all-zero
     * payload must equal `AES-ECB-encrypt(key, iv' [14..15] = sn BE)`, verified
     * against the javax.crypto oracle (p334x9/b.java:476-516 algorithm).
     */
    @Test
    fun `keystream equals single aes block of the sn conditioned iv`() {
        val cipher = P6KeyDerivation.derive(identity)!!
        val key = P6KeyDerivation.sessionKey(
            P6KeyDerivation.parseMac(identity.macAddress)!!,
            P6KeyDerivation.userBlock(identity.userId),
        )
        val iv = P6KeyDerivation.sessionIv(P6KeyDerivation.parseMac(identity.macAddress)!!)

        for (sequenceId in intArrayOf(0, 1, 0x0102, 0xF0F1)) {
            val counter = iv.copyOf().also {
                it[14] = ((sequenceId shr 8) and 0xFF).toByte()
                it[15] = (sequenceId and 0xFF).toByte()
            }
            val oracle = Cipher.getInstance("AES/ECB/NoPadding")
            oracle.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val expectedKeystream = oracle.doFinal(counter)

            val encryptedZeroBlock = cipher.crypt(ByteArray(16), sequenceId)
            assertEquals(expectedKeystream.toList(), encryptedZeroBlock.toList(), "sn=$sequenceId")
        }
    }

    /** The transform repeats the 16-byte keystream cyclically across the payload. */
    @Test
    fun `keystream repeats cyclically across the payload`() {
        val cipher = P6KeyDerivation.derive(identity)!!
        val keystream = cipher.crypt(ByteArray(16), 7)
        val zeros = ByteArray(48)

        val encrypted = cipher.crypt(zeros, 7)
        for (block in 0 until 3) {
            assertEquals(
                keystream.toList(),
                encrypted.copyOfRange(block * 16, block * 16 + 16).toList(),
                "keystream block replay at block $block",
            )
        }
    }

    @Test
    fun `counter bytes land in iv positions 14 and 15 big endian`() {
        // Different SNs must give different ciphertext for the same plaintext.
        val cipher = P6KeyDerivation.derive(identity)!!
        val zeros = ByteArray(32)
        assertTrue(
            !cipher.crypt(zeros, 1).contentEquals(cipher.crypt(zeros, 2)),
            "SN must influence the keystream via iv[14..15]",
        )
        assertEquals(
            cipher.crypt(zeros, 0x00FF).toList(),
            cipher.crypt(zeros, 0xFF).toList(),
            "SN is masked to 16 bits big-endian",
        )
    }

    @Test
    fun `rejects malformed key and iv`() {
        assertTrue(runCatching { P6SessionCipher(ByteArray(31), ByteArray(16)) }.isFailure)
        assertTrue(runCatching { P6SessionCipher(ByteArray(32), ByteArray(15)) }.isFailure)
    }
}
