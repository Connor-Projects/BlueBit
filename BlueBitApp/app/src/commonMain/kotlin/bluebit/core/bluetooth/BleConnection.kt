package bluebit.core.bluetooth

import bluebit.core.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

interface BleConnection {
    val state: StateFlow<ConnectionState>
    val discoveredServices: StateFlow<List<BleService>>
    val notifications: Flow<BleNotification>

    suspend fun connect(device: BleDevice)
    suspend fun discoverServices()
    suspend fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean
    )

    suspend fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        value: ByteArray
    )

    suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        writeType: Int
    )

    /**
     * Request an ATT MTU change. Returns true when the stack reports success
     * (the negotiated value is the caller's to observe afterwards; Android
     * publishes the final agreed MTU in [onMtuChanged]). Implementations that
     * cannot negotiate an MTU return false — Java/BLE stacks without the call
     * and test doubles inherit this default so the interface stays non-breaking.
     */
    suspend fun requestMtu(mtu: Int): Boolean = false

    suspend fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): ByteArray

    suspend fun disconnect()

    fun dumpServices(): String
}

class NoOpBleConnection : BleConnection {
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredServices = MutableStateFlow(emptyList<BleService>())
    override val discoveredServices: StateFlow<List<BleService>> = _discoveredServices.asStateFlow()

    override val notifications: Flow<BleNotification> = emptyFlow()

    override suspend fun connect(device: BleDevice) {}
    override suspend fun discoverServices() {}
    override suspend fun setCharacteristicNotification(serviceUuid: String, characteristicUuid: String, enable: Boolean) {}
    override suspend fun writeDescriptor(serviceUuid: String, characteristicUuid: String, descriptorUuid: String, value: ByteArray) {}
    override suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: Int) {}
    override suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): ByteArray = ByteArray(16) { 0 }
    override suspend fun disconnect() {}
    override fun dumpServices(): String = "NoOpBleConnection: no services"
}
