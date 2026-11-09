package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class P6ProtocolPacketCodecTest {

    @Test
    fun `tx serialization is cmdId 0x00 keyId len be16 data`() {
        assertEquals(
            listOf(0x03, 0x00, 0x14, 0x00, 0x00),
            P6ProtocolPacketCodec.serialize(3, 0x14, null).map { it.toInt() and 0xFF },
        )
        val data = byteArrayOf(0x11, 0x03, 0x41, 0x42, 0x43)
        assertEquals(
            listOf(0x02, 0x00, 0x21, 0x00, 0x05, 0x11, 0x03, 0x41, 0x42, 0x43),
            P6ProtocolPacketCodec.serialize(2, 0x21, data).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `rx parse reads cfgId and chained key items`() {
        val packet = P6ProtocolPacketCodec.parse(
            byteArrayOf(0x02, 0x00, 0x21, 0x00, 0x05, 0x11, 0x03, 0x41, 0x42, 0x43, 0x24, 0x00, 0x02, 0x01, 0x02),
        )!!
        assertEquals(2, packet.cfgId)
        assertEquals(2, packet.items.size)
        assertEquals(
            P6ProtocolItem(0x21, byteArrayOf(0x11, 0x03, 0x41, 0x42, 0x43)),
            packet.items[0],
        )
        assertEquals(P6ProtocolItem(0x24, byteArrayOf(0x01, 0x02)), packet.items[1])
        assertEquals(0x21, packet.keyId)
        assertEquals(listOf(0x11, 0x03, 0x41, 0x42, 0x43), packet.firstData!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `rx parse ignores the reserved second byte`() {
        // TX binds byte[1] to 0x00; RX reads but ignores it — a nonzero reserved
        // byte must not break the item chain.
        val packet = P6ProtocolPacketCodec.parse(
            byteArrayOf(0x03, 0x7F, 0x14, 0x00, 0x01, 0x00),
        )!!
        assertEquals(3, packet.cfgId)
        assertEquals(0x14, packet.keyId)
    }

    @Test
    fun `truncated tail keeps the leading items`() {
        val packet = P6ProtocolPacketCodec.parse(
            byteArrayOf(0x02, 0x00, 0x21, 0x00, 0x01, 0x11, 0x24, 0x00, 0x02, 0x01),
        )!!
        assertEquals(1, packet.items.size)
        assertEquals(P6ProtocolItem(0x21, byteArrayOf(0x11)), packet.items[0])
    }

    @Test
    fun `header only containers parse to an empty item list`() {
        // C0501l.a() builds its item list up front and returns the container even
        // when no items follow the (cfgId, reserved) header.
        val packet = P6ProtocolPacketCodec.parse(byteArrayOf(0x03, 0x00))!!
        assertEquals(3, packet.cfgId)
        assertTrue(packet.items.isEmpty())
        assertNull(packet.keyId)
        assertNull(packet.firstData)
    }

    @Test
    fun `malformed payloads return null`() {
        assertNull(P6ProtocolPacketCodec.parse(ByteArray(0)))
        assertNull(P6ProtocolPacketCodec.parse(byteArrayOf(0x03))) // < 2 bytes
        // item header claims a value longer than the payload AND nothing was read
        assertNull(P6ProtocolPacketCodec.parse(byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x50)))
    }

    @Test
    fun `serialize then parse round trips for assorted ids`() {
        for (cfgId in intArrayOf(2, 3, 5, 8)) {
            for (keyId in intArrayOf(0x11, 0x14, 0x21, 0x30)) {
                val data = byteArrayOf(1, 2, 3, 4, 5)
                val packet = P6ProtocolPacketCodec.parse(
                    P6ProtocolPacketCodec.serialize(cfgId, keyId, data),
                )!!
                assertEquals(cfgId, packet.cfgId)
                assertEquals(1, packet.items.size)
                assertEquals(keyId, packet.items[0].keyId)
                assertEquals(data.toList(), packet.items[0].data.toList())
            }
        }
    }
}
