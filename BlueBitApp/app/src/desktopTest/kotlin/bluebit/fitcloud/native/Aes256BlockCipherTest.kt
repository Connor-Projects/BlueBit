package bluebit.fitcloud.native

import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Aes256BlockCipherTest {

    /**
     * FIPS-197 appendix C.3 AES-256 known-answer vector (published standard,
     * independent of the FitCloud APK). NOTE: the appendix C example vectors all
     * use the sequential key family with input 00112233...ee ff:
     * - C.1 AES-128: key 000102...0f, ciphertext 69c4e0d86a7b0430d8cdb78070b4c55a
     * - C.2 AES-192: key 000102...1617, ciphertext dda97ca4864cdfe06eaf70a0ec0d7191
     * - C.3 AES-256: key 000102...1f, ciphertext 8ea2b7ca516745bfeafc49904b496089
     *
     * (The 32-byte key 603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a3
     * 0914dff4 is the APPENDIX A.3 key-expansion ILLUSTRATION key, not an
     * appendix C vector; it must not be paired with 8ea2b7ca... — verifying the
     * expansion table of A.3 against it: w8=9ba35411, w9=8e6925af, to which our
     * implementation agrees, see the javax.crypto oracle test below.)
     */
    @Test
    fun `fips 197 appendix c3 aes 256 known answer`() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plaintext = hexToBytes("00112233445566778899aabbccddeeff")
        val expected = hexToBytes("8ea2b7ca516745bfeafc49904b496089")

        assertEquals(expected.toList(), Aes256BlockCipher.encryptBlock(key, plaintext).toList())
    }

    /**
     * The appendix A.3 expansion-illustration key is pinned here to document the
     * pairing trap: encrypting the appendix C input under 603deb...df f4 is a
     * DIFFERENT computation whose oracle-verified result is
     * d83414223d20a0c928b136c884d07ea2 (javax.crypto, JDK). Our implementation
     * must keep reproducing it; this is a machine-verified cross-check, not a
     * published C vector.
     */
    @Test
    fun `appendix a3 expansion illustration key oracle cross check`() {
        val key = hexToBytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val plaintext = hexToBytes("00112233445566778899aabbccddeeff")
        val expected = hexToBytes("d83414223d20a0c928b136c884d07ea2")

        assertEquals(expected.toList(), Aes256BlockCipher.encryptBlock(key, plaintext).toList())
    }

    /**
     * Oracle cross-check: identical results to the platform AES-256-ECB cipher
     * over deterministically generated random keys and blocks. This does not
     * encode any FitCloud assumption — it verifies the FIPS implementation
     * independent of the FitCloud derivation inputs.
     */
    @Test
    fun `matches javax crypto aes ecb oracle on random inputs`() {
        val oracle = Cipher.getInstance("AES/ECB/NoPadding")
        val random = Random(0x5A54) // fixed seed: deterministic across runs
        repeat(64) {
            val key = ByteArray(Aes256BlockCipher.KEY_SIZE).also { random.nextBytes(it) }
            val block = ByteArray(Aes256BlockCipher.BLOCK_SIZE).also { random.nextBytes(it) }

            oracle.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val expected = oracle.doFinal(block)

            assertEquals(expected.toList(), Aes256BlockCipher.encryptBlock(key, block).toList())
        }
    }

    @Test
    fun `rejects wrong sizes`() {
        val key = ByteArray(32)
        val block = ByteArray(16)
        assertTrue(
            runCatching { Aes256BlockCipher.encryptBlock(ByteArray(31), block) }.isFailure,
        )
        assertTrue(
            runCatching { Aes256BlockCipher.encryptBlock(key, ByteArray(15)) }.isFailure,
        )
    }

    private fun hexToBytes(text: String): ByteArray {
        check(text.length % 2 == 0)
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
