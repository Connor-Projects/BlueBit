package bluebit.fitbit.diagnostics

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import bluebit.fitbit.FitbitPairingApi
import kotlinx.coroutines.*

/**
 * Experiment: Does calling Fitbit pairing server endpoints before bond/control
 * change the Inspire 3's authorization response?
 *
 * Theory: The Inspire 3 firmware may require server-mediated pairing validation.
 * If so, calling validate.json -> pair.json before bond/control should change
 * the 4.01 Unauthorized response to 2.01 Created.
 *
 * Method:
 *   1. Call mock validate.json -> get pairingToken
 *   2. Call mock pair.json with token -> get server response
 *   3. (Discard response since we can't parse unknown protobuf)
 *   4. Proceed with normal BLE/GoldenGate/bond/control flow
 *   5. Observe if bond/control response changes
 *
 * If bond/control still returns 4.01, then server responses do NOT affect
 * the firmware authorization state.
 *
 * If bond/control returns 2.01, then server responses ARE required.
 *
 * Limitation: This uses a mock server with placeholder responses. Even if
 * the real server is required, the mock may not return the correct data.
 * This test determines the *minimum* requirement ("does ANY server call help?").
 */
class ServerPairingExperiment(private val context: Context) {

    companion object {
        const val TAG = "ServerPairingExp"
    }

    private val api = FitbitPairingApi(baseUrl = "http://10.0.2.2:8080")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Run the full server-then-bond experiment.
     *
     * @param device The Inspire 3 BluetoothDevice
     * @param onLog Callback for logging output
     * @param onComplete Callback with result: true if server calls succeeded, false otherwise
     */
    fun runExperiment(
        device: BluetoothDevice,
        onLog: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        scope.launch {
            try {
                onLog("========================================")
                onLog("SERVER PAIRING EXPERIMENT")
                onLog("========================================")
                onLog("Device: ${device.name} ${device.address}")
                onLog("")

                // Step 1: Call validate.json
                onLog("STEP 1: POST validate.json")
                onLog("  btleName=${device.name}")
                onLog("  btAddress=${device.address}")
                onLog("  secret=000000 (placeholder)")

                val validateResponse = api.validate(
                    btleName = device.name ?: "Inspire3",
                    secret = "000000",
                    btAddress = device.address
                )

                onLog("  -> pairingToken=${validateResponse.pairingToken}")
                onLog("  -> peripheralDeviceType=${validateResponse.peripheralDeviceType}")
                onLog("")

                // Step 2: Call pair.json
                onLog("STEP 2: POST pair.json")
                onLog("  pairingToken=${validateResponse.pairingToken}")

                val pairResponse = api.pair(
                    pairingToken = validateResponse.pairingToken
                )

                onLog("  -> response length=${pairResponse.length} chars")
                onLog("  -> response preview=${pairResponse.take(40)}...")
                onLog("")

                // Step 3: Acknowledge (optional, for completeness)
                onLog("STEP 3: POST ack.json")
                val ackResponse = api.ack(ackToken = "mock-ack-token")
                onLog("  -> status=${ackResponse.status}")
                onLog("")

                onLog("SERVER CALLS COMPLETE")
                onLog("Now proceeding with BLE/GoldenGate/bond/control...")
                onLog("========================================")

                onComplete(true, "Server validate+pair succeeded. Token=${validateResponse.pairingToken.take(20)}...")

            } catch (e: Exception) {
                Log.e(TAG, "Server pairing experiment failed", e)
                onLog("ERROR: ${e.message}")
                onComplete(false, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Quick connectivity test to the mock server.
     */
    fun testServerConnection(onLog: (String) -> Unit) {
        scope.launch {
            try {
                onLog("Testing mock server at http://10.0.2.2:8080...")
                // We can't easily do a GET with our current API, so just try validate
                val response = api.validate(
                    btleName = "TestDevice",
                    secret = "123456",
                    btAddress = "A4CF12345678"
                )
                onLog("  -> SUCCESS: pairingToken=${response.pairingToken.take(20)}...")
            } catch (e: Exception) {
                onLog("  -> FAILED: ${e.message}")
                onLog("  Make sure the mock server is running:")
                onLog("    python fitbit_mock_server.py --port 8080")
            }
        }
    }

    fun cancel() {
        scope.cancel()
        api.close()
    }
}
