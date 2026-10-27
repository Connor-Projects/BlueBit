package bluebit.fitbit.diagnostics

/**
 * Testable state machine for the DTLS bootstrap probe.
 *
 * Tracks probe stage, DTLS status, byte counters, and result construction.
 * Lives in commonMain so desktop tests can exercise it without Android APIs.
 */
enum class ProbeStage {
    NOT_STARTED,
    BLE_DISCOVERY,
    GATTLINK_SERVICE_CHECK,
    BLE_CONNECTION,
    GATTLINK_SETUP,
    DTLS_HANDSHAKE,
    SESSION_ESTABLISHED,
    CLEANUP,
    FAILED
}

data class ProbeResult(
    val stage: ProbeStage,
    val success: Boolean,
    val error: String?,
    val dtlsState: Int?,
    val dtlsCode: Int?,
    val txCount: Int,
    val rxCount: Int,
    /**
     * Number of recoverable ("transient") DTLS errors observed before the result
     * was produced. A transient error does not end the probe; it records that the
     * native stack had to reset and retry the handshake.
     */
    val transientErrorCount: Int = 0
)

enum class DtlsStatusInterpretation {
    SESSION_ESTABLISHED,
    ERROR,
    PENDING
}

class DtlsProbeStateMachine {

    companion object {
        const val DTLS_STATE_SESSION = 2
        const val DTLS_STATE_ERROR = 3

        /**
         * `GG_ERROR_TLS_UNKNOWN_IDENTITY`, i.e. `GG_ERROR_BASE_TLS - 1` where
         * `GG_ERROR_BASE_TLS` is `-10600`.
         *
         * The native stack reports it when the peer offered a PSK identity that the
         * hub-side resolver chain could not resolve to a key. In server mode
         * GoldenGate resets the DTLS session to `GG_TLS_STATE_INIT` and re-handshakes
         * by itself, so the condition is recoverable and must not end the probe.
         *
         * Source: `BlueBitApp/dtls_error_minus_10601_investigation.md` sections 2 and 6.
         */
        const val DTLS_ERROR_UNKNOWN_IDENTITY = -10601

        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L
    }

    private var _stage: ProbeStage = ProbeStage.NOT_STARTED
    val stage: ProbeStage get() = _stage

    private var _txCount: Int = 0
    val txCount: Int get() = _txCount

    private var _rxCount: Int = 0
    val rxCount: Int get() = _rxCount

    private var _dtlsState: Int? = null
    val dtlsState: Int? get() = _dtlsState

    private var _dtlsCode: Int? = null
    val dtlsCode: Int? get() = _dtlsCode

    private var _error: String? = null
    val error: String? get() = _error

    /**
     * Number of recoverable ("transient") DTLS errors observed. A transient error
     * does not change the probe stage; it only records that the native stack had
     * to reset the DTLS session and retry the handshake.
     */
    private var _transientErrorCount: Int = 0
    val transientErrorCount: Int get() = _transientErrorCount

    private val allowedTransitions: Map<ProbeStage, Set<ProbeStage>> = mapOf(
        ProbeStage.NOT_STARTED to setOf(ProbeStage.BLE_CONNECTION, ProbeStage.FAILED),
        ProbeStage.BLE_CONNECTION to setOf(ProbeStage.BLE_DISCOVERY, ProbeStage.FAILED),
        ProbeStage.BLE_DISCOVERY to setOf(ProbeStage.GATTLINK_SERVICE_CHECK, ProbeStage.FAILED),
        ProbeStage.GATTLINK_SERVICE_CHECK to setOf(ProbeStage.GATTLINK_SETUP, ProbeStage.FAILED),
        ProbeStage.GATTLINK_SETUP to setOf(ProbeStage.DTLS_HANDSHAKE, ProbeStage.FAILED),
        ProbeStage.DTLS_HANDSHAKE to setOf(ProbeStage.SESSION_ESTABLISHED, ProbeStage.FAILED),
        ProbeStage.SESSION_ESTABLISHED to setOf(ProbeStage.CLEANUP, ProbeStage.FAILED),
        ProbeStage.FAILED to setOf(ProbeStage.CLEANUP),
        ProbeStage.CLEANUP to emptySet()
    )

    /**
     * Attempt to move to [stage]. Returns true if the transition is allowed.
     */
    fun transitionTo(stage: ProbeStage): Boolean {
        if (allowedTransitions[_stage]?.contains(stage) != true) {
            return false
        }
        _stage = stage
        return true
    }

    fun recordTx() {
        _txCount++
    }

    fun recordRx() {
        _rxCount++
    }

    /**
     * Interprets a DTLS status notification from the native stack.
     *
     * State 2 (TLS_STATE_SESSION) means the bootstrap session is up.
     *
     * Error [DTLS_ERROR_UNKNOWN_IDENTITY] (-10601) is transient: the native stack
     * resets the DTLS session to INIT and re-handshakes by itself, so the probe
     * must keep waiting instead of failing. Every other error stays terminal.
     *
     * The state machine never starts a retry itself - the native stack owns the
     * reset/retry cycle - so repeated transient notifications cannot spin here.
     * The total wait is bounded by [DEFAULT_HANDSHAKE_TIMEOUT_MS] in the callers.
     */
    fun onDtlsStatusChange(state: Int, code: Int): DtlsStatusInterpretation {
        _dtlsState = state
        _dtlsCode = code
        return when (state) {
            DTLS_STATE_SESSION -> {
                if (_stage == ProbeStage.SESSION_ESTABLISHED) {
                    // Duplicate notification for a session that is already up.
                    DtlsStatusInterpretation.SESSION_ESTABLISHED
                } else if (transitionTo(ProbeStage.SESSION_ESTABLISHED)) {
                    _error = null
                    DtlsStatusInterpretation.SESSION_ESTABLISHED
                } else {
                    // The session arrived after the probe had already failed, e.g.
                    // after a terminal DTLS error. A terminal failure must not be
                    // resurrected by a late status notification.
                    DtlsStatusInterpretation.ERROR
                }
            }
            DTLS_STATE_ERROR -> {
                if (code == DTLS_ERROR_UNKNOWN_IDENTITY && _stage != ProbeStage.FAILED) {
                    // The native stack may reset and retry with BOOTSTRAP.
                    _error = "DTLS transient error state=$state code=$code"
                    _transientErrorCount++
                    DtlsStatusInterpretation.PENDING
                } else if (_stage == ProbeStage.FAILED) {
                    // Already failed: keep the first terminal error authoritative.
                    DtlsStatusInterpretation.ERROR
                } else {
                    fail("DTLS error state=$state code=$code")
                    DtlsStatusInterpretation.ERROR
                }
            }
            else -> DtlsStatusInterpretation.PENDING
        }
    }

    /**
     * Mark the probe as failed and return a [ProbeResult] snapshot.
     */
    fun fail(error: String): ProbeResult {
        _error = error
        _stage = ProbeStage.FAILED
        return ProbeResult(
            stage = ProbeStage.FAILED,
            success = false,
            error = error,
            dtlsState = _dtlsState,
            dtlsCode = _dtlsCode,
            txCount = _txCount,
            rxCount = _rxCount,
            transientErrorCount = _transientErrorCount
        )
    }

    /**
     * Return a successful result. Caller must already have reached SESSION_ESTABLISHED.
     */
    fun success(): ProbeResult {
        return ProbeResult(
            stage = ProbeStage.SESSION_ESTABLISHED,
            success = true,
            error = null,
            dtlsState = _dtlsState,
            dtlsCode = _dtlsCode,
            txCount = _txCount,
            rxCount = _rxCount,
            transientErrorCount = _transientErrorCount
        )
    }

    /**
     * Return a cleanup result that preserves whether the session was established.
     */
    fun cleanupResult(): ProbeResult {
        return ProbeResult(
            stage = ProbeStage.CLEANUP,
            success = _stage == ProbeStage.SESSION_ESTABLISHED,
            error = _error,
            dtlsState = _dtlsState,
            dtlsCode = _dtlsCode,
            txCount = _txCount,
            rxCount = _rxCount,
            transientErrorCount = _transientErrorCount
        )
    }
}
