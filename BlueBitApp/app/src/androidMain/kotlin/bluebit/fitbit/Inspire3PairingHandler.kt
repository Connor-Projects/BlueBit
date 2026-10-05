package bluebit.fitbit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Inspire 3 pairing handler.
 *
 * Uses the verified Gattlink cache validation sequence instead of Android bonding:
 * 1. Connect GATT
 * 2. Discover services
 * 3. Read AC2F0145 (ephemeral pointer) under AC2F0045
 * 4. Parse 16-byte response as UUID (big-endian)
 * 5. Write 0x00 to parsed UUID with WRITE_TYPE_NO_RESPONSE
 * 6. Gattlink sends RESET_REQUEST (0x80) on ABBAFF02
 * 7. Write RESET_COMPLETE (0x81 00 00 08 08) to ABBAFF01
 * 8. Connection stays alive
 *
 * Based on APK analysis (GattDatabaseValidator.java) and physical device verification.
 */
class Inspire3PairingHandler(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitInspire3Pairing"

        private val GATT_CACHE_SERVICE = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
        private val EPHEMERAL_POINTER = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")

        private val GATTLINK_SERVICE = UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")
        private val GATTLINK_RECEIVE = UUID.fromString("ABBAFF01-E56A-484C-B832-8B17CF6CBFE8")
        private val GATTLINK_TRANSMIT = UUID.fromString("ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
        private val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Gattlink control packet types
        private const val GATTLINK_CONTROL_RESET_REQUEST: Byte = 0x80.toByte()
        private const val GATTLINK_CONTROL_RESET_COMPLETE: Byte = 0x81.toByte()
        private const val GATTLINK_WINDOW_SIZE: Byte = 0x08
        private val GATTLINK_RESET_COMPLETE_PAYLOAD = byteArrayOf(
            GATTLINK_CONTROL_RESET_COMPLETE,
            0x00,
            0x00,
            GATTLINK_WINDOW_SIZE,
            GATTLINK_WINDOW_SIZE,
        )

        // BLE disconnect reasons
        private const val GATT_CONN_TIMEOUT = 8
        private const val GATT_CONN_TERMINATE_PEER_USER = 19
        private const val GATT_CONN_TERMINATE_LOCAL_HOST = 22

        private const val LISTEN_DURATION_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val READ_TIMEOUT_MS = 10000L
        private const val WRITE_TIMEOUT_MS = 10000L
        private const val ENABLE_NOTIFICATION_TIMEOUT_MS = 10000L
    }

    private var gatt: BluetoothGatt? = null
    private var gattCacheService: android.bluetooth.BluetoothGattService? = null
    private var notificationCount = 0
    private var notifiedBytes = 0

    // Frame dump state
    private var pairingStartTime: Long = 0L
    private var dumpFile: File? = null
    private val dumpBuilder = StringBuilder()

    // Reset handshake state (volatile because callbacks run on binder thread)
    @Volatile
    private var resetRequestReceived = false

    /**
     * Run the Gattlink cache validation pairing sequence with reset handshake.
     *
     * @return true if the full sequence succeeded (write + RESET_REQUEST + RESET_COMPLETE),
     * false otherwise. The GATT connection is left open on success so the caller
     * can continue using it. Call [disconnect] when done.
     */
    @SuppressLint("MissingPermission")
    suspend fun pairDevice(
        device: BluetoothDevice,
        extraCaptureMs: Long = 0L,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                Log.i(TAG, "========================================")
                Log.i(TAG, "PAIRING_START: ${device.name} ${device.address}")

                // Initialize frame dump for this pairing attempt
                val dateStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                dumpFile = File(context.filesDir, "gattlink_dump_$dateStamp.txt")
                dumpBuilder.clear()
                pairingStartTime = System.currentTimeMillis()

                // Clean up any stale connection
                disconnect()
                resetRequestReceived = false

                val connectedDeferred = CompletableDeferred<Boolean>()
                val servicesDiscoveredDeferred = CompletableDeferred<Boolean>()
                val readDeferred = CompletableDeferred<Boolean>()
                val writeDeferred = CompletableDeferred<Boolean>()
                val notificationsEnabledDeferred = CompletableDeferred<Boolean>()
                val resetCompleteDeferred = CompletableDeferred<Boolean>()
                val disconnectDeferred = CompletableDeferred<Boolean>()

                val callback = object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        val stateName = when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                            else -> "UNKNOWN($newState)"
                        }
                        Log.i(TAG, "BLE_CONNECT state=$stateName status=$status")

                        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "GATT_CONNECTED: requesting service discovery")
                            gatt.discoverServices()
                            connectedDeferred.complete(true)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            when (status) {
                                GATT_CONN_TERMINATE_PEER_USER ->
                                    Log.e(TAG, "ERROR_DISCONNECTED_BY_PEER status=$status (GATT_CONN_TERMINATE_PEER_USER)")
                                GATT_CONN_TIMEOUT ->
                                    Log.e(TAG, "ERROR_DISCONNECTED_BY_PEER status=$status (GATT_CONN_TIMEOUT)")
                                GATT_CONN_TERMINATE_LOCAL_HOST ->
                                    Log.e(TAG, "ERROR_DISCONNECTED_BY_PEER status=$status (GATT_CONN_TERMINATE_LOCAL_HOST)")
                                else ->
                                    Log.e(TAG, "ERROR_DISCONNECTED_BY_PEER status=$status")
                            }
                            connectedDeferred.complete(false)
                            servicesDiscoveredDeferred.complete(false)
                            readDeferred.complete(false)
                            writeDeferred.complete(false)
                            notificationsEnabledDeferred.complete(false)
                            resetCompleteDeferred.complete(false)
                            disconnectDeferred.complete(false)
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        Log.i(TAG, "GATT_DISCOVERY_COMPLETE status=$status services=${gatt.services.size}")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "ERROR_SERVICE_DISCOVERY_FAILED status=$status")
                            servicesDiscoveredDeferred.complete(false)
                            return
                        }

                        val cacheService = gatt.services.find { it.uuid == GATT_CACHE_SERVICE }
                        if (cacheService == null) {
                            Log.e(TAG, "ERROR_CACHE_SERVICE_NOT_FOUND: $GATT_CACHE_SERVICE")
                            servicesDiscoveredDeferred.complete(false)
                            return
                        }
                        gattCacheService = cacheService
                        Log.i(TAG, "CACHE_SERVICE_FOUND: ${cacheService.uuid}")

                        val pointerChar = cacheService.getCharacteristic(EPHEMERAL_POINTER)
                        if (pointerChar == null) {
                            Log.e(TAG, "ERROR_EPHEMERAL_POINTER_NOT_FOUND: $EPHEMERAL_POINTER")
                            servicesDiscoveredDeferred.complete(false)
                            return
                        }

                        Log.i(TAG, "EPHEMERAL_POINTER_FOUND: ${pointerChar.uuid}")
                        servicesDiscoveredDeferred.complete(true)

                        // Step: read ephemeral pointer
                        Log.i(TAG, "AC2F0145_READ_START")
                        val readResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.readCharacteristic(pointerChar)
                        } else {
                            @Suppress("DEPRECATION")
                            gatt.readCharacteristic(pointerChar)
                        }
                        Log.i(TAG, "READ_REQUESTED result=$readResult")
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int,
                    ) {
                        Log.i(TAG, "AC2F0145_READ_RESULT uuid=${characteristic.uuid} status=$status len=${value.size}")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "ERROR_READ_FAILED status=$status")
                            readDeferred.complete(false)
                            return
                        }

                        val hex = value.joinToString("") { "%02X".format(it) }
                        Log.i(TAG, "VALUE_HEX=$hex")

                        if (value.size != 16) {
                            Log.w(TAG, "VALUE_UNEXPECTED_LENGTH expected=16 actual=${value.size}")
                        }

                        val parsedUuid = try {
                            val bb = ByteBuffer.wrap(value)
                            UUID(bb.long, bb.long)
                        } catch (e: Exception) {
                            Log.e(TAG, "VALUE_PARSE_FAILED", e)
                            readDeferred.complete(false)
                            return
                        }
                        Log.i(TAG, "EPHEMERAL_CHARACTERISTIC_FOUND: $parsedUuid")
                        readDeferred.complete(true)

                        // Step: write 0x00 to ephemeral UUID
                        performWrite(gatt, parsedUuid, writeDeferred)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            @Suppress("DEPRECATION")
                            onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        when (characteristic.uuid) {
                            GATTLINK_RECEIVE -> {
                                // RESET_COMPLETE write
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    Log.i(TAG, "RESET_COMPLETE_SENT")
                                    resetCompleteDeferred.complete(true)
                                } else {
                                    Log.e(TAG, "ERROR_RESET_COMPLETE_FAILED status=$status")
                                    resetCompleteDeferred.complete(false)
                                }
                            }
                            else -> {
                                // Ephemeral write
                                Log.i(TAG, "EPHEMERAL_WRITE_RESULT uuid=${characteristic.uuid} status=$status")
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    Log.e(TAG, "ERROR_WRITE_FAILED status=$status")
                                    writeDeferred.complete(false)
                                    return
                                }
                                Log.i(TAG, "WRITE_SUCCESS")
                                writeDeferred.complete(true)
                                enableGattlinkNotifications(gatt, notificationsEnabledDeferred)
                            }
                        }
                    }

                    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        if (descriptor.uuid == CCC_DESCRIPTOR) {
                            Log.i(TAG, "CCC_WRITE_RESULT status=$status")
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                Log.i(TAG, "NOTIFICATIONS_ENABLED")
                                notificationsEnabledDeferred.complete(true)
                            } else {
                                Log.e(TAG, "ERROR_NOTIFICATIONS_FAILED status=$status")
                                notificationsEnabledDeferred.complete(false)
                            }
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        if (characteristic.uuid == GATTLINK_TRANSMIT) {
                            notificationCount++
                            notifiedBytes += value.size
                            val fullHex = value.joinToString("") { "%02X".format(it) }
                            val hex = if (value.size <= 32) {
                                fullHex
                            } else {
                                fullHex.take(64) + "...(${value.size} total)"
                            }
                            Log.i(TAG, "GATTLINK_TRAFFIC_DETECTED #$notificationCount len=${value.size} hex=$hex")

                            // Append to Gattlink frame dump
                            val label = when (value.getOrNull(0)) {
                                GATTLINK_CONTROL_RESET_REQUEST -> "CTRL RESET_REQUEST"
                                GATTLINK_CONTROL_RESET_COMPLETE -> "CTRL RESET_COMPLETE"
                                else -> "DATA"
                            }
                            val elapsed = System.currentTimeMillis() - pairingStartTime
                            val seq = String.format(Locale.US, "%03d", notificationCount)
                            dumpBuilder.appendLine("[+${elapsed}ms] #$seq len=${value.size} $label $fullHex")

                            if (value.isNotEmpty()) {
                                when (value[0]) {
                                    GATTLINK_CONTROL_RESET_REQUEST -> {
                                        Log.i(TAG, "RESET_REQUEST received")
                                        resetRequestReceived = true
                                        sendResetComplete(gatt)
                                    }
                                    GATTLINK_CONTROL_RESET_COMPLETE -> {
                                        Log.i(TAG, "RESET_COMPLETE received")
                                    }
                                    else -> {
                                        Log.w(TAG, "UNEXPECTED_GATTLINK_PACKET hex=$hex")
                                    }
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            @Suppress("DEPRECATION")
                            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
                        }
                    }
                }

                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, callback)
                }

                // Step 1: connect
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectedDeferred.await() } == true
                if (!connected) {
                    Log.e(TAG, "ERROR_CONNECT_TIMEOUT")
                    disconnect()
                    return@withContext false
                }

                // Step 2: discover services
                val servicesDiscovered = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesDiscoveredDeferred.await() } == true
                if (!servicesDiscovered) {
                    Log.e(TAG, "ERROR_SERVICE_DISCOVERY_FAILED")
                    disconnect()
                    return@withContext false
                }

                // Step 3: read AC2F0145
                val readOk = withTimeoutOrNull(READ_TIMEOUT_MS) { readDeferred.await() } == true
                if (!readOk) {
                    Log.e(TAG, "ERROR_READ_FAILED")
                    disconnect()
                    return@withContext false
                }

                // Step 4: write 0x00 to ephemeral
                val writeOk = withTimeoutOrNull(WRITE_TIMEOUT_MS) { writeDeferred.await() } == true
                if (!writeOk) {
                    Log.e(TAG, "ERROR_WRITE_FAILED")
                    disconnect()
                    return@withContext false
                }

                // Step 5: enable notifications and wait for reset handshake
                val notifOk = withTimeoutOrNull(ENABLE_NOTIFICATION_TIMEOUT_MS) { notificationsEnabledDeferred.await() } == true
                if (!notifOk) {
                    Log.e(TAG, "ERROR_NOTIFICATIONS_FAILED")
                    disconnect()
                    return@withContext false
                }

                // Wait for RESET_COMPLETE write to be acknowledged
                val resetResult = withTimeoutOrNull(LISTEN_DURATION_MS) { resetCompleteDeferred.await() }
                if (resetResult != true) {
                    if (!resetRequestReceived) {
                        Log.e(TAG, "ERROR_RESET_REQUEST_NOT_RECEIVED")
                    } else {
                        Log.e(TAG, "ERROR_RESET_COMPLETE_FAILED")
                    }
                    disconnect()
                    return@withContext false
                }

                Log.i(TAG, "RESET_HANDSHAKE_COMPLETE")

                if (extraCaptureMs > 0) {
                    Log.i(TAG, "EXTRA_CAPTURE_START ms=$extraCaptureMs")
                    val interrupted = withTimeoutOrNull(extraCaptureMs) { disconnectDeferred.await() }
                    if (interrupted == false) {
                        Log.i(TAG, "EXTRA_CAPTURE_INTERRUPTED_BY_DISCONNECT")
                    } else {
                        Log.i(TAG, "EXTRA_CAPTURE_END")
                    }
                }

                Log.i(TAG, "LISTEN_END notifications=$notificationCount bytes=$notifiedBytes")
                Log.i(TAG, "PAIRING_END success=true")
                Log.i(TAG, "========================================")
                true
            } finally {
                flushDump()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun performWrite(
        gatt: BluetoothGatt,
        targetUuid: UUID,
        deferred: CompletableDeferred<Boolean>,
    ) {
        val service = gattCacheService
        val targetChar = service?.getCharacteristic(targetUuid)
        if (targetChar == null) {
            Log.e(TAG, "EPHEMERAL_WRITE_TARGET_NOT_FOUND: $targetUuid")
            deferred.complete(false)
            return
        }
        Log.i(TAG, "EPHEMERAL_WRITE_START: uuid=$targetUuid")

        targetChar.value = byteArrayOf(0x00)
        targetChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(targetChar, byteArrayOf(0x00), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(targetChar)
        }
        Log.i(TAG, "WRITE_QUEUED result=$writeResult")
    }

    @SuppressLint("MissingPermission")
    private fun enableGattlinkNotifications(
        gatt: BluetoothGatt,
        deferred: CompletableDeferred<Boolean>,
    ) {
        val gattlinkService = gatt.services.find { it.uuid == GATTLINK_SERVICE }
        val transmitChar = gattlinkService?.getCharacteristic(GATTLINK_TRANSMIT)
        if (transmitChar == null) {
            Log.w(TAG, "GATTLINK_TRANSMIT_NOT_FOUND: $GATTLINK_TRANSMIT")
            deferred.complete(false)
            return
        }

        val notifOk = gatt.setCharacteristicNotification(transmitChar, true)
        Log.i(TAG, "setCharacteristicNotification=$notifOk")

        val ccc = transmitChar.getDescriptor(CCC_DESCRIPTOR)
        if (ccc == null) {
            Log.w(TAG, "CCC_DESCRIPTOR_NOT_FOUND")
            deferred.complete(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(ccc, byteArrayOf(0x01, 0x00))
        } else {
            @Suppress("DEPRECATION")
            ccc.value = byteArrayOf(0x01, 0x00)
            gatt.writeDescriptor(ccc)
        }
        Log.i(TAG, "CCC_WRITE_QUEUED")
    }

    @SuppressLint("MissingPermission")
    private fun sendResetComplete(gatt: BluetoothGatt) {
        val gattlinkService = gatt.services.find { it.uuid == GATTLINK_SERVICE }
        val receiveChar = gattlinkService?.getCharacteristic(GATTLINK_RECEIVE)
        if (receiveChar == null) {
            Log.e(TAG, "ERROR_GATTLINK_RECEIVE_NOT_FOUND: $GATTLINK_RECEIVE")
            return
        }

        Log.i(TAG, "RESET_COMPLETE_WRITE_START")
        val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                receiveChar,
                GATTLINK_RESET_COMPLETE_PAYLOAD,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
        } else {
            @Suppress("DEPRECATION")
            receiveChar.value = GATTLINK_RESET_COMPLETE_PAYLOAD
            receiveChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(receiveChar)
        }
        Log.i(TAG, "RESET_COMPLETE_WRITE_QUEUED result=$writeResult")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.i(TAG, "DISCONNECTING")
        try {
            gatt?.disconnect()
        } catch (_: Exception) {}
        try {
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        gattCacheService = null
        notificationCount = 0
        notifiedBytes = 0
        resetRequestReceived = false
    }

    /**
     * Flush captured Gattlink frames to the timestamped dump file.
     */
    private fun flushDump() {
        val file = dumpFile ?: return
        try {
            Log.i(TAG, "DUMP_FLUSH: bytes=${dumpBuilder.length} chars")
            file.bufferedWriter().use { writer ->
                writer.write(dumpBuilder.toString())
            }
            Log.i(TAG, "DUMP_FILE: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "DUMP_WRITE_FAILED", e)
        }
    }
}
