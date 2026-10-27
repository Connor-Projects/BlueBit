package bluebit.fitbit.diagnostics

import bluebit.core.diagnostics.CoapReadOnlyResult

/**
 * Testable description of a CoAP request.
 */
data class CoapRequestSpec(val path: String, val method: CoapReadOnlyMethod)

enum class CoapReadOnlyMethod { GET, POST, PUT, DELETE }

/**
 * Testable description of a CoAP response code.
 */
data class CoapResponseCode(val responseClass: Byte, val detail: Byte) {
    override fun toString(): String =
        "$responseClass.${detail.toInt().toString().padStart(2, '0')}"
}

/**
 * Intermediate summary produced while processing a CoAP response.
 */
data class CoapReadOnlyResponseSummary(
    val coapSuccess: Boolean,
    val responseCode: String?,
    val responseLength: Int,
    val resourceSummary: String,
    val error: String?
)

/**
 * Hardware-independent helpers for the CoAP read-only probe.
 *
 * Kept in commonMain so desktop tests can exercise request construction,
 * response handling, timeout handling, and cleanup failure handling without
 * Android APIs or native GoldenGate libraries.
 */
object CoapReadOnlyProbeLogic {

    private const val DEFAULT_MAX_SUMMARY_LENGTH = 240

    // Sensitive query parameters that must never be returned in logs or summaries.
    private val SECRET_PATTERNS = listOf("psk=", "token=", "key=", "secret=", "password=", "auth=")

    private val LINK_FORMAT_REGEX = "<([^>]+)>".toRegex()

    /**
     * Returns the request spec for a read-only CoAP GET.
     *
     * @param path CoAP Uri-Path; leading slash is stripped. Defaults to
     *   `.well-known/core` to preserve the existing diagnostic behaviour.
     */
    fun createRequestSpec(path: String = ".well-known/core"): CoapRequestSpec {
        require(path.isNotBlank()) { "CoAP path must not be blank" }
        require(!path.contains("?")) { "Query strings are not supported" }
        return CoapRequestSpec(path = path.removePrefix("/"), method = CoapReadOnlyMethod.GET)
    }

    /**
     * Processes a CoAP response body and code into a testable summary.
     *
     * @param responseCode CoAP response code.
     * @param responseBody Raw response body as a UTF-8 string.
     * @param maxSummaryLength Maximum length of the generated resource summary.
     * @param onWarning Callback invoked when the payload does not look like link-format.
     */
    fun processResponse(
        responseCode: CoapResponseCode,
        responseBody: String,
        maxSummaryLength: Int = DEFAULT_MAX_SUMMARY_LENGTH,
        onWarning: (String) -> Unit = {}
    ): CoapReadOnlyResponseSummary {
        if (responseBody.isEmpty()) {
            val error = if (responseCode.responseClass.toInt() == 2) {
                "Empty CoAP response body"
            } else {
                "Empty CoAP response body (code $responseCode)"
            }
            return CoapReadOnlyResponseSummary(
                coapSuccess = false,
                responseCode = responseCode.toString(),
                responseLength = 0,
                resourceSummary = "",
                error = error
            )
        }

        val isSuccess = responseCode.responseClass.toInt() == 2
        val error = if (!isSuccess) "CoAP error response: $responseCode" else null
        val cleanedBody = redactSecrets(responseBody)
        val summary = formatResourceSummary(cleanedBody, maxSummaryLength, onWarning)

        return CoapReadOnlyResponseSummary(
            coapSuccess = isSuccess,
            responseCode = responseCode.toString(),
            responseLength = responseBody.length,
            resourceSummary = summary,
            error = error
        )
    }

    /**
     * Produces the summary used when the CoAP request times out.
     */
    fun createTimeoutResult(timeoutMs: Long): CoapReadOnlyResponseSummary =
        CoapReadOnlyResponseSummary(
            coapSuccess = false,
            responseCode = null,
            responseLength = 0,
            resourceSummary = "",
            error = "CoAP request timed out after ${timeoutMs}ms"
        )

    /**
     * Redacts common secret-bearing query parameters from a payload.
     */
    fun redactSecrets(input: String): String {
        var result = input
        SECRET_PATTERNS.forEach { pattern ->
            result = result.replace(Regex("$pattern[^\\s,>&;]*", RegexOption.IGNORE_CASE), "$pattern***")
        }
        return result
    }

    /**
     * Formats a link-format payload as a comma-separated resource list.
     *
     * If the payload cannot be parsed as link-format, the raw (redacted) payload
     * is returned and [onWarning] is invoked.
     */
    fun formatResourceSummary(
        body: String,
        maxLength: Int,
        onWarning: (String) -> Unit = {}
    ): String {
        if (body.isBlank()) return ""
        val resources = parseLinkFormat(body)
        val summary = if (resources.isEmpty()) {
            onWarning("Malformed link-format response, showing raw payload")
            body.trim()
        } else {
            resources.joinToString(", ")
        }
        return if (summary.length > maxLength) summary.take(maxLength - 3) + "..." else summary
    }

    /**
     * Parses a CoRE link-format payload and returns the resource paths.
     */
    fun parseLinkFormat(body: String): List<String> =
        LINK_FORMAT_REGEX.findAll(body).map { it.groupValues[1] }.toList()

    /**
     * Runs a list of cleanup actions, swallowing any exceptions so that one
     * failed cleanup step does not prevent the rest from running.
     *
     * @param actions Cleanup actions to execute in order.
     * @param onError Callback invoked for each failed action.
     * @return true if all actions succeeded, false if any threw.
     */
    fun runCleanupActions(
        actions: List<() -> Unit>,
        onError: (String, Throwable) -> Unit = { _, _ -> }
    ): Boolean {
        var allSucceeded = true
        actions.forEachIndexed { index, action ->
            try {
                action()
            } catch (e: Exception) {
                allSucceeded = false
                onError("Cleanup action #$index failed", e)
            }
        }
        return allSucceeded
    }
}
