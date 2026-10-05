package bluebit.fitcloud

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.DevicePhylum
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.PairingMethod
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderScanFilter
import bluebit.core.provider.WearableProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class FitCloudProvider(
    private val connectionFactory: (BleDevice) -> BleConnection,
) : WearableProvider {

    override val name: String = "FitCloud Pro"
    override val id: String = "fitcloud"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override fun canHandle(scanResult: BleScanResult): Boolean {
        return scanResult.serviceUuids.any { it.uppercase() == FitCloudUuids.FITCLOUD_PRIMARY_SERVICE_UUID }
    }

    override fun scanFilter(): ProviderScanFilter = ProviderScanFilter(
        serviceUuids = listOf(FitCloudUuids.FITCLOUD_PRIMARY_SERVICE_UUID),
    )

    override fun identify(scanResult: BleScanResult): DeviceProfile? {
        if (!canHandle(scanResult)) return null
        return DeviceProfile(
            id = scanResult.address.hashCode(),
            name = scanResult.name ?: "FitCloud Pro Device",
            identifier = scanResult.address.hashCode(),
            pairingMethod = PairingMethod.SHOW_SECRET,
            devicePhylum = DevicePhylum.SMARTWATCH,
            supportsBluetooth = true,
            supportsWiFi = false
        )
    }

    override fun createConnection(device: BleDevice): BleConnection =
        connectionFactory(device)

    override suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit> {
        return Result.failure(NotImplementedError("FitCloud Pro not yet implemented"))
    }

    override suspend fun disconnect(connection: BleConnection) {
        connection.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun capabilities(device: BleDevice): DeviceCapabilities =
        DeviceCapabilities()

    override suspend fun sync(device: BleDevice): kotlinx.coroutines.flow.Flow<Int?> =
        flowOf(null)

    override fun diagnostics(): List<ProviderDiagnostic> = listOf(
        ProviderDiagnostic(
            id = "dump_gatt",
            label = "Dump GATT Services",
            action = { addr -> println("Dump GATT for $addr") }
        )
    )
}
