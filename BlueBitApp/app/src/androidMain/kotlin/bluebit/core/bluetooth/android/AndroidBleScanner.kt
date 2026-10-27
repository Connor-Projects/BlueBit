package bluebit.core.bluetooth.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.bluetooth.BleScanner
import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.bleLogWatchdog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android-specific implementation of [BleScanner].
 *
 * Uses confirmed Fitbit service-UUID scan filters recovered from the
 * official Fitbit Android app. Logs every advertisement in detail.
 */
class AndroidBleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
) : BleScanner {

    companion object {
        private const val TAG = "BlueBitScan"
    }

    private val scanner = bluetoothAdapter.bluetoothLeScanner

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: Boolean get() = _isScanning.value

    private val _scanResults = MutableSharedFlow<BleScanResult>(extraBufferCapacity = 128)
    override fun scanResults(): Flow<BleScanResult> = _scanResults.asSharedFlow()

    private val _scanningState = MutableStateFlow(false)
    override fun scanningState(): Flow<Boolean> = _scanningState.asStateFlow()

    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resultCount = 0
    private var activeFilters: List<ScanFilter> = emptyList()
    private val lastDiagnosticLogMs = mutableMapOf<String, Long>()
    private val diagnosticLogIntervalMs = 2000L
    private val watchdogRunnable = Runnable {
        if (resultCount == 0 && isScanning) {
            Log.w(TAG, "WATCHDOG: No results after 5s. Restarting scan...")
            stopScanInternal()
            startScanInternal(activeFilters)
        }
    }

    override fun startScan() {
        // Log permission state before every scan
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val btScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        val btConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        Log.i(TAG, "PERMISSION_STATE: ACCESS_FINE_LOCATION=${fineLocation == PackageManager.PERMISSION_GRANTED}, BLUETOOTH_SCAN=${btScan == PackageManager.PERMISSION_GRANTED}, BLUETOOTH_CONNECT=${btConnect == PackageManager.PERMISSION_GRANTED}")

        if (!hasScanPermission()) {
            Log.e(TAG, "PERMISSION_DENIED: BLUETOOTH_SCAN or ACCESS_FINE_LOCATION not granted")
            bleLogWatchdog.error(BleLogTag.SCAN, "PERMISSION_DENIED: cannot start filtered scan")
            return
        }
        if (!isAdapterEnabledSafe()) {
            Log.e(TAG, "BLUETOOTH_DISABLED: Adapter is not enabled")
            bleLogWatchdog.error(BleLogTag.SCAN, "BLUETOOTH_DISABLED: cannot start filtered scan")
            return
        }
        if (scanner == null) {
            Log.e(TAG, "SCANNER_NULL: bluetoothLeScanner is null")
            return
        }
        Log.i(TAG, "SCAN_START: Starting Fitbit UUID filtered scan")
        bleLogWatchdog.info(BleLogTag.SCAN, "SCAN_START: Fitbit filtered scan")
        startScanInternal()
    }

    override fun startUnfilteredScan() {
        // Log permission state before every scan
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val btScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        val btConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        Log.i(TAG, "PERMISSION_STATE_UNFILTERED: ACCESS_FINE_LOCATION=${fineLocation == PackageManager.PERMISSION_GRANTED}, BLUETOOTH_SCAN=${btScan == PackageManager.PERMISSION_GRANTED}, BLUETOOTH_CONNECT=${btConnect == PackageManager.PERMISSION_GRANTED}")

        if (!hasScanPermission()) {
            Log.e(TAG, "PERMISSION_DENIED: BLUETOOTH_SCAN or ACCESS_FINE_LOCATION not granted")
            bleLogWatchdog.error(BleLogTag.SCAN, "PERMISSION_DENIED: cannot start unfiltered scan")
            return
        }
        if (!isAdapterEnabledSafe()) {
            Log.e(TAG, "BLUETOOTH_DISABLED: Adapter is not enabled")
            bleLogWatchdog.error(BleLogTag.SCAN, "BLUETOOTH_DISABLED: cannot start unfiltered scan")
            return
        }
        if (scanner == null) {
            Log.e(TAG, "SCANNER_NULL: bluetoothLeScanner is null")
            return
        }
        Log.i(TAG, "SCAN_START_UNFILTERED: Starting unfiltered scan (all BLE advertisements, no auto-connect)")
        resultCount = 0
        bleLogWatchdog.info(BleLogTag.SCAN, "SCAN_START_UNFILTERED: all BLE devices")
        startScanInternal(emptyList())
    }

    private fun startScanInternal() {
        val fitbitFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid.fromString("ADABFB00-6E7D-4601-BDA2-BFFAA68956BA"),
                    ParcelUuid.fromString("FFFF0000-FFFF-FFFF-FFFF-FFFFFFFFFFFF")
                ).build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString("0000FD63-0000-1000-8000-00805F9B34FB"))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString("ABBAFB00-E56A-484C-B832-8B17CF6CBFE8"))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8"))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString("0000FD62-0000-1000-8000-00805F9B34FB"))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString("0000181D-0000-1000-8000-00805F9B34FB"))
                .build(),
        )
        Log.i(TAG, "SCAN_START: Starting Fitbit UUID filtered scan, filterCount=${fitbitFilters.size}")
        fitbitFilters.forEachIndexed { i, filter ->
            Log.i(TAG, "  filter[$i]=${filter.serviceUuid?.uuid}")
        }
        startScanInternal(fitbitFilters)
    }

    private fun startScanInternal(filters: List<ScanFilter>) {
        activeFilters = filters
        resultCount = 0

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                resultCount++
                result ?: return
                val device = result.device
                val record = result.scanRecord

                // BLUETOOTH_CONNECT is required on Android 12+ to read address/name from a
                // BluetoothDevice object. The ScanRecord may still expose a local name and
                // advertisement payload even when BLUETOOTH_CONNECT is denied.
                val connectGranted = hasBluetoothConnectPermission()
                val address = if (connectGranted) {
                    try {
                        device.address ?: "unknown"
                    } catch (e: SecurityException) {
                        Log.w(TAG, "DEVICE_ADDRESS_SECURITY_EXCEPTION", e)
                        "unknown-${System.identityHashCode(device)}"
                    }
                } else {
                    "hidden-${System.identityHashCode(device)}"
                }
                val name = try {
                    record?.deviceName ?: if (connectGranted) device.name else null
                } catch (e: SecurityException) {
                    Log.w(TAG, "DEVICE_NAME_SECURITY_EXCEPTION", e)
                    null
                }

                val serviceUuids = record?.serviceUuids?.map { it.uuid.toString().uppercase() } ?: emptyList()
                val serviceData = record?.serviceData
                    ?.mapKeys { it.key.uuid.toString().uppercase() }
                    ?.mapValues { it.value.copyOf() }
                    ?: emptyMap()

                val manufacturerData = mutableMapOf<Int, ByteArray>()
                record?.manufacturerSpecificData?.let { md ->
                    for (i in 0 until md.size()) {
                        manufacturerData[md.keyAt(i)] = md.valueAt(i).copyOf()
                    }
                }
                val rawBytes = record?.bytes?.copyOf()

                if (resultCount == 1) {
                    Log.i(TAG, "FIRST_ADVERTISEMENT_RECEIVED: name=$name, address=$address, rssi=${result.rssi}, connectGranted=$connectGranted")
                }
                // Concise per-result debug log
                Log.d(TAG, "RAW_SCAN_RESULT: name=$name, address=$address, rssi=${result.rssi}, serviceUUIDs=$serviceUuids")
                Log.d(TAG, "MANUFACTURER_DATA: ids=${manufacturerData.keys}")
                bleLogWatchdog.debug(BleLogTag.SCAN, "RAW: name=$name, addr=$address, rssi=${result.rssi}, uuids=$serviceUuids, mfg=${manufacturerData.keys}")

                // Rate-limited detailed diagnostic summary (once per address per interval)
                logDiagnosticSummary(address) {
                    val serviceDataSummary = serviceData.entries.joinToString { "${it.key}=${it.value.size}b" }
                    val mfgSummary = manufacturerData.entries.joinToString { "0x${it.key.toString(16).uppercase()}=${it.value.size}b" }
                    "DIAGNOSTIC_ADVERTISEMENT: name=$name, address=$address, rssi=${result.rssi}, " +
                        "txPower=${record?.txPowerLevel ?: "unknown"}, " +
                        "serviceUUIDs=$serviceUuids, serviceData=[$serviceDataSummary], " +
                        "manufacturerData=[$mfgSummary], rawBytes=${rawBytes?.size ?: 0}b, " +
                        "connectGranted=$connectGranted"
                }

                val bleResult = BleScanResult(
                    address = address,
                    name = name,
                    rssi = result.rssi,
                    serviceUuids = serviceUuids,
                    serviceData = serviceData,
                    manufacturerData = manufacturerData,
                    rawBytes = rawBytes,
                    timestampNanos = result.timestampNanos,
                )

                _scanResults.tryEmit(bleResult)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "SCAN_FAILED: errorCode=$errorCode")
                bleLogWatchdog.error(BleLogTag.SCAN, "SCAN_FAILED: errorCode=$errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(filters, settings, callback!!)
            _isScanning.value = true
            _scanningState.value = true
            val mode = if (filters.isEmpty()) "UNFILTERED" else "FILTERED(filterCount=${filters.size})"
            Log.i(TAG, "SCAN_STARTED_OK: mode=$mode")
            bleLogWatchdog.info(BleLogTag.SCAN, "SCAN_STARTED_OK: mode=$mode")
            handler.postDelayed(watchdogRunnable, 5000)
        } catch (e: SecurityException) {
            Log.e(TAG, "SCAN_SECURITY_EXCEPTION: ${e.message}", e)
            bleLogWatchdog.error(BleLogTag.SCAN, "SCAN_SECURITY_EXCEPTION: ${e.message}")
            _isScanning.value = false
            _scanningState.value = false
        } catch (e: Exception) {
            Log.e(TAG, "SCAN_START_EXCEPTION: ${e.message}", e)
            bleLogWatchdog.error(BleLogTag.SCAN, "SCAN_START_EXCEPTION: ${e.message}")
            _isScanning.value = false
            _scanningState.value = false
        }
    }

    override fun stopScan() {
        handler.removeCallbacks(watchdogRunnable)
        stopScanInternal()
    }

    private fun stopScanInternal() {
        val cb = callback ?: return
        try {
            scanner?.stopScan(cb)
            Log.i(TAG, "SCAN_STOPPED")
        } catch (e: SecurityException) {
            Log.e(TAG, "STOP_SCAN_SECURITY_EXCEPTION: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "STOP_SCAN_EXCEPTION: ${e.message}", e)
        }
        callback = null
        _isScanning.value = false
        _scanningState.value = false
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * BLUETOOTH_CONNECT is required on Android 12+ to call BluetoothDevice.getAddress(),
     * getName(), BluetoothAdapter.isEnabled(), etc. Scanning itself only needs BLUETOOTH_SCAN.
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isAdapterEnabledSafe(): Boolean {
        return try {
            bluetoothAdapter.isEnabled
        } catch (e: SecurityException) {
            Log.e(TAG, "ADAPTER_ENABLED_SECURITY_EXCEPTION: ${e.message}", e)
            false
        }
    }

    /**
     * Rate-limited detailed diagnostic log. Logs at most once per [diagnosticLogIntervalMs]
     * for each address to avoid log spam during continuous scanning.
     */
    private fun logDiagnosticSummary(key: String, message: () -> String) {
        val now = System.currentTimeMillis()
        val last = lastDiagnosticLogMs[key]
        if (last == null || now - last >= diagnosticLogIntervalMs) {
            lastDiagnosticLogMs[key] = now
            Log.i(TAG, message())
        }
    }
}
