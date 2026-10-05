package bluebit.core.connection

import bluebit.core.bluetooth.BleDevice
import bluebit.core.devices.DeviceProfile

sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data object Scanning : DiscoveryState()
    data class DeviceFound(val device: BleDevice) : DiscoveryState()
    data class DeviceRecognised(val profile: DeviceProfile) : DiscoveryState()
    data object DeviceUnrecognised : DiscoveryState()
    data object VerificationRequired : DiscoveryState()
    data object Verified : DiscoveryState()
    data object VerificationFailed : DiscoveryState()
    data class Error(val reason: String) : DiscoveryState()
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data object DiscoveringServices : ConnectionState()
    data object Subscribing : ConnectionState()
    data object GattlinkReady : ConnectionState()
    data object Disconnecting : ConnectionState()
    data class Syncing(val progress: Int?) : ConnectionState()
    data object SyncComplete : ConnectionState()
    data class Error(val reason: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

sealed class VerificationResult {
    data object Verified : VerificationResult()
    data object Invalid : VerificationResult()
    data object KeyUnavailable : VerificationResult()
    data class Error(val reason: String) : VerificationResult()
}
