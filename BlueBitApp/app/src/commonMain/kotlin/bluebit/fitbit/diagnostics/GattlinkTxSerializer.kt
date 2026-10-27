package bluebit.fitbit.diagnostics

/**
 * FIFO serializer for the gattlink TX write path (Phase 14).
 *
 * Android's BLE GATT client permits exactly one outstanding request per client:
 * a second `writeCharacteristic()` issued while a previous WRITE_TYPE_DEFAULT
 * write is still awaiting its completion callback is rejected by the framework
 * and never reaches the air. Phase 12 captured exactly that failure — the
 * rejected `8100000808` gattlink frame starved the tracker's DTLS handshake and
 * the link supervision-timed out. This class enforces one-at-a-time submission:
 * frames are enqueued FIFO and the next frame is submitted only from [complete],
 * after the previous write's callback has been processed.
 *
 * The serializer also provides:
 *  - per-write identity ([PendingWrite]) from submission through completion;
 *  - explicit propagation of platform submission refusals and non-success
 *    completion statuses (nothing masquerades as delivered);
 *  - timeout detection for a write whose completion callback never arrives;
 *  - loud, enumerable drops of every abandoned frame on failure or close.
 *
 * Threading: all methods are plain state transitions with no internal locking —
 * the owner must serialize every call (on Android all access runs under a single
 * lock covering enqueue, submission, completion callbacks, timeout, and close).
 *
 * @param submitWrite hands one frame to the platform; must return 0 when the
 *   frame was accepted for transmission, or a non-zero status when the platform
 *   refused it. Called from [enqueue] and [complete] only, never concurrently.
 * @param nowMs injected clock (submission timestamps) so behavior is testable.
 */
class GattlinkTxSerializer(
    private val submitWrite: (seq: Int, data: ByteArray) -> Int,
    private val nowMs: () -> Long,
) {

    /** A gattlink frame tracked from submission through its completion callback. */
    class PendingWrite(val seq: Int, val data: ByteArray, var submittedAtMs: Long) {
        val hex: String get() = data.joinToString("") { "%02X".format(it) }
    }

    /** Result of [enqueue]. */
    sealed class EnqueueResult {
        /** Frame accepted: submitted now (idle transport) or queued behind the in-flight write. */
        data class Accepted(val seq: Int) : EnqueueResult()

        /**
         * The platform refused this write at submission time (e.g. another GATT
         * request was unexpectedly outstanding). The transport is now failed and
         * [dropped] — frames queued behind it — were discarded with it.
         */
        data class Rejected(val seq: Int, val status: Int, val dropped: List<PendingWrite>) : EnqueueResult()

        /** The transport had already failed; the frame was NOT accepted. */
        object NotAccepted : EnqueueResult()
    }

    /** Result of [complete]. */
    sealed class CompletionResult {
        /**
         * The in-flight write completed successfully; [nextSubmitted] is the next
         * queued frame if one was handed to the platform as a result.
         */
        data class Completed(val write: PendingWrite, val nextSubmitted: PendingWrite?) : CompletionResult()

        /** No write was in flight — a late or unsolicited callback. Nothing advanced. */
        object LateCallback : CompletionResult()

        /**
         * The write completed with a non-success status. The transport is now
         * failed and [dropped] — all remaining queued frames — were discarded.
         */
        data class Failed(val write: PendingWrite, val status: Int, val dropped: List<PendingWrite>) : CompletionResult()

        /**
         * The write completed successfully, but the platform refused the next
         * queued frame at submission. The transport is now failed and [dropped]
         * (the remaining queue after the refused frame) were discarded.
         */
        data class NextRejected(
            val write: PendingWrite,
            val rejectedSeq: Int,
            val status: Int,
            val dropped: List<PendingWrite>,
        ) : CompletionResult()
    }

    /** Non-null result of [timeout]: the write was still in flight when it expired. */
    data class TimedOut(val write: PendingWrite, val dropped: List<PendingWrite>)

    var isFailed: Boolean = false
        private set

    var failureReason: String? = null
        private set

    val queueDepth: Int get() = queue.size

    val inFlight: PendingWrite? get() = inFlightWrite

    /** Frames accepted but not yet completed: queued plus in-flight. */
    val outstanding: Int get() = queue.size + (if (inFlightWrite != null) 1 else 0)

    private val queue = ArrayDeque<PendingWrite>()
    private var inFlightWrite: PendingWrite? = null
    private var nextSeq = 0

    /**
     * Enqueue a frame (defensively copied — the platform may reuse the buffer
     * once the caller acknowledges it). Submits immediately when idle.
     */
    fun enqueue(data: ByteArray): EnqueueResult {
        if (isFailed) return EnqueueResult.NotAccepted
        nextSeq++
        val write = PendingWrite(nextSeq, data.copyOf(), nowMs())
        queue.addLast(write)
        val failure = submitHeadIfIdle()
        return if (failure == null) {
            EnqueueResult.Accepted(write.seq)
        } else {
            EnqueueResult.Rejected(write.seq, failure.status, failure.dropped)
        }
    }

    /** Process the platform completion callback for the in-flight write. */
    fun complete(status: Int): CompletionResult {
        val write = inFlightWrite ?: return CompletionResult.LateCallback
        inFlightWrite = null
        if (status != 0) {
            val dropped = drainQueue()
            fail("write completion status=$status (seq=${write.seq})")
            return CompletionResult.Failed(write, status, dropped)
        }
        val failure = submitHeadIfIdle()
        return if (failure == null) {
            CompletionResult.Completed(write, inFlightWrite)
        } else {
            CompletionResult.NextRejected(write, failure.rejected.seq, failure.status, failure.dropped)
        }
    }

    /**
     * Called when the completion callback for [seq] did not arrive in time.
     * Returns the timed-out write (with everything queued behind it) when it was
     * still in flight, or null when that write has since completed (stale timer).
     */
    fun timeout(seq: Int): TimedOut? {
        val write = inFlightWrite ?: return null
        if (write.seq != seq) return null
        inFlightWrite = null
        val dropped = drainQueue()
        fail("completion timeout (seq=$seq, submitted at ${write.submittedAtMs})")
        return TimedOut(write, dropped)
    }

    /**
     * Fail everything and return all abandoned frames — the in-flight write
     * first, then the queue in order. Used when the connection closes or the
     * experiment is torn down. Marks the transport failed; [reset] for a new run.
     */
    fun abortAll(reason: String): List<PendingWrite> {
        val aborted = ArrayList<PendingWrite>()
        inFlightWrite?.let { aborted.add(it) }
        inFlightWrite = null
        aborted.addAll(drainQueue())
        fail(reason)
        return aborted
    }

    /** Clear all state so the serializer can serve a new run. */
    fun reset() {
        queue.clear()
        inFlightWrite = null
        isFailed = false
        failureReason = null
    }

    private class SubmissionFailure(val rejected: PendingWrite, val status: Int, val dropped: List<PendingWrite>)

    /**
     * Submit the queue head when the transport is idle. Any platform refusal
     * fails the transport immediately and reports every discarded frame —
     * frames are never dropped silently.
     */
    private fun submitHeadIfIdle(): SubmissionFailure? {
        if (inFlightWrite != null) return null
        val head = queue.firstOrNull() ?: return null
        queue.removeFirst()
        inFlightWrite = head
        // Submission timestamp = the moment the frame is handed to the platform,
        // so completion latency measures the write round-trip, not queue wait.
        head.submittedAtMs = nowMs()
        val status = submitWrite(head.seq, head.data)
        if (status != 0) {
            inFlightWrite = null
            val dropped = drainQueue()
            fail("platform refused write submission (seq=${head.seq}, status=$status)")
            return SubmissionFailure(head, status, dropped)
        }
        return null
    }

    private fun drainQueue(): List<PendingWrite> {
        val drained = queue.toList()
        queue.clear()
        return drained
    }

    private fun fail(reason: String) {
        isFailed = true
        failureReason = reason
    }
}
