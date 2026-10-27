package bluebit.fitbit

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.bluetooth.bleLogWatchdog
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.BlueBitDeviceMatcher
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.DeviceProfileRepository
import bluebit.core.pairing.PairedDeviceRecord
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderScanFilter
import bluebit.core.provider.WearableProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf

class FitbitProvider(
    private val profileRepository: DeviceProfileRepository,
    private val connectionFactory: (BleDevice) -> BleConnection,
    private val diagnosticsFactory: () -> List<ProviderDiagnostic> = { emptyList() },
    private val inspire3PairingHandler: (suspend (BleDevice) -> Result<Unit>)? = null,
) : WearableProvider {

    override val name: String = "Fitbit"
    override val id: String = "fitbit"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val matcher = BlueBitDeviceMatcher(profileRepository)

    /**
     * Emits a [PairedDeviceRecord] each time [connect] returns successfully, mirroring the
     * connector-driven shape used by [bluebit.fitcloud.AndroidFitCloudProvider] so the
     * [bluebit.core.pairing.PairedDeviceCoordinator] can persist Fitbit devices and
     * reconnect them via [restoreOnLaunch] (routed back through [connect], which re-emits
     * and re-upserts the same `(providerId, address)` record — the store dedupes).
     */
    private val _pairedEvents = MutableSharedFlow<PairedDeviceRecord>(extraBufferCapacity = 16)
    override val pairedDeviceEvents: Flow<PairedDeviceRecord> = _pairedEvents.asSharedFlow()

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

    private fun BleDevice.isInspire3(): Boolean =
        name?.contains("Inspire 3", ignoreCase = true) == true

    override fun createConnection(device: BleDevice): BleConnection =
        connectionFactory(device)

    override suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit> {
        _connectionState.value = ConnectionState.Connecting
        return try {
            if (device.isInspire3() && inspire3PairingHandler != null) {
                bleLogWatchdog.info(BleLogTag.CONNECTION, "INSPIRE3_PAIRING_HANDLER: address=${device.address}, using cache-validation sequence")
                val handlerResult = inspire3PairingHandler.invoke(device)
                handlerResult.getOrThrow()
            } else {
                connection.connect(device)
            }
            _connectionState.value = ConnectionState.Connected
            // Successful connection side-effect: surface the device for persistence. Emissions
            // are non-blocking (tryEmit); the coordinator subscribes at start() and every
            // (re)emission upserts the same record, so repeated connects create no duplicates.
            _pairedEvents.tryEmit(
                PairedDeviceRecord(
                    providerId = id,
                    address = device.address,
                    name = device.name ?: "Fitbit Device",
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.CONNECTION, "CONNECT_FAILED: address=${device.address}, error=${e.message}")
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
