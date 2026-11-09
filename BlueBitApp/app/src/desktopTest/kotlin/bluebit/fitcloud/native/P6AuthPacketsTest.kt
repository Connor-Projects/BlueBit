package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class P6AuthPacketsTest {

    @Test
    fun `probe packets are empty five byte auth commands`() {
        assertEquals(
            listOf(0x03, 0x00, 0x12, 0x00, 0x00),
            P6AuthPackets.bindProbe().map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0x03, 0x00, 0x14, 0x00, 0x00),
            P6AuthPackets.loginProbe().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `submit packets carry the 32 byte zero padded user block`() {
        val expected = byteArrayOf(0x03, 0x00, 0x11, 0x00, 0x20) +
            byteArrayOf(0x32, 0x36, 0x35, 0x34, 0x34, 0x30, 0x33, 0x37) +
            ByteArray(24)
        assertEquals(
            expected.toList(),
            P6AuthPackets.submit("26544037", bind = true).toList(),
        )
        assertEquals(
            expected.toList().map { if (it.toInt() == 0x11) 0x13 else it.toInt() },
            P6AuthPackets.submit("26544037", bind = false).map { it.toInt() },
        )
    }

    @Test
    fun `result codes describe the reference mappings`() {
        assertEquals("0", P6AuthPackets.describeResult(0))
        assertTrue(P6AuthPackets.describeResult(1).contains("bind required"))
        assertTrue(P6AuthPackets.describeResult(5).contains("state error"))
        assertTrue(P6AuthPackets.describeResult(7).contains("unknown"))
    }

    @Test
    fun `auth mode enum exposes the two reference flows`() {
        assertEquals(setOf(P6AuthMode.BIND, P6AuthMode.LOGIN), P6AuthMode.entries.toSet())
    }
}
