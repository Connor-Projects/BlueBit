package bluebit.bluetooth

import bluebit.core.bluetooth.BleScanResult
import bluebit.core.bluetooth.BleServiceData
import bluebit.core.bluetooth.DeviceServiceDataParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceServiceDataParserTest {

    @Test
    fun `parse returns null when service data is missing`() {
        val scan = BleScanResult(
            address = "00:11:22:33:44:55",
            name = null,
            rssi = -60,
            serviceUuids = emptyList(),
            serviceData = emptyMap(),
            timestampNanos = 0,
        )
        assertNull(DeviceServiceDataParser.parse(scan))
    }

    @Test
    fun `parse returns null when payload is too short`() {
        val scan = BleScanResult(
            address = "00:11:22:33:44:55",
            name = null,
            rssi = -60,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to byteArrayOf(0x01, 0x02, 0x03),
            ),
            timestampNanos = 0,
        )
        assertNull(DeviceServiceDataParser.parse(scan))
    }

    @Test
    fun `parse returns BleServiceData for valid payload`() {
        val payload = byteArrayOf(0x02, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val scan = BleScanResult(
            address = "00:11:22:33:44:55",
            name = "Test",
            rssi = -60,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to payload,
            ),
            timestampNanos = 0,
        )
        val data = DeviceServiceDataParser.parse(scan)!!
        assertEquals(0x02, data.deviceTypeId)
    }
}
