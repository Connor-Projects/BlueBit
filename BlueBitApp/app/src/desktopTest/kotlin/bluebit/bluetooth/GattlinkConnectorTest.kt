package bluebit.bluetooth

import bluebit.core.bluetooth.BleService
import bluebit.core.connection.ConnectionState
import bluebit.fitbit.FitbitGattUuids
import bluebit.fitbit.transport.FitbitGattlinkConnector
import bluebit.fitbit.transport.FitbitGattlinkTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GattlinkConnectorTest {

    @Test
    fun `runLinkUp discovers services and subscribes to link configuration characteristics`() = runTest {
        val connection = FakeBleConnection()
        connection.setDiscoveredServices(
            listOf(
                BleService(
                    FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
                    emptyList(),
                ),
                BleService(
                    FitbitGattUuids.GATTLINK_SERVICE_UUID,
                    emptyList(),
                ),
            )
        )

        val transport = object : FitbitGattlinkTransport {
            override suspend fun write(data: ByteArray): Result<Unit> = Result.success(Unit)
            override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        }

        val connector = FitbitGattlinkConnector(connection, transport)
        connector.runLinkUp()

        // Should have called discoverServices
        assertEquals(ConnectionState.DiscoveringServices, connection.state.value)

        // Should have subscribed to all 3 Link Configuration characteristics
        val linkConfigSubs = connection.notificationCalls.filter {
            it.first == FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID
        }
        assertEquals(3, linkConfigSubs.size)

        assertTrue(
            linkConfigSubs.any { it.second == FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_CONFIGURATION_CHARACTERISTIC_UUID },
            "Expected subscription to CLIENT_PREFERRED_CONNECTION_CONFIGURATION"
        )
        assertTrue(
            linkConfigSubs.any { it.second == FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_MODE_CHARACTERISTIC_UUID },
            "Expected subscription to CLIENT_PREFERRED_CONNECTION_MODE"
        )
        assertTrue(
            linkConfigSubs.any { it.second == FitbitGattUuids.GENERAL_PURPOSE_COMMAND_CHARACTERISTIC_UUID },
            "Expected subscription to GENERAL_PURPOSE_COMMAND"
        )
    }

    @Test
    fun `runLinkUp works with modern Gattlink service`() = runTest {
        val connection = FakeBleConnection()
        connection.setDiscoveredServices(
            listOf(
                BleService(
                    FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
                    emptyList(),
                ),
                BleService(
                    FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID,
                    emptyList(),
                ),
            )
        )

        val transport = object : FitbitGattlinkTransport {
            override suspend fun write(data: ByteArray): Result<Unit> = Result.success(Unit)
            override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        }

        val connector = FitbitGattlinkConnector(connection, transport)
        connector.runLinkUp()

        assertEquals(3, connection.notificationCalls.size)
    }

    @Test
    fun `connector state exposes connection state`() = runTest {
        val connection = FakeBleConnection()
        val transport = object : FitbitGattlinkTransport {
            override suspend fun write(data: ByteArray): Result<Unit> = Result.success(Unit)
            override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        }

        val connector = FitbitGattlinkConnector(connection, transport)
        assertEquals(ConnectionState.Disconnected, connector.state.value)

        connection.setDiscoveredServices(emptyList())
        connector.runLinkUp()
        assertEquals(ConnectionState.DiscoveringServices, connector.state.value)
    }
}
