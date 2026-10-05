package bluebit.fitbit.diagnostics

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Diagnostic experiment for Fitbit Inspire 3 GattCache protocol.
 *
 * Reproduces the first confirmed APK post-connection operation:
 * 1. READ AC2F0145 under AC2F0045
 * 2. Parse returned bytes as UUID
 * 3. WRITE [0x00] with WRITE_NO_RESPONSE to that UUID under AC2F0045
 *
 * Based on APK analysis of GattDatabaseValidator.java.
 */
class FitbitGattCacheDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitGattCache"
        private val GATT_CACHE_SERVICE_UUID = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
        private val EPHEMERAL_POINTER_UUID = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")
    }

    private var gatt: BluetoothGatt? = null
    private var targetWriteUuid: UUID? = null
    private var gattCacheService: android.bluetooth.BluetoothGattService? = null

    fun startExperiment(device: BluetoothDevice) {
        Log.i(TAG, "========== GATTCACHE EXPERIMENT ==========")
        Log.i(TAG, "GATTCACHE_EXPERIMENT_START")
        Log.i(TAG, "DEVICE_NAME=${device.name ?: "null"}")
        Log.i(TAG, "DEVICE_ADDRESS=${device.address}")

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val stateName = when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "UNKNOWN($newState)"
                }
                Log.i(TAG, "CONNECTION_STATE status=$status newState=$stateName")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT_CONNECTED: requesting service discovery")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "GATT_DISCONNECTED")
                    this@FitbitGattCacheDiagnostic.gatt = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "SERVICE_DISCOVERY_FAILED status=$status")
                    disconnect()
                    return
                }
                Log.i(TAG, "SERVICES_DISCOVERED status=0 services=${gatt.services.size}")

                val cacheService = gatt.services.find { it.uuid == GATT_CACHE_SERVICE_UUID }
                if (cacheService == null) {
                    Log.e(TAG, "SERVICE_NOT_FOUND uuid=$GATT_CACHE_SERVICE_UUID")
                    disconnect()
                    return
                }
                gattCacheService = cacheService
                Log.i(TAG, "SERVICE_FOUND uuid=${cacheService.uuid} type=${cacheService.type}")

                // Log all characteristics in the service
                cacheService.characteristics.forEachIndexed { index, ch ->
                    Log.i(TAG, "  Characteristic[$index]: uuid=${ch.uuid} properties=${decodeProperties(ch.properties)}")
                }

                val pointerChar = cacheService.getCharacteristic(EPHEMERAL_POINTER_UUID)
                if (pointerChar == null) {
                    Log.e(TAG, "EPHEMERAL_POINTER_NOT_FOUND uuid=$EPHEMERAL_POINTER_UUID")
                    disconnect()
                    return
                }
                Log.i(TAG, "EPHEMERAL_POINTER_FOUND uuid=${pointerChar.uuid}")
                Log.i(TAG, "EPHEMERAL_POINTER_PROPERTIES=${decodeProperties(pointerChar.properties)}")
                Log.i(TAG, "EPHEMERAL_POINTER_PERMISSIONS=${decodePermissions(pointerChar.permissions)}")

                if ((pointerChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                    Log.e(TAG, "EPHEMERAL_POINTER_NOT_READABLE")
                    disconnect()
                    return
                }

                Log.i(TAG, "READ_REQUESTING uuid=${pointerChar.uuid}")
                val readResult = gatt.readCharacteristic(pointerChar)
                Log.i(TAG, "READ_REQUESTED result=$readResult")
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                Log.i(TAG, "READ_COMPLETE uuid=${characteristic.uuid} status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "READ_FAILED status=$status")
                    disconnect()
                    return
                }

                Log.i(TAG, "VALUE_LENGTH=${value.size}")
                Log.i(TAG, "VALUE_HEX=${value.joinToString("") { "%02X".format(it) }}")

                if (value.size != 16) {
                    Log.w(TAG, "VALUE_UNEXPECTED_LENGTH expected=16 actual=${value.size}")
                }

                // Parse bytes as UUID (big-endian, per UuidHelperKt.toUuid)
                val parsedUuid = try {
                    val bb = ByteBuffer.wrap(value)
                    UUID(bb.long, bb.long)
                } catch (e: Exception) {
                    Log.e(TAG, "VALUE_PARSE_FAILED", e)
                    disconnect()
                    return
                }
                Log.i(TAG, "VALUE_UUID=$parsedUuid")
                targetWriteUuid = parsedUuid

                // Now perform the write to the parsed UUID
                performWrite(gatt, parsedUuid)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Handled by the new API above on Android 13+
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.i(TAG, "WRITE_COMPLETE uuid=${characteristic.uuid} status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "WRITE_SUCCESS")
                } else {
                    Log.e(TAG, "WRITE_FAILED status=$status")
                }
                disconnect()
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    private fun performWrite(gatt: BluetoothGatt, targetUuid: UUID) {
        val service = gattCacheService
        if (service == null) {
            Log.e(TAG, "WRITE_NO_SERVICE")
            disconnect()
            return
        }

        val targetChar = service.getCharacteristic(targetUuid)
        if (targetChar == null) {
            Log.e(TAG, "WRITE_TARGET_NOT_FOUND uuid=$targetUuid")
            disconnect()
            return
        }

        Log.i(TAG, "WRITE_TARGET_UUID=$targetUuid")
        Log.i(TAG, "WRITE_TARGET_PROPERTIES=${decodeProperties(targetChar.properties)}")
        Log.i(TAG, "WRITE_VALUE_HEX=00")
        Log.i(TAG, "WRITE_TYPE=WRITE_NO_RESPONSE")

        targetChar.value = byteArrayOf(0x00)
        targetChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        Log.i(TAG, "WRITE_REQUESTED uuid=$targetUuid")
        val writeResult = gatt.writeCharacteristic(targetChar)
        Log.i(TAG, "WRITE_QUEUED result=$writeResult")
    }

    fun disconnect() {
        val g = gatt
        if (g == null) {
            Log.w(TAG, "DISCONNECT_NO_GATT")
            return
        }
        Log.i(TAG, "DISCONNECTING")
        g.disconnect()
        g.close()
        gatt = null
        Log.i(TAG, "DISCONNECTED")
        Log.i(TAG, "========== END GATTCACHE EXPERIMENT ==========")
    }

    private fun decodeProperties(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return if (list.isEmpty()) "NONE(0x${properties.toString(16)})" else list.joinToString(", ")
    }

    private fun decodePermissions(permissions: Int): String {
        val list = mutableListOf<String>()
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ != 0) list.add("READ")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) list.add("WRITE")
        return if (list.isEmpty()) "NONE(0x${permissions.toString(16)})" else list.joinToString(", ")
    }
}
