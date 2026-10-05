package bluebit.bluetooth

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-double for [BleConnection] that allows deterministic control of
 * connection state, discovered services, and notifications.
 */
class FakeBleConnection : BleConnection {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BleService>>(emptyList())
    override val discoveredServices: StateFlow<List<BleService>> = _discoveredServices.asStateFlow()

    fun setDiscoveredServices(services: List<BleService>) {
        _discoveredServices.value = services
    }

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
    override val notifications: Flow<BleNotification> = _notifications.asSharedFlow()

    val connectCalls = mutableListOf<BleDevice>()
    val notificationCalls = mutableListOf<Triple<String, String, Boolean>>()
    val descriptorWriteCalls = mutableListOf<DescriptorWriteCall>()
    val characteristicWriteCalls = mutableListOf<CharacteristicWriteCall>()
    var disconnected = false

    data class DescriptorWriteCall(
        val serviceUuid: String,
        val characteristicUuid: String,
        val descriptorUuid: String,
        val value: ByteArray,
    )

    data class CharacteristicWriteCall(
        val serviceUuid: String,
        val characteristicUuid: String,
        val value: ByteArray,
        val writeType: Int,
    )

    override suspend fun connect(device: BleDevice) {
        connectCalls.add(device)
        _state.value = ConnectionState.Connecting
        _state.value = ConnectionState.Connected
    }

    override suspend fun discoverServices() {
        _state.value = ConnectionState.DiscoveringServices
    }

    override suspend fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean,
    ) {
        notificationCalls.add(Triple(serviceUuid, characteristicUuid, enable))
    }

    override suspend fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        value: ByteArray,
    ) {
        descriptorWriteCalls.add(DescriptorWriteCall(serviceUuid, characteristicUuid, descriptorUuid, value))
    }

    override suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        writeType: Int,
    ) {
        characteristicWriteCalls.add(CharacteristicWriteCall(serviceUuid, characteristicUuid, value, writeType))
    }

    val readCalls = mutableListOf<Pair<String, String>>()
    var readReturn: ByteArray = ByteArray(16) { 0 }

    override suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): ByteArray {
        readCalls.add(serviceUuid to characteristicUuid)
        return readReturn
    }

    override suspend fun disconnect() {
        disconnected = true
        _state.value = ConnectionState.Disconnected
    }

    override fun dumpServices(): String = "FakeBleConnection: no services"

    fun emitNotification(notification: BleNotification) {
        _notifications.tryEmit(notification)
    }
}
