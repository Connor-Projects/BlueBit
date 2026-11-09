package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class P6DeviceInfoTest {

    /** Inner TLV: key 0x11 = device-info blob, as seen in `Hd/q.java`. */
    @Test
    fun `parses the 0x11 device info item out of the 0x21 container value`() {
        val container = P6DeviceInfoParser.parse(
            byteArrayOf(0x11, 0x03, 0x41, 0x42, 0x43),
        )!!
        assertEquals(1, container.items.size)
        assertEquals(P6DeviceInfoItem(0x11, byteArrayOf(0x41, 0x42, 0x43)), container.item(0x11))
        assertEquals(listOf(0x41, 0x42, 0x43), container.rawDeviceInfoBlob!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `parses chained items and exposes other keys`() {
        val container = P6DeviceInfoParser.parse(
            byteArrayOf(
                0x11, 0x02, 0x00, 0x52, // 0x11 -> blob "00 52"
                0x24, 0x01, 0x07,       // other feature key
            ),
        )!!
        assertEquals(2, container.items.size)
        assertEquals(listOf(0x00, 0x52), container.rawDeviceInfoBlob!!.map { it.toInt() and 0xFF })
        assertEquals(0x07, container.item(0x24)!!.value[0].toInt())
    }

    @Test
    fun `absent 0x11 key leaves the blob null`() {
        val container = P6DeviceInfoParser.parse(byteArrayOf(0x24, 0x01, 0x01))!!
        assertNull(container.rawDeviceInfoBlob)
    }

    @Test
    fun `truncated containers keep leading items and empties return null`() {
        assertNull(P6DeviceInfoParser.parse(ByteArray(0)))
        assertNull(P6DeviceInfoParser.parse(byteArrayOf(0x11, 0x05, 0x01))) // value overrun, no complete item
        val container = P6DeviceInfoParser.parse(
            byteArrayOf(0x11, 0x01, 0x51, 0x24, 0x02, 0x01), // tail truncated after a valid item
        )!!
        assertEquals(1, container.items.size)
        assertEquals(0x51, container.rawDeviceInfoBlob!![0].toInt() and 0xFF)
    }
}
