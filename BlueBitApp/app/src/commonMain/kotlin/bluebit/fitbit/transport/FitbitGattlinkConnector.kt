package bluebit.fitbit.transport

import bluebit.core.bluetooth.BleConnection
import bluebit.core.connection.ConnectionState
import bluebit.fitbit.FitbitGattUuids
import kotlinx.coroutines.flow.StateFlow

class FitbitGattlinkConnector(
    private val connection: BleConnection,
    private val transport: FitbitGattlinkTransport
) {
    val state: StateFlow<ConnectionState> get() = connection.state

    suspend fun runLinkUp() {
        // Deterministic link-up sequence:
        // 1. Discover services on the connected peripheral.
        // 2. Subscribe to the three Link Configuration characteristics.
        connection.discoverServices()
        connection.setCharacteristicNotification(
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
            FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_CONFIGURATION_CHARACTERISTIC_UUID,
            true
        )
        connection.setCharacteristicNotification(
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
            FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_MODE_CHARACTERISTIC_UUID,
            true
        )
        connection.setCharacteristicNotification(
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
            FitbitGattUuids.GENERAL_PURPOSE_COMMAND_CHARACTERISTIC_UUID,
            true
        )
    }
}
