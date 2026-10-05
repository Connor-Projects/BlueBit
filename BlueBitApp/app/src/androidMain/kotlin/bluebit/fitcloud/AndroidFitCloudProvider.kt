package bluebit.fitcloud

import android.content.Context
import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.DevicePhylum
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.PairingMethod
import bluebit.core.pairing.PairedDeviceRecord
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderScanFilter
import bluebit.core.provider.WearableProvider
import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.bleLogWatchdog
import com.topstep.fitcloud.sdk.v2.FcSDK
import com.topstep.wearkit.base.connector.AutoReconnectMode
import com.topstep.wearkit.base.connector.ConnectorState
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx3.asFlow

class AndroidFitCloudProvider(
    private val context: Context,
    private val fcSDK: FcSDK,
) : WearableProvider {

    override val name: String = "FitCloud Pro"
    override val id: String = "fitcloud"

    private val connector = fcSDK.connector
    private val disposables = CompositeDisposable()

    private val dialInspector = FitCloudDialInspector(fcSDK)

    private var lastConnectedAddress: String? = null
    private var lastConnectedName: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    init {
        // Observe SDK connection state and map to our ConnectionState
        disposables.add(
            connector.observerConnectorState().subscribe { fcState ->
                bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud connector state: $fcState")
                _connectionState.value = when (fcState) {
                    ConnectorState.DISCONNECTED -> ConnectionState.Disconnected
                    ConnectorState.PRE_CONNECTING,
                    ConnectorState.CONNECTING -> ConnectionState.Connecting
                    ConnectorState.PRE_CONNECTED -> ConnectionState.DiscoveringServices
                    ConnectorState.CONNECTED -> ConnectionState.Connected
                    else -> ConnectionState.Disconnected
                }
            }
        )
        // Let the SDK retry unexpected drops with its own bounded backoff
        runCatching { connector.setAutoReconnectMode(AutoReconnectMode.BALANCED) }
            .onFailure {
                bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "AutoReconnectMode not supported: ${it.message}")
            }
    }

    override fun canHandle(scanResult: BleScanResult): Boolean {
        return scanResult.serviceUuids.any { it.uppercase() == FitCloudUuids.FITCLOUD_PRIMARY_SERVICE_UUID }
    }

    override fun scanFilter(): ProviderScanFilter {
        return ProviderScanFilter(
            serviceUuids = listOf(FitCloudUuids.FITCLOUD_PRIMARY_SERVICE_UUID),
        )
    }

    override fun identify(scanResult: BleScanResult): DeviceProfile? {
        return DeviceProfile(
            id = 0,
            name = scanResult.name ?: "FitCloud Device",
            identifier = 0,
            pairingMethod = PairingMethod.SHOW_SECRET,
            devicePhylum = DevicePhylum.SMARTWATCH,
            supportsBluetooth = true,
            supportsWiFi = false,
        )
    }

    override fun createConnection(device: BleDevice): BleConnection {
        return FitCloudSdkBleConnection(device)
    }

    override suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit> {
        bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud connect() called: addr=${device.address}, bindOrLogin=true")
        return try {
            // Use BIND mode for first connection; device data may be cleared if previously bound to another app
            connector.connect(
                address = device.address,
                userId = USER_ID,
                bindOrLogin = true,
                sex = true,   // male
                age = 30,
                height = 175f,
                weight = 70f,
            )
            lastConnectedAddress = device.address
            lastConnectedName = device.name
            Result.success(Unit)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud connect() failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun reconnect(device: BleDevice): Result<Unit> {
        bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud reconnect() called: addr=${device.address}, bindOrLogin=false")
        return try {
            // Non-destructive login path for an already-bound device: never re-binds,
            // never clears watch data. Same userId as the original binding.
            connector.connect(
                address = device.address,
                userId = USER_ID,
                bindOrLogin = false,
                sex = true,   // male
                age = 30,
                height = 175f,
                weight = 70f,
            )
            lastConnectedAddress = device.address
            lastConnectedName = device.name
            Result.success(Unit)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud reconnect() failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Emits a [PairedDeviceRecord] each time the connector reaches CONNECTED.
     * Identity comes from the SDK: connected BluetoothDevice address/name and the
     * FitCloud project string from FcDeviceInfo.
     */
    override val pairedDeviceEvents: Flow<PairedDeviceRecord> =
        connector.observerConnectorState()
            .asFlow()
            .filter { it == ConnectorState.CONNECTED }
            .map {
                val btDevice = runCatching { connector.getDevice() }.getOrNull()
                val address = btDevice?.address ?: lastConnectedAddress.orEmpty()
                val name = btDevice?.name ?: lastConnectedName ?: "FitCloud Device"
                val project = runCatching {
                    connector.configFeature().getDeviceInfo().getProject()
                }.getOrNull()
                PairedDeviceRecord(
                    providerId = id,
                    address = address,
                    name = name,
                    extra = listOfNotNull(project?.let { "project" to it }).toMap(),
                )
            }

    override suspend fun disconnect(connection: BleConnection) {
        bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud disconnect() called")
        connector.close()
        lastConnectedAddress = null
        lastConnectedName = null
    }

    override fun capabilities(device: BleDevice): DeviceCapabilities {
        return DeviceCapabilities(
            supportsSteps = true,
            supportsHeartRate = true,
            supportsSleep = true,
            supportsBatteryQuery = true,
            supportsOta = true,
            supportsNotifications = true,
            supportsWeather = true,
            diagnostics = listOf(
                ProviderDiagnostic(
                    id = "dump_gatt",
                    label = "Dump GATT Services",
                    action = { addr ->
                        android.util.Log.i("BlueBitFitCloud", "GATT dump not available for SDK-managed connection: $addr")
                    }
                ),
                ProviderDiagnostic(
                    id = "query_device_info",
                    label = "Query Device Info (FitCloud)",
                    action = { dialInspector.queryDeviceInfo() },
                    result = dialInspector.deviceReport,
                ),
                ProviderDiagnostic(
                    id = "query_dial_info",
                    label = "Query Watch Face Info (FitCloud)",
                    action = { dialInspector.queryDialInfo() },
                    result = dialInspector.dialReport,
                ),
            ),
        )
    }

    override suspend fun sync(device: BleDevice): Flow<Int?> = flowOf(null)

    override fun diagnostics(): List<ProviderDiagnostic> = emptyList()

    fun dispose() {
        disposables.dispose()
    }

    private companion object {
        /** Same userId must be used for bind and later login reconnects. */
        const val USER_ID = "bluebit_user"
    }
}
