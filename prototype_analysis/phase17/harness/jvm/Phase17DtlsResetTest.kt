package bluebit.fitbit.phase17

import bluebit.fitbit.diagnostics.DtlsProbeStateMachine
import bluebit.fitbit.diagnostics.DtlsStatusInterpretation
import bluebit.fitbit.diagnostics.ProbeStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 17 / T1 (L2) — the DTLS reset/recovery shape:
 * `state=1 -> 3(-10601) -> 0 -> 1 -> 2` recovers to a session, and the
 * unknown-identity error (`-10601`, `GG_ERROR_TLS_UNKNOWN_IDENTITY`) is treated
 * as *transient* rather than fatal.
 *
 * Exercises the real `commonMain` class [DtlsProbeStateMachine] (no Android APIs).
 */
class Phase17DtlsResetTest {

    private fun atHandshake(): DtlsProbeStateMachine {
        val sm = DtlsProbeStateMachine()
        assertEquals(true, sm.transitionTo(ProbeStage.BLE_CONNECTION))
        assertEquals(true, sm.transitionTo(ProbeStage.BLE_DISCOVERY))
        assertEquals(true, sm.transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK))
        assertEquals(true, sm.transitionTo(ProbeStage.GATTLINK_SETUP))
        assertEquals(true, sm.transitionTo(ProbeStage.DTLS_HANDSHAKE))
        return sm
    }

    @Test
    fun `unknown identity is transient, not fatal`() {
        val sm = atHandshake()
        assertEquals(DtlsStatusInterpretation.PENDING, sm.onDtlsStatusChange(3, -10601))
        // the probe did not fail; it is expected to retry with BOOTSTRAP
        assertEquals(ProbeStage.DTLS_HANDSHAKE, sm.stage)
        assertNotNull(sm.error)
    }

    @Test
    fun `reset sequence 1 to 3 to 0 to 1 to 2 recovers to a session`() {
        val sm = atHandshake()
        assertEquals(DtlsStatusInterpretation.PENDING, sm.onDtlsStatusChange(1, 0))
        assertEquals(DtlsStatusInterpretation.PENDING, sm.onDtlsStatusChange(3, -10601))
        assertEquals(DtlsStatusInterpretation.PENDING, sm.onDtlsStatusChange(0, 0))
        assertEquals(DtlsStatusInterpretation.PENDING, sm.onDtlsStatusChange(1, 0))
        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, sm.onDtlsStatusChange(2, 0))
        assertEquals(ProbeStage.SESSION_ESTABLISHED, sm.stage)
        assertEquals(2, sm.dtlsState)
    }

    @Test
    fun `a non-transient error is fatal`() {
        val sm = atHandshake()
        assertEquals(DtlsStatusInterpretation.ERROR, sm.onDtlsStatusChange(3, -1000))
        assertEquals(ProbeStage.FAILED, sm.stage)
    }

    @Test
    fun `session constants match the native stack`() {
        assertEquals(2, DtlsProbeStateMachine.DTLS_STATE_SESSION)
        assertEquals(3, DtlsProbeStateMachine.DTLS_STATE_ERROR)
    }

    @Test
    fun `success is only reported after a session`() {
        val sm = atHandshake()
        sm.onDtlsStatusChange(2, 0)
        val result = sm.success()
        assertEquals(true, result.success)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, result.stage)
    }
}
