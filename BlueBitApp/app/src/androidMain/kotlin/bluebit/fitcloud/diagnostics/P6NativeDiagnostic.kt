package bluebit.fitcloud.diagnostics

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.android.AndroidBleConnection
import bluebit.core.diagnostics.DiagnosticLog
import bluebit.fitcloud.native.P6AuthMode
import bluebit.fitcloud.native.P6Connector
import bluebit.fitcloud.native.P6Identity
import bluebit.fitcloud.native.P6SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diagnostic probe for the independent (non-SDK) FitCloud P6 transport stack.
 *
 * Runs the verified handshake (LOGIN: probe `(3,0x14)` -> result -> submit
 * `(3,0x13,uidBlock)`; BIND: probe `(3,0x12)` -> result -> submit
 * `(3,0x11,uidBlock)`) over BlueBit's own [AndroidBleConnection] +
 * [P6Connector], then keeps the link open briefly to observe and log any
 * spontaneous config pushes (observe-only — nothing except the auth submit is
 * ever written; `requestLcdShape` and friends are never sent, and no failure
 * is ever reported as success).
 *
 * Auth identity is the hardware-verified pair: userId 26544037 / MAC
 * 90:EB:99:D6:5F:99 (doLoginAuth result:0 baseline). The userId must not be
 * changed silently.
 */
class P6NativeDiagnostic(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {

    companion object {
        private const val TAG = "BlueBitP6Native"

        /** Reference app negotiates 247 for the FitCloud transport. [A] value. */
        const val REQUESTED_MTU = 247

        /** Auth identity — hardware-verified, never altered silently. */
        const val VERIFIED_USER_ID = "26544037"

        /** Idle window after auth to observe spontaneous pushes, then disconnect. */
        const val OBSERVE_WINDOW_MS = 5_000L

        private const val HEX_LINE_LIMIT = 32
    }

    /** Connector for a run in flight, for [disconnect] from non-suspend contexts. */
    private var activeConnector: P6Connector? = null

    /** App-scoped (activity-independent) cleanup scope for [disconnect]. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Run the full native handshake against [device] using [mode] (LOGIN by
     * default). Returns a summary string describing exactly what happened;
     * failures throw and the caller logs the exception text. Never reports
     * success it did not earn.
     */
    suspend fun runLoginHandshake(device: BluetoothDevice, mode: P6AuthMode = P6AuthMode.LOGIN): String {
        if (adapter == null) throw IllegalStateException("BluetoothAdapter absent")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_CONNECT not granted")
        }

        val startedAt = System.currentTimeMillis()
        val connection = AndroidBleConnection(
            context = context,
            bluetoothAdapter = adapter,
            logger = { msg -> Log.i(TAG, msg) },
        )
        val connector = P6Connector(
            connection,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = { msg -> Log.i(TAG, "P6: $msg") },
        )
        activeConnector = connector
        var pushCount = 0
        try {
            connector.connectAndAuthenticate(
                device.asAbstract(),
                identityFor(address = device.address),
                mode,
            ) {
                if (connection.requestMtu(REQUESTED_MTU)) {
                    connection.negotiatedMtu - 3
                } else {
                    // Honest fallback: MTU negotiation refused; do NOT pretend
                    // it worked. Fall back to the largest legal single ATT write.
                    Log.w(TAG, "MTU request to $REQUESTED_MTU refused; using default write ceiling")
                    P6Connector.DEFAULT_WRITE_LIMIT
                }
            }

            val elapsedAtAuth = System.currentTimeMillis() - startedAt
            Log.i(TAG, "AUTH_OK at ${elapsedAtAuth}ms; observing ${OBSERVE_WINDOW_MS}ms of pushes")

            // Observe-only window: connectAndAuthenticate returned; spontaneous
            // config pushes (typically (2,0x21) device info) arrive on ff03.
            withTimeoutOrNull(OBSERVE_WINDOW_MS) {
                connector.events.collect { event ->
                    when (event) {
                        is P6SessionEvent.Data -> {
                            pushCount++
                            val packet = event.packet
                            val payloadText = packet.firstData
                                ?.take(HEX_LINE_LIMIT)
                                ?.joinToString(" ") { "%02X".format(it) }
                                ?.ifEmpty { "<empty>" }
                                ?: "<unparseable>"
                            if (packet.keyId != null) {
                                DiagnosticLog.info(
                                    "P6 Native Login",
                                    "push #$pushCount: cfgId=${packet.cfgId} keyId=0x" +
                                        "%02X".format(packet.keyId) +
                                        " len=${packet.firstData?.size ?: 0} data=$payloadText",
                                    device.address,
                                )
                            } else {
                                DiagnosticLog.info(
                                    "P6 Native Login",
                                    "push #$pushCount: cfgId=${packet.cfgId} payload=$payloadText",
                                    device.address,
                                )
                            }
                        }

                        is P6SessionEvent.InvalidFrame ->
                            DiagnosticLog.warning(
                                "P6 Native Login",
                                "invalid frame sn=${event.sequenceId}: ${event.reason}",
                                device.address,
                            )

                        is P6SessionEvent.Ended -> { /* disconnect below closes the window */ }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startedAt
            return "$mode handshake OK: uid=$VERIFIED_USER_ID, mtu-limit=" +
                "${connector.sessionWriteLimit}, pushes=$pushCount, ${elapsed}ms"
        } finally {
            activeConnector = null
            runCatching { connector.disconnect() }
            Log.i(TAG, "runLoginHandshake finished for ${device.address}")
        }
    }

    /** Release any lingering GATT connection (idempotent, safe from onDestroy). */
    fun disconnect() {
        val connector = activeConnector ?: return
        activeConnector = null
        cleanupScope.launch {
            runCatching { connector.disconnect() }
        }
    }

    /** Bridge the Android [BluetoothDevice] into the abstract [BleDevice] model. */
    private fun BluetoothDevice.asAbstract(): BleDevice {
        val name = try { name } catch (_: SecurityException) { null }
        return BleDevice(address = address, name = name)
    }

    /** Identity snapshot for the tracker under test; MAC comes from the BLE address. */
    private fun identityFor(address: String): P6Identity =
        P6Identity(userId = VERIFIED_USER_ID, macAddress = address.uppercase())
}
