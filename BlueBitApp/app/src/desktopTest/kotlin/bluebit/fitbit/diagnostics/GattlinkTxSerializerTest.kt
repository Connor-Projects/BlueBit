package bluebit.fitbit.diagnostics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 14 tests for [GattlinkTxSerializer] — the gattlink TX write serializer
 * that enforces one-at-a-time submission on Android's single-outstanding-request
 * GATT client.
 *
 * Covers the pre-registered validation points:
 *  - the next write is not submitted until the preceding write completes;
 *  - a rejected submission is reported as a failure, not a success;
 *  - a failed completion does not masquerade as success;
 *  - queued writes are handled when the connection closes;
 *  - a missing callback cannot silently leave the transport marked successful;
 *  - frame ordering and payload bytes remain unchanged.
 */
class GattlinkTxSerializerTest {

    private class Harness {
        val submitted = mutableListOf<Pair<Int, ByteArray>>()
        var nextSubmitStatus = 0
        var clockMs = 1_000L

        val serializer = GattlinkTxSerializer(
            submitWrite = { seq, data ->
                submitted.add(seq to data)
                nextSubmitStatus
            },
            nowMs = { clockMs }
        )
    }

    @Test
    fun `next write is submitted only after the preceding write completes`() {
        val h = Harness()
        val a = byteArrayOf(0x80.toByte())
        val b = byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x08, 0x08)

        assertTrue(h.serializer.enqueue(a) is GattlinkTxSerializer.EnqueueResult.Accepted)
        // First frame submitted immediately on an idle transport.
        assertEquals(1, h.submitted.size)
        assertEquals(1, h.serializer.inFlight?.seq)
        assertEquals(0, h.serializer.queueDepth)

        // Second frame must NOT reach the platform while the first is in flight.
        assertTrue(h.serializer.enqueue(b) is GattlinkTxSerializer.EnqueueResult.Accepted)
        assertEquals(1, h.submitted.size, "second write must not be submitted while first is outstanding")
        assertEquals(1, h.serializer.queueDepth)
        assertEquals(2, h.serializer.outstanding)

        val completed = h.serializer.complete(0)
        assertTrue(completed is GattlinkTxSerializer.CompletionResult.Completed)
        assertEquals(2, h.submitted.size, "second write must be submitted from the completion callback")
        assertEquals(2, h.serializer.inFlight?.seq, "second write is now in flight")
        assertEquals(0, h.serializer.queueDepth)
        assertFalse(h.serializer.isFailed)
    }

    @Test
    fun `rejected submission is reported as failure`() {
        val h = Harness()
        h.nextSubmitStatus = 133 // framework refusal, as captured in Phase 12
        val result = h.serializer.enqueue(byteArrayOf(0x55))

        assertTrue(result is GattlinkTxSerializer.EnqueueResult.Rejected)
        result as GattlinkTxSerializer.EnqueueResult.Rejected
        assertEquals(1, result.seq)
        assertEquals(133, result.status)
        assertTrue(h.serializer.isFailed)
        assertEquals("platform refused write submission (seq=1, status=133)", h.serializer.failureReason)
        assertEquals(0, h.serializer.outstanding)

        // Further frames are refused outright, not silently queued.
        assertTrue(h.serializer.enqueue(byteArrayOf(0x66)) is GattlinkTxSerializer.EnqueueResult.NotAccepted)
        assertEquals(1, h.submitted.size, "no frame is ever handed to the platform after a refusal")
    }

    @Test
    fun `submission rejection of a queued frame reports every remaining dropped frame`() {
        val h = Harness()
        h.serializer.enqueue(byteArrayOf(0x01)) // in flight
        h.serializer.enqueue(byteArrayOf(0x02)) // queued
        h.serializer.enqueue(byteArrayOf(0x03)) // queued
        h.submitted.clear()

        // The in-flight write completes; the platform then refuses frame 2 at
        // submission, which must also discard frame 3 and fail the transport.
        h.nextSubmitStatus = 133
        val result = h.serializer.complete(0)

        assertTrue(result is GattlinkTxSerializer.CompletionResult.NextRejected)
        result as GattlinkTxSerializer.CompletionResult.NextRejected
        assertEquals(1, result.write.seq)
        assertEquals(2, result.rejectedSeq)
        assertEquals(133, result.status)
        assertEquals(1, h.submitted.size)
        assertTrue(h.serializer.isFailed)
        assertTrue(h.serializer.failureReason!!.contains("status=133"))
        // Only frame 3 was queued behind the refused frame 2.
        assertEquals(1, result.dropped.size)
        assertEquals(3, result.dropped[0].seq)
        assertContentEquals(byteArrayOf(0x03), result.dropped[0].data)
        assertEquals(0, h.serializer.outstanding)
    }

    @Test
    fun `failed completion does not masquerade as success`() {
        val h = Harness()
        h.serializer.enqueue(byteArrayOf(0x80.toByte()))
        h.serializer.enqueue(byteArrayOf(0x81.toByte()))

        val result = h.serializer.complete(133)

        assertTrue(result is GattlinkTxSerializer.CompletionResult.Failed)
        result as GattlinkTxSerializer.CompletionResult.Failed
        assertEquals(133, result.status)
        assertEquals(1, result.write.seq)
        // The queued frame was discarded with the failure and is enumerable.
        assertEquals(1, result.dropped.size)
        assertEquals(2, result.dropped[0].seq)
        assertTrue(h.serializer.isFailed)
        assertEquals("write completion status=133 (seq=1)", h.serializer.failureReason)
        assertEquals(0, h.serializer.outstanding)
        assertNull(h.serializer.inFlight)
        // No further frame is ever submitted.
        assertTrue(h.serializer.enqueue(byteArrayOf(0x04)) is GattlinkTxSerializer.EnqueueResult.NotAccepted)
        assertEquals(1, h.submitted.size)
    }

    @Test
    fun `abortAll returns in-flight and queued writes and fails the transport`() {
        val h = Harness()
        h.serializer.enqueue(byteArrayOf(0x01))
        h.serializer.enqueue(byteArrayOf(0x02))
        h.serializer.enqueue(byteArrayOf(0x03))
        h.submitted.clear()

        val aborted = h.serializer.abortAll("connection closed")

        assertEquals(listOf(1, 2, 3), aborted.map { it.seq })
        assertContentEquals(byteArrayOf(0x01), aborted[0].data)
        assertTrue(h.serializer.isFailed)
        assertEquals("connection closed", h.serializer.failureReason)
        assertEquals(0, h.serializer.outstanding)
        assertEquals(0, h.submitted.size, "no frame may be submitted after abort")
        // A second abort finds nothing — nothing is ever dropped silently.
        assertTrue(h.serializer.abortAll("again").isEmpty())
    }

    @Test
    fun `missing callback times out and a late callback cannot advance the queue`() {
        val h = Harness()
        h.serializer.enqueue(byteArrayOf(0x80.toByte()))
        h.serializer.enqueue(byteArrayOf(0x81.toByte()))
        h.submitted.clear()

        // Completion callback for seq 1 never arrives; the timer fires for seq 1.
        h.clockMs += 5_000
        val timedOut = assertNotNull(h.serializer.timeout(1))
        assertEquals(1, timedOut.write.seq)
        // The queued frame 2 is reported as dropped, not silently lost.
        assertEquals(listOf(2), timedOut.dropped.map { it.seq })
        assertTrue(h.serializer.isFailed)
        assertEquals(0, h.serializer.outstanding)
        assertEquals(0, h.submitted.size, "queued frame must not be submitted after timeout")

        // A stale timer for an already-completed write is a no-op.
        h.serializer.reset()
        h.serializer.enqueue(byteArrayOf(0x10))
        h.serializer.complete(0)
        assertNull(h.serializer.timeout(1), "stale timer for completed write must be ignored")
        assertFalse(h.serializer.isFailed)

        // A late callback arriving when nothing is in flight never advances the queue.
        val late = h.serializer.complete(0)
        assertTrue(late is GattlinkTxSerializer.CompletionResult.LateCallback)
        assertEquals(1, h.submitted.size, "late callback must not trigger any submission")
        assertFalse(h.serializer.isFailed)
    }

    @Test
    fun `frame ordering and payload bytes are preserved through the queue`() {
        val h = Harness()
        val frames = List(5) { i -> ByteArray(4) { j -> ((i * 4 + j) and 0xFF).toByte() } }

        // Fill the queue: frame 1 goes to the platform, frames 2-5 wait behind it.
        frames.forEachIndexed { i, frame ->
            assertTrue(h.serializer.enqueue(frame) is GattlinkTxSerializer.EnqueueResult.Accepted)
            assertEquals(1, h.submitted.size, "only frame 1 may reach the platform before any completion (i=$i)")
        }
        assertEquals(4, h.serializer.queueDepth)

        // Drain: each completion submits exactly the next queued frame.
        repeat(4) { step ->
            val completion = h.serializer.complete(0)
            assertTrue(completion is GattlinkTxSerializer.CompletionResult.Completed)
            assertEquals(step + 2, h.submitted.size, "completion $step must submit exactly one next frame")
        }
        val last = h.serializer.complete(0)
        assertTrue(last is GattlinkTxSerializer.CompletionResult.Completed)
        assertNull((last as GattlinkTxSerializer.CompletionResult.Completed).nextSubmitted)

        // Platform saw exactly the original frames, in order, byte-for-byte.
        assertEquals(5, h.submitted.size)
        frames.forEachIndexed { i, frame ->
            assertEquals(i + 1, h.submitted[i].first, "submission order mismatch at $i")
            assertContentEquals(frame, h.submitted[i].second, "payload mismatch at $i")
        }
        assertFalse(h.serializer.isFailed)
        assertEquals(0, h.serializer.outstanding)
        assertNull(h.serializer.inFlight)
    }

    @Test
    fun `payload is defensively copied at enqueue`() {
        val h = Harness()
        val frame = byteArrayOf(0x80.toByte(), 0x01)
        h.serializer.enqueue(frame)
        frame[1] = 0x7F // caller mutates its buffer after enqueue (native buffer reuse)

        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), h.serializer.inFlight!!.data)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), h.submitted[0].second)
    }

    @Test
    fun `reset clears failure state for a new run`() {
        val h = Harness()
        h.serializer.enqueue(byteArrayOf(0x01))
        h.serializer.abortAll("cleanup")
        assertTrue(h.serializer.isFailed)

        h.serializer.reset()

        assertFalse(h.serializer.isFailed)
        assertNull(h.serializer.failureReason)
        assertEquals(0, h.serializer.outstanding)
        val result = h.serializer.enqueue(byteArrayOf(0x80.toByte()))
        assertTrue(result is GattlinkTxSerializer.EnqueueResult.Accepted)
        assertEquals(2, h.submitted.size) // aborted run's frame + post-reset frame
    }

    @Test
    fun `submission timestamp reflects actual platform submission not enqueue time`() {
        val h = Harness()
        h.clockMs = 42_000L
        h.serializer.enqueue(byteArrayOf(0x01)) // submitted immediately
        assertEquals(42_000L, h.serializer.inFlight?.submittedAtMs)

        // Frame 2 waits in the queue for 90 ms before its completion-driven submission.
        h.clockMs = 42_090L
        h.serializer.enqueue(byteArrayOf(0x02))
        assertEquals(1, h.serializer.queueDepth)

        h.clockMs = 42_095L
        val done = h.serializer.complete(0)
        assertTrue(done is GattlinkTxSerializer.CompletionResult.Completed)
        done as GattlinkTxSerializer.CompletionResult.Completed
        assertEquals(42_000L, done.write.submittedAtMs, "frame 1 submitted at 42000")
        assertEquals(42_095L, done.nextSubmitted?.submittedAtMs, "frame 2 stamped at actual submission (42095), not enqueue (42090)")
    }
}
