package bluebit.fitbit.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused tests for the handling of the GoldenGate DTLS "unknown identity" error
 * (-10601, `GG_ERROR_TLS_UNKNOWN_IDENTITY`) and for the native reset/retry
 * sequence that follows it.
 *
 * Evidence for the transient classification:
 *  - `BlueBitApp/dtls_error_minus_10601_investigation.md` section 2: -10601 is
 *    `GG_ERROR_TLS_UNKNOWN_IDENTITY`, reported when the peer offered a PSK
 *    identity the hub-side resolver chain could not resolve to a key.
 *  - Same document, section 6: in server mode `GG_DtlsProtocol_AdvanceHandshake`
 *    calls `GG_DtlsProtocol_Reset` after an error, which emits
 *    `GG_TLS_STATE_INIT` and re-enters `GG_TLS_STATE_HANDSHAKE`. The reference
 *    app therefore ignores `TLS_STATE_ERROR` ("retries build in XP library").
 *  - `prototype_analysis/captured-logs/phase16/phase16_live_logcat.txt`
 *    (2026-10-19 16:33): the live sequence was
 *    `state=1 -> state=3(-10601) -> state=0 -> state=1 -> state=0 -> state=1 -> state=2`.
 *
 * These tests only cover the Kotlin state machine; they do not assert anything
 * about the tracker's authorization policy.
 */
class DtlsProbeStateMachineTransientErrorTest {

    @Test
    fun `unknown identity error is transient and leaves the probe pending`() {
        val machine = machineAtDtlsHandshake()

        val interpretation =
            machine.onDtlsStatusChange(3, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)

        assertEquals(DtlsStatusInterpretation.PENDING, interpretation)
        assertEquals(ProbeStage.DTLS_HANDSHAKE, machine.stage)
        assertEquals(1, machine.transientErrorCount)
        assertEquals(3, machine.dtlsState)
        assertEquals(-10601, machine.dtlsCode)
    }

    @Test
    fun `the unknown identity constant matches the native error code`() {
        assertEquals(-10601, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)
    }

    @Test
    fun `the session state after a transient error is processed successfully`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(3, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)

        val interpretation = machine.onDtlsStatusChange(2, 0)

        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, interpretation)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
        assertNull(machine.error)

        val result = machine.success()
        assertTrue(result.success)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, result.stage)
        assertEquals(1, result.transientErrorCount)
    }

    @Test
    fun `the observed native reset and bootstrap retry sequence reaches a session`() {
        val machine = machineAtDtlsHandshake()

        // Exact state/code sequence observed in the Phase 16 live logcat.
        val observed = listOf(
            1 to 0,
            3 to DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY,
            0 to 0,
            1 to 0,
            0 to 0,
            1 to 0,
            2 to 0
        )
        val interpretations = observed.map { (state, code) ->
            machine.onDtlsStatusChange(state, code)
        }

        assertEquals(DtlsStatusInterpretation.PENDING, interpretations[1])
        assertEquals(DtlsStatusInterpretation.PENDING, interpretations[2])
        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, interpretations.last())
        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
        assertEquals(1, machine.transientErrorCount)
        assertNull(machine.error)
    }

    @Test
    fun `other DTLS errors remain terminal`() {
        val terminalCodes = listOf(-10600, -10602, -10603, -10604, -1000, 42)

        for (code in terminalCodes) {
            val machine = machineAtDtlsHandshake()

            val interpretation = machine.onDtlsStatusChange(3, code)

            assertEquals(DtlsStatusInterpretation.ERROR, interpretation, "code=$code")
            assertEquals(ProbeStage.FAILED, machine.stage, "code=$code")
            assertEquals("DTLS error state=3 code=$code", machine.error, "code=$code")
            assertEquals(0, machine.transientErrorCount, "code=$code")
        }
    }

    @Test
    fun `a terminal error is not resurrected by a later session notification`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(3, -10600)

        val interpretation = machine.onDtlsStatusChange(2, 0)

        assertEquals(DtlsStatusInterpretation.ERROR, interpretation)
        assertEquals(ProbeStage.FAILED, machine.stage)
        assertEquals("DTLS error state=3 code=-10600", machine.error)
    }

    @Test
    fun `the first terminal error stays authoritative`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(3, -10600)

        val interpretation = machine.onDtlsStatusChange(3, -10602)

        assertEquals(DtlsStatusInterpretation.ERROR, interpretation)
        assertEquals(ProbeStage.FAILED, machine.stage)
        assertEquals("DTLS error state=3 code=-10600", machine.error)
    }

    @Test
    fun `a transient error does not mask a later terminal error`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(3, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)

        val interpretation = machine.onDtlsStatusChange(3, -10600)

        assertEquals(DtlsStatusInterpretation.ERROR, interpretation)
        assertEquals(ProbeStage.FAILED, machine.stage)
        assertEquals("DTLS error state=3 code=-10600", machine.error)
        assertEquals(1, machine.transientErrorCount)
    }

    @Test
    fun `repeated transient errors do not loop or change the stage`() {
        val machine = machineAtDtlsHandshake()

        repeat(50) {
            val interpretation =
                machine.onDtlsStatusChange(3, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)
            assertEquals(DtlsStatusInterpretation.PENDING, interpretation)
            assertEquals(ProbeStage.DTLS_HANDSHAKE, machine.stage)
        }

        assertEquals(50, machine.transientErrorCount)
        assertEquals(ProbeStage.DTLS_HANDSHAKE, machine.stage)
    }

    @Test
    fun `a transient error after the session is up does not tear the session down`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(2, 0)

        val interpretation =
            machine.onDtlsStatusChange(3, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)

        assertEquals(DtlsStatusInterpretation.PENDING, interpretation)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
    }

    @Test
    fun `a duplicate session notification is idempotent`() {
        val machine = machineAtDtlsHandshake()

        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, machine.onDtlsStatusChange(2, 0))
        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, machine.onDtlsStatusChange(2, 0))

        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
        assertNull(machine.error)
    }

    @Test
    fun `the handshake transition table is unchanged`() {
        val machine = DtlsProbeStateMachine()

        assertTrue(machine.transitionTo(ProbeStage.BLE_CONNECTION))
        assertTrue(machine.transitionTo(ProbeStage.BLE_DISCOVERY))
        assertTrue(machine.transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK))
        assertTrue(machine.transitionTo(ProbeStage.GATTLINK_SETUP))
        assertTrue(machine.transitionTo(ProbeStage.DTLS_HANDSHAKE))
        assertTrue(machine.transitionTo(ProbeStage.SESSION_ESTABLISHED))
        assertTrue(machine.transitionTo(ProbeStage.CLEANUP))
    }

    private fun machineAtDtlsHandshake(): DtlsProbeStateMachine {
        return DtlsProbeStateMachine().apply {
            transitionTo(ProbeStage.BLE_CONNECTION)
            transitionTo(ProbeStage.BLE_DISCOVERY)
            transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK)
            transitionTo(ProbeStage.GATTLINK_SETUP)
            transitionTo(ProbeStage.DTLS_HANDSHAKE)
        }
    }
}
