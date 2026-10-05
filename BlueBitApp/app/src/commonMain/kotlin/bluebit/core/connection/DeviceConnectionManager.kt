package bluebit.core.connection

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanner
import bluebit.core.devices.BlueBitDeviceMatcher
import bluebit.core.crypto.AdvertisementVerifier
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.DeviceProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceConnectionManager(
    private val scanner: BleScanner,
    private val profileRepository: DeviceProfileRepository,
    private val advertisementVerifier: AdvertisementVerifier,
    private val scope: CoroutineScope
) {
    private val matcher = BlueBitDeviceMatcher(profileRepository)

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun startDiscovery() {
        _discoveryState.value = DiscoveryState.Scanning
        scanner.startScan()
        scope.launch {
            scanner.scanResults().collect { scanResult ->
                val profile = matcher.match(scanResult)
                _discoveryState.value = if (profile != null) {
                    DiscoveryState.DeviceRecognised(profile)
                } else {
                    DiscoveryState.DeviceUnrecognised
                }
            }
        }
    }

    fun stopDiscovery() {
        scanner.stopScan()
        if (_discoveryState.value == DiscoveryState.Scanning) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    suspend fun connect(profile: DeviceProfile) {
        TODO("Not yet implemented")
    }

    suspend fun connect(device: BleDevice, connection: BleConnection) {
        _connectionState.value = ConnectionState.Connecting
        connection.connect(device)
        _connectionState.value = ConnectionState.Connected
    }

    suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnecting
        _connectionState.value = ConnectionState.Disconnected
    }

    suspend fun sync() {
        TODO("Not yet implemented")
    }
}
