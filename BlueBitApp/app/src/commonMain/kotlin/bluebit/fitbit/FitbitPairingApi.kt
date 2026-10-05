package bluebit.fitbit

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fitbit pairing API client for experimenting with server-mediated pairing.
 *
 * This client calls the mock Fitbit backend to determine whether
 * server responses influence the Inspire 3 pairing authorization state.
 *
 * Endpoints (from APK analysis):
 *   POST /1/devices/client/tracker/data/validate.json
 *   POST /1/devices/client/tracker/data/pair.json
 *   POST /1/devices/client/tracker/data/ack.json
 *   POST /1/devices/client/tracker/data/sync.json
 *
 * The official Fitbit app calls validate.json first, which returns a pairingToken.
 * The pairingToken is then used in pair.json and subsequent sync calls.
 *
 * Experiment question: Does the mere act of calling these endpoints
 * (regardless of response content) affect bond/control authorization?
 */
class FitbitPairingApi(baseUrl: String = "http://10.0.2.2:8080") {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        defaultRequest {
            url(baseUrl)
        }
        expectSuccess = false
    }

    /**
     * POST /1/devices/client/tracker/data/validate.json
     *
     * Query: btleName, secret, btAddress
     * Body: base64-encoded device data
     * Response: { pairingToken, peripheralDeviceType }
     */
    suspend fun validate(
        btleName: String,
        secret: String,
        btAddress: String,
        deviceDataBase64: String = ""
    ): ValidateResponse {
        val response: HttpResponse = client.post("/1/devices/client/tracker/data/validate.json") {
            parameter("btleName", btleName)
            parameter("secret", secret)
            parameter("btAddress", btAddress.replace(":", ""))
            header("Device-Data-Encoding", "BASE_64")
            contentType(ContentType.Text.Plain)
            setBody(deviceDataBase64.ifEmpty { "dGVzdA==" })
        }
        return response.body()
    }

    /**
     * POST /1/devices/client/tracker/data/pair.json
     *
     * Query: pairingToken, challengesRun, challengeResults, maxCommsVersion
     * Body: base64-encoded device data
     * Response: base64-encoded binary string
     */
    suspend fun pair(
        pairingToken: String,
        deviceDataBase64: String = "",
        challengesRun: Int = 0,
        challengeResults: Int = 0,
        maxCommsVersion: String = "2"
    ): String {
        val response: HttpResponse = client.post("/1/devices/client/tracker/data/pair.json") {
            parameter("pairingToken", pairingToken)
            parameter("challengesRun", challengesRun)
            parameter("challengeResults", challengeResults)
            parameter("maxCommsVersion", maxCommsVersion)
            header("Device-Data-Encoding", "BASE_64")
            header("X-Fitbit-CompanionAPIVersion", "4.28")
            contentType(ContentType.Text.Plain)
            setBody(deviceDataBase64.ifEmpty { "dGVzdA==" })
        }
        return response.bodyAsText()
    }

    /**
     * POST /1/devices/client/tracker/data/ack.json
     *
     * Query: ackToken, challengesRun, challengeResults
     */
    suspend fun ack(
        ackToken: String,
        challengesRun: Int = 0,
        challengeResults: Int = 0
    ): AckResponse {
        val response: HttpResponse = client.post("/1/devices/client/tracker/data/ack.json") {
            parameter("ackToken", ackToken)
            parameter("challengesRun", challengesRun)
            parameter("challengeResults", challengeResults)
        }
        return response.body()
    }

    /**
     * POST /1/devices/client/tracker/data/sync.json
     *
     * Query: trigger, btleName, maxCommsVersion
     * Body: base64-encoded device data
     */
    suspend fun sync(
        trigger: String = "USER",
        btleName: String,
        deviceDataBase64: String = "",
        maxCommsVersion: String = "2"
    ): String {
        val response: HttpResponse = client.post("/1/devices/client/tracker/data/sync.json") {
            parameter("trigger", trigger)
            parameter("btleName", btleName)
            parameter("maxCommsVersion", maxCommsVersion)
            header("Device-Data-Encoding", "BASE_64")
            header("X-Client-Criticality", "CRITICAL")
            header("X-Fitbit-CompanionAPIVersion", "4.28")
            contentType(ContentType.Text.Plain)
            setBody(deviceDataBase64.ifEmpty { "dGVzdA==" })
        }
        return response.bodyAsText()
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class ValidateResponse(
    val pairingToken: String,
    val peripheralDeviceType: String
)

@Serializable
data class AckResponse(
    val status: String
)
