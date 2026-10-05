package bluebit.bluetooth

import bluebit.core.bluetooth.BleScanResult
import bluebit.core.devices.BlueBitDeviceMatcher
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.DevicePhylum
import bluebit.core.devices.InMemoryDeviceProfileRepository
import bluebit.core.devices.PairingMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlueBitDeviceMatcherTest {

    private val profiles = listOf(
        DeviceProfile(
            id = 1,
            name = "bluebit-aura",
            identifier = 0x01,
            pairingMethod = PairingMethod.TAP,
            devicePhylum = DevicePhylum.TRACKER,
            supportsBluetooth = true,
            supportsWiFi = false,
        ),
        DeviceProfile(
            id = 2,
            name = "legacy-one",
            identifier = 0x99,
            pairingMethod = PairingMethod.SHOW_SECRET,
            devicePhylum = DevicePhylum.TRACKER,
            supportsBluetooth = true,
            supportsWiFi = false,
        ),
    )

    private val repo = InMemoryDeviceProfileRepository(profiles)
    private val matcher = BlueBitDeviceMatcher(repo)

    @Test
    fun `matchByServiceData finds known device`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to byteArrayOf(0x01, 0x00, 0, 0, 0, 0, 0, 0),
            ),
            timestampNanos = 0,
        )
        val profile = matcher.matchByServiceData(scan)
        assertNotNull(profile)
        assertEquals("bluebit-aura", profile?.name)
    }

    @Test
    fun `matchByServiceData returns null for unknown identifier`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to byteArrayOf(0xAB.toByte(), 0x00, 0, 0, 0, 0, 0, 0),
            ),
            timestampNanos = 0,
        )
        assertNull(matcher.matchByServiceData(scan))
    }

    @Test
    fun `matchByName finds legacy device`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = "legacy-one",
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = emptyMap(),
            timestampNanos = 0,
        )
        val profile = matcher.matchByName(scan)
        assertNotNull(profile)
        assertEquals("legacy-one", profile?.name)
    }

    @Test
    fun `matchByName returns null for unknown name`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Unknown",
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = emptyMap(),
            timestampNanos = 0,
        )
        assertNull(matcher.matchByName(scan))
    }

    @Test
    fun `match prefers service data over name`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = "legacy-one",
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to byteArrayOf(0x01, 0x00, 0, 0, 0, 0, 0, 0),
            ),
            timestampNanos = 0,
        )
        val profile = matcher.match(scan)
        assertNotNull(profile)
        assertEquals("bluebit-aura", profile?.name)
    }

    @Test
    fun `match falls back to name when service data unknown`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = "legacy-one",
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = mapOf(
                BleScanResult.DEVICE_INFORMATION_SERVICE_UUID to byteArrayOf(0x99.toByte(), 0x00, 0, 0, 0, 0, 0, 0),
            ),
            timestampNanos = 0,
        )
        val profile = matcher.match(scan)
        assertNotNull(profile)
        assertEquals("legacy-one", profile?.name)
    }

    @Test
    fun `match returns null when nothing matches`() {
        val scan = BleScanResult(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Nothing",
            rssi = -50,
            serviceUuids = emptyList(),
            serviceData = emptyMap(),
            timestampNanos = 0,
        )
        assertNull(matcher.match(scan))
    }
}
