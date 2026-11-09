package bluebit.fitcloud.native

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-GATT-connection protocol state, mirroring the fields of `p363zd.e`
 * (`p363zd/e.java`):
 * - `f54934o` = outgoing sequence counter starting at 1 (one SN per logical packet),
 * - `f54930j` = sequence id of the last delivered inbound data packet (duplicate filter),
 * - `f54928h` = "currently awaiting an ACK for our last send",
 * - `U1.e.f12360g` mirror = encrypted flag of the last seen inbound frame, reused as
 *   the encryption flag of the next outgoing frame (`U1/e.java:79,167`).
 */
class P6SessionState {

    /** Next outbound transport sequence id; first data packet of a session is SN 1. */
    var nextTxSequenceId: Int = 1
        private set

    /** Sequence id of the last delivered inbound data frame, or -1 before the first. */
    var lastDeliveredSequenceId: Int = -1
        private set

    /** Sequence id whose ACK the sender is waiting for, or null when idle. */
    var pendingAckSequenceId: Int? = null
        private set

    /** The pending send's ACK arrived with the failure bit clear (accepted). */
    var lastSendAccepted: Boolean = false
        private set

    /** The pending send's ACK arrived with the failure bit set (NAK -> retransmit). */
    var lastSendNacked: Boolean = false
        private set

    /**
     * Encrypted flag mirror (reference `U1.e.f12360g`): set from the last inbound
     * frame's 0x40 bit and consulted when encoding outgoing frames.
     */
    var inboundEncryptedMirror: Boolean = false

    /** Inbound frame reassembler (reference `U1.e.e()/c()`). */
    val decoder = P6TransportDecoder()

    private var ackDeferred: CompletableDeferred<Boolean>? = null

    /** Claim the sequence id for a new logical outbound packet. */
    fun claimTxSequenceId(): Int = nextTxSequenceId++

    /**
     * Arm the pending-send state for [sequenceId]. Retries of the same logical packet
     * reuse the SN, so this is called once per ATTEMPT: each attempt gets a fresh
     * ack latch (`p363zd/e.java` retry loop semantics).
     */
    fun beginPendingSend(sequenceId: Int) {
        pendingAckSequenceId = sequenceId
        lastSendAccepted = false
        lastSendNacked = false
        ackDeferred = CompletableDeferred()
    }

    /** Clear the pending-send state once the retry loop completes. */
    fun endPendingSend() {
        pendingAckSequenceId = null
        ackDeferred = null
    }

    /**
     * Suspend until the armed ACK resolves: true = accepted (0x20 clear),
     * false = NAK. Null when nothing is pending for the async gap or the
     * [deadlineMs] elapses without a matching ACK.
     */
    suspend fun awaitPendingAck(deadlineMs: Long): Boolean? {
        val deferred = ackDeferred ?: return null
        return withTimeoutOrNull(deadlineMs) { deferred.await() }
    }

    /** Handle an inbound ACK; true when it matched the pending send. */
    fun onAck(ack: P6TransportPacket.Ack): Boolean {
        val pending = pendingAckSequenceId ?: return false
        if (ack.sequenceId != pending) return false
        if (ack.failed) lastSendNacked = true else lastSendAccepted = true
        ackDeferred?.complete(!ack.failed)
        return true
    }

    /** Register delivered data; false when the frame is a duplicate (re-ACK only). */
    fun onDataDelivered(sequenceId: Int): Boolean {
        if (sequenceId == lastDeliveredSequenceId) return false
        lastDeliveredSequenceId = sequenceId
        return true
    }

    /** Reset per-connection state (reference nulls key/IV and restarts SN at 1). */
    fun reset() {
        nextTxSequenceId = 1
        lastDeliveredSequenceId = -1
        pendingAckSequenceId = null
        ackDeferred = null
        lastSendAccepted = false
        lastSendNacked = false
        inboundEncryptedMirror = false
        decoder.reset()
    }
}
