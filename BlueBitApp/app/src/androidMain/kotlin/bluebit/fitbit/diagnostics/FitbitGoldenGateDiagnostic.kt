package bluebit.fitbit.diagnostics

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import bluebit.bluetooth.android.DirectBondExperiment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.fitbit.goldengate.bindings.Bridge
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.EmptyOptionValue
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.IntOptionValue
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OpaqueOptionValue
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.coap.data.StringOptionValue
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlinkStackConfig
import com.fitbit.goldengate.bindings.stack.Stack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

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
        private const val EXPERIMENT_TIMEOUT_MS = 120000L
        // Phase 14: how long to wait for a gattlink write's onCharacteristicWrite
        // before declaring the write path dead (healthy writes complete in 23-95 ms).
        private const val WRITE_COMPLETION_TIMEOUT_MS = 5000L
        // Non-success status (GATT_ERROR) reported to the native stack when the
        // gattlink transport is already failed and a frame cannot be accepted.
        private const val TX_STATUS_TRANSPORT_FAILED = 133

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
    private var negotiatedMtu = 23 // Android default; updated after MTU negotiation
    // Phase 14: gattlink TX write serialization. Android's GATT client allows one
    // outstanding request per client; the Phase 12 DTLS stall was an unserialized
    // second write the framework rejected ("prior command is not finished") before
    // it reached the air. All gattlink TX now flows through this FIFO serializer,
    // drained one write at a time from onCharacteristicWrite. Every access runs
    // under synchronized(txSerializer).
    private val txSerializer = GattlinkTxSerializer(
        submitWrite = { seq, data -> submitGattlinkWrite(seq, data) },
        nowMs = { System.currentTimeMillis() }
    )
    private var txWriteTimeoutRunnable: Runnable? = null
    private var bondStateReceiver: BroadcastReceiver? = null
    private var bondStateChangeCallback: ((Int) -> Unit)? = null
    private var activeDialog: AlertDialog? = null
    private var experimentDevice: BluetoothDevice? = null
    private var sessionId: String = ""
    private var skipUserPrompt: Boolean = false
    private var pairingRequestObserved: Boolean = false
    private var experimentMode: ExperimentMode = ExperimentMode.INTERACTIVE

    private enum class ExperimentMode { INTERACTIVE, REPRODUCIBLE, BOND_BEFORE_CONTROL, TRANSPORT_ONLY }

    private data class PairingEndpointResult(
        val name: String,
        val requestPath: String,
        val requestMethod: String,
        val requestBodySize: Int,
        val responseCode: String,
        val responseBodySize: Int,
        val responseBodyHashPrefix: String
    )

    fun startExperiment(device: BluetoothDevice, reproducible: Boolean = false) {
        startExperimentInternal(
            device,
            if (reproducible) ExperimentMode.REPRODUCIBLE else ExperimentMode.INTERACTIVE
        )
    }

    fun startBondBeforeControlExperiment(device: BluetoothDevice) {
        startExperimentInternal(device, ExperimentMode.BOND_BEFORE_CONTROL)
    }

    /**
     * Phase 14: transport-only run. The connection, gattlink, bridge, stack, and
     * DTLS startup run byte-identically to the other modes; the post-stack phase
     * stops WITHOUT any CoAP request or bonding (bond/control and createBond are
     * out of scope for the write-serialization experiment).
     */
    fun startTransportOnlyExperiment(device: BluetoothDevice) {
        startExperimentInternal(device, ExperimentMode.TRANSPORT_ONLY)
    }

    private fun startExperimentInternal(device: BluetoothDevice, mode: ExperimentMode) {
        if (isRunning) {
            Log.w(TAG, "Experiment already running, ignoring start request")
            return
        }
        isRunning = true
        txCount = 0
        rxCount = 0
        synchronized(txSerializer) {
            txSerializer.reset()
        }
        experimentDevice = device
        sessionId = java.util.UUID.randomUUID().toString().take(8)
        experimentMode = mode
        skipUserPrompt = (mode == ExperimentMode.REPRODUCIBLE)
        pairingRequestObserved = false

        Log.i(TAG, "========================================")
        Log.i(TAG, "GOLDENGATE EXPERIMENT START")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "DEVICE=${device.name} ADDR=${device.address}")

        // Register bond-state and pairing-request receivers so we can observe any
        // Android SMP activity that occurs during the experiment.
        registerBondStateReceiver()

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
        // Phase 14: the status-returning TX path lets TxSink.putData propagate write
        // submission failures to the native stack instead of unconditional success;
        // when it is set, Bridge uses it instead of the Unit onTxData.
        val txPath: (ByteArray) -> Int = { data -> sendToBle(data) }
        bridge = Bridge(
            onTxData = { data -> txPath(data) },
            onRxData = { data -> Log.i(TAG, "RX data logged: ${data.size} bytes") },
            onTxDataStatus = txPath
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
                if (uuid == RECEIVE_CHAR) {
                    handleGattlinkWriteCompleted(status)
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
        // Diagnostic run 2026-10-18 showed createBond() causes Android to terminate the
        // BLE connection with status=22 (GATT_CONN_TERMINATE_LOCAL_HOST) after ~30 s,
        // preventing the GoldenGate/DTLS/CoAP part of the diagnostic from running at all.
        // Skipping it lets us observe the actual GoldenGate session progress.
        Log.i(TAG, "DIRECT BOND EXPERIMENT: skipped for this diagnostic run")
        proceedWithGattInit(gatt)
        /*
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
        */
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

    /**
     * Reproducible variant of the GoldenGate experiment.
     * Same BLE/Gattlink/GoldenGate/DTLS/CoAP sequence, but skips the user
     * bonding dialog and always waits a fixed 5 s after pair/display before
     * the after-action bond/control. This makes before/after comparisons
     * (e.g. before vs. after official Fitbit app onboarding) independent of
     * user interaction timing.
     */
    fun startReproducibleExperiment(device: BluetoothDevice) {
        startExperiment(device, reproducible = true)
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

            // Step 5: Wait for DTLS handshake then run the selected experiment
            scope.launch {
                delay(10000L) // Allow DTLS handshake to complete (10s)
                when (experimentMode) {
                    ExperimentMode.BOND_BEFORE_CONTROL -> runBondBeforeControlExperiment(device)
                    ExperimentMode.TRANSPORT_ONLY -> runTransportOnlyPhase()
                    else -> runControlledAuthorizationExperiment(device)
                }
            }

            // Start timeout
            startTimeout()

        } catch (e: Exception) {
            Log.e(TAG, "Stack initialization failed", e)
            cleanup()
        }
    }

    /**
     * Phase 14: transport-only observation window (10 s after Stack.start(),
     * matching the other modes' delay). Logs a summary and ends the session
     * WITHOUT any CoAP request or bonding — bond/control and createBond are out
     * of scope for the write-serialization experiment. DTLS progress itself is
     * observable in the logcat via BlueBitGG_Stack's onDtlsStatusChange lines.
     */
    private fun runTransportOnlyPhase() {
        Log.i(TAG, "[TRANSPORT_ONLY] Observation window elapsed (10 s after Stack.start)")
        if (!isRunning) {
            // The session already ended (e.g. supervision timeout cleaned up) —
            // same guard the bond-before-control phase uses.
            Log.w(TAG, "[TRANSPORT_ONLY] Experiment already cleaned up; nothing to end")
            return
        }
        synchronized(txSerializer) {
            Log.i(
                TAG,
                "[TRANSPORT_ONLY] SESSION=$sessionId txCount=$txCount rxCount=$rxCount outstanding=${txSerializer.outstanding} txFailed=${txSerializer.isFailed}"
            )
        }
        Log.i(TAG, "[TRANSPORT_ONLY] Skipping CoAP/bond phase — no bond/control, no createBond (Phase 14 scope)")
        Log.i(TAG, "[TRANSPORT_ONLY] Ending session")
        cleanup()
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
            logResponseDetails(experimentName, response)
            response
        } catch (e: Exception) {
            Log.e(TAG, "[$experimentName] Request failed with exception", e)
            null
        }
    }

    private fun logResponseDetails(prefix: String, response: IncomingResponse?) {
        if (response == null) {
            Log.i(TAG, "[$prefix] Response: null")
            return
        }
        val rc = response.getResponseCode()
        val rspClass = rc.responseClass.toInt()
        val rspDetail = rc.detail.toInt()
        val body = response.getBody()
        val bodyBytes = body.asData()
        Log.i(TAG, "[$prefix] Response: $rspClass.$rspDetail, body=${bodyBytes.size} bytes")
        val options = response.getOptions()
        if (options.isNotEmpty()) {
            Log.i(TAG, "[$prefix] Options count=${options.size}")
            for (option in options) {
                val valueStr = when (val v = option.value) {
                    is IntOptionValue -> "int=${v.value}"
                    is StringOptionValue -> "string=${v.value}"
                    is OpaqueOptionValue -> {
                        val hexPrefix = v.value.take(8).joinToString("") { "%02X".format(it) }
                        "opaque(len=${v.value.size},hex=${hexPrefix}...)"
                    }
                    is EmptyOptionValue -> "empty"
                    else -> "unknown"
                }
                Log.i(TAG, "[$prefix]   Option ${option.number.name}($valueStr)")
            }
        } else {
            Log.i(TAG, "[$prefix] Options count=0")
        }
    }

    private fun hashPrefix(bytes: ByteArray, length: Int = 8): String {
        if (bytes.isEmpty()) return "EMPTY"
        return bytes.take(length.coerceAtMost(bytes.size))
            .joinToString("") { "%02X".format(it) } + (if (bytes.size > length) "..." else "")
    }

    private suspend fun probeDeviceSidePairingEndpoint(
        endpoint: CoapEndpoint,
        name: String,
        pathPrefix: String
    ): PairingEndpointResult {
        Log.i(TAG, "[PAIR_ENDPOINT] $name: probing GET $pathPrefix/request")
        val requestBody = byteArrayOf()
        val requestResponse = sendCoapRequest(
            endpoint, "$pathPrefix/request", Method.GET, requestBody, "$name-request"
        )
        val requestCode = requestResponse?.let {
            "${it.getResponseCode().responseClass.toInt()}.${it.getResponseCode().detail.toInt()}"
        } ?: "NO_RESPONSE"
        val challenge = requestResponse?.getBody()?.asData() ?: byteArrayOf()
        Log.i(TAG, "[PAIR_ENDPOINT] $name: GET returned $requestCode, challengeBody=${challenge.size} bytes")

        Log.i(TAG, "[PAIR_ENDPOINT] $name: probing POST $pathPrefix/response")
        val responseResponse = sendCoapRequest(
            endpoint, "$pathPrefix/response", Method.POST, challenge, "$name-response"
        )
        val responseCode = responseResponse?.let {
            "${it.getResponseCode().responseClass.toInt()}.${it.getResponseCode().detail.toInt()}"
        } ?: "NO_RESPONSE"
        val responseBody = responseResponse?.getBody()?.asData() ?: byteArrayOf()
        Log.i(TAG, "[PAIR_ENDPOINT] $name: POST returned $responseCode, responseBody=${responseBody.size} bytes")

        return PairingEndpointResult(
            name = name,
            requestPath = "$pathPrefix/request",
            requestMethod = "GET",
            requestBodySize = requestBody.size,
            responseCode = responseCode,
            responseBodySize = responseBody.size,
            responseBodyHashPrefix = hashPrefix(responseBody)
        )
    }

    private suspend fun runDeviceSidePairingEndpointProbe(endpoint: CoapEndpoint): List<PairingEndpointResult> {
        Log.i(TAG, "========================================")
        Log.i(TAG, "DEVICE-SIDE PAIRING ENDPOINT PROBE START")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "========================================")
        val results = mutableListOf<PairingEndpointResult>()
        results += probeDeviceSidePairingEndpoint(endpoint, "AFFILIATION", "pair/affiliation")
        results += probeDeviceSidePairingEndpoint(endpoint, "ATTESTATION", "pair/attestation")
        results += probeDeviceSidePairingEndpoint(endpoint, "REGISTRATION", "pair/registration")
        Log.i(TAG, "========================================")
        Log.i(TAG, "DEVICE-SIDE PAIRING ENDPOINT PROBE COMPLETE")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "========================================")
        return results
    }

    private fun registerBondStateReceiver() {
        if (bondStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                val deviceExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                when (action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        Log.i(TAG, "BOND_STATE_CHANGED: addr=${deviceExtra?.address} prev=${bondStateName(prevState)} new=${bondStateName(newState)}")
                        bondStateChangeCallback?.invoke(newState)
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        pairingRequestObserved = true
                        Log.i(TAG, "PAIRING_REQUEST: addr=${deviceExtra?.address} — system dialog should be visible")
                    }
                }
            }
        }
        bondStateReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterBondStateReceiver() {
        bondStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Bond state receiver not registered", e)
            }
            bondStateReceiver = null
        }
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }

    private suspend fun promptForAndroidBond(device: BluetoothDevice): Boolean = suspendCancellableCoroutine { cont ->
        val builder = AlertDialog.Builder(context)
            .setTitle("Pairing code on watch")
            .setMessage(
                "The Inspire 3 is displaying a pairing code. " +
                "If your phone shows a Bluetooth pairing dialog, confirm it normally. " +
                "Then choose whether BlueBit should call Android createBond() before the next bond/control request, " +
                "or skip Android bonding entirely."
            )
            .setPositiveButton("Bond via Android") { _, _ ->
                if (!cont.isCompleted) cont.resume(true)
            }
            .setNegativeButton("Skip bonding") { _, _ ->
                if (!cont.isCompleted) cont.resume(false)
            }
            .setOnCancelListener {
                if (!cont.isCompleted) cont.resume(false)
            }
        val dialog = builder.create()
        activeDialog = dialog
        dialog.show()

        // Auto-dismiss and default to skip after 25 s so the overall timeout can fire.
        handler.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            activeDialog = null
            if (!cont.isCompleted) cont.resume(false)
        }, 25000)

        cont.invokeOnCancellation {
            dialog.dismiss()
            activeDialog = null
        }
    }

    private suspend fun waitForBondState(device: BluetoothDevice, timeoutMs: Long): Int {
        val terminalStates = setOf(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDED)
        // If already bonded, there is nothing to wait for.
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return BluetoothDevice.BOND_BONDED
        }
        // Wait for a bond-state broadcast that reaches a terminal state. Do NOT
        // return immediately when the state is NONE: after createBond() returns true
        // Android first transitions to BONDING, and the initial NONE read is racy.
        // Returning early caused Phase 8 to clean up before the bond state machine
        // could send the SMP Pairing Request.
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bondStateChangeCallback = { state ->
                    if (state in terminalStates && !cont.isCompleted) {
                        bondStateChangeCallback = null
                        cont.resume(state)
                    }
                }
                cont.invokeOnCancellation { bondStateChangeCallback = null }
            }
        } ?: device.bondState
    }

    private suspend fun runControlledAuthorizationExperiment(device: BluetoothDevice) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "CONTROLLED AUTHORIZATION EXPERIMENT")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "REPRODUCIBLE_MODE=$skipUserPrompt")
        Log.i(TAG, "Initial Android bond state: ${bondStateName(device.bondState)}")
        Log.i(TAG, "========================================")

        val endpoint = coapEndpoint ?: run {
            Log.e(TAG, "CoAP endpoint is null")
            return
        }

        // Baseline bond/control (no pair/display, no Android bonding)
        Log.i(TAG, "[BASELINE] Sending bond/control before pair/display at ${System.currentTimeMillis()}...")
        val baselineResponse = sendCoapRequest(
            endpoint, "bond/control", Method.PUT, byteArrayOf(0x08, 0x78), "bond/control-baseline"
        )
        logBondControlResult("BASELINE", baselineResponse)

        // pair/display SHOW_CODE
        Log.i(TAG, "[PAIR_DISPLAY] Sending pair/display SHOW_CODE at ${System.currentTimeMillis()}...")
        val displayResponse = sendCoapRequest(
            endpoint, "pair/display", Method.PUT, byteArrayOf(0x08, 0x01), "pair/display"
        )
        val displaySuccess = displayResponse?.getResponseCode()?.responseClass?.toInt() == 2
        Log.i(TAG, "[PAIR_DISPLAY] pair/display result: ${if (displaySuccess) "OK" else "FAILED"}")

        val shouldBond: Boolean
        if (skipUserPrompt) {
            // Reproducible mode: do not show a dialog; wait a fixed interval so
            // any spontaneous system pairing dialog has time to appear, matching
            // the wait used in the skip-bond branch of the interactive mode.
            Log.i(TAG, "[REPRODUCIBLE] Skipping user prompt; waiting 5 s...")
            delay(5000)
            shouldBond = false
        } else {
            // Wait for user to handle any system dialog and optionally trigger Android bonding
            Log.i(TAG, "[PROMPT] Showing user bonding choice dialog...")
            shouldBond = promptForAndroidBond(device)
            Log.i(TAG, "[USER_CHOICE] shouldBond=$shouldBond at ${System.currentTimeMillis()}")
        }

        if (shouldBond) {
            val bondResult = device.createBond()
            Log.i(TAG, "[ANDROID_BOND] createBond() returned $bondResult at ${System.currentTimeMillis()}")
            val finalState = waitForBondState(device, 15000)
            Log.i(TAG, "[ANDROID_BOND] Bond state after wait: ${bondStateName(finalState)}")
        } else {
            // Give any spontaneous system dialog a few seconds to appear
            Log.i(TAG, "[SKIP_BOND] Waiting 5s to observe spontaneous pairing dialogs...")
            delay(5000)
        }

        // In reproducible mode, probe the device-side pairing endpoints after the wait.
        var endpointProbeResults: List<PairingEndpointResult> = emptyList()
        if (skipUserPrompt) {
            val probeEndpoint = coapEndpoint
            if (probeEndpoint == null) {
                Log.w(TAG, "[PAIR_ENDPOINT] CoAP endpoint is null; skipping device-side pairing endpoint probe")
            } else {
                endpointProbeResults = runDeviceSidePairingEndpointProbe(probeEndpoint)
            }
        }

        // bond/control after user action / wait / endpoint probe
        val endpointForAfter = coapEndpoint
        if (endpointForAfter == null) {
            Log.w(TAG, "[AFTER_ACTION] CoAP endpoint is null (experiment already cleaned up); skipping after-action bond/control")
            logExperimentSummary(device, baselineResponse, null, endpointProbeResults)
        } else {
            Log.i(TAG, "[AFTER_ACTION] Android bond state: ${bondStateName(device.bondState)}")
            Log.i(TAG, "[AFTER_ACTION] Sending bond/control at ${System.currentTimeMillis()}...")
            val afterResponse = sendCoapRequest(
                endpointForAfter, "bond/control", Method.PUT, byteArrayOf(0x08, 0x78), "bond/control-after-action"
            )
            logBondControlResult("AFTER_ACTION", afterResponse)

            // Final summary for easy before/after comparison.
            logExperimentSummary(device, baselineResponse, afterResponse, endpointProbeResults)
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "CONTROLLED AUTHORIZATION EXPERIMENT COMPLETE")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "========================================")
    }

    /**
     * Diagnostic-only experiment: establish the GoldenGate/CoAP session first, then call
     * Android [BluetoothDevice.createBond] before sending [bond/control]. This tests whether
     * an existing Android bond changes the tracker's authorization decision without changing
     * the production pairing order or default app behaviour.
     */
    private suspend fun runBondBeforeControlExperiment(device: BluetoothDevice) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "BOND-BEFORE-CONTROL EXPERIMENT")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "Initial Android bond state: ${bondStateName(device.bondState)}")
        Log.i(TAG, "========================================")

        if (!isRunning) {
            Log.w(TAG, "[BOND_BEFORE_CONTROL] Experiment already cleaned up; aborting")
            return
        }

        val endpoint = coapEndpoint
        if (endpoint == null) {
            Log.e(TAG, "[BOND_BEFORE_CONTROL] CoAP endpoint is null; session invalid")
            logBondBeforeControlSummary(device, initialBondState = device.bondState, response = null, finalBondStateName = bondStateName(device.bondState))
            return
        }

        val initialBondState = device.bondState
        val finalBondState: Int
        val bondReached: Boolean

        when (initialBondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "[BOND_BEFORE_CONTROL] Already BOND_BONDED; skipping createBond()")
                finalBondState = initialBondState
                bondReached = true
            }
            BluetoothDevice.BOND_NONE -> {
                val bondResult = device.createBond()
                Log.i(TAG, "[BOND_BEFORE_CONTROL] createBond() returned $bondResult")
                if (!bondResult) {
                    Log.w(TAG, "[BOND_BEFORE_CONTROL] createBond() returned false")
                }
                finalBondState = waitForBondState(device, 60000L)
                Log.i(TAG, "[BOND_BEFORE_CONTROL] Bond state after 60 s wait: ${bondStateName(finalBondState)}")
                bondReached = (finalBondState == BluetoothDevice.BOND_BONDED)
            }
            else -> {
                // BONDING or unknown: wait for a terminal state.
                Log.i(TAG, "[BOND_BEFORE_CONTROL] Device in ${bondStateName(initialBondState)}; waiting for terminal state...")
                finalBondState = waitForBondState(device, 60000L)
                Log.i(TAG, "[BOND_BEFORE_CONTROL] Bond state after 60 s wait: ${bondStateName(finalBondState)}")
                bondReached = (finalBondState == BluetoothDevice.BOND_BONDED)
            }
        }

        if (!bondReached) {
            Log.i(TAG, "[BOND_BEFORE_CONTROL] BOND_BONDED not reached; skipping bond/control")
            logBondBeforeControlSummary(device, initialBondState, null, bondStateName(finalBondState))
            cleanup()
            return
        }

        if (!isRunning || coapEndpoint == null) {
            Log.w(TAG, "[BOND_BEFORE_CONTROL] Session became invalid during bonding; skipping bond/control")
            logBondBeforeControlSummary(device, initialBondState, null, bondStateName(finalBondState))
            return
        }

        Log.i(TAG, "[BOND_BEFORE_CONTROL] Sending bond/control after bond...")
        val response = sendCoapRequest(
            coapEndpoint!!, "bond/control", Method.PUT, byteArrayOf(0x08, 0x78), "bond/control-bond-before-control"
        )
        logBondControlResult("BOND_BEFORE_CONTROL", response)

        logBondBeforeControlSummary(device, initialBondState, response, bondStateName(finalBondState))

        Log.i(TAG, "========================================")
        Log.i(TAG, "BOND-BEFORE-CONTROL EXPERIMENT COMPLETE")
        Log.i(TAG, "SESSION=$sessionId")
        Log.i(TAG, "========================================")

        cleanup()
    }

    private fun logBondBeforeControlSummary(
        device: BluetoothDevice,
        initialBondState: Int,
        response: IncomingResponse?,
        finalBondStateName: String
    ) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "BOND-BEFORE-CONTROL SUMMARY [session=$sessionId]")
        Log.i(TAG, "Initial bond state: ${bondStateName(initialBondState)}")
        Log.i(TAG, "Pairing request observed: $pairingRequestObserved")
        Log.i(TAG, "Final bond state: $finalBondStateName")
        Log.i(TAG, "bond/control (after bond): ${describeResponse(response)}")
        Log.i(TAG, "========================================")
    }

    private fun logExperimentSummary(
        device: BluetoothDevice,
        baseline: IncomingResponse?,
        afterAction: IncomingResponse?,
        endpointProbeResults: List<PairingEndpointResult> = emptyList()
    ) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "EXPERIMENT SUMMARY [session=$sessionId]")
        Log.i(TAG, "Initial bond state: ${bondStateName(device.bondState)}")
        Log.i(TAG, "Pairing request observed: $pairingRequestObserved")
        Log.i(TAG, "Baseline bond/control: ${describeResponse(baseline)}")
        Log.i(TAG, "After-action bond/control: ${describeResponse(afterAction)}")
        if (endpointProbeResults.isNotEmpty()) {
            Log.i(TAG, "Device-side pairing endpoint probe results:")
            for (r in endpointProbeResults) {
                Log.i(TAG, "  ${r.name}: ${r.requestMethod} ${r.requestPath} -> ${r.responseCode}, responseBody=${r.responseBodySize} bytes (prefix=${r.responseBodyHashPrefix})")
            }
        } else {
            Log.i(TAG, "Device-side pairing endpoint probe: not run")
        }
        val baselineCode = baseline?.getResponseCode()?.let { "${it.responseClass.toInt()}.${it.detail.toInt()}" } ?: "NO_RESPONSE"
        val afterCode = afterAction?.getResponseCode()?.let { "${it.responseClass.toInt()}.${it.detail.toInt()}" } ?: "NO_RESPONSE"
        val changed = baselineCode != afterCode
        Log.i(TAG, "Response changed between baseline and after-action: $changed")
        if (changed) {
            Log.i(TAG, "Change: $baselineCode -> $afterCode")
        }
        Log.i(TAG, "========================================")
    }

    private fun describeResponse(response: IncomingResponse?): String {
        if (response == null) return "NO_RESPONSE"
        val rc = response.getResponseCode()
        val body = response.getBody().asData()
        return "${rc.responseClass.toInt()}.${rc.detail.toInt()} body=${body.size}"
    }

    private fun logBondControlResult(label: String, response: IncomingResponse?) {
        if (response == null) {
            Log.e(TAG, "[$label] bond/control failed: no response")
            return
        }
        val rc = response.getResponseCode()
        val rspClass = rc.responseClass.toInt()
        val rspDetail = rc.detail.toInt()
        if (rspClass == 2) {
            Log.i(TAG, "[$label] bond/control ACCEPTED ($rspClass.$rspDetail)")
        } else {
            Log.e(TAG, "[$label] bond/control REJECTED: $rspClass.$rspDetail")
        }
    }

    /**
     * Phase 14: serialized gattlink TX.
     *
     * Frames are enqueued in [txSerializer] and handed to the Android GATT client
     * one write at a time — a write is only submitted when no other write is
     * awaiting its onCharacteristicWrite callback (Phase 12's stall was an
     * overlapping second write the framework rejected before it reached the air).
     * The next queued frame is submitted from [handleGattlinkWriteCompleted] after
     * the previous write completes.
     *
     * Returns 0 (GG_SUCCESS) when the frame is accepted for in-order delivery, or
     * a non-zero GATT-style status when it cannot be accepted at all — propagated
     * to the native stack through TxSink.putData instead of unconditional success.
     * Completion failures surface as loud TX-FAILED/TX-DROPPED logs plus transport
     * teardown; they cannot flow backward through putData, which has already
     * returned by then.
     *
     * Runs under synchronized(txSerializer) together with every other serializer
     * access (submission, completion, timeout, cleanup).
     */
    private fun sendToBle(data: ByteArray): Int {
        val hex = data.joinToString("") { "%02X".format(it) }
        val firstByte = if (data.isNotEmpty()) "%02X".format(data[0]) else "empty"
        synchronized(txSerializer) {
            txCount++
            // outstanding = this frame plus everything already queued or in flight
            // (matches the Phase 12 log semantics where TX #2 printed outstanding=2).
            Log.i(TAG, "TX #$txCount → ABBAFF01: ${data.size} bytes: $hex (first=$firstByte, type=DEFAULT, outstanding=${txSerializer.outstanding + 1})")
            return when (val result = txSerializer.enqueue(data)) {
                is GattlinkTxSerializer.EnqueueResult.Accepted -> 0
                is GattlinkTxSerializer.EnqueueResult.Rejected -> {
                    // writeCharacteristic refused the request: the frame never
                    // reached the air. Report it instead of dropping silently.
                    Log.e(TAG, "TX REJECTED seq=${result.seq} status=${result.status} — writeCharacteristic refused submission; frame never reached the air")
                    result.dropped.forEach { logDroppedWrite(it, "transport failed: submission rejected (status=${result.status})") }
                    result.status
                }
                GattlinkTxSerializer.EnqueueResult.NotAccepted -> {
                    Log.e(TAG, "TX NOT ACCEPTED: ${data.size} bytes — transport already failed (${txSerializer.failureReason})")
                    TX_STATUS_TRANSPORT_FAILED
                }
            }
        }
    }

    /**
     * Hand one gattlink frame to the Android GATT client. Called only from
     * [GattlinkTxSerializer] under synchronized(txSerializer). Returns 0 when the
     * framework accepted the write, non-zero when it refused it.
     */
    private fun submitGattlinkWrite(seq: Int, data: ByteArray): Int {
        val g = gatt
        val receiveChar = g?.getService(GATTLINK_SERVICE)?.getCharacteristic(RECEIVE_CHAR)
        if (g == null || receiveChar == null) {
            Log.e(TAG, "TX-SUBMIT seq=$seq FAILED: gatt=$g receiveChar=$receiveChar")
            return TX_STATUS_TRANSPORT_FAILED
        }
        // EXPERIMENT: WRITE_TYPE_DEFAULT (1) instead of WRITE_TYPE_NO_RESPONSE (2)
        // Fitbit's RemoteGattlinkNodeDataSender uses writeType=1
        val submitResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(receiveChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            receiveChar.value = data
            @Suppress("DEPRECATION")
            receiveChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(receiveChar)) BluetoothGatt.GATT_SUCCESS else TX_STATUS_TRANSPORT_FAILED
        }
        if (submitResult == BluetoothGatt.GATT_SUCCESS) {
            Log.i(
                TAG,
                "TX-SUBMIT seq=$seq → ABBAFF01 type=DEFAULT bytes=${data.size} submitTs=${System.currentTimeMillis()} result=ACCEPTED queued=${txSerializer.queueDepth}"
            )
            armGattlinkWriteTimeout(seq)
        } else {
            Log.e(TAG, "TX-SUBMIT seq=$seq → ABBAFF01 bytes=${data.size} result=REFUSED status=$submitResult")
        }
        return submitResult
    }

    /**
     * Phase 14: complete the in-flight gattlink write and let the serializer
     * submit the next queued frame. Runs under synchronized(txSerializer).
     */
    private fun handleGattlinkWriteCompleted(status: Int) {
        val needsCleanup: Boolean
        synchronized(txSerializer) {
            disarmGattlinkWriteTimeout()
            when (val result = txSerializer.complete(status)) {
                is GattlinkTxSerializer.CompletionResult.LateCallback -> {
                    // No write in flight: never advance the queue from an
                    // unsolicited or late callback.
                    Log.w(TAG, "onCharacteristicWrite ABBAFF01 status=$status LATE/UNMATCHED (no write in flight; ignored)")
                    return
                }
                is GattlinkTxSerializer.CompletionResult.Completed -> {
                    val now = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "onCharacteristicWrite ABBAFF01 status=$status seq=${result.write.seq} outstanding=${txSerializer.outstanding} elapsed=${now - result.write.submittedAtMs}ms submitTs=${result.write.submittedAtMs} completeTs=$now queued=${txSerializer.queueDepth}"
                    )
                    needsCleanup = false
                }
                is GattlinkTxSerializer.CompletionResult.Failed -> {
                    val now = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "onCharacteristicWrite ABBAFF01 status=$status seq=${result.write.seq} elapsed=${now - result.write.submittedAtMs}ms submitTs=${result.write.submittedAtMs}"
                    )
                    Log.e(TAG, "TX FAILED seq=${result.write.seq} status=$status — gattlink write did not complete successfully")
                    result.dropped.forEach { logDroppedWrite(it, "completion failed (status=${status})") }
                    needsCleanup = true
                }
                is GattlinkTxSerializer.CompletionResult.NextRejected -> {
                    Log.i(TAG, "onCharacteristicWrite ABBAFF01 status=$status seq=${result.write.seq} (write completed)")
                    Log.e(TAG, "TX-SUBMIT seq=${result.rejectedSeq} REFUSED status=${result.status} — next queued frame rejected at submission")
                    result.dropped.forEach { logDroppedWrite(it, "submission of next frame rejected (status=${result.status})") }
                    needsCleanup = true
                }
            }
        }
        if (needsCleanup) {
            cleanup()
        }
    }

    /** Phase 14: a gattlink write that never completes must fail loudly, not stall silently. */
    private fun armGattlinkWriteTimeout(seq: Int) {
        disarmGattlinkWriteTimeout()
        val runnable = Runnable {
            val timedOut = synchronized(txSerializer) { txSerializer.timeout(seq) }
            if (timedOut != null) {
                Log.e(
                    TAG,
                    "TX-TIMEOUT seq=${timedOut.write.seq} — no onCharacteristicWrite after ${WRITE_COMPLETION_TIMEOUT_MS}ms (submitTs=${timedOut.write.submittedAtMs})"
                )
                timedOut.dropped.forEach { logDroppedWrite(it, "write completion timeout") }
                cleanup()
            }
        }
        txWriteTimeoutRunnable = runnable
        handler.postDelayed(runnable, WRITE_COMPLETION_TIMEOUT_MS)
    }

    private fun disarmGattlinkWriteTimeout() {
        txWriteTimeoutRunnable?.let { handler.removeCallbacks(it) }
        txWriteTimeoutRunnable = null
    }

    /** Frames are never dropped silently: every abandoned write is logged at E level. */
    private fun logDroppedWrite(write: GattlinkTxSerializer.PendingWrite, reason: String) {
        Log.e(TAG, "TX-DROPPED seq=${write.seq} ${write.data.size} bytes: ${write.hex} — $reason")
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
        // Phase 14: abort any in-flight/queued gattlink writes loudly before the
        // transport is torn down — frames are never dropped silently.
        disarmGattlinkWriteTimeout()
        synchronized(txSerializer) {
            txSerializer.abortAll("experiment cleanup").forEach { logDroppedWrite(it, "cleanup") }
        }
        Log.i(TAG, "Cleanup: txCount=$txCount rxCount=$rxCount")

        // Dismiss any active user prompt so the UI doesn't leak.
        activeDialog?.dismiss()
        activeDialog = null

        // Unregister bond-state observer.
        unregisterBondStateReceiver()
        bondStateChangeCallback = null

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
        experimentDevice = null
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
