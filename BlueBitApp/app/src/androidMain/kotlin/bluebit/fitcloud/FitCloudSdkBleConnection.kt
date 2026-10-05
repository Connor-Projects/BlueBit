package bluebit.fitcloud

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

class FitCloudSdkBleConnection(
    private val device: BleDevice,
) : BleConnection {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BleService>>(emptyList())
    override val discoveredServices: StateFlow<List<BleService>> = _discoveredServices.asStateFlow()

    override val notifications: Flow<BleNotification> = emptyFlow()

    fun updateState(newState: ConnectionState) {
        _state.value = newState
    }

    override suspend fun connect(device: BleDevice) {}
    override suspend fun discoverServices() {}
    override suspend fun setCharacteristicNotification(serviceUuid: String, characteristicUuid: String, enable: Boolean) {}
    override suspend fun writeDescriptor(serviceUuid: String, characteristicUuid: String, descriptorUuid: String, value: ByteArray) {}
    override suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: Int) {}
    override suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): ByteArray = ByteArray(16) { 0 }
    override suspend fun disconnect() {}
    override fun dumpServices(): String = "FitCloudSdkBleConnection: GATT managed by SDK"
}
