package bluebit.core.bluetooth.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import bluebit.core.bluetooth.BleCharacteristic
import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.bleLogWatchdog
import bluebit.core.connection.ConnectionState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android-specific implementation of [BleConnection].
 *
 * All operations are serialised with a [Mutex] and bridged to coroutines via [CompletableDeferred].
 */
class AndroidBleConnection(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit = {},
) : BleConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gatt: BluetoothGatt? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BleService>>(emptyList())
    override val discoveredServices: StateFlow<List<BleService>> = _discoveredServices.asStateFlow()

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
    override val notifications: Flow<BleNotification> = _notifications.asSharedFlow()

    private val operationMutex = Mutex()
    private var pendingOperation: CompletableDeferred<Unit>? = null
    private var pendingReadDeferred: CompletableDeferred<ByteArray>? = null

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            bleLogWatchdog.info(BleLogTag.CONNECTION, "onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger("[AndroidBle] Connected (status=$status)")
                    _state.value = ConnectionState.Connected
                    completePending()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger("[AndroidBle] Disconnected (status=$status)")
                    _state.value = ConnectionState.Disconnected
                    failPending("Disconnected (status=$status)")
                    this@AndroidBleConnection.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val serviceUuids = gatt.services.map { it.uuid.toString() }
            bleLogWatchdog.info(BleLogTag.GATT_SERVICES, "onServicesDiscovered: status=$status, services=$serviceUuids")
            val services = gatt.services.map { svc ->
                BleService(
                    uuid = svc.uuid.toString(),
                    characteristics = svc.characteristics.map { ch ->
                        BleCharacteristic(
                            uuid = ch.uuid.toString(),
                            properties = ch.properties,
                        )
                    }
                )
            }
            _discoveredServices.value = services
            logger("[AndroidBle] Services discovered: ${services.size} (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completePending()
            } else {
                failPending("Service discovery failed: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            bleLogWatchdog.debug(BleLogTag.GATT_DESCRIPTOR, "onDescriptorWrite: ${descriptor.uuid}, status=$status")
            logger("[AndroidBle] DescriptorWrite ${descriptor.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completePending()
            } else {
                failPending("Descriptor write failed: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            bleLogWatchdog.debug(BleLogTag.GATT_WRITE, "onCharacteristicWrite: ${characteristic.uuid}, status=$status")
            logger("[AndroidBle] CharacteristicWrite ${characteristic.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completePending()
            } else {
                failPending("Characteristic write failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            bleLogWatchdog.debug(BleLogTag.GATT_READ, "onCharacteristicRead: ${characteristic.uuid}, value=${value.toHexString()}, status=$status")
            logger("[AndroidBle] CharacteristicRead ${characteristic.uuid} status=$status len=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingReadDeferred?.complete(value.clone())
                pendingReadDeferred = null
            } else {
                pendingReadDeferred?.completeExceptionally(IllegalStateException("Characteristic read failed: $status"))
                pendingReadDeferred = null
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            bleLogWatchdog.debug(BleLogTag.GATT_NOTIFY, "onCharacteristicChanged: ${characteristic.uuid}, value=${value.toHexString()}")
            val notification = BleNotification(
                serviceUuid = characteristic.service.uuid.toString(),
                characteristicUuid = characteristic.uuid.toString(),
                value = value.clone(),
            )
            _notifications.tryEmit(notification)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            bleLogWatchdog.info(BleLogTag.CONNECTION, "onMtuChanged: mtu=$mtu, status=$status")
            logger("[AndroidBle] MTU changed to $mtu (status=$status)")
        }
    }

    private fun completePending() {
        pendingOperation?.complete(Unit)
        pendingOperation = null
    }

    private fun failPending(message: String) {
        pendingOperation?.completeExceptionally(IllegalStateException(message))
        pendingOperation = null
    }

    override suspend fun connect(device: BleDevice) = operationMutex.withLock {
        if (gatt != null) {
            throw IllegalStateException("Already connected")
        }
        bleLogWatchdog.info(BleLogTag.CONNECTION, "connect: addr=${device.address}")
        _state.value = ConnectionState.Connecting
        val deferred = CompletableDeferred<Unit>()
        pendingOperation = deferred
        val bluetoothDevice: BluetoothDevice = bluetoothAdapter.getRemoteDevice(device.address)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothDevice.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } else {
            bluetoothDevice.connectGatt(context, false, callback)
        }
        deferred.await()
    }

    override suspend fun discoverServices() = operationMutex.withLock {
        _state.value = ConnectionState.DiscoveringServices
        val gatt = this.gatt ?: throw IllegalStateException("Not connected")
        val deferred = CompletableDeferred<Unit>()
        pendingOperation = deferred
        if (!gatt.discoverServices()) {
            pendingOperation = null
            throw IllegalStateException("discoverServices() returned false")
        }
        deferred.await()
    }

    override suspend fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean,
    ) = operationMutex.withLock {
        val gatt = this.gatt ?: throw IllegalStateException("Not connected")
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            ?: throw IllegalStateException("Characteristic not found")
        val success = gatt.setCharacteristicNotification(characteristic, enable)
        if (!success) {
            throw IllegalStateException("setCharacteristicNotification returned false")
        }
    }

    override suspend fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        value: ByteArray,
    ) = operationMutex.withLock {
        val gatt = this.gatt ?: throw IllegalStateException("Not connected")
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            ?: throw IllegalStateException("Characteristic not found")
        val descriptor = characteristic.getDescriptor(UUID.fromString(descriptorUuid))
            ?: throw IllegalStateException("Descriptor not found")
        descriptor.value = value
        val deferred = CompletableDeferred<Unit>()
        pendingOperation = deferred
        if (!gatt.writeDescriptor(descriptor)) {
            pendingOperation = null
            throw IllegalStateException("writeDescriptor() returned false")
        }
        deferred.await()
    }

    override suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        writeType: Int,
    ) = operationMutex.withLock {
        val gatt = this.gatt ?: throw IllegalStateException("Not connected")
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            ?: throw IllegalStateException("Characteristic not found")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val deferred = CompletableDeferred<Unit>()
            pendingOperation = deferred
            val status = gatt.writeCharacteristic(characteristic, value, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingOperation = null
                throw IllegalStateException("writeCharacteristic() returned status=$status")
            }
            deferred.await()
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            val deferred = CompletableDeferred<Unit>()
            pendingOperation = deferred
            if (!gatt.writeCharacteristic(characteristic)) {
                pendingOperation = null
                throw IllegalStateException("writeCharacteristic() returned false")
            }
            deferred.await()
        }
    }

    override suspend fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): ByteArray = operationMutex.withLock {
        val gatt = this.gatt ?: throw IllegalStateException("Not connected")
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            ?: throw IllegalStateException("Characteristic not found")
        val deferred = CompletableDeferred<ByteArray>()
        pendingReadDeferred = deferred
        @Suppress("DEPRECATION")
        val ok = gatt.readCharacteristic(characteristic)
        if (!ok) {
            pendingReadDeferred = null
            throw IllegalStateException("readCharacteristic() failed")
        }
        deferred.await()
    }

    override suspend fun disconnect() = operationMutex.withLock {
        bleLogWatchdog.info(BleLogTag.CONNECTION, "disconnect")
        _state.value = ConnectionState.Disconnecting
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _discoveredServices.value = emptyList()
        _state.value = ConnectionState.Disconnected
    }

    override fun dumpServices(): String {
        val gatt = this.gatt ?: return "Not connected"
        val sb = StringBuilder()
        sb.appendLine("=== GATT SERVICE DUMP ===")
        gatt.services.forEach { svc ->
            sb.appendLine("SERVICE: ${svc.uuid}")
            svc.characteristics.forEach { ch ->
                val props = buildString {
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("READ ")
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("WRITE ")
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIFY ")
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("INDICATE ")
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NO_RESP ")
                }
                sb.appendLine("  CHARACTERISTIC: ${ch.uuid} [${props.trim()}]")
                ch.descriptors.forEach { desc ->
                    sb.appendLine("    DESCRIPTOR: ${desc.uuid}")
                }
            }
        }
        sb.appendLine("=== END DUMP ===")
        val result = sb.toString()
        logger(result)
        bleLogWatchdog.info(BleLogTag.GATT_SERVICES, result)
        return result
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: String,
        characteristicUuid: String,
    ): BluetoothGattCharacteristic? {
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: return null
        return service.getCharacteristic(UUID.fromString(characteristicUuid))
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
