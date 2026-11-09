package bluebit.fitcloud.diagnostics

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Diagnostic GATT connector for read-only P6 GATT discovery.
 *
 * Connects to the given [BluetoothDevice], discovers services, enumerates the
 * complete GATT database (services, characteristics with properties/permissions,
 * descriptors with permissions), and disconnects cleanly.
 *
 * This diagnostic is read-only: it never writes to a characteristic or descriptor
 * and never enables notifications or indications.
 *
 * All events are logged with tag [TAG] and the full enumeration is also written
 * to a timestamped file under the app's external files directory so it can be
 * pulled with `adb pull` and archived under `Reports/`.
 */
class GattDiscoveryDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitP6Gatt"
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
        return operationMutex.withLock {
            deviceAddress = device.address
            val bondState = formatBondState(device.bondState)

            Log.i(TAG, "========== P6 GATT DISCOVERY ==========")
            Log.i(TAG, "P6_SELECTED")
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
                        val report = enumerateGattDatabase(gatt)
                        writeReportToFile(device, report)
                        Log.i(TAG, report)
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

            discoveryDeferred.await()
        }
    }

    /**
     * Enumerate the full GATT database read-only and return a formatted report string.
     */
    private fun enumerateGattDatabase(gatt: BluetoothGatt): String {
        val services = gatt.services
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("========== DEVICE GATT DATABASE ==========")
        sb.appendLine("Device:")
        sb.appendLine("  Name: ${gatt.device?.name ?: "null"}")
        sb.appendLine("  Address: ${gatt.device?.address ?: "null"}")
        sb.appendLine("  Services: ${services.size}")
        sb.appendLine("")

        services.forEachIndexed { svcIndex, service ->
            val type = when (service.type) {
                BluetoothGattService.SERVICE_TYPE_PRIMARY -> "PRIMARY"
                BluetoothGattService.SERVICE_TYPE_SECONDARY -> "SECONDARY"
                else -> "UNKNOWN(${service.type})"
            }
            sb.appendLine("Service [$svcIndex]:")
            sb.appendLine("  UUID: ${service.uuid}")
            sb.appendLine("  Type: $type")

            service.characteristics.forEachIndexed { chIndex, characteristic ->
                val props = decodeProperties(characteristic.properties)
                val permissions = decodePermissions(characteristic.permissions)
                sb.appendLine("  Characteristic [$chIndex]:")
                sb.appendLine("    UUID: ${characteristic.uuid}")
                sb.appendLine("    Properties: $props")
                sb.appendLine("    Permissions: $permissions")

                characteristic.descriptors.forEachIndexed { descIndex, descriptor ->
                    val descPerms = decodePermissions(descriptor.permissions)
                    sb.appendLine("    Descriptor [$descIndex]:")
                    sb.appendLine("      UUID: ${descriptor.uuid}")
                    sb.appendLine("      Permissions: $descPerms")
                }
            }
            sb.appendLine("")
        }

        sb.appendLine("========== END DEVICE GATT DATABASE ==========")
        sb.appendLine("")
        return sb.toString()
    }

    /** Write the GATT report to a timestamped file under the app's external files dir. */
    private fun writeReportToFile(device: BluetoothDevice, report: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = (device.name ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "p6_gatt_dump_$timestamp.txt"
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            fileName,
        ).also { parentFile ->
            parentFile.parentFile?.mkdirs()
        }
        try {
            file.writeText(
                buildString {
                    appendLine("P6 GATT Discovery Report")
                    appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("Device Name: ${device.name ?: "null"}")
                    appendLine("Device Address: ${device.address}")
                    appendLine("Bond State: ${formatBondState(device.bondState)}")
                    appendLine("")
                    appendLine(report)
                }
            )
            Log.i(TAG, "GATT_REPORT_WRITTEN: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "GATT_REPORT_WRITE_FAILED: ${e.message}")
        }
    }

    suspend fun disconnect() {
        operationMutex.withLock {
            val g = gatt
            if (g == null) {
                Log.w(TAG, "GATT_DISCONNECT: no active GATT connection")
                return@withLock
            }
            Log.i(TAG, "GATT_DISCONNECT: calling disconnect()")
            g.disconnect()
            g.close()
            this.gatt = null
            Log.i(TAG, "GATT_DISCONNECT: closed")
        }
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
        return if (list.isEmpty()) "NONE(0x${properties.toString(16).uppercase(Locale.US)})" else list.joinToString(", ")
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
        return if (list.isEmpty()) "NONE(0x${permissions.toString(16).uppercase(Locale.US)})" else list.joinToString(", ")
    }

    fun formatBondState(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }
}
