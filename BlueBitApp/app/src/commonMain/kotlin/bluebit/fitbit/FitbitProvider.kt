package bluebit.fitbit

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.BlueBitDeviceMatcher
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.DeviceProfileRepository
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderScanFilter
import bluebit.core.provider.WearableProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class FitbitProvider(
    private val profileRepository: DeviceProfileRepository,
    private val connectionFactory: (BleDevice) -> BleConnection,
    private val diagnosticsFactory: () -> List<ProviderDiagnostic> = { emptyList() },
) : WearableProvider {

    override val name: String = "Fitbit"
    override val id: String = "fitbit"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val matcher = BlueBitDeviceMatcher(profileRepository)

    override fun canHandle(scanResult: BleScanResult): Boolean {
        // Check if any Fitbit service UUID is advertised
        val fitbitUuids = setOf(
            FitbitGattUuids.ADABFB00_SERVICE_UUID,
            FitbitGattUuids.FD63_SERVICE_UUID,
            FitbitGattUuids.ABBAFB00_SERVICE_UUID,
            FitbitGattUuids.ABBAFF00_SERVICE_UUID,
            FitbitGattUuids.FD62_SERVICE_UUID,
            FitbitGattUuids.WEIGHT_SERVICE_UUID,
        )
        return scanResult.serviceUuids.any { it.uppercase() in fitbitUuids }
    }

    override fun scanFilter(): ProviderScanFilter = ProviderScanFilter(
        serviceUuids = listOf(
            FitbitGattUuids.ADABFB00_SERVICE_UUID,
            FitbitGattUuids.FD63_SERVICE_UUID,
            FitbitGattUuids.ABBAFB00_SERVICE_UUID,
            FitbitGattUuids.ABBAFF00_SERVICE_UUID,
            FitbitGattUuids.FD62_SERVICE_UUID,
            FitbitGattUuids.WEIGHT_SERVICE_UUID,
        )
    )

    override fun identify(scanResult: BleScanResult): DeviceProfile? =
        matcher.match(scanResult)

    override fun createConnection(device: BleDevice): BleConnection =
        connectionFactory(device)

    override suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit> {
        _connectionState.value = ConnectionState.Connecting
        return try {
            connection.connect(device)
            _connectionState.value = ConnectionState.Connected
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Disconnected
            Result.failure(e)
        }
    }

    override suspend fun disconnect(connection: BleConnection) {
        connection.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun capabilities(device: BleDevice): DeviceCapabilities =
        DeviceCapabilities(
            supportsSteps = true,
            supportsHeartRate = true,
            supportsSleep = true,
            supportsBatteryQuery = true,
            supportsOta = true,
            supportsNotifications = false,
            supportsWeather = false,
            diagnostics = diagnostics(),
        )

    override suspend fun sync(device: BleDevice): kotlinx.coroutines.flow.Flow<Int?> =
        flowOf(null)

    override fun diagnostics(): List<ProviderDiagnostic> = listOf(
        ProviderDiagnostic(
            id = "dump_gatt",
            label = "Dump GATT Services",
            action = { deviceAddress ->
                println("Diagnostic requested: dump_gatt for $deviceAddress")
            }
        )
    ) + diagnosticsFactory()
}
