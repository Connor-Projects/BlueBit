package bluebit.core.diagnostics

/**
 * Result returned by a CoAP read-only probe.
 *
 * Kept in the provider-neutral core module so UI screens can display it
 * without importing provider-specific diagnostic packages.
 */
data class CoapReadOnlyResult(
    val success: Boolean,
    val dtlsEstablished: Boolean,
    val coapSuccess: Boolean,
    val responseCode: String?,
    val responseLength: Int,
    val resourceSummary: String,
    val error: String?
)
