package bluebit.bluetooth

import bluebit.core.bluetooth.BleServiceData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BleServiceDataTest {

    @Test
    fun `parse returns null for empty payload`() {
        assertNull(BleServiceData.parse(byteArrayOf()))
    }

    @Test
    fun `parse returns null for 7-byte payload`() {
        assertNull(BleServiceData.parse(ByteArray(7) { it.toByte() }))
    }

    @Test
    fun `parse returns null for payload longer than 8 bytes`() {
        // Current implementation requires exactly 8 bytes
        assertNull(BleServiceData.parse(ByteArray(9) { it.toByte() }))
    }

    @Test
    fun `parse parses exactly 8-byte payload`() {
        val payload = byteArrayOf(0x01, 0x00, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x12, 0x34, 0x56)
        val data = BleServiceData.parse(payload)!!
        assertEquals(0x01, data.deviceTypeId)
        assertArrayEquals(byteArrayOf(0x00, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x12, 0x34, 0x56), data.secret)
    }

    @Test
    fun `toString does not leak secrets`() {
        val data = BleServiceData.parse(
            byteArrayOf(0x01, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        )!!
        val s = data.toString()
        assertTrue(s.contains("BleServiceData"))
        assertTrue(s.contains("deviceTypeId=1"))
        assertFalse(s.contains("0x00"))
        assertFalse(s.contains("0x01"))
    }
}
