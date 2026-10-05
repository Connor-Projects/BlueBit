package bluebit.fitbit

import bluebit.fitbit.FitbitPairingApi
import kotlinx.coroutines.runBlocking

/**
 * Desktop smoke test for the Fitbit pairing API client against the mock server.
 *
 * Run mock server first:
 *   python BlueBitApp/mock-server/fitbit_mock_server.py --port 8765
 *
 * Then run from Gradle:
 *   ./gradlew :app:desktopRun -DmainClass=bluebit.PairingApiTest
 */
fun main() = runBlocking {
    val api = FitbitPairingApi(baseUrl = "http://localhost:8765")

    println("=== FitbitPairingApi smoke test ===")

    try {
        println("Calling validate.json...")
        val validate = api.validate(
            btleName = "Inspire3",
            secret = "123456",
            btAddress = "A4:CF:12:34:56:78"
        )
        println("  -> pairingToken=${validate.pairingToken}")
        println("  -> peripheralDeviceType=${validate.peripheralDeviceType}")

        println("Calling pair.json...")
        val pair = api.pair(
            pairingToken = validate.pairingToken
        )
        println("  -> pair response length=${pair.length}")
        println("  -> pair response preview=${pair.take(40)}...")

        println("Calling ack.json...")
        val ack = api.ack(ackToken = "mock-ack")
        println("  -> ack status=${ack.status}")

        println("Calling sync.json...")
        val sync = api.sync(
            trigger = "USER",
            btleName = "Inspire3"
        )
        println("  -> sync response length=${sync.length}")

        println("=== SMOKE TEST PASSED ===")
    } catch (e: Exception) {
        println("=== SMOKE TEST FAILED ===")
        e.printStackTrace()
    } finally {
        api.close()
    }
}
