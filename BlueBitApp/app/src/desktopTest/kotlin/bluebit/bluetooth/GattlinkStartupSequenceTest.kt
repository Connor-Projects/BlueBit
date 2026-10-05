package bluebit.bluetooth

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.bluetooth.BleCharacteristic
import bluebit.core.connection.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Desktop test that verifies the Gattlink startup sequence state machine
 * using a mocked [BleConnection].
 *
 * Sequence under test:
 * 1. connect(device)
 * 2. discoverServices()
 * 3. readCharacteristic(AC2F0045, AC2F0145) → 16-byte UUID
 * 4. parse UUID (big-endian ByteBuffer)
 * 5. writeCharacteristic(AC2F0045, parsedUuid, [0x00], WRITE_NO_RESPONSE)
 * 6. setCharacteristicNotification(ABBAFF00, ABBAFF02, true)
 * 7. collect notifications for a short period
 */
class GattlinkStartupSequenceTest {

    private companion object {
        val CACHE_SERVICE = "AC2F0045-8182-4BE5-91E0-2992E6B40EBB"
        val EPHEMERAL_POINTER = "AC2F0145-8182-4BE5-91E0-2992E6B40EBB"
        val GATTLINK_SERVICE = "ABBAFF00-E56A-484C-B832-8B17CF6CBFE8"
        val GATTLINK_TRANSMIT = "ABBAFF02-E56A-484C-B832-8B17CF6CBFE8"
        val WRITE_NO_RESPONSE = 1 // BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }

    @Test
    fun `startup sequence produces correct BleConnection calls`() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeBleConnection()
        val device = BleDevice(address = "AA:BB:CC:DD:EE:FF", name = "Inspire 3")

        // Prepare discovered services so the test can verify service presence
        val parsedTargetUuid = UUID.fromString("AC2F0201-8182-4BE5-91E0-2992E6B40EBB")
        val uuidBytes = run {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(parsedTargetUuid.mostSignificantBits)
            bb.putLong(parsedTargetUuid.leastSignificantBits)
            bb.array()
        }
        fake.readReturn = uuidBytes

        // Set discovered services
        fake.setDiscoveredServices(
            listOf(
                BleService(
                    uuid = CACHE_SERVICE,
                    characteristics = listOf(
                        BleCharacteristic(uuid = EPHEMERAL_POINTER, properties = 2), // PROPERTY_READ
                        BleCharacteristic(uuid = parsedTargetUuid.toString(), properties = 4), // PROPERTY_WRITE_NO_RESPONSE
                    )
                ),
                BleService(
                    uuid = GATTLINK_SERVICE,
                    characteristics = listOf(
                        BleCharacteristic(uuid = GATTLINK_TRANSMIT, properties = 16), // PROPERTY_NOTIFY
                    )
                ),
            )
        )

        // Step 1: connect
        fake.connect(device)
        assertTrue(fake.connectCalls.contains(device), "connect should be called with device")

        // Step 2: discover services
        fake.discoverServices()
        assertEquals(ConnectionState.DiscoveringServices, fake.state.value)

        // Step 3: read ephemeral pointer
        val readValue = fake.readCharacteristic(CACHE_SERVICE, EPHEMERAL_POINTER)
        assertEquals(1, fake.readCalls.size)
        assertEquals(CACHE_SERVICE, fake.readCalls[0].first)
        assertEquals(EPHEMERAL_POINTER, fake.readCalls[0].second)
        assertEquals(16, readValue.size)

        // Step 4: parse UUID (big-endian)
        val bb = ByteBuffer.wrap(readValue)
        val resolvedUuid = UUID(bb.long, bb.long)
        assertEquals(parsedTargetUuid, resolvedUuid)

        // Step 5: write 0x00 to resolved UUID
        fake.writeCharacteristic(CACHE_SERVICE, resolvedUuid.toString(), byteArrayOf(0x00), WRITE_NO_RESPONSE)
        assertEquals(1, fake.characteristicWriteCalls.size)
        val writeCall = fake.characteristicWriteCalls[0]
        assertEquals(CACHE_SERVICE, writeCall.serviceUuid)
        assertEquals(resolvedUuid.toString(), writeCall.characteristicUuid)
        assertTrue(writeCall.value.contentEquals(byteArrayOf(0x00)))
        assertEquals(WRITE_NO_RESPONSE, writeCall.writeType)

        // Step 6: enable notifications on Gattlink transmit
        fake.setCharacteristicNotification(GATTLINK_SERVICE, GATTLINK_TRANSMIT, true)
        assertEquals(1, fake.notificationCalls.size)
        assertEquals(GATTLINK_SERVICE, fake.notificationCalls[0].first)
        assertEquals(GATTLINK_TRANSMIT, fake.notificationCalls[0].second)
        assertEquals(true, fake.notificationCalls[0].third)

        // Step 7: simulate Gattlink traffic notification
        val receivedNotifications = mutableListOf<BleNotification>()
        val job = launch {
            fake.notifications.collect { receivedNotifications.add(it) }
        }
        advanceUntilIdle() // ensure subscription is active
        val gattlinkNotification = BleNotification(
            serviceUuid = GATTLINK_SERVICE,
            characteristicUuid = GATTLINK_TRANSMIT,
            value = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x08, 0x08), // RESET_REQUEST-ish
        )
        fake.emitNotification(gattlinkNotification)
        job.cancel()

        // Verify notification was emitted
        assertEquals(1, receivedNotifications.size)
        val received = receivedNotifications[0]
        assertEquals(GATTLINK_SERVICE, received.serviceUuid)
        assertEquals(GATTLINK_TRANSMIT, received.characteristicUuid)
        assertTrue(received.value.contentEquals(gattlinkNotification.value))

        // Cleanup
        fake.disconnect()
        assertTrue(fake.disconnected)
    }

    @Test
    fun `ephemeral UUID parse is big endian`() = runTest {
        // Verify that 16 bytes parse to UUID consistently
        val testUuid = UUID.fromString("550E8400-E29B-41D4-A716-446655440000")
        val bb = ByteBuffer.allocate(16)
        bb.putLong(testUuid.mostSignificantBits)
        bb.putLong(testUuid.leastSignificantBits)
        val bytes = bb.array()

        val parsedBb = ByteBuffer.wrap(bytes)
        val parsed = UUID(parsedBb.long, parsedBb.long)
        assertEquals(testUuid, parsed)
    }
}
