package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Key/IV derivation fixtures were computed by independent arithmetic following
 * spec 3.1 (uidBlock = UTF-8 truncated to 28 + zero pad to 32; constBlock =
 * 4 x {4D 4A 42 54 55 AA A5 5A}; key[i] = (i<6 ? mac[i] : 0) xor uidBlock[i] xor
 * constBlock[i]; iv = {D4 EF F0 D3 F4 E5 F0} + mac[0..5] + {A1 00 00}) for the
 * hardware-verified identity userId 26544037 / MAC 90:EB:99:D6:5F:99.
 */
class P6KeyDerivationTest {

    private val identity = P6Identity(userId = "26544037", macAddress = "90:EB:99:D6:5F:99")

    @Test
    fun `user block is utf8 bytes zero padded to 32`() {
        val expected = byteArrayOf(
            0x32, 0x36, 0x35, 0x34, 0x34, 0x30, 0x33, 0x37, // "26544037"
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
        ).map { it.toInt() }
        assertEquals(expected, P6KeyDerivation.userBlock("26544037").map { it.toInt() })
    }

    @Test
    fun `user block truncates utf8 to 28 bytes`() {
        val longUserId = "abcdefghijklmnopqrstuvwxyz0123" // 30 chars
        val block = P6KeyDerivation.userBlock(longUserId)
        assertEquals(32, block.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz01", block.decodeToString().trimEnd('\u0000'))
        // byte 28..31 stay zero: only the first 28 bytes participate
        for (index in 28..31) {
            assertEquals(0, block[index].toInt(), "padding at index $index")
        }
    }

    @Test
    fun `session key matches independent arithmetic for the verified identity`() {
        val macBytes = P6KeyDerivation.parseMac(identity.macAddress)!!
        val key = P6KeyDerivation.sessionKey(macBytes, P6KeyDerivation.userBlock(identity.userId))
        assertEquals(
            listOf(
                0xEF, 0x97, 0xEE, 0xB6, 0x3E, 0x03, 0x96, 0x6D, // mac-xored corner + const head
                0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA, 0xA5, 0x5A,
                0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA, 0xA5, 0x5A,
                0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA, 0xA5, 0x5A,
            ),
            key.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `session iv matches independent arithmetic for the verified identity`() {
        val macBytes = P6KeyDerivation.parseMac(identity.macAddress)!!
        val iv = P6KeyDerivation.sessionIv(macBytes)
        assertEquals(
            listOf(
                0xD4, 0xEF, 0xF0, 0xD3, 0xF4, 0xE5, 0xF0,
                0x90, 0xEB, 0x99, 0xD6, 0x5F, 0x99,
                0xA1, 0x00, 0x00,
            ),
            iv.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `const block is the fixed unit repeated four times`() {
        // Zero mac + empty user id: every input contribution vanishes except the
        // constant block, so key[i] == const[i] for ALL 32 positions.
        val key = P6KeyDerivation.sessionKey(
            P6KeyDerivation.parseMac("00:00:00:00:00:00")!!,
            P6KeyDerivation.userBlock(""),
        )
        val unit = P6KeyDerivation.CONST_UNIT
        for (index in 0..31) {
            assertEquals(
                unit[index % 8].toInt() and 0xFF,
                key[index].toInt() and 0xFF,
                "const passthrough at index $index",
            )
        }
    }

    @Test
    fun `the mac xors only the six corner bytes with the const block`() {
        // Real MAC + empty user id: corners are mac[i] ^ const[i] (independent
        // arithmetic), positions 6..31 stay pure const. Expected corner used to
        // check the xor against the decompiled derivation, still holds here:
        // 90^4D=DD EB^4A=A1 99^42=DB D6^54=82 5F^55=0A 99^AA=33.
        val key = P6KeyDerivation.sessionKey(
            P6KeyDerivation.parseMac(identity.macAddress)!!,
            P6KeyDerivation.userBlock(""),
        )
        val unit = P6KeyDerivation.CONST_UNIT
        val corner = listOf(0xDD, 0xA1, 0xDB, 0x82, 0x0A, 0x33)
        for (index in 0..5) {
            assertEquals(corner[index], key[index].toInt() and 0xFF, "corner index $index")
        }
        for (index in 6..31) {
            assertEquals(
                unit[index % 8].toInt() and 0xFF,
                key[index].toInt() and 0xFF,
                "const tail at index $index",
            )
        }
    }

    @Test
    fun `mac parsing accepts upper and lower case and rejects junk`() {
        assertEquals(
            listOf(0x90, 0xEB, 0x99, 0xD6, 0x5F, 0x99),
            P6KeyDerivation.parseMac("90:EB:99:D6:5F:99")!!.map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0xFF, 0x00, 0x0A, 0x00, 0x0F, 0x0B),
            P6KeyDerivation.parseMac("ff:00:0a:00:0f:0b")!!.map { it.toInt() and 0xFF },
        )
        assertNull(P6KeyDerivation.parseMac("90:EB:99:D6:5F"))
        assertNull(P6KeyDerivation.parseMac("90:EB:99:D6:5F:9"))
        assertNull(P6KeyDerivation.parseMac("ZZ:EB:99:D6:5F:99"))
        assertNull(P6KeyDerivation.parseMac("90EB99D65F99"))
    }

    @Test
    fun `derive returns a cipher for the verified identity and null for junk mac`() {
        assertTrue(P6KeyDerivation.derive(identity) != null)
        assertNull(P6KeyDerivation.derive(P6Identity("26544037", "not-a-mac")))
    }
}
