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
import com.fitbit.goldengate.bindings.Bridge
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlinkStackConfig
import com.fitbit.goldengate.bindings.stack.Stack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Controlled DTLS bootstrap compatibility probe for Fitbit Inspire 3.
 *
 * Establishes a BLE connection, sets up Gattlink, starts the GoldenGate
 * DTLS/Gattlink stack using the public bootstrap PSK, and stops as soon as
 * the DTLS session is established. No CoAP application requests are sent.
 */
class DtlsBootstrapProbe(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitDTLSProbe"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val GATT_STEP_TIMEOUT_MS = 10_000L

        private val GATTLINK_SERVICE = UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")
        private val RECEIVE_CHAR = UUID.fromString("ABBAFF01-E56A-484C-B832-8B17CF6CBFE8")
        private val TRANSMIT_CHAR = UUID.fromString("ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
        private val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private val GATTDB_CONFIRM_SERVICE = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
        private val EPHEMERAL_POINTER_CHAR = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")

        private val GENERIC_ATTRIBUTE_SERVICE = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
        private val SERVICE_CHANGED_CHAR = UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null
    private var bridge: Bridge? = null
    private var stack: Stack? = null
    private var coapEndpoint: CoapEndpoint? = null
    private var transmitChar: BluetoothGattCharacteristic? = null
    private var ephemeralCharUuid: UUID? = null
    private var negotiatedMtu = 23

    @SuppressLint("MissingPermission")
    suspend fun runProbe(device: BluetoothDevice): ProbeResult {
        Log.i(TAG, "========================================")
        Log.i(TAG, "PROBE_START: name=${device.name} address=${device.address}")

        val machine = DtlsProbeStateMachine()
        machine.transitionTo(ProbeStage.BLE_CONNECTION)

        val connectionDeferred = CompletableDeferred<Boolean>()
        val serviceDiscoveryDeferred = CompletableDeferred<Boolean>()
        val serviceChangedEnabledDeferred = CompletableDeferred<Boolean>()
        val gattlinkNotificationsEnabledDeferred = CompletableDeferred<Boolean>()
        val dtlsSessionDeferred = CompletableDeferred<Boolean>()

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestMtu(185)
                    } else {
                        gatt.discoverServices()
                    }
                    connectionDeferred.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionDeferred.complete(false)
                    completeAllDeferredsFalse()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.i(TAG, "onServicesDiscovered status=$status services=${gatt.services.size}")
                serviceDiscoveryDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (characteristic.uuid == EPHEMERAL_POINTER_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Ephemeral pointer read: ${value.size} bytes")
                    handleEphemeralPointerRead(gatt, value, machine, gattlinkNotificationsEnabledDeferred)
                } else {
                    Log.i(TAG, "Characteristic read: ${characteristic.uuid} status=$status len=${value.size}")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.i(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
                if (characteristic.uuid == ephemeralCharUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "GattDatabase validation write complete")
                    } else {
                        Log.w(TAG, "GattDatabase validation write failed, continuing")
                    }
                    machine.transitionTo(ProbeStage.GATTLINK_SETUP)
                    enableGattlinkNotifications(gatt, gattlinkNotificationsEnabledDeferred)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val charUuid = descriptor.characteristic?.uuid
                Log.i(TAG, "onDescriptorWrite uuid=${descriptor.uuid} char=$charUuid status=$status")
                when {
                    descriptor.uuid == CCC_DESCRIPTOR && charUuid == SERVICE_CHANGED_CHAR -> {
                        serviceChangedEnabledDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }
                    descriptor.uuid == CCC_DESCRIPTOR && charUuid == TRANSMIT_CHAR -> {
                        gattlinkNotificationsEnabledDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == TRANSMIT_CHAR) {
                    machine.recordRx()
                    bridge?.receiveFromBle(value)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    onCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
                }
            }

            private fun completeAllDeferredsFalse() {
                if (!serviceDiscoveryDeferred.isCompleted) serviceDiscoveryDeferred.complete(false)
                if (!serviceChangedEnabledDeferred.isCompleted) serviceChangedEnabledDeferred.complete(false)
                if (!gattlinkNotificationsEnabledDeferred.isCompleted) gattlinkNotificationsEnabledDeferred.complete(false)
                if (!dtlsSessionDeferred.isCompleted) dtlsSessionDeferred.complete(false)
            }
        }

        try {
            Log.i(TAG, "GoldenGate.init()")
            GoldenGate.init()
            Log.i(TAG, "GoldenGate version: ${GoldenGate.getVersion()}")
        } catch (e: Exception) {
            Log.e(TAG, "GoldenGate.init() failed", e)
            return cleanup(machine.fail("GoldenGate.init() failed: ${e.message}"))
        }

        bridge = Bridge(
            onTxData = { data ->
                machine.recordTx()
                sendToBle(data)
            },
            onRxData = { data ->
                Log.i(TAG, "Bridge RX data: ${data.size} bytes")
            }
        )

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }

        val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { connectionDeferred.await() } == true
        if (!connected) {
            return cleanup(machine.fail("BLE connection failed or timed out"))
        }

        machine.transitionTo(ProbeStage.BLE_DISCOVERY)
        val discovered = withTimeoutOrNull(SERVICE_DISCOVERY_TIMEOUT_MS) { serviceDiscoveryDeferred.await() } == true
        if (!discovered) {
            return cleanup(machine.fail("Service discovery failed"))
        }

        val gattlinkService = gatt?.services?.find { it.uuid == GATTLINK_SERVICE }
        if (gattlinkService == null) {
            return cleanup(machine.fail("Gattlink service not found"))
        }

        val receiveChar = gattlinkService.getCharacteristic(RECEIVE_CHAR)
        transmitChar = gattlinkService.getCharacteristic(TRANSMIT_CHAR)
        if (receiveChar == null || transmitChar == null) {
            return cleanup(machine.fail("Gattlink characteristics missing"))
        }

        machine.transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK)
        enableServiceChangedIndications(gatt!!, serviceChangedEnabledDeferred)

        val serviceChangedEnabled = withTimeoutOrNull(GATT_STEP_TIMEOUT_MS) { serviceChangedEnabledDeferred.await() } == true
        if (!serviceChangedEnabled) {
            Log.w(TAG, "Service Changed indications not enabled, continuing")
        }

        // After Service Changed is enabled, perform GattDatabase validation,
        // which will chain into enabling Gattlink notifications.
        readEphemeralPointer(gatt!!, machine, gattlinkNotificationsEnabledDeferred)

        val gattlinkNotificationsEnabled = withTimeoutOrNull(GATT_STEP_TIMEOUT_MS) { gattlinkNotificationsEnabledDeferred.await() } == true
        if (!gattlinkNotificationsEnabled) {
            return cleanup(machine.fail("Gattlink notifications not enabled"))
        }

        machine.transitionTo(ProbeStage.DTLS_HANDSHAKE)
        try {
            startStack(device, machine, dtlsSessionDeferred)
        } catch (e: Exception) {
            Log.e(TAG, "Stack start failed", e)
            return cleanup(machine.fail("Stack start failed: ${e.message}"))
        }

        val sessionEstablished = withTimeoutOrNull(DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS) {
            dtlsSessionDeferred.await()
        } == true

        if (!sessionEstablished) {
            return cleanup(machine.fail("DTLS handshake timed out after ${DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS}ms"))
        }

        val result = machine.success()
        cleanup(result)
        return result
    }

    private fun enableServiceChangedIndications(gatt: BluetoothGatt, deferred: CompletableDeferred<Boolean>) {
        val genericAttrService = gatt.services.find { it.uuid == GENERIC_ATTRIBUTE_SERVICE }
        val serviceChangedChar = genericAttrService?.getCharacteristic(SERVICE_CHANGED_CHAR)
        if (serviceChangedChar != null) {
            Log.i(TAG, "Enabling Service Changed indications")
            val notifOk = gatt.setCharacteristicNotification(serviceChangedChar, true)
            Log.i(TAG, "setCharacteristicNotification(ServiceChanged)=$notifOk")
            val ccc = serviceChangedChar.getDescriptor(CCC_DESCRIPTOR)
            if (ccc != null) {
                ccc.value = byteArrayOf(0x02, 0x00)
                gatt.writeDescriptor(ccc)
            } else {
                deferred.complete(true)
            }
        } else {
            Log.w(TAG, "Service Changed characteristic not found, skipping")
            deferred.complete(true)
        }
    }

    private fun readEphemeralPointer(
        gatt: BluetoothGatt,
        machine: DtlsProbeStateMachine,
        notificationsDeferred: CompletableDeferred<Boolean>,
    ) {
        val gattDbService = gatt.services.find { it.uuid == GATTDB_CONFIRM_SERVICE }
        val ephemeralPointerChar = gattDbService?.getCharacteristic(EPHEMERAL_POINTER_CHAR)
        if (ephemeralPointerChar != null) {
            Log.i(TAG, "Reading ephemeral pointer AC2F0145")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readCharacteristic(ephemeralPointerChar)
            } else {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(ephemeralPointerChar)
            }
        } else {
            Log.w(TAG, "GattDatabaseConfirmationService not found, skipping validation")
            machine.transitionTo(ProbeStage.GATTLINK_SETUP)
            enableGattlinkNotifications(gatt, notificationsDeferred)
        }
    }

    private fun handleEphemeralPointerRead(
        gatt: BluetoothGatt,
        value: ByteArray,
        machine: DtlsProbeStateMachine,
        notificationsDeferred: CompletableDeferred<Boolean>,
    ) {
        val uuid = parseEphemeralUuid(value)
        if (uuid == null) {
            Log.w(TAG, "Could not parse ephemeral UUID, skipping GattDatabase validation")
            machine.transitionTo(ProbeStage.GATTLINK_SETUP)
            enableGattlinkNotifications(gatt, notificationsDeferred)
            return
        }

        ephemeralCharUuid = uuid
        Log.i(TAG, "Ephemeral characteristic UUID: $uuid")

        val gattDbService = gatt.getService(GATTDB_CONFIRM_SERVICE)
        val ephemeralChar = gattDbService?.getCharacteristic(uuid)
        if (ephemeralChar != null) {
            Log.i(TAG, "Writing 0x00 to ephemeral char $uuid")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ephemeralChar, byteArrayOf(0x00), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ephemeralChar.value = byteArrayOf(0x00)
                @Suppress("DEPRECATION")
                ephemeralChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ephemeralChar)
            }
        } else {
            Log.w(TAG, "Ephemeral characteristic $uuid not found, skipping validation")
            machine.transitionTo(ProbeStage.GATTLINK_SETUP)
            enableGattlinkNotifications(gatt, notificationsDeferred)
        }
    }

    private fun parseEphemeralUuid(value: ByteArray): UUID? {
        return try {
            if (value.size >= 16) {
                val bb = ByteBuffer.wrap(value)
                UUID(bb.long, bb.long)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ephemeral UUID", e)
            null
        }
    }

    private fun enableGattlinkNotifications(gatt: BluetoothGatt, deferred: CompletableDeferred<Boolean>) {
        val char = transmitChar ?: run {
            Log.e(TAG, "Transmit characteristic not available for notification setup")
            deferred.complete(false)
            return
        }
        Log.i(TAG, "Enabling Gattlink notifications on ${char.uuid}")
        val notifOk = gatt.setCharacteristicNotification(char, true)
        Log.i(TAG, "setCharacteristicNotification(GattlinkTransmit)=$notifOk")
        val ccc = char.getDescriptor(CCC_DESCRIPTOR)
        if (ccc == null) {
            Log.e(TAG, "CCC descriptor not found on Gattlink transmit")
            deferred.complete(false)
            return
        }
        ccc.value = byteArrayOf(0x01, 0x00)
        gatt.writeDescriptor(ccc)
    }

    private fun startStack(
        device: BluetoothDevice,
        machine: DtlsProbeStateMachine,
        dtlsSessionDeferred: CompletableDeferred<Boolean>,
    ) {
        val b = bridge ?: throw IllegalStateException("Bridge is null")

        val dsngConfig = DtlsSocketNetifGattlinkStackConfig()
        Log.i(TAG, "Creating Stack with config='${dsngConfig.configDescriptor}'")

        val newStack = Stack(
            nodeKey = BluetoothAddressNodeKey(device.address),
            transportSinkPtr = b.txSink.getThisPointer(),
            transportSourcePtr = b.rxSource.getThisPointer(),
            stackConfig = dsngConfig,
            isNode = false
        )
        newStack.setDtlsStatusListener { state, code, pskIdentity ->
            val pskStr = pskIdentity?.toString(Charsets.UTF_8) ?: "null"
            val pskHex = pskIdentity?.joinToString("") { "%02X".format(it) } ?: "null"
            Log.i(TAG, "onDtlsStatusChange state=$state code=$code psk='$pskStr' pskHex=$pskHex")
            when (machine.onDtlsStatusChange(state, code)) {
                DtlsStatusInterpretation.SESSION_ESTABLISHED -> dtlsSessionDeferred.complete(true)
                DtlsStatusInterpretation.ERROR -> dtlsSessionDeferred.complete(false)
                DtlsStatusInterpretation.PENDING -> { /* wait */ }
            }
        }
        stack = newStack

        val endpoint = CoapEndpoint()
        coapEndpoint = endpoint
        endpoint.attach(newStack)
        Log.i(TAG, "CoapEndpoint attached (no requests will be sent)")

        b.start()
        newStack.start()

        val stackMtu = (negotiatedMtu - 3).coerceAtLeast(20)
        val mtuResult = newStack.updateMtu(stackMtu)
        Log.i(TAG, "Stack.updateMtu($stackMtu) returned $mtuResult")
    }

    private fun sendToBle(data: ByteArray) {
        val g = gatt ?: return
        val service = g.getService(GATTLINK_SERVICE)
        val receiveChar = service?.getCharacteristic(RECEIVE_CHAR) ?: return

        val hex = data.joinToString("") { "%02X".format(it) }
        Log.i(TAG, "TX → ABBAFF01: ${data.size} bytes: $hex")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(receiveChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            receiveChar.value = data
            @Suppress("DEPRECATION")
            receiveChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(receiveChar)
        }
    }

    private fun cleanup(result: ProbeResult): ProbeResult {
        Log.i(TAG, "Cleanup: stage=${result.stage} success=${result.success} tx=${result.txCount} rx=${result.rxCount}")
        try { coapEndpoint?.detach() } catch (_: Exception) { }
        try { coapEndpoint?.close() } catch (_: Exception) { }
        try { stack?.close() } catch (_: Exception) { }
        try { bridge?.close() } catch (_: Exception) { }
        gatt?.disconnect()
        gatt?.close()

        gatt = null
        coapEndpoint = null
        stack = null
        bridge = null
        transmitChar = null
        ephemeralCharUuid = null

        Log.i(TAG, "PROBE_END: ${result.stage}")
        Log.i(TAG, "========================================")
        return result
    }
}
