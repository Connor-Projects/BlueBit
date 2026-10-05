package bluebit.core.provider

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.DeviceProfile
import bluebit.core.pairing.PairedDeviceRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface WearableProvider {
    val name: String
    val id: String
    fun canHandle(scanResult: BleScanResult): Boolean
    fun scanFilter(): ProviderScanFilter
    fun identify(scanResult: BleScanResult): DeviceProfile?
    fun createConnection(device: BleDevice): BleConnection
    suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit>
    suspend fun disconnect(connection: BleConnection)
    val connectionState: StateFlow<ConnectionState>

    /**
     * Non-destructive reconnect for a device this provider already has a binding with.
     * Must not re-run first-pairing/bind flows. Default falls back to [connect].
     */
    suspend fun reconnect(device: BleDevice): Result<Unit> = connect(device, createConnection(device))

    /** Emits a record every time a device is successfully paired/bound through this provider. */
    val pairedDeviceEvents: Flow<PairedDeviceRecord> get() = emptyFlow()

    fun capabilities(device: BleDevice): DeviceCapabilities
    suspend fun sync(device: BleDevice): Flow<Int?>
    fun diagnostics(): List<ProviderDiagnostic>
}
