package bluebit.fitbit.diagnostics

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import bluebit.bluetooth.android.DirectBondExperiment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fitbit.goldengate.bindings.Bridge
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlinkStackConfig
import com.fitbit.goldengate.bindings.stack.Stack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Diagnostic experiment: Initialize the actual Fitbit GoldenGate native stack
 * and observe the Gattlink initialization sequence on Inspire 3.
 *
 * APK-derived initialization path:
 * 1. GoldenGate.init() → loads libxp.so, starts RunLoop
 * 2. Create TxSink/RxSource
 * 3. Connect BLE, discover services
 * 4. Create Stack(BluetoothAddressNodeKey, TxSink.ptr, RxSource.ptr, DtlsSocketNetifGattlinkStackConfig("DSNG"))
 * 5. Stack.start() → native "starting gattlink session" → SendResetPacket
 * 6. Observe TX data → write to ABBAFF01
 * 7. Observe ABBAFF02 notifications → feed to RxSource
 */
class FitbitGoldenGateDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitGG_Diag"
        private const val EXPERIMENT_TIMEOUT_MS = 60000L

        private val GATTLINK_SERVICE = UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")
        private val RECEIVE_CHAR = UUID.fromString("ABBAFF01-E56A-484C-B832-8B17CF6CBFE8")
        private val TRANSMIT_CHAR = UUID.fromString("ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
        private val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // GattDatabaseConfirmationService UUIDs (Fitbit proprietary)
        private val GATTDB_CONFIRM_SERVICE = UUID.fromString("AC2F0045-8182-4BE5-91E0-2992E6B40EBB")
        private val EPHEMERAL_POINTER_CHAR = UUID.fromString("AC2F0145-8182-4BE5-91E0-2992E6B40EBB")

        // Generic Attribute Service (standard)
        private val GENERIC_ATTRIBUTE_SERVICE = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
        private val SERVICE_CHANGED_CHAR = UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null
    private var bridge: Bridge? = null
    private var stack: Stack? = null
    private var coapEndpoint: CoapEndpoint? = null
    private var transmitChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutRunnable: Runnable? = null
    private var isRunning = false
    private var txCount = 0
    private var rxCount = 0
    private var ephemeralCharUuid: UUID? = null
    private var initStep = 0 // 0=initial, 1=service_changed, 2=gattdb, 3=gattlink, 4=done
    private var outstandingWrites = 0
    private var lastWriteTimestamp = 0L
    private var negotiatedMtu = 23 // Android default; updated after MTU negotiation

    fun startExperiment(device: BluetoothDevice) {
        if (isRunning) {
            Log.w(TAG, "Experiment already running, ignoring start request")
            return
        }
        isRunning = true
        txCount = 0
        rxCount = 0

        Log.i(TAG, "========================================")
        Log.i(TAG, "GOLDENGATE EXPERIMENT START")
        Log.i(TAG, "DEVICE=${device.name} ADDR=${device.address}")

        // Step 1: Initialize GoldenGate native stack
        try {
            Log.i(TAG, "Step 1: GoldenGate.init()...")
            GoldenGate.init()
            Log.i(TAG, "GoldenGate version: ${GoldenGate.getVersion()}")
        } catch (e: Exception) {
            Log.e(TAG, "GoldenGate.init() failed", e)
            return
        }

        // Step 2: Create Bridge (TxSink + RxSource)
        Log.i(TAG, "Step 2: Creating Bridge...")
        bridge = Bridge(
            onTxData = { data -> sendToBle(data) },
            onRxData = { data -> Log.i(TAG, "RX data logged: ${data.size} bytes") }
        )

        // Step 2b: Skip upfront bonding — Fitbit does NOT call createBond() before GATT connection.
        // Bonding happens later via encrypted characteristic access or CoAP over Gattlink.
        val bondState = device.bondState
        Log.i(TAG, "Step 2b: Device bond state=$bondState (${when(bondState) {
            BluetoothDevice.BOND_NONE -> "NONE"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "UNKNOWN"
        }})")
        Log.i(TAG, "SKIPPING createBond() — bonding happens after GoldenGate linkup")

        // Step 3: Connect BLE
        Log.i(TAG, "Step 3: Connecting BLE...")
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "BLE connection state: status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Log.i(TAG, "Requesting MTU=185")
                        gatt.requestMtu(185)
                    } else {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanup()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU changed: mtu=$mtu status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    cleanup()
                    return
                }
                Log.i(TAG, "Services discovered: ${gatt.services.size}")
                for (svc in gatt.services) {
                    Log.i(TAG, "  Service: ${svc.uuid}")
                    for (char in svc.characteristics) {
                        Log.i(TAG, "    Char: ${char.uuid} props=${char.properties}")
                        for (desc in char.descriptors) {
                            Log.i(TAG, "      Desc: ${desc.uuid}")
                        }
                    }
                }
                handleServicesDiscovered(gatt, device)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                val hex = value.joinToString("") { "%02X".format(it) }
                if (characteristic.uuid == EPHEMERAL_POINTER_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Ephemeral pointer read: ${value.size} bytes: $hex")
                    handleEphemeralPointerRead(gatt, value)
                } else {
                    Log.i(TAG, "Characteristic read: ${characteristic.uuid} status=$status len=${value.size} bytes: $hex")
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
                val uuid = characteristic.uuid
                val elapsed = System.currentTimeMillis() - lastWriteTimestamp
                if (uuid == RECEIVE_CHAR) {
                    outstandingWrites--
                    Log.i(TAG, "onCharacteristicWrite ABBAFF01 status=$status outstanding=$outstandingWrites elapsed=${elapsed}ms")
                } else if (uuid == ephemeralCharUuid && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GattDatabase validation complete: ephemeral char written")
                    enableGattlinkNotifications(gatt)
                } else if (uuid == ephemeralCharUuid) {
                    Log.e(TAG, "Ephemeral char write failed: status=$status")
                    cleanup()
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                when {
                    descriptor.uuid == CCC_DESCRIPTOR && initStep == 1 -> {
                        // Service Changed CCC written
                        Log.i(TAG, "Service Changed CCC write status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            proceedToGattDatabaseValidation(gatt)
                        } else {
                            Log.w(TAG, "Service Changed CCC failed, continuing anyway")
                            proceedToGattDatabaseValidation(gatt)
                        }
                    }
                    descriptor.uuid == CCC_DESCRIPTOR && initStep == 3 -> {
                        // Gattlink CCC written
                        Log.i(TAG, "Gattlink CCC write status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            startGoldenGateStack(device)
                        } else {
                            Log.e(TAG, "Gattlink CCC write failed")
                            cleanup()
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == TRANSMIT_CHAR) {
                    rxCount++
                    Log.i(TAG, "ABBAFF02 notification #$rxCount: ${value.size} bytes: ${value.joinToString("") { "%02X".format(it) }}")
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
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, device: BluetoothDevice) {
        val service = gatt.services.find { it.uuid == GATTLINK_SERVICE }
        if (service == null) {
            Log.e(TAG, "Gattlink service not found")
            cleanup()
            return
        }
        Log.i(TAG, "Gattlink service found")

        val receiveChar = service.getCharacteristic(RECEIVE_CHAR)
        transmitChar = service.getCharacteristic(TRANSMIT_CHAR)

        if (receiveChar == null) {
            Log.e(TAG, "Receive characteristic not found")
        } else {
            Log.i(TAG, "Receive characteristic found: props=${receiveChar.properties}")
        }

        if (transmitChar == null) {
            Log.e(TAG, "Transmit characteristic not found")
            cleanup()
            return
        }
        Log.i(TAG, "Transmit characteristic found: props=${transmitChar!!.properties}")

        // Step 2c: Skip probing — causes GATT queue congestion and delays linkup.
        // probeUnknownServices(gatt)

        // DIRECT BOND EXPERIMENT: Test Android createBond() without bond/control.
        // This runs before GoldenGate to test if the device accepts standard SMP pairing.
        scope.launch {
            Log.i(TAG, "========================================")
            Log.i(TAG, "DIRECT BOND EXPERIMENT")
            Log.i(TAG, "Testing createBond() without bond/control")
            Log.i(TAG, "========================================")
            val experiment = DirectBondExperiment(context)
            experiment.testDirectBond(device)

            // After direct bond experiment, proceed with GoldenGate regardless of result
            Log.i(TAG, "Proceeding to GoldenGate initialization...")
            proceedWithGattInit(gatt)
        }
    }

    private fun proceedWithGattInit(gatt: BluetoothGatt) {
        // Step 3a: Subscribe to Generic Attribute Service Changed (indications) - Fitbit does this first
        val genericAttrService = gatt.services.find { it.uuid == GENERIC_ATTRIBUTE_SERVICE }
        val serviceChangedChar = genericAttrService?.getCharacteristic(SERVICE_CHANGED_CHAR)
        if (serviceChangedChar != null) {
            Log.i(TAG, "Step 3a: Enabling Service Changed indications...")
            initStep = 1
            // CRITICAL: Android requires setCharacteristicNotification BEFORE writing CCC descriptor.
            val notifOk = gatt.setCharacteristicNotification(serviceChangedChar, true)
            Log.i(TAG, "setCharacteristicNotification(ServiceChanged)=$notifOk")
            val ccc = serviceChangedChar.getDescriptor(CCC_DESCRIPTOR)
            if (ccc != null) {
                ccc.value = byteArrayOf(0x02, 0x00) // Enable INDICATIONS
                gatt.writeDescriptor(ccc)
            } else {
                Log.w(TAG, "No CCC on Service Changed, skipping")
                proceedToGattDatabaseValidation(gatt)
            }
        } else {
            Log.w(TAG, "Generic Attribute Service not found, skipping Service Changed")
            proceedToGattDatabaseValidation(gatt)
        }
    }

    private fun proceedToGattDatabaseValidation(gatt: BluetoothGatt) {
        initStep = 2
        val gattDbService = gatt.services.find { it.uuid == GATTDB_CONFIRM_SERVICE }
        val ephemeralPointerChar = gattDbService?.getCharacteristic(EPHEMERAL_POINTER_CHAR)
        if (ephemeralPointerChar != null) {
            Log.i(TAG, "Step 3b: GattDatabase validation - reading ephemeral pointer AC2F0145...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readCharacteristic(ephemeralPointerChar)
            } else {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(ephemeralPointerChar)
            }
        } else {
            Log.w(TAG, "GattDatabaseConfirmationService not found, skipping validation")
            enableGattlinkNotifications(gatt)
        }
    }

    private fun handleEphemeralPointerRead(gatt: BluetoothGatt, value: ByteArray) {
        // Parse UUID from bytes (little-endian UUID format)
        val uuid = try {
            if (value.size >= 16) {
                // Fitbit parses UUID as big-endian MSB+LSB (no endianness swap)
                var msb = 0L
                var lsb = 0L
                for (i in 0 until 8) {
                    msb = (msb shl 8) or (value[i].toLong() and 0xFF)
                }
                for (i in 8 until 16) {
                    lsb = (lsb shl 8) or (value[i].toLong() and 0xFF)
                }
                UUID(msb, lsb)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UUID from ephemeral pointer", e)
            null
        }

        if (uuid == null) {
            Log.w(TAG, "Could not parse ephemeral UUID, skipping validation")
            enableGattlinkNotifications(gatt)
            return
        }

        ephemeralCharUuid = uuid
        Log.i(TAG, "Ephemeral characteristic UUID: $uuid")

        // Write 0x00 to ephemeral characteristic with WRITE_TYPE_DEFAULT (Fitbit uses type=2)
        val gattDbService = gatt.getService(GATTDB_CONFIRM_SERVICE)
        val ephemeralChar = gattDbService?.getCharacteristic(uuid)
        if (ephemeralChar != null) {
            Log.i(TAG, "Writing 0x00 to ephemeral char $uuid...")
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
            enableGattlinkNotifications(gatt)
        }
    }

    private fun probeUnknownServices(gatt: BluetoothGatt) {
        Log.i(TAG, "Probing unknown services for readable characteristics...")
        val knownServices = setOf(GATTLINK_SERVICE, GATTDB_CONFIRM_SERVICE, GENERIC_ATTRIBUTE_SERVICE, UUID.fromString("00001800-0000-1000-8000-00805F9B34FB"), UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB"))
        for (svc in gatt.services) {
            if (svc.uuid in knownServices) continue
            Log.i(TAG, "Probing service: ${svc.uuid}")
            for (char in svc.characteristics) {
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    Log.i(TAG, "  Reading char: ${char.uuid}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.readCharacteristic(char)
                    } else {
                        @Suppress("DEPRECATION")
                        gatt.readCharacteristic(char)
                    }
                }
            }
        }
    }

    private fun enableGattlinkNotifications(gatt: BluetoothGatt) {
        initStep = 3
        val char = transmitChar
        if (char == null) {
            Log.e(TAG, "Transmit characteristic not available for notification setup")
            cleanup()
            return
        }
        // CRITICAL: Android requires setCharacteristicNotification BEFORE writing CCC descriptor.
        val notifOk = gatt.setCharacteristicNotification(char, true)
        Log.i(TAG, "setCharacteristicNotification(GattlinkTransmit)=$notifOk")
        val ccc = char.getDescriptor(CCC_DESCRIPTOR)
        if (ccc == null) {
            Log.e(TAG, "CCC descriptor not found")
            cleanup()
            return
        }

        ccc.value = byteArrayOf(0x01, 0x00)
        Log.i(TAG, "Writing CCC to enable Gattlink notifications...")
        val result = gatt.writeDescriptor(ccc)
        Log.i(TAG, "writeDescriptor queued: $result")
    }

    private fun startGoldenGateStack(device: BluetoothDevice) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Step 4: Starting GoldenGate Stack...")

        val b = bridge ?: run {
            Log.e(TAG, "Bridge is null")
            cleanup()
            return
        }

        try {
            // Step 4a: Create Stack with DSNG config
            val dsngConfig = DtlsSocketNetifGattlinkStackConfig()
            Log.i(TAG, "Creating Stack with config='${dsngConfig.configDescriptor}' (DSNG = DTLS+Socket+Netif+Gattlink)")
            // CRITICAL: isNode=false for tracker connections (phone is central, tracker is peripheral).
            // Fitbit's StackPeer passes isNode = (peerRole == Central) = false for Peripheral peerRole.
            stack = Stack(
                nodeKey = BluetoothAddressNodeKey(device.address),
                transportSinkPtr = b.txSink.getThisPointer(),
                transportSourcePtr = b.rxSource.getThisPointer(),
                stackConfig = dsngConfig,
                isNode = false
            )
            Log.i(TAG, "Stack created successfully (isNode=false)")

            // Step 4b: Create CoAP endpoint and attach BEFORE starting bridge/stack.
            // Fitbit order: create Bridge → create Stack → attach CoapEndpoint → Bridge.start() → Stack.start()
            Log.i(TAG, "Step 4b: Creating CoAP endpoint...")
            val endpoint = CoapEndpoint()
            coapEndpoint = endpoint
            val st = stack ?: throw IllegalStateException("Stack is null")
            endpoint.attach(st)
            Log.i(TAG, "CoAP endpoint attached to stack")

            // Step 4c: Start the bridge
            b.start()

            // Step 4d: Start the stack → triggers "starting gattlink session"
            Log.i(TAG, "Calling Stack.start()...")
            stack?.start()

            // Update MTU in stack using actual negotiated MTU (Fitbit passes mtu-3 to native)
            val stackMtu = (negotiatedMtu - 3).coerceAtLeast(20)
            val mtuResult = stack?.updateMtu(stackMtu)
            Log.i(TAG, "Stack.updateMtu($stackMtu) [negotiated=$negotiatedMtu] returned $mtuResult")

            // Step 5: Wait for DTLS handshake then send bond/control
            scope.launch {
                delay(10000L) // Allow DTLS handshake to complete (10s)
                sendBondControl(device)
            }

            // Start timeout
            startTimeout()

        } catch (e: Exception) {
            Log.e(TAG, "Stack initialization failed", e)
            cleanup()
        }
    }

    private suspend fun sendCoapRequest(
        endpoint: CoapEndpoint,
        path: String,
        method: Method,
        payload: ByteArray,
        experimentName: String
    ): IncomingResponse? {
        val request = OutgoingRequestBuilder(path, method)
            .body(payload)
            .forceNonBlockwise(true)
            .build()
        return try {
            Log.i(TAG, "[$experimentName] Sending CoAP $method $path, payload=${payload.size} bytes")
            val response = endpoint.sendRequest(request)
            val rc = response.getResponseCode()
            val rspClass = rc.responseClass.toInt()
            val rspDetail = rc.detail.toInt()
            val body = response.getBody()
            val bodyLen = body.asData().size
            Log.i(TAG, "[$experimentName] Response: $rspClass.$rspDetail, body=$bodyLen bytes")
            response
        } catch (e: Exception) {
            Log.e(TAG, "[$experimentName] Request failed with exception", e)
            null
        }
    }

    private suspend fun runBondControlExperiment(device: BluetoothDevice, experiment: String) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "EXPERIMENT: $experiment")

        val endpoint = coapEndpoint ?: run {
            Log.e(TAG, "CoAP endpoint is null")
            return
        }

        when (experiment) {
            "A_DIRECT" -> {
                // Experiment A: bond/control directly
                Log.i(TAG, "[A] Sending bond/control directly...")
            }
            "B_SHOW_THEN_BOND" -> {
                // Experiment B: SHOW_CODE → immediate bond/control
                Log.i(TAG, "[B] Sending pair/display SHOW_CODE...")
                val showPayload = byteArrayOf(0x08, 0x01)
                sendCoapRequest(endpoint, "pair/display", Method.PUT, showPayload, "pair/display-show")
                Log.i(TAG, "[B] Tracker should be showing code NOW. Sending bond/control immediately...")
            }
            "C_SHOW_WAIT_BOND" -> {
                // Experiment C: SHOW_CODE → wait 30s → bond/control
                Log.i(TAG, "[C] Sending pair/display SHOW_CODE...")
                val showPayload = byteArrayOf(0x08, 0x01)
                sendCoapRequest(endpoint, "pair/display", Method.PUT, showPayload, "pair/display-show")
                Log.i(TAG, "[C] Waiting 30s for pairing state to expire...")
                kotlinx.coroutines.delay(30000)
                Log.i(TAG, "[C] Sending bond/control after wait...")
            }
            else -> {
                Log.e(TAG, "Unknown experiment: $experiment")
                return
            }
        }

        val bondControlPayload = byteArrayOf(0x08, 0x78)
        val bondResponse = sendCoapRequest(
            endpoint, "bond/control", Method.PUT, bondControlPayload, "bond/control"
        )

        if (bondResponse != null) {
            val rc = bondResponse.getResponseCode()
            val rspClass = rc.responseClass.toInt()
            val rspDetail = rc.detail.toInt()
            if (rspClass == 2) {
                Log.i(TAG, "[$experiment] bond/control ACCEPTED ($rspClass.$rspDetail)")
                val bondResult = device.createBond()
                Log.i(TAG, "[$experiment] createBond() returned $bondResult")
            } else {
                Log.e(TAG, "[$experiment] bond/control REJECTED: $rspClass.$rspDetail")
            }
        } else {
            Log.e(TAG, "[$experiment] bond/control failed: no response")
        }
    }

    private suspend fun sendBondControl(device: BluetoothDevice) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "RUNNING ALL 3 EXPERIMENTS SEQUENTIALLY")
        Log.i(TAG, "========================================")

        // Experiment A: Direct bond/control (baseline)
        runBondControlExperiment(device, "A_DIRECT")

        // Small delay between experiments
        kotlinx.coroutines.delay(2000)

        // Experiment B: pair/display SHOW → immediate bond/control
        runBondControlExperiment(device, "B_SHOW_THEN_BOND")

        // Delay before experiment C
        kotlinx.coroutines.delay(2000)

        // Experiment C: pair/display SHOW → wait 30s → bond/control
        runBondControlExperiment(device, "C_SHOW_WAIT_BOND")

        Log.i(TAG, "========================================")
        Log.i(TAG, "ALL EXPERIMENTS COMPLETE")
        Log.i(TAG, "========================================")
    }

    private fun sendToBle(data: ByteArray) {
        txCount++
        val hex = data.joinToString("") { "%02X".format(it) }
        val firstByte = if (data.isNotEmpty()) "%02X".format(data[0]) else "empty"
        outstandingWrites++
        lastWriteTimestamp = System.currentTimeMillis()
        Log.i(TAG, "TX #$txCount → ABBAFF01: ${data.size} bytes: $hex (first=$firstByte, type=DEFAULT, outstanding=$outstandingWrites)")

        val g = gatt
        val char = transmitChar
        if (g == null || char == null) {
            Log.e(TAG, "Cannot send: gatt=$g char=$char")
            outstandingWrites--
            return
        }

        val service = g.getService(GATTLINK_SERVICE)
        val receiveChar = service?.getCharacteristic(RECEIVE_CHAR)
        if (receiveChar == null) {
            Log.e(TAG, "Receive char not available for write")
            outstandingWrites--
            return
        }

        // EXPERIMENT: WRITE_TYPE_DEFAULT (1) instead of WRITE_TYPE_NO_RESPONSE (2)
        // Fitbit's RemoteGattlinkNodeDataSender uses writeType=1
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

    private fun startTimeout() {
        val runnable = Runnable {
            Log.i(TAG, "TIMEOUT: txCount=$txCount rxCount=$rxCount")
            cleanup()
        }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, EXPERIMENT_TIMEOUT_MS)
        Log.i(TAG, "Timeout started: ${EXPERIMENT_TIMEOUT_MS}ms")
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun disconnect() {
        Log.i(TAG, "Manual disconnect requested")
        cleanup()
    }

    private fun cleanup() {
        cancelTimeout()
        Log.i(TAG, "Cleanup: txCount=$txCount rxCount=$rxCount")

        // NOTE: Do NOT stop GoldenGate RunLoop here — it's a global singleton
        // that must run for the app's lifetime. Stopping it breaks future pairings.

        try { coapEndpoint?.detach() } catch (e: Exception) { Log.w(TAG, "CoapEndpoint detach error", e) }
        try { coapEndpoint?.close() } catch (e: Exception) { Log.w(TAG, "CoapEndpoint close error", e) }
        try { stack?.close() } catch (e: Exception) { Log.w(TAG, "Stack close error", e) }
        try { bridge?.close() } catch (e: Exception) { Log.w(TAG, "Bridge close error", e) }

        gatt?.disconnect()
        gatt?.close()
        gatt = null
        coapEndpoint = null
        stack = null
        bridge = null
        transmitChar = null
        isRunning = false

        Log.i(TAG, "GOLDENGATE EXPERIMENT END")
        Log.i(TAG, "========================================")
    }

    /**
     * Direct bond experiment: Test Android createBond() WITHOUT bond/control.
     * Called after BLE connection is established but before GoldenGate stack starts.
     */
    fun runDirectBondExperiment() {
        val device = gatt?.device
        if (device == null) {
            Log.e(TAG, "No connected device for direct bond experiment")
            return
        }
        scope.launch {
            Log.i(TAG, "========================================")
            Log.i(TAG, "DIRECT BOND EXPERIMENT")
            Log.i(TAG, "Testing createBond() without bond/control")
            Log.i(TAG, "========================================")
            val experiment = DirectBondExperiment(context)
            experiment.testDirectBond(device)
        }
    }
}
