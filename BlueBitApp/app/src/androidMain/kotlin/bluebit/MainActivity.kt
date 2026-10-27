package bluebit.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import bluebit.BlueBitAppShell
import bluebit.core.bluetooth.android.AndroidBleConnection
import bluebit.core.bluetooth.android.AndroidBleScanner
import bluebit.core.bluetooth.android.BleDiagnosticLogger
import bluebit.core.bluetooth.BleScanner
import bluebit.core.devices.InMemoryDeviceProfileRepository
import bluebit.core.provider.ProviderContext
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderRegistry
import bluebit.core.pairing.PairedDeviceCoordinator
import bluebit.core.permissions.DevicePermissionPolicy
import bluebit.pairing.SharedPreferencesPairedDeviceStore
import bluebit.fitbit.FitbitProvider
import bluebit.fitcloud.AndroidFitCloudProvider
import bluebit.fitcloud.fcSDK
import bluebit.fitcloud.initFitCloudSdk
import bluebit.core.bluetooth.bleLogWatchdog
import bluebit.core.bluetooth.BleLogTag
import java.io.File
import bluebit.fitbit.diagnostics.FitbitGattCacheDiagnostic
import bluebit.fitbit.diagnostics.FitbitGattDiagnostic
import bluebit.fitbit.diagnostics.FitbitGattlinkDiagnostic
import bluebit.fitbit.diagnostics.FitbitGoldenGateDiagnostic
import bluebit.fitbit.diagnostics.GattlinkStartupDiagnostic
import bluebit.fitbit.diagnostics.DtlsBootstrapProbe
import bluebit.fitbit.diagnostics.CoapReadOnlyProbe
import bluebit.core.diagnostics.CoapReadOnlyResult
import bluebit.fitbit.Inspire3PairingHandler
import bluebit.fitbit.diagnostics.ServerPairingExperiment
import bluebit.core.ui.components.PairingDialog
import bluebit.core.ui.theme.BlueBitTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedBluetoothPermissions = permissionPolicy.deniedBluetoothPermissions(permissions)
        val notificationsDenied = permissions[Manifest.permission.POST_NOTIFICATIONS] == false
        Log.d("BlueBit", "Permission result: $permissions, deniedBluetoothPermissions=$deniedBluetoothPermissions")
        when {
            deniedBluetoothPermissions.isNotEmpty() -> {
                // Do not re-prompt automatically to avoid loop. UI shows disabled state.
                Log.w("BlueBit", "PERMISSION_DENIED: $deniedBluetoothPermissions. BLE features disabled until granted via Settings.")
                logBluetoothAdapterState()
            }

            notificationsDenied -> Log.i(
                "BlueBit",
                "NOTIFICATIONS_OPTIONAL: POST_NOTIFICATIONS denied — only this app's notifications are hidden " +
                    "(e.g. the BLE diagnostics foreground-service notification). In-app connection, auto-restore and diagnostics remain available."
            )
        }
        if (deniedBluetoothPermissions.isEmpty()) {
            initializeBluetoothDependentOperations()
        }
    }

    private lateinit var gattDiagnostic: FitbitGattDiagnostic
    private lateinit var gattCacheDiagnostic: FitbitGattCacheDiagnostic
    private lateinit var fitcloudProvider: AndroidFitCloudProvider
    private var pairedDeviceCoordinator: PairedDeviceCoordinator? = null
    private lateinit var gattlinkDiagnostic: FitbitGattlinkDiagnostic
    private lateinit var goldenGateDiagnostic: FitbitGoldenGateDiagnostic
    private lateinit var gattlinkStartupDiagnostic: GattlinkStartupDiagnostic
    private lateinit var pairingHandler: Inspire3PairingHandler
    private lateinit var serverPairingExperiment: ServerPairingExperiment
    private lateinit var dtlsBootstrapProbe: DtlsBootstrapProbe
    private lateinit var coapReadOnlyProbe: CoapReadOnlyProbe
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BleScanner? = null
    private val bondState = mutableStateOf("UNKNOWN")
    private val isDiagnosticLogging = mutableStateOf(false)
    private val lastCoapReadOnlyResult = mutableStateOf<CoapReadOnlyResult?>(null)
    private var bondDeferred: CompletableDeferred<Boolean>? = null

    /** Classifies permission results; notification permission never blocks BLE features. */
    private val permissionPolicy = DevicePermissionPolicy(
        buildSet {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        },
    )

    /** Guards [initializeBluetoothDependentOperations] so multiple permission paths cannot run it twice. */
    private var bluetoothOperationsInitialized = false

    // Pairing dialog state
    private val showPairingDialog = mutableStateOf(false)
    private val pairingDeviceName = mutableStateOf("")
    private val pairingDeviceAddress = mutableStateOf("")
    private var pairingPinDeferred: CompletableDeferred<String>? = null

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val bondStateExtra = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)

            val stateName = gattDiagnostic.formatBondState(bondStateExtra)
            val prevName = gattDiagnostic.formatBondState(prevState)

            val addressForLog = if (this@MainActivity.hasBluetoothConnectPermission()) {
                try {
                    device?.address ?: "null"
                } catch (e: SecurityException) {
                    "redacted"
                }
            } else {
                "redacted"
            }
            Log.i("BlueBitGatt", "BOND_STATE_CHANGED: $addressForLog $prevName -> $stateName")

            bondState.value = stateName

            when (bondStateExtra) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i("BlueBitGatt", "BONDING_SUCCESS: Device is now bonded")
                    bondDeferred?.complete(true)
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w("BlueBitGatt", "BONDING_FAILED: Bonding rejected or failed")
                    bondDeferred?.complete(false)
                }
                BluetoothDevice.BOND_BONDING -> {
                    Log.i("BlueBitGatt", "BONDING_IN_PROGRESS: Waiting for user/system...")
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initFitCloudSdk(application)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e("BlueBit", "BLUETOOTH_NOT_AVAILABLE: No BluetoothAdapter")
        } else {
            // isEnabled and getAddress require BLUETOOTH_CONNECT on Android 12+.
            // Defer detailed logging until permission is granted.
            val connectGranted = hasBluetoothConnectPermission()
            Log.i("BlueBit", "BLUETOOTH_ADAPTER_PRESENT: connectGranted=$connectGranted")
            if (connectGranted) {
                logBluetoothAdapterState()
            } else {
                Log.i("BlueBit", "BLUETOOTH_DEFERRED: Waiting for BLUETOOTH_CONNECT before reading adapter state/address")
            }
        }

        gattDiagnostic = FitbitGattDiagnostic(this)
        gattCacheDiagnostic = FitbitGattCacheDiagnostic(this)
        gattlinkDiagnostic = FitbitGattlinkDiagnostic(this)
        goldenGateDiagnostic = FitbitGoldenGateDiagnostic(this)
        gattlinkStartupDiagnostic = GattlinkStartupDiagnostic(this)
        pairingHandler = Inspire3PairingHandler(this)
        serverPairingExperiment = ServerPairingExperiment(this)
        dtlsBootstrapProbe = DtlsBootstrapProbe(this)
        coapReadOnlyProbe = CoapReadOnlyProbe(this)

        // Forward all BLE watchdog events to the optional background file logger.
        bleLogWatchdog.fileLogger = { entry -> BleDiagnosticLogger.log(entry) }

        scanner = if (bluetoothAdapter != null) {
            AndroidBleScanner(this, bluetoothAdapter!!)
        } else {
            bluebit.core.bluetooth.NoOpBleScanner()
        }

        val profileRepository = InMemoryDeviceProfileRepository(emptyList())
        val connectionFactory: (bluebit.core.bluetooth.BleDevice) -> bluebit.core.bluetooth.BleConnection = { device ->
            AndroidBleConnection(
                context = this,
                bluetoothAdapter = bluetoothAdapter!!,
                logger = { msg -> Log.i("BlueBitBle", msg) }
            )
        }
        val fitbitProvider = FitbitProvider(
            profileRepository = profileRepository,
            connectionFactory = connectionFactory,
            diagnosticsFactory = {
                val adapter = bluetoothAdapter
                if (adapter == null) {
                    Log.w("BlueBit", "DIAG_FACTORY: BluetoothAdapter null, no diagnostics")
                    emptyList()
                } else {
                    listOf(
                        ProviderDiagnostic(
                            id = "gatt_inspect",
                            label = "Inspect GATT",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    gattDiagnostic.connectAndDiscover(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GATT_INSPECT failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "gattcache_inspect",
                            label = "Inspect GATT Cache",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    gattCacheDiagnostic.startExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GATTCACHE_INSPECT failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "gattlink_test",
                            label = "Test Gattlink",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    gattlinkDiagnostic.startExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GATTLINK_TEST failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "goldengate_test",
                            label = "Test GoldenGate",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    goldenGateDiagnostic.startExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GOLDENGATE_TEST failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "goldengate_repro_test",
                            label = "Test GoldenGate Reproducible",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    goldenGateDiagnostic.startReproducibleExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GOLDENGATE_REPRO_TEST failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "goldengate_bond_before_control_test",
                            label = "GG Bond→Control Experiment",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    goldenGateDiagnostic.startBondBeforeControlExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GOLDENGATE_BOND_BEFORE_CONTROL failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "goldengate_transport_only_test",
                            label = "GG Transport Race Test",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    goldenGateDiagnostic.startTransportOnlyExperiment(device)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GOLDENGATE_TRANSPORT_ONLY failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "gattlink_startup_test",
                            label = "Test Gattlink Startup",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    lifecycleScope.launch {
                                        val result = gattlinkStartupDiagnostic.runDiagnostic(device)
                                        Log.i("BlueBit", "GATTLINK_STARTUP result=$result")
                                    }
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "GATTLINK_STARTUP failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "dtls_bootstrap_probe",
                            label = "Probe DTLS Bootstrap",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    lifecycleScope.launch {
                                        val result = dtlsBootstrapProbe.runProbe(device)
                                        Log.i("BlueBit", "DTLS_BOOTSTRAP_PROBE result=$result")
                                    }
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "DTLS_BOOTSTRAP_PROBE failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "coap_readonly_probe",
                            label = "Probe CoAP Read-Only",
                            action = { address ->
                                try {
                                    val device = adapter.getRemoteDevice(address)
                                    lifecycleScope.launch {
                                        val result = coapReadOnlyProbe.runProbe(device)
                                        lastCoapReadOnlyResult.value = result
                                        Log.i("BlueBit", "COAP_READONLY_PROBE result=$result")
                                    }
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "COAP_READONLY_PROBE failed: $address", e)
                                }
                            }
                        ),
                        ProviderDiagnostic(
                            id = "inspire3_pair",
                            label = "Pair with Inspire 3",
                            action = { address ->
                                try {
                                    pairWithInspire3(address)
                                } catch (e: Exception) {
                                    Log.e("BlueBit", "PAIR_WITH_INSPIRE3 failed: $address", e)
                                }
                            }
                        ),
                    )
                }
            },
            inspire3PairingHandler = { bleDevice ->
                val adapter = bluetoothAdapter
                    ?: return@FitbitProvider Result.failure(IllegalStateException("BluetoothAdapter null"))
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return@FitbitProvider Result.failure(SecurityException("BLUETOOTH_CONNECT not granted"))
                }
                bleLogWatchdog.info(BleLogTag.CONNECTION, "INSPIRE3_PAIRING_START: address=${bleDevice.address}")
                try {
                    val device = adapter.getRemoteDevice(bleDevice.address)
                    val result = pairingHandler.pairDevice(device, extraCaptureMs = 0L)
                    if (result) {
                        bleLogWatchdog.info(BleLogTag.CONNECTION, "INSPIRE3_PAIRING_SUCCESS: address=${bleDevice.address}")
                        Result.success(Unit)
                    } else {
                        bleLogWatchdog.error(BleLogTag.CONNECTION, "INSPIRE3_PAIRING_FAILED: address=${bleDevice.address}")
                        Result.failure(IllegalStateException("Inspire 3 pairing sequence returned false"))
                    }
                } catch (e: Exception) {
                    bleLogWatchdog.error(BleLogTag.CONNECTION, "INSPIRE3_PAIRING_EXCEPTION: address=${bleDevice.address}, error=${e.message}")
                    Result.failure(e)
                }
            },
        )
        fitcloudProvider = AndroidFitCloudProvider(this, fcSDK)
        val providerRegistry = ProviderRegistry(listOf(fitbitProvider, fitcloudProvider))
        val providerContext = ProviderContext(providerRegistry, scanner!!)

        val pairedDeviceStore = SharedPreferencesPairedDeviceStore(applicationContext)
        pairedDeviceCoordinator = PairedDeviceCoordinator(
            registry = providerRegistry,
            store = pairedDeviceStore,
            scope = lifecycleScope,
        ).also { it.start() }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bondStateReceiver, filter)
        }

        setContent {
            BlueBitTheme {
                BlueBitAppShell(
                    providerContext = providerContext,
                    bondState = bondState.value,
                    onConnect = { address ->
                        bleLogWatchdog.info(BleLogTag.CONNECTION, "CONNECT_BUTTON_PRESSED: address=$address")
                        Log.i("BlueBit", "CONNECT_REQUESTED: address=$address")
                    },
                    watchdogDumpAction = { address ->
                        val dump = bleLogWatchdog.formatEntries()
                        val file = File(getExternalFilesDir(null), "bluebit_ble_log_${System.currentTimeMillis()}.txt")
                        file.writeText(dump)
                        Log.i("BlueBitWatchdog", "Dumped ${bleLogWatchdog.dump().size} entries for $address to ${file.absolutePath}")
                    },
                    pairedDeviceCoordinator = pairedDeviceCoordinator,
                    lastCoapReadOnlyResult = lastCoapReadOnlyResult.value,
                    diagnosticLoggingEnabled = isDiagnosticLogging.value,
                    onDiagnosticLoggingToggle = { enabled ->
                        isDiagnosticLogging.value = enabled
                        if (enabled) {
                            BleDiagnosticLogger.start(this@MainActivity)
                        } else {
                            BleDiagnosticLogger.stop(this@MainActivity)
                        }
                    },
                )
                if (showPairingDialog.value) {
                    PairingDialog(
                        deviceName = pairingDeviceName.value,
                        deviceAddress = pairingDeviceAddress.value,
                        onSubmit = { pin ->
                            showPairingDialog.value = false
                            pairingPinDeferred?.complete(pin)
                        },
                        onDismiss = {
                            showPairingDialog.value = false
                            pairingPinDeferred?.completeExceptionally(
                                Exception("User cancelled pairing")
                            )
                        }
                    )
                }
            }
        }

        // Trigger permission flow after all BLE-dependent objects are initialised.
        // If permissions are already granted this will proceed immediately.
        requestBlePermissions()
    }

    /**
     * Returns true if BLUETOOTH_CONNECT is granted. Required for getAddress(), getName(),
     * getRemoteDevice(), createBond(), and other BluetoothDevice APIs on Android 12+.
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 12: BLUETOOTH permission covers these operations.
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Safely logs adapter state. Only call after verifying BLUETOOTH_CONNECT (or pre-12 BLUETOOTH)
     * is granted, otherwise Android 12+ throws SecurityException for isEnabled()/getAddress().
     */
    private fun logBluetoothAdapterState() {
        val adapter = bluetoothAdapter ?: return
        if (!hasBluetoothConnectPermission()) {
            Log.w("BlueBit", "BLUETOOTH_STATE_SKIPPED: BLUETOOTH_CONNECT not granted")
            return
        }
        try {
            val enabled = adapter.isEnabled
            val address = adapter.address
            Log.i("BlueBit", "BLUETOOTH_STATE: enabled=$enabled, address=$address")
        } catch (e: SecurityException) {
            Log.e("BlueBit", "BLUETOOTH_STATE_SECURITY_EXCEPTION: ${e.message}", e)
        }
    }

    /**
     * Runs operations that require runtime BLE permissions after the user has granted them.
     * Called from the permission launcher and when permissions are already granted at startup.
     * Idempotent: multiple call sites converge to a single run per activity instance.
     */
    private fun initializeBluetoothDependentOperations() {
        if (bluetoothOperationsInitialized) {
            Log.i("BlueBit", "INIT_BLE_OPS_SKIPPED: already initialized")
            return
        }
        bluetoothOperationsInitialized = true
        logBluetoothAdapterState()
        val scannerRef = scanner
        if (scannerRef == null) {
            Log.w("BlueBit", "INIT_BLE_OPS: scanner is null")
        } else {
            Log.i("BlueBit", "INIT_BLE_OPS: scanner ready, isScanning=${scannerRef.isScanning}")
        }
        lifecycleScope.launch {
            try {
                pairedDeviceCoordinator?.restoreOnLaunch()
            } catch (e: SecurityException) {
                Log.e("BlueBit", "RESTORE_ON_LAUNCH_SECURITY_EXCEPTION: ${e.message}", e)
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).apply {
            // Location is only required for BLE scanning on Android 11 and lower.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // Direct Downloads writes on Android 9 and lower need storage permission.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        val status = permissions.associateWith {
            when (ContextCompat.checkSelfPermission(this, it)) {
                PackageManager.PERMISSION_GRANTED -> "GRANTED"
                PackageManager.PERMISSION_DENIED -> "DENIED"
                else -> "UNKNOWN"
            }
        }
        Log.i("BlueBit", "PERMISSION_STATUS: $status")

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            val grantedMap = status.mapValues { it.value == "GRANTED" }
            val deniedBluetoothPermissions = permissionPolicy.deniedBluetoothPermissions(grantedMap)
            if (deniedBluetoothPermissions.isEmpty()) {
                // Bluetooth-critical permissions are already granted (e.g. only the notification
                // permission is pending) — BLE features must not wait on that non-critical request.
                val notificationsDenied = status[Manifest.permission.POST_NOTIFICATIONS] == "DENIED"
                if (notificationsDenied) {
                    Log.i(
                        "BlueBit",
                        "NOTIFICATIONS_OPTIONAL: POST_NOTIFICATIONS pending/denied — only app notifications are affected; " +
                            "in-app connection and auto-restore initialize now."
                    )
                }
                initializeBluetoothDependentOperations()
            }
            Log.i("BlueBit", "REQUESTING_PERMISSIONS: ${permissions.joinToString()}")
            permissionLauncher.launch(permissions)
        } else {
            Log.i("BlueBit", "PERMISSIONS_ALREADY_GRANTED: ${permissions.joinToString()}")
            initializeBluetoothDependentOperations()
        }
    }

    private fun inspectGatt(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "GATT_INSPECT: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "GATT_INSPECT: BLUETOOTH_CONNECT not granted")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            lifecycleScope.launch {
                gattDiagnostic.connectAndDiscover(device)
            }
        } catch (e: IllegalArgumentException) {
            Log.e("BlueBit", "GATT_INSPECT: Invalid address=$address", e)
        }
    }

    private fun inspectGattCache(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "GATTCACHE_INSPECT: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "GATTCACHE_INSPECT: BLUETOOTH_CONNECT not granted")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            gattCacheDiagnostic.startExperiment(device)
        } catch (e: IllegalArgumentException) {
            Log.e("BlueBit", "GATTCACHE_INSPECT: Invalid address=$address", e)
        }
    }

    private fun testGattlink(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "GATTLINK_TEST: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "GATTLINK_TEST: BLUETOOTH_CONNECT not granted")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            gattlinkDiagnostic.startExperiment(device)
        } catch (e: IllegalArgumentException) {
            Log.e("BlueBit", "GATTLINK_TEST: Invalid address=$address", e)
        }
    }

    private fun testGoldenGate(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "GOLDENGATE_TEST: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "GOLDENGATE_TEST: BLUETOOTH_CONNECT not granted")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            goldenGateDiagnostic.startExperiment(device)
        } catch (e: IllegalArgumentException) {
            Log.e("BlueBit", "GOLDENGATE_TEST: Invalid address=$address", e)
        }
    }

    private fun bondAndInspectGatt(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "BOND_INSPECT: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "BOND_INSPECT: BLUETOOTH_CONNECT not granted")
            return
        }

        lifecycleScope.launch {
            try {
                val device = adapter.getRemoteDevice(address)
                val currentBond = device.bondState
                val currentBondName = gattDiagnostic.formatBondState(currentBond)
                bondState.value = currentBondName

                Log.i("BlueBitGatt", "========== BONDING EXPERIMENT ==========")
                Log.i("BlueBitGatt", "BOND_EXPERIMENT_START")
                Log.i("BlueBitGatt", "DEVICE_NAME=${device.name ?: "null"}")
                Log.i("BlueBitGatt", "DEVICE_ADDRESS=${device.address}")
                Log.i("BlueBitGatt", "CURRENT_BOND_STATE=$currentBondName")

                if (currentBond == BluetoothDevice.BOND_NONE) {
                    Log.i("BlueBitGatt", "BOND_INITIATING: createBond() called")
                    val deferred = CompletableDeferred<Boolean>()
                    bondDeferred = deferred
                    val success = device.createBond()
                    Log.i("BlueBitGatt", "CREATE_BOND_RETURNED=$success")

                    if (!success) {
                        Log.e("BlueBitGatt", "BOND_INITIATE_FAILED: createBond() returned false immediately")
                        return@launch
                    }

                    val bonded = deferred.await()
                    if (!bonded) {
                        Log.e("BlueBitGatt", "BONDING_FAILED: Did not reach BOND_BONDED")
                        return@launch
                    }
                } else if (currentBond == BluetoothDevice.BOND_BONDED) {
                    Log.i("BlueBitGatt", "BOND_ALREADY_BONDED: Skipping createBond()")
                }

                // Verify bond state after bonding
                val postBond = device.bondState
                val postBondName = gattDiagnostic.formatBondState(postBond)
                Log.i("BlueBitGatt", "POST_BOND_STATE=$postBondName")

                // Step 1: Connect GATT and discover services
                Log.i("BlueBitGatt", "STEP_1: GATT connect + service discovery (bonded)")
                val discovered = gattDiagnostic.connectAndDiscover(device)
                if (!discovered) {
                    Log.e("BlueBitGatt", "STEP_1_FAILED: Service discovery failed")
                    return@launch
                }
                Log.i("BlueBitGatt", "STEP_1_SUCCESS: Services discovered while bonded")

                // Step 2: Disconnect
                Log.i("BlueBitGatt", "STEP_2: Disconnecting...")
                gattDiagnostic.disconnect()
                kotlinx.coroutines.delay(1000)

                // Step 3: Reconnect and verify
                Log.i("BlueBitGatt", "STEP_3: Reconnecting to verify bond persistence...")
                val reconnectDevice = adapter.getRemoteDevice(address)
                val reconnectBond = reconnectDevice.bondState
                val reconnectBondName = gattDiagnostic.formatBondState(reconnectBond)
                Log.i("BlueBitGatt", "RECONNECT_BOND_STATE=$reconnectBondName")

                if (reconnectBond == BluetoothDevice.BOND_BONDED) {
                    Log.i("BlueBitGatt", "BOND_PERSISTENCE_VERIFIED: Android still reports BONDED")
                } else {
                    Log.w("BlueBitGatt", "BOND_NOT_PERSISTENT: Android reports $reconnectBondName after disconnect")
                }

                val rediscovered = gattDiagnostic.connectAndDiscover(reconnectDevice)
                if (rediscovered) {
                    Log.i("BlueBitGatt", "STEP_3_SUCCESS: Reconnect + rediscovery succeeded")
                } else {
                    Log.e("BlueBitGatt", "STEP_3_FAILED: Reconnect rediscovery failed")
                }

                Log.i("BlueBitGatt", "BOND_EXPERIMENT_COMPLETE")
                Log.i("BlueBitGatt", "=======================================")

            } catch (e: IllegalArgumentException) {
                Log.e("BlueBit", "BOND_INSPECT: Invalid address=$address", e)
            }
        }
    }

    private fun pairWithInspire3(address: String) {
        Log.i("BlueBit", "PAIR_BUTTON_CLICKED: address=$address")
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "PAIR: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "PAIR: BLUETOOTH_CONNECT not granted")
            return
        }

        lifecycleScope.launch {
            try {
                val device = adapter.getRemoteDevice(address)
                pairingDeviceName.value = device.name ?: "Inspire 3"
                pairingDeviceAddress.value = device.address

                val result = pairingHandler.pairDevice(device, extraCaptureMs = 55000L)

                if (result) {
                    Log.i("BlueBit", "PAIR: Success! Device bonded.")
                    bondState.value = "BONDED"
                } else {
                    Log.e("BlueBit", "PAIR: Failed to bond")
                    bondState.value = "FAILED"
                }
            } catch (e: Exception) {
                Log.e("BlueBit", "PAIR: Exception during pairing", e)
                bondState.value = "ERROR"
            }
        }
    }

    /**
     * Experiment: Call mock Fitbit server (validate + pair) before attempting bond/control.
     * Logs results to logcat. Does not modify BLE logic.
     */
    private fun testServerThenBond(address: String) {
        Log.i("BlueBit", "SERVER_EXPERIMENT_BUTTON_CLICKED: address=$address")
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("BlueBit", "SERVER_EXPERIMENT: BluetoothAdapter is null")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BlueBit", "SERVER_EXPERIMENT: BLUETOOTH_CONNECT not granted")
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            serverPairingExperiment.runExperiment(
                device = device,
                onLog = { msg -> Log.i("BlueBit", msg) },
                onComplete = { success, msg ->
                    Log.i("BlueBit", "SERVER_EXPERIMENT_RESULT: success=$success, msg=$msg")
                    if (success) {
                        Log.i("BlueBit", "Now run GoldenGate/bond/control manually to observe if 4.01 persists.")
                    }
                }
            )
        } catch (e: IllegalArgumentException) {
            Log.e("BlueBit", "SERVER_EXPERIMENT: Invalid address=$address", e)
        }
    }

    /**
     * Quick test: Can we reach the mock server?
     */
    private fun testMockServerConnection() {
        serverPairingExperiment.testServerConnection { msg ->
            Log.i("BlueBit", msg)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            scanner?.stopScan()
        } catch (e: Exception) {
            Log.w("BlueBit", "STOP_SCAN_ON_DESTROY_FAILED: ${e.message}")
        }
        fitcloudProvider.dispose()
        gattDiagnostic.disconnect()
        gattCacheDiagnostic.disconnect()
        gattlinkDiagnostic.disconnect()
        goldenGateDiagnostic.disconnect()
        gattlinkStartupDiagnostic.disconnect()
        pairingHandler.disconnect()
        serverPairingExperiment.cancel()
        coapReadOnlyProbe.disconnect()
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver not registered
        }
    }
}
