package bluebit.fitbit.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop tests for the DTLS bootstrap probe state machine.
 *
 * The Android-only [DtlsBootstrapProbe] is not exercised here; instead these
 * tests cover the extracted [DtlsProbeStateMachine] logic that the probe uses.
 */
class DtlsBootstrapProbeTest {

    @Test
    fun `stage enum has expected values and ordering`() {
        val entries = ProbeStage.entries
        assertEquals(9, entries.size)
        assertEquals(ProbeStage.NOT_STARTED, entries[0])
        assertEquals(ProbeStage.BLE_DISCOVERY, entries[1])
        assertEquals(ProbeStage.GATTLINK_SERVICE_CHECK, entries[2])
        assertEquals(ProbeStage.BLE_CONNECTION, entries[3])
        assertEquals(ProbeStage.GATTLINK_SETUP, entries[4])
        assertEquals(ProbeStage.DTLS_HANDSHAKE, entries[5])
        assertEquals(ProbeStage.SESSION_ESTABLISHED, entries[6])
        assertEquals(ProbeStage.CLEANUP, entries[7])
        assertEquals(ProbeStage.FAILED, entries[8])
    }

    @Test
    fun `state machine follows expected forward transitions`() {
        val machine = DtlsProbeStateMachine()
        assertEquals(ProbeStage.NOT_STARTED, machine.stage)

        assertTrue(machine.transitionTo(ProbeStage.BLE_CONNECTION))
        assertTrue(machine.transitionTo(ProbeStage.BLE_DISCOVERY))
        assertTrue(machine.transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK))
        assertTrue(machine.transitionTo(ProbeStage.GATTLINK_SETUP))
        assertTrue(machine.transitionTo(ProbeStage.DTLS_HANDSHAKE))
        assertTrue(machine.transitionTo(ProbeStage.SESSION_ESTABLISHED))
        assertTrue(machine.transitionTo(ProbeStage.CLEANUP))

        assertFalse(machine.transitionTo(ProbeStage.FAILED))
        assertFalse(machine.transitionTo(ProbeStage.DTLS_HANDSHAKE))
    }

    @Test
    fun `state machine rejects invalid transitions from not started`() {
        val machine = DtlsProbeStateMachine()
        assertFalse(machine.transitionTo(ProbeStage.SESSION_ESTABLISHED))
        assertFalse(machine.transitionTo(ProbeStage.CLEANUP))
        assertFalse(machine.transitionTo(ProbeStage.GATTLINK_SETUP))
        assertFalse(machine.transitionTo(ProbeStage.DTLS_HANDSHAKE))
    }

    @Test
    fun `state machine rejects backward transitions`() {
        val machine = machineAtDtlsHandshake()

        assertFalse(machine.transitionTo(ProbeStage.GATTLINK_SETUP))
        assertFalse(machine.transitionTo(ProbeStage.BLE_CONNECTION))
        assertFalse(machine.transitionTo(ProbeStage.NOT_STARTED))
    }

    @Test
    fun `failed state can only transition to cleanup`() {
        val machine = DtlsProbeStateMachine()
        machine.transitionTo(ProbeStage.BLE_CONNECTION)
        machine.fail("connection timeout")
        assertEquals(ProbeStage.FAILED, machine.stage)

        assertFalse(machine.transitionTo(ProbeStage.BLE_DISCOVERY))
        assertTrue(machine.transitionTo(ProbeStage.CLEANUP))
    }

    @Test
    fun `DTLS state 2 marks session established`() {
        val machine = machineAtDtlsHandshake()

        val interpretation = machine.onDtlsStatusChange(2, 0)

        assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, interpretation)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
        assertEquals(2, machine.dtlsState)
        assertEquals(0, machine.dtlsCode)
    }

    @Test
    fun `DTLS state 3 marks error`() {
        val machine = machineAtDtlsHandshake()

        val interpretation = machine.onDtlsStatusChange(3, 42)

        assertEquals(DtlsStatusInterpretation.ERROR, interpretation)
        assertEquals(ProbeStage.FAILED, machine.stage)
        assertEquals("DTLS error state=3 code=42", machine.error)
        assertEquals(3, machine.dtlsState)
        assertEquals(42, machine.dtlsCode)
    }

    @Test
    fun `DTLS unknown state remains pending`() {
        val machine = machineAtDtlsHandshake()

        val interpretation = machine.onDtlsStatusChange(1, 0)

        assertEquals(DtlsStatusInterpretation.PENDING, interpretation)
        assertEquals(ProbeStage.DTLS_HANDSHAKE, machine.stage)
        assertEquals(1, machine.dtlsState)
    }

    @Test
    fun `success result reflects session state and counters`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(2, 0)
        machine.recordTx()
        machine.recordTx()
        machine.recordRx()

        val result = machine.success()

        assertTrue(result.success)
        assertEquals(ProbeStage.SESSION_ESTABLISHED, result.stage)
        assertEquals(2, result.dtlsState)
        assertEquals(0, result.dtlsCode)
        assertEquals(2, result.txCount)
        assertEquals(1, result.rxCount)
        assertEquals(null, result.error)
    }

    @Test
    fun `fail result captures error and preserves counters`() {
        val machine = DtlsProbeStateMachine()
        machine.transitionTo(ProbeStage.BLE_CONNECTION)
        machine.recordRx()

        val result = machine.fail("Connection timeout")

        assertFalse(result.success)
        assertEquals(ProbeStage.FAILED, result.stage)
        assertEquals("Connection timeout", result.error)
        assertEquals(0, result.txCount)
        assertEquals(1, result.rxCount)
    }

    @Test
    fun `cleanup result preserves success when session established`() {
        val machine = machineAtDtlsHandshake()
        machine.onDtlsStatusChange(2, 0)

        val result = machine.cleanupResult()

        assertTrue(result.success)
        assertEquals(ProbeStage.CLEANUP, result.stage)
        assertEquals(2, result.dtlsState)
    }

    @Test
    fun `cleanup result is unsuccessful when session not established`() {
        val machine = DtlsProbeStateMachine()
        machine.transitionTo(ProbeStage.DTLS_HANDSHAKE)
        machine.fail("handshake timeout")

        val result = machine.cleanupResult()

        assertFalse(result.success)
        assertEquals(ProbeStage.CLEANUP, result.stage)
        assertEquals("handshake timeout", result.error)
    }

    @Test
    fun `handshake timeout is 20 seconds`() {
        assertEquals(20_000L, DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS)
    }

    @Test
    fun `DTLS session and error constants match expected values`() {
        assertEquals(2, DtlsProbeStateMachine.DTLS_STATE_SESSION)
        assertEquals(3, DtlsProbeStateMachine.DTLS_STATE_ERROR)
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
