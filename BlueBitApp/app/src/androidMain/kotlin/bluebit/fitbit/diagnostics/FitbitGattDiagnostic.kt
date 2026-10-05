package bluebit.fitbit.diagnostics

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Diagnostic GATT connector for Fitbit devices.
 *
 * Connects, discovers services, enumerates the full GATT database,
 * and disconnects cleanly.  All events are logged with tag BlueBitGatt.
 */
class FitbitGattDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitGatt"
    }

    private var gatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private val operationMutex = Mutex()
    private var pendingDiscovery: CompletableDeferred<Boolean>? = null

    /**
     * Connect to the given device, discover services, and log the complete GATT database.
     * Returns true if service discovery succeeded.
     */
    suspend fun connectAndDiscover(device: BluetoothDevice): Boolean {
        deviceAddress = device.address
        val bondState = formatBondState(device.bondState)

        Log.i(TAG, "========== FITBIT GATT DIAGNOSTIC ==========")
        Log.i(TAG, "FITBIT_SELECTED")
        Log.i(TAG, "DEVICE_NAME=${device.name ?: "null"}")
        Log.i(TAG, "DEVICE_ADDRESS=${device.address}")
        Log.i(TAG, "BOND_STATE=$bondState")
        Log.i(TAG, "GATT_CONNECT_START")

        val discoveryDeferred = CompletableDeferred<Boolean>()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val stateName = when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "UNKNOWN($newState)"
                }
                Log.i(TAG, "GATT_CONNECTION_STATE status=$status newState=$stateName")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT_SERVICES_DISCOVERY_START")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    discoveryDeferred.complete(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT_SERVICES_DISCOVERED status=0")
                    enumerateGattDatabase(gatt)
                    discoveryDeferred.complete(true)
                } else {
                    Log.e(TAG, "GATT_SERVICES_DISCOVERED status=$status (failed)")
                    discoveryDeferred.complete(false)
                }
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
                value: ByteArray,
            ) {
                // Not used in this diagnostic (read-only enumeration)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                // Not used in this diagnostic (read-only enumeration)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "GATT_MTU_CHANGED mtu=$mtu status=$status")
            }

            override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                Log.i(TAG, "GATT_PHY_UPDATE txPhy=$txPhy rxPhy=$rxPhy status=$status")
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }

        return discoveryDeferred.await()
    }

    private fun enumerateGattDatabase(gatt: BluetoothGatt) {
        val services = gatt.services
        Log.i(TAG, "")
        Log.i(TAG, "========== DEVICE GATT DATABASE ==========")
        Log.i(TAG, "Device:")
        Log.i(TAG, "  Name: ${gatt.device?.name ?: "null"}")
        Log.i(TAG, "  Address: ${gatt.device?.address ?: "null"}")
        Log.i(TAG, "  Services: ${services.size}")
        Log.i(TAG, "")

        services.forEachIndexed { svcIndex, service ->
            val type = when (service.type) {
                BluetoothGattService.SERVICE_TYPE_PRIMARY -> "PRIMARY"
                BluetoothGattService.SERVICE_TYPE_SECONDARY -> "SECONDARY"
                else -> "UNKNOWN(${service.type})"
            }
            Log.i(TAG, "Service [$svcIndex]:")
            Log.i(TAG, "  UUID: ${service.uuid}")
            Log.i(TAG, "  Type: $type")

            service.characteristics.forEachIndexed { chIndex, characteristic ->
                val props = decodeProperties(characteristic.properties)
                val permissions = decodePermissions(characteristic.permissions)
                Log.i(TAG, "  Characteristic [$chIndex]:")
                Log.i(TAG, "    UUID: ${characteristic.uuid}")
                Log.i(TAG, "    Properties: $props")
                Log.i(TAG, "    Permissions: $permissions")

                characteristic.descriptors.forEachIndexed { descIndex, descriptor ->
                    val descPerms = decodePermissions(descriptor.permissions)
                    Log.i(TAG, "    Descriptor [$descIndex]:")
                    Log.i(TAG, "      UUID: ${descriptor.uuid}")
                    Log.i(TAG, "      Permissions: $descPerms")
                }
            }
            Log.i(TAG, "")
        }

        Log.i(TAG, "========== END DEVICE GATT DATABASE ==========")
        Log.i(TAG, "")
    }

    fun disconnect() {
        val g = gatt
        if (g == null) {
            Log.w(TAG, "GATT_DISCONNECT: no active GATT connection")
            return
        }
        Log.i(TAG, "GATT_DISCONNECT: calling disconnect()")
        g.disconnect()
        // close() should be called after onConnectionStateChange reports DISCONNECTED
        // but we also call it here as a safety net
        g.close()
        this.gatt = null
        Log.i(TAG, "GATT_DISCONNECT: closed")
    }

    private fun decodeProperties(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) list.add("SIGNED_WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) list.add("EXTENDED_PROPS")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) list.add("BROADCAST")
        return if (list.isEmpty()) "NONE(0x${properties.toString(16)})" else list.joinToString(", ")
    }

    private fun decodePermissions(permissions: Int): String {
        val list = mutableListOf<String>()
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ != 0) list.add("READ")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) list.add("READ_ENCRYPTED")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0) list.add("READ_ENCRYPTED_MITM")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) list.add("WRITE")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0) list.add("WRITE_ENCRYPTED")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0) list.add("WRITE_ENCRYPTED_MITM")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED != 0) list.add("WRITE_SIGNED")
        if (permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM != 0) list.add("WRITE_SIGNED_MITM")
        return if (list.isEmpty()) "NONE(0x${permissions.toString(16)})" else list.joinToString(", ")
    }

    fun formatBondState(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }
}
