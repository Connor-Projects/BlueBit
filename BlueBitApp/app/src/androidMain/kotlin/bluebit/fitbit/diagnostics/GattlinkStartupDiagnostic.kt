package bluebit.fitbit.diagnostics

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
import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.bleLogWatchdog
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Focused diagnostic to test the Gattlink startup sequence on Inspire 3.
 *
 * Sequence:
 * 1. Connect GATT
 * 2. Discover services
 * 3. Read AC2F0145 (ephemeral pointer) under AC2F0045
 * 4. Parse 16-byte response as UUID (big-endian)
 * 5. Write 0x00 to parsed UUID under AC2F0045
 * 6. Enable notifications on ABBAFF02 (Gattlink transmit)
 * 7. Listen for ~10 seconds, log all Gattlink traffic
 * 8. Disconnect
 *
 * Based on APK GattDatabaseValidator.java and community evidence
 * (philldk, Gadgetbridge #504).
 */
class GattlinkStartupDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitGattlinkStartup"
        private const val LISTEN_DURATION_MS = 10000L

        private val GATT_CACHE_SERVICE_UUID = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
        private val EPHEMERAL_POINTER_UUID = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")

        private val GATTLINK_SERVICE_UUID = UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")
        private val GATTLINK_TRANSMIT_UUID = UUID.fromString("ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null
    private var gattCacheService: android.bluetooth.BluetoothGattService? = null
    private var targetWriteUuid: UUID? = null
    private var notifiedBytes = 0
    private var notificationCount = 0

    @SuppressLint("MissingPermission")
    suspend fun runDiagnostic(device: BluetoothDevice): Boolean {
        bleLogWatchdog.info(BleLogTag.CONNECTION, "GattlinkStartup: START device=${device.address}")
        Log.i(TAG, "========================================")
        Log.i(TAG, "DIAGNOSTIC_START: ${device.name} ${device.address}")

        val connectedDeferred = CompletableDeferred<Boolean>()
        val servicesDiscoveredDeferred = CompletableDeferred<Boolean>()
        val readDeferred = CompletableDeferred<Boolean>()
        val writeDeferred = CompletableDeferred<Boolean>()
        val notificationsEnabledDeferred = CompletableDeferred<Boolean>()

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
                bleLogWatchdog.info(BleLogTag.CONNECTION, "GattlinkStartup: state=$stateName status=$status")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT_CONNECTED: requesting service discovery")
                    gatt.discoverServices()
                    connectedDeferred.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "GATT_DISCONNECTED")
                    bleLogWatchdog.info(BleLogTag.CONNECTION, "GattlinkStartup: DISCONNECTED")
                    connectedDeferred.complete(false)
                    servicesDiscoveredDeferred.complete(false)
                    readDeferred.complete(false)
                    writeDeferred.complete(false)
                    notificationsEnabledDeferred.complete(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i(TAG, "GATT_DISCOVERY_COMPLETE status=$status services=${gatt.services.size}")
                bleLogWatchdog.info(BleLogTag.GATT_SERVICES, "GattlinkStartup: discovered ${gatt.services.size} services")
                gatt.services.forEach { svc ->
                    Log.i(TAG, "  SERVICE: ${svc.uuid}")
                    svc.characteristics.forEach { ch ->
                        Log.i(TAG, "    CHAR: ${ch.uuid} props=${ch.properties}")
                    }
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "SERVICE_DISCOVERY_FAILED status=$status")
                    servicesDiscoveredDeferred.complete(false)
                    return
                }

                val cacheService = gatt.services.find { it.uuid == GATT_CACHE_SERVICE_UUID }
                if (cacheService == null) {
                    Log.e(TAG, "CACHE_SERVICE_NOT_FOUND: $GATT_CACHE_SERVICE_UUID")
                    servicesDiscoveredDeferred.complete(false)
                    return
                }
                gattCacheService = cacheService
                Log.i(TAG, "CACHE_SERVICE_FOUND: ${cacheService.uuid}")
                bleLogWatchdog.info(BleLogTag.GATT_SERVICES, "GattlinkStartup: cache service found")

                val ephemeralChar = cacheService.getCharacteristic(EPHEMERAL_POINTER_UUID)
                if (ephemeralChar == null) {
                    Log.e(TAG, "EPHEMERAL_POINTER_NOT_FOUND: $EPHEMERAL_POINTER_UUID")
                    servicesDiscoveredDeferred.complete(false)
                    return
                }
                Log.i(TAG, "EPHEMERAL_POINTER_FOUND: ${ephemeralChar.uuid}")
                servicesDiscoveredDeferred.complete(true)

                // Step: read ephemeral pointer
                Log.i(TAG, "AC2F0145_READ_START")
                bleLogWatchdog.info(BleLogTag.GATT_READ, "GattlinkStartup: reading AC2F0145")
                val readResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.readCharacteristic(ephemeralChar)
                } else {
                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(ephemeralChar)
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
                bleLogWatchdog.info(BleLogTag.GATT_READ, "GattlinkStartup: read result status=$status len=${value.size}")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "READ_FAILED status=$status")
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
                bleLogWatchdog.info(BleLogTag.GATT_READ, "GattlinkStartup: ephemeral UUID=$parsedUuid")
                targetWriteUuid = parsedUuid
                readDeferred.complete(true)

                // Step: write 0x00 to ephemeral UUID
                performWrite(gatt, parsedUuid, writeDeferred)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.i(TAG, "EPHEMERAL_WRITE_RESULT uuid=${characteristic.uuid} status=$status")
                bleLogWatchdog.info(BleLogTag.GATT_WRITE, "GattlinkStartup: write result status=$status")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "WRITE_FAILED status=$status")
                    writeDeferred.complete(false)
                    return
                }
                Log.i(TAG, "WRITE_SUCCESS")
                writeDeferred.complete(true)

                // Step: enable Gattlink notifications
                enableGattlinkNotifications(gatt, notificationsEnabledDeferred)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                    Log.i(TAG, "CCC_WRITE_RESULT status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "NOTIFICATIONS_ENABLED")
                        bleLogWatchdog.info(BleLogTag.GATT_NOTIFY, "GattlinkStartup: notifications enabled")
                        notificationsEnabledDeferred.complete(true)
                    } else {
                        Log.e(TAG, "NOTIFICATIONS_ENABLE_FAILED status=$status")
                        notificationsEnabledDeferred.complete(false)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == GATTLINK_TRANSMIT_UUID) {
                    notificationCount++
                    notifiedBytes += value.size
                    val hex = if (value.size <= 32) {
                        value.joinToString("") { "%02X".format(it) }
                    } else {
                        value.take(32).joinToString("") { "%02X".format(it) } + "...(${value.size} total)"
                    }
                    Log.i(TAG, "GATTLINK_TRAFFIC_DETECTED #$notificationCount len=${value.size} hex=$hex")
                    bleLogWatchdog.info(BleLogTag.GATT_NOTIFY, "GattlinkStartup: notify #$notificationCount len=${value.size} hex=$hex")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
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

        // Wait for each step sequentially
        val connected = withTimeoutOrNull(15000L) { connectedDeferred.await() } == true
        if (!connected) {
            Log.e(TAG, "STEP_FAILED: connection")
            disconnect()
            return false
        }

        val servicesDiscovered = withTimeoutOrNull(15000L) { servicesDiscoveredDeferred.await() } == true
        if (!servicesDiscovered) {
            Log.e(TAG, "STEP_FAILED: service discovery")
            disconnect()
            return false
        }

        val readOk = withTimeoutOrNull(10000L) { readDeferred.await() } == true
        if (!readOk) {
            Log.e(TAG, "STEP_FAILED: read AC2F0145")
            disconnect()
            return false
        }

        val writeOk = withTimeoutOrNull(10000L) { writeDeferred.await() } == true
        if (!writeOk) {
            Log.e(TAG, "STEP_FAILED: write ephemeral")
            disconnect()
            return false
        }

        val notifOk = withTimeoutOrNull(10000L) { notificationsEnabledDeferred.await() } == true
        if (!notifOk) {
            Log.w(TAG, "STEP_WARNING: notifications not enabled, but will still listen")
        }

        // Step: listen for Gattlink traffic
        Log.i(TAG, "LISTEN_START duration=${LISTEN_DURATION_MS}ms")
        bleLogWatchdog.info(BleLogTag.CONNECTION, "GattlinkStartup: listening for ${LISTEN_DURATION_MS}ms")
        delay(LISTEN_DURATION_MS)

        Log.i(TAG, "LISTEN_END notifications=$notificationCount bytes=$notifiedBytes")
        bleLogWatchdog.info(BleLogTag.CONNECTION, "GattlinkStartup: listened notifications=$notificationCount bytes=$notifiedBytes")

        disconnect()
        Log.i(TAG, "DIAGNOSTIC_END success=true")
        Log.i(TAG, "========================================")
        return true
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
        bleLogWatchdog.info(BleLogTag.GATT_WRITE, "GattlinkStartup: writing 0x00 to $targetUuid")

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
        val gattlinkService = gatt.services.find { it.uuid == GATTLINK_SERVICE_UUID }
        val transmitChar = gattlinkService?.getCharacteristic(GATTLINK_TRANSMIT_UUID)
        if (transmitChar == null) {
            Log.w(TAG, "GATTLINK_TRANSMIT_NOT_FOUND: $GATTLINK_TRANSMIT_UUID")
            deferred.complete(false)
            return
        }

        Log.i(TAG, "ENABLING_NOTIFICATIONS: ${transmitChar.uuid}")
        val notifOk = gatt.setCharacteristicNotification(transmitChar, true)
        Log.i(TAG, "setCharacteristicNotification=$notifOk")

        val ccc = transmitChar.getDescriptor(CCC_DESCRIPTOR_UUID)
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
        targetWriteUuid = null
        notificationCount = 0
        notifiedBytes = 0
    }
}
