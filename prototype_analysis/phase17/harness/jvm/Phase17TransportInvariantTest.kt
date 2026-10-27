package bluebit.fitbit.phase17

import bluebit.fitbit.diagnostics.GattlinkTxSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 17 / T7 (L2) — transport integrity: one outstanding write at a time,
 * byte-identical payloads, and no silent success on refusal/failure.
 *
 * Exercises the real `commonMain` class [GattlinkTxSerializer] (the Phase 14
 * fix must stay intact). This complements the existing
 * `GattlinkTxSerializerTest` with the Phase 17 invariants that matter for the
 * transport-vs-application distinction: a `4.01` proves the transport *worked*.
 */
class Phase17TransportInvariantTest {

    private class Fake {
        val attempts = mutableListOf<Pair<Int, ByteArray>>()
        val accepted = mutableListOf<Pair<Int, ByteArray>>()
        var status = 0

        val serializer = GattlinkTxSerializer(
            submitWrite = { seq, data ->
                attempts.add(seq to data)
                if (status == 0) accepted.add(seq to data)
                status
            },
            nowMs = { 0L },
        )
    }

    @Test
    fun `only one write is outstanding at a time`() {
        val f = Fake()
        assertTrue(f.serializer.enqueue(byteArrayOf(1)) is GattlinkTxSerializer.EnqueueResult.Accepted)
        assertTrue(f.serializer.enqueue(byteArrayOf(2)) is GattlinkTxSerializer.EnqueueResult.Accepted)
        assertTrue(f.serializer.enqueue(byteArrayOf(3)) is GattlinkTxSerializer.EnqueueResult.Accepted)
        assertEquals(1, f.accepted.size)
        assertEquals(2, f.serializer.queueDepth)

        assertTrue(f.serializer.complete(0) is GattlinkTxSerializer.CompletionResult.Completed)
        assertEquals(2, f.accepted.size)
        assertTrue(f.serializer.complete(0) is GattlinkTxSerializer.CompletionResult.Completed)
        assertEquals(3, f.accepted.size)
    }

    @Test
    fun `order and payload bytes are preserved`() {
        val f = Fake()
        f.serializer.enqueue(byteArrayOf(0xA1.toByte()))
        f.serializer.enqueue(byteArrayOf(0xA2.toByte()))
        f.serializer.complete(0)
        assertContentEquals(byteArrayOf(0xA1.toByte()), f.accepted[0].second)
        assertContentEquals(byteArrayOf(0xA2.toByte()), f.accepted[1].second)
    }

    @Test
    fun `a refused submission fails the transport and drops the queue`() {
        val f = Fake()
        f.status = 5
        val result = f.serializer.enqueue(byteArrayOf(1))
        assertTrue(result is GattlinkTxSerializer.EnqueueResult.Rejected)
        assertTrue(f.serializer.isFailed)
        assertEquals(0, f.accepted.size)
    }

    @Test
    fun `a non-success completion fails the transport`() {
        val f = Fake()
        f.serializer.enqueue(byteArrayOf(1))
        f.serializer.enqueue(byteArrayOf(2))
        val result = f.serializer.complete(6)
        assertTrue(result is GattlinkTxSerializer.CompletionResult.Failed)
        assertTrue(f.serializer.isFailed)
    }

    @Test
    fun `the payload is copied defensively`() {
        val f = Fake()
        val buffer = byteArrayOf(1, 2, 3)
        f.serializer.enqueue(buffer)
        buffer[0] = 99
        val pending = assertNotNull(f.serializer.inFlight)
        assertContentEquals(byteArrayOf(1, 2, 3), pending.data)
    }

    @Test
    fun `a late completion is not mistaken for progress`() {
        val f = Fake()
        assertTrue(f.serializer.complete(0) is GattlinkTxSerializer.CompletionResult.LateCallback)
    }
}
