package bluebit.fitcloud

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import bluebit.fitcloud.diagnostics.GattDiscoveryDiagnostic
import com.topstep.fitcloud.sdk.v2.FcSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class AndroidFitCloudProvider(
    private val context: Context,
    private val fcSDK: FcSDK,
    private val connector: FitCloudConnector = SdkFitCloudConnector(fcSDK.connector),
) : WearableProvider {

    override val name: String = "FitCloud Pro"
    override val id: String = "fitcloud"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dialInspector = FitCloudDialInspector(fcSDK)

    private var lastConnectedAddress: String? = null
    private var lastConnectedName: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    init {
        // Observe SDK connection state and map to our ConnectionState
        connector.states
            .onEach { fcState ->
                bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud connector state: $fcState")
                _connectionState.value = when (fcState) {
                    FitCloudConnectorState.DISCONNECTED -> ConnectionState.Disconnected
                    FitCloudConnectorState.PRE_CONNECTING,
                    FitCloudConnectorState.CONNECTING -> ConnectionState.Connecting
                    FitCloudConnectorState.PRE_CONNECTED -> ConnectionState.DiscoveringServices
                    FitCloudConnectorState.CONNECTED -> ConnectionState.Connected
                }
            }
            .launchIn(scope)
        // Let the SDK retry unexpected drops with its own bounded backoff
        runCatching { connector.setAutoReconnectMode(FitCloudAutoReconnectMode.BALANCED) }
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
     * Diagnostic-only SDK connection test using the documented [FcConnector.connect] API
     * with [bindOrLogin = false] (login path, never initiates binding).
     *
     * Uses the userId from the official FitCloud app binding (26544037).
     * Returns [Result.success] if the SDK accepts the connect call and the connector
     * transitions toward CONNECTED. Returns [Result.failure] with the SDK exception
     * otherwise. Does not fall back to binding.
     */
    suspend fun testConnectLogin(address: String): Result<Unit> {
        val OFFICIAL_APP_USER_ID = "26544037"
        bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud testConnectLogin called: addr=$address, bindOrLogin=false, userId=$OFFICIAL_APP_USER_ID")
        return try {
            connector.connect(
                address = address,
                userId = OFFICIAL_APP_USER_ID,
                bindOrLogin = false,
                sex = true,
                age = 11,
                height = 170f,
                weight = 58.2f,
            )
            lastConnectedAddress = address
            Result.success(Unit)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud testConnectLogin failed: addr=$address, error=${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Diagnostic-only read-only query: device info via [FcConfigFeature.getDeviceInfo].
     */
    suspend fun testQueryDeviceInfo(): Result<String> {
        return try {
            val info = connector.deviceInfo()
            val report = buildString {
                appendLine("Project: ${info.project}")
                appendLine("Patch: ${info.patch}")
                appendLine("Flash: ${info.flash}")
                appendLine("App: ${info.app}")
                appendLine("IC type: ${info.icType}")
            }
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryDeviceInfo: $report")
            Result.success(report)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryDeviceInfo failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Diagnostic-only read-only query: LCD shape via [FcSettingsFeature.requestLcdShape].
     */
    suspend fun testQueryLcdShape(): Result<String> {
        return try {
            val shape = connector.lcdShapeInfo()
            val report = "LCD ${shape.lcd}: ${shape.width}x${shape.height}" +
                (if (shape.isCircle) " circle" else " rect corners=${shape.corners}")
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryLcdShape: $report")
            Result.success(report)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryLcdShape failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Diagnostic-only read-only query: dial push info via [FcSettingsFeature.requestDialPushInfo].
     */
    suspend fun testQueryDialInfo(): Result<String> {
        return try {
            val info = connector.dialPushInfo()
            val report = buildString {
                appendLine("Current dial num: ${info.currentDialNum}")
                appendLine("Current bin version: ${info.currentBinVersion}")
                appendLine("Current position: ${info.currentPosition}")
                appendLine("Tool version: ${info.toolVersion}")
                appendLine("Dial spaces: ${info.dialSpaceCount}")
            }
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryDialInfo: $report")
            Result.success(report)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryDialInfo failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Diagnostic-only read-only query: UI info via [FcSettingsFeature.requestUiInfo].
     */
    suspend fun testQueryUiInfo(): Result<String> {
        return try {
            val info = connector.uiInfo()
            val report = "UI num: ${info.uiNum}, serial: ${info.uiSerial}, style: ${info.styleIndex}"
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryUiInfo: $report")
            Result.success(report)
        } catch (e: Exception) {
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, "FitCloud queryUiInfo failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Emits a [PairedDeviceRecord] each time the connector reaches CONNECTED.
     * Identity comes from the SDK: connected BluetoothDevice address/name and the
     * FitCloud project string from FcDeviceInfo.
     */
    override val pairedDeviceEvents: Flow<PairedDeviceRecord> =
        connector.states
            .filter { it == FitCloudConnectorState.CONNECTED }
            .map {
                val btDevice = connector.connectedDevice
                val address = btDevice?.address ?: lastConnectedAddress.orEmpty()
                val name = btDevice?.name ?: lastConnectedName ?: "FitCloud Device"
                val project = runCatching { connector.deviceInfo().project }.getOrNull()
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
                    label = "Dump GATT Services (read-only)",
                    action = { addr ->
                        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val adapter = manager?.adapter
                        val target = adapter?.getRemoteDevice(addr)
                        if (target == null) {
                            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "GATT dump skipped: no adapter/device for $addr")
                        } else {
                        val diagnostic = GattDiscoveryDiagnostic(context)
                        try {
                            val success = diagnostic.connectAndDiscover(target)
                            bleLogWatchdog.info(
                                BleLogTag.PROVIDER_CONNECTOR,
                                "GATT dump for $addr completed: success=$success"
                            )
                        } finally {
                            diagnostic.disconnect()
                        }
                    }
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
        scope.cancel()
    }

    private companion object {
        /** Same userId must be used for bind and later login reconnects. */
        const val USER_ID = "bluebit_user"
    }
}
