package bluebit.fitbit.phase18

import bluebit.fitbit.diagnostics.DtlsProbeStateMachine
import bluebit.fitbit.diagnostics.DtlsStatusInterpretation
import bluebit.fitbit.diagnostics.ProbeStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 18 - replay of REAL recorded DTLS status sequences through the REAL
 * production state machine.
 *
 * Unlike the Phase 17 JVM tests (two of which are synthetic mirrors), every test
 * here drives [DtlsProbeStateMachine] from `commonMain` - the same class the
 * Android probe uses - with `(state, code)` pairs extracted from existing
 * logcat captures. Nothing here contacts a device, the native stack, or the
 * tracker.
 *
 * Evidence input : `prototype_analysis/phase18/evidence/recorded_dtls_sequences.tsv`
 * Provenance     : the TSV records a SHA-256 for each source log; this test
 *                  recomputes it, so the replay cannot silently drift from the
 *                  captures it claims to represent.
 *
 * What this DOES prove : given the native status sequence that was actually
 *   observed, the Kotlin state machine classifies it correctly and reports
 *   success with the right transient-error count.
 * What this does NOT prove : that the native stack still emits that sequence on
 *   a future run, or that the Android probe wiring is intact on a live device.
 *   That distinction is the point of Phase 18 and is recorded in the report.
 */
class Phase18RecordedSequenceReplayTest {

    private data class Session(
        val id: String,
        val source: String,
        val sourceSha256: String,
        val first: String,
        val last: String,
        val seq: List<Pair<Int, Int>>
    ) {
        val transientErrors: Int get() = seq.count { (s, c) -> s == DtlsProbeStateMachine.DTLS_STATE_ERROR && c == DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY }
    }

    private fun evidenceDir(): File {
        val path = System.getProperty("phase18.evidence.dir")
            ?: fail<Nothing>(
                "System property 'phase18.evidence.dir' is not set. Run via " +
                    "phase18-desktopTest.init.gradle; this test refuses to fall back to " +
                    "hard-coded data because that would hide a missing evidence file."
            )
        val dir = File(path)
        assertTrue(dir.isDirectory, "evidence dir does not exist: $dir")
        return dir
    }

    private fun loadSessions(): List<Session> {
        val tsv = File(evidenceDir(), "recorded_dtls_sequences.tsv")
        assertTrue(tsv.isFile, "missing evidence file: $tsv")
        val rows = tsv.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(rows.isNotEmpty(), "evidence file has no data rows: $tsv")
        return rows.map { line ->
            val f = line.split('\t')
            assertEquals(6, f.size, "malformed evidence row (expected 6 tab-separated fields): $line")
            val seq = f[5].split(',').map { pair ->
                val sc = pair.split(':')
                assertEquals(2, sc.size, "malformed state:code token '$pair' in row: $line")
                sc[0].trim().toInt() to sc[1].trim().toInt()
            }
            Session(f[0], f[1], f[2], f[3], f[4], seq)
        }
    }

    private fun repoRoot(): File = evidenceDir().parentFile.parentFile.parentFile

    /**
     * Reproduces the stage progression that `DtlsBootstrapProbe.runProbe` performs
     * before `startStack(...)`, so `onDtlsStatusChange` sees the machine in the
     * same stage it would see on a device.
     */
    private fun machineAwaitingDtlsHandshake(): DtlsProbeStateMachine {
        val m = DtlsProbeStateMachine()
        assertTrue(m.transitionTo(ProbeStage.BLE_CONNECTION), "caller step: NOT_STARTED -> BLE_CONNECTION")
        assertTrue(m.transitionTo(ProbeStage.BLE_DISCOVERY), "caller step: BLE_CONNECTION -> BLE_DISCOVERY")
        assertTrue(m.transitionTo(ProbeStage.GATTLINK_SERVICE_CHECK), "caller step: BLE_DISCOVERY -> GATTLINK_SERVICE_CHECK")
        assertTrue(m.transitionTo(ProbeStage.GATTLINK_SETUP), "caller step: GATTLINK_SERVICE_CHECK -> GATTLINK_SETUP")
        assertTrue(m.transitionTo(ProbeStage.DTLS_HANDSHAKE), "caller step: GATTLINK_SETUP -> DTLS_HANDSHAKE")
        return m
    }

    private fun replay(seq: List<Pair<Int, Int>>): Pair<DtlsProbeStateMachine, List<DtlsStatusInterpretation>> {
        val machine = machineAwaitingDtlsHandshake()
        val interpretation = seq.map { (state, code) -> machine.onDtlsStatusChange(state, code) }
        println("[phase18] replay ${seq.joinToString(",") { "${it.first}:${it.second}" }} -> ${interpretation.joinToString(",")}")
        return machine to interpretation
    }

    @Test
    fun `evidence file exposes the three recorded sessions`() {
        val sessions = loadSessions()
        assertEquals(listOf("S01", "S02", "S03"), sessions.map { it.id })
        assertEquals(listOf(7, 4, 9), sessions.map { it.seq.size }, "recorded notification counts per session")
        assertEquals(listOf(1, 0, 1), sessions.map { it.transientErrors }, "-10601 count per session")
    }

    @Test
    fun `recorded source hashes still match the captures`() {
        val root = repoRoot()
        val bySource = loadSessions().groupBy { it.source }
        bySource.forEach { (relative, sessions) ->
            val expected = sessions.map { it.sourceSha256 }.distinct()
            assertEquals(1, expected.size, "conflicting hashes recorded for $relative")
            val file = File(root, relative.replace('/', File.separatorChar))
            assertTrue(file.isFile, "source log missing: ${file.absolutePath}")
            val md = MessageDigest.getInstance("SHA-256")
            val actual = file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }.let { md.digest().joinToString("") { b -> "%02x".format(b) } }
            assertEquals(expected.single(), actual, "SHA-256 drift for $relative")
            println("[phase18] provenance OK: $relative sha256=$actual")
        }
    }

    @Test
    fun `every recorded session ends in the established-session state`() {
        loadSessions().forEach { s ->
            assertEquals(DtlsProbeStateMachine.DTLS_STATE_SESSION, s.seq.last().first, "${s.id} final DTLS state")
            assertEquals(0, s.seq.last().second, "${s.id} final DTLS code")
        }
    }

    @Test
    fun `real recorded recovery sequences reach an established session after unknown identity`() {
        val recovered = loadSessions().filter { it.transientErrors > 0 }
        assertEquals(listOf("S01", "S03"), recovered.map { it.id }, "sessions that contain -10601")

        recovered.forEach { s ->
            val (machine, interpretations) = replay(s.seq)

            assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage, "${s.id} final stage")
            assertEquals(1, machine.transientErrorCount, "${s.id} transient error count")
            assertNull(machine.error, "${s.id} error must be cleared once the session is established")
            assertEquals(DtlsProbeStateMachine.DTLS_STATE_SESSION, machine.dtlsState, "${s.id} dtlsState")

            // The -10601 notification itself must be classified PENDING, not ERROR:
            // an ERROR classification would have completed the probe's deferred with `false`.
            val errorIndex = s.seq.indexOfFirst { (st, c) ->
                st == DtlsProbeStateMachine.DTLS_STATE_ERROR && c == DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY
            }
            assertTrue(errorIndex >= 0, "${s.id} has no -10601")
            assertEquals(DtlsStatusInterpretation.PENDING, interpretations[errorIndex], "${s.id} -10601 interpretation")

            // Exactly one SESSION_ESTABLISHED interpretation, and it is the last one.
            assertEquals(1, interpretations.count { it == DtlsStatusInterpretation.SESSION_ESTABLISHED }, "${s.id} established count")
            assertEquals(DtlsStatusInterpretation.SESSION_ESTABLISHED, interpretations.last(), "${s.id} final interpretation")
            assertEquals(0, interpretations.count { it == DtlsStatusInterpretation.ERROR }, "${s.id} must produce no ERROR interpretation")

            // Mirror the caller: it calls success() once the session deferred completed true.
            val result = machine.success()
            assertTrue(result.success, "${s.id} probe result success")
            assertNull(result.error, "${s.id} probe result error")
            assertEquals(1, result.transientErrorCount, "${s.id} probe result transient count")
        }
    }

    @Test
    fun `control sequence without unknown identity establishes with zero transient errors`() {
        val control = loadSessions().single { it.transientErrors == 0 }
        val (machine, interpretations) = replay(control.seq)

        assertEquals(ProbeStage.SESSION_ESTABLISHED, machine.stage)
        assertEquals(0, machine.transientErrorCount)
        assertNull(machine.error)
        assertEquals(0, interpretations.count { it == DtlsStatusInterpretation.ERROR })
        assertTrue(machine.success().success)
    }

    @Test
    fun `unknown identity alone does not establish a session if the native stack never retries`() {
        // Truncate S01 immediately after the -10601 notification. This is the
        // "recovery failed" branch: no native reset/retry followed.
        val s01 = loadSessions().single { it.id == "S01" }
        val cutoff = s01.seq.indexOfFirst { (st, c) ->
            st == DtlsProbeStateMachine.DTLS_STATE_ERROR && c == DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY
        }
        assertTrue(cutoff >= 0, "S01 must contain -10601")
        val truncated = s01.seq.subList(0, cutoff + 1)

        val (machine, interpretations) = replay(truncated)

        assertEquals(DtlsStatusInterpretation.PENDING, interpretations.last(), "truncated -10601 must stay PENDING")
        assertEquals(ProbeStage.DTLS_HANDSHAKE, machine.stage, "no session without a native retry")
        assertEquals(1, machine.transientErrorCount)
        assertFalse(machine.stage == ProbeStage.SESSION_ESTABLISHED)

        // The caller's 20 s handshake timeout then fails the probe. Success must not be fabricated.
        val timedOut = machine.fail("DTLS handshake timed out after ${DtlsProbeStateMachine.DEFAULT_HANDSHAKE_TIMEOUT_MS}ms")
        assertFalse(timedOut.success, "timeout after -10601 must not report success")
        assertNotNull(timedOut.error)
        assertEquals(ProbeStage.FAILED, timedOut.stage)
        assertEquals(1, timedOut.transientErrorCount, "timeout still reports the observed transient error")
    }

    @Test
    fun `late session notification cannot resurrect a timed-out probe`() {
        // Regression guard for the Phase 17 task-2 bug: state=2 arriving after
        // fail() must not produce SESSION_ESTABLISHED / success=true.
        val s01 = loadSessions().single { it.id == "S01" }
        val machine = machineAwaitingDtlsHandshake()
        machine.onDtlsStatusChange(DtlsProbeStateMachine.DTLS_STATE_ERROR, DtlsProbeStateMachine.DTLS_ERROR_UNKNOWN_IDENTITY)
        val failed = machine.fail("DTLS handshake timed out")
        assertFalse(failed.success)

        val late = machine.onDtlsStatusChange(DtlsProbeStateMachine.DTLS_STATE_SESSION, 0)
        assertEquals(DtlsStatusInterpretation.ERROR, late, "late state=2 after failure must be rejected")
        assertEquals(ProbeStage.FAILED, machine.stage)
        assertFalse(machine.cleanupResult().success, "cleanup result must keep the failed outcome")
        assertEquals(1, machine.transientErrorCount, "failure still reports the observed transient error")
        assertEquals(s01.seq.last().first, DtlsProbeStateMachine.DTLS_STATE_SESSION)
    }
}
