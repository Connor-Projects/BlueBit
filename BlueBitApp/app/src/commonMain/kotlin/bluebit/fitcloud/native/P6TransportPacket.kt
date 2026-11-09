package bluebit.fitcloud.native

/**
 * FitCloud `Fc#TransportPacket` framing (decompiled class `U1.e`).
 *
 * Frame layout (all multibyte fields big-endian), [V] `U1/e.java:59–183`:
 * ```
 * [0]     magic 0xAB
 * [1]     flags: 0x10 ack frame, 0x20 ack failure, 0x40 encrypted payload
 * [2..3]  payload length (BE16)
 * [4..5]  CRC-16 over the PLAINTEXT payload (BE16), 0 when payload length is 0
 * [6..7]  sequence number (BE16)
 * [8..]   payload (AES-protected when the encrypted flag is set)
 * ```
 *
 * ACK frames are header-only (8 bytes, length/CRC zero). Bit 0x20 set marks the
 * acked transmission as failed (the receiver could not verify it); 0x20 clear on
 * an ACK means acceptance. Evidence: `i9/b.java:268–337` (valid packets are acked
 * with flags 0x10 via `a(false, sn)`, corrupt frames with 0x30 via `a(true, sn)`)
 * and `p363zd/e.java:330–336` (an incoming ACK with 0x20 set keeps the sender
 * retransmitting).
 */
object P6TransportPacket {

    const val MAGIC = 0xAB
    const val HEADER_SIZE = 8
    const val FLAG_ACK = 0x10
    const val FLAG_ACK_FAILED = 0x20
    const val FLAG_ENCRYPTED = 0x40

    /** Reassembly ceiling for inbound frames (`U1/e.java:112`). */
    const val MAX_REASSEMBLY_LENGTH = 1024

    /** Auth request keyIds that are always transmitted plaintext (`U1/e.java:76–78`). */
    private val ALWAYS_PLAINTEXT_KEY_IDS = intArrayOf(0x11, 0x13)

    /** Parsed transport ACK (`p349yd.b`). */
    data class Ack(val failed: Boolean, val sequenceId: Int)

    /**
     * Recognise a header-only ACK frame (`U1.e.a`, static): 8 bytes, magic 0xAB,
     * the ACK flag set, and length/CRC fields all zero.
     */
    fun decodeAck(frame: ByteArray): Ack? {
        if (frame.size != HEADER_SIZE) return null
        if ((frame[0].toInt() and 0xFF) != MAGIC) return null
        val flags = frame[1].toInt() and 0xFF
        if (flags and FLAG_ACK == 0) return null
        for (index in 2..5) {
            if (frame[index].toInt() != 0) return null
        }
        val sequenceId = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
        return Ack(failed = flags and FLAG_ACK_FAILED != 0, sequenceId = sequenceId)
    }

    /** Build an 8-byte ACK answering [sequenceId]; [failed] sets the failure bit. */
    fun buildAck(sequenceId: Int, failed: Boolean): ByteArray {
        val flags = FLAG_ACK or (if (failed) FLAG_ACK_FAILED else 0)
        val frame = ByteArray(HEADER_SIZE)
        frame[0] = MAGIC.toByte()
        frame[1] = flags.toByte()
        frame[6] = ((sequenceId shr 8) and 0xFF).toByte()
        frame[7] = (sequenceId and 0xFF).toByte()
        return frame
    }

    /**
     * Build one complete data frame around [payload] (mirror of `U1.e.b` for
     * `z10=false`):
     * - CRC is computed over the plaintext payload bytes before any encryption,
     * - [encrypted] sets the encrypted flag; auth packets targeting keyId 0x11/0x13
     *   (payload[0] == 3, payload[2] in {0x11, 0x13}) are force-decrypted to plain,
     *   exactly as the reference clears its encrypted flag for them.
     */
    fun encode(payload: ByteArray, sequenceId: Int, encrypted: Boolean): ByteArray {
        var encrypted = encrypted
        if (payload.size > 2 && payload[0].toInt() == 3) {
            val keyId = payload[2].toInt() and 0xFF
            if (keyId in ALWAYS_PLAINTEXT_KEY_IDS) encrypted = false
        }
        val crc = P6Crc16.compute(payload)
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = MAGIC.toByte()
        frame[1] = (if (encrypted) FLAG_ENCRYPTED else 0).toByte()
        frame[2] = ((payload.size shr 8) and 0xFF).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        frame[4] = ((crc shr 8) and 0xFF).toByte()
        frame[5] = (crc and 0xFF).toByte()
        frame[6] = ((sequenceId shr 8) and 0xFF).toByte()
        frame[7] = (sequenceId and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)
        return frame
    }

    /**
     * Encrypt [plaintext] with the session cipher and frame it, using the reference's
     * encrypted-path chunk boundaries when the payload would be split
     * (`U1/e.java:85–105`: first chunk `limit - 8`, later chunks `limit`).
     *
     * The CRC field is always computed over the PLAINTEXT bytes (§2.2: `U1/e.java`
     * numbers and CRCs the payload before `z10=true` bulk encryption, and RX
     * verifies the CRC after decryption), so the frame header is built here
     * instead of routing encrypted bytes back through [encode], which CRCs what
     * it is given. The single-chunk path applies one cyclic keystream over the
     * whole payload, which round-trips against [P6TransportDecoder] exactly.
     */
    fun encodeEncrypted(
        plaintext: ByteArray,
        sequenceId: Int,
        cipher: P6SessionCipher,
        maxWriteBytes: Int,
    ): ByteArray {
        val crc = P6Crc16.compute(plaintext)
        val frame = ByteArray(HEADER_SIZE + plaintext.size)
        frame[0] = MAGIC.toByte()
        frame[1] = FLAG_ENCRYPTED.toByte()
        frame[2] = ((plaintext.size shr 8) and 0xFF).toByte()
        frame[3] = (plaintext.size and 0xFF).toByte()
        frame[4] = ((crc shr 8) and 0xFF).toByte()
        frame[5] = (crc and 0xFF).toByte()
        frame[6] = ((sequenceId shr 8) and 0xFF).toByte()
        frame[7] = (sequenceId and 0xFF).toByte()

        val firstChunk = maxWriteBytes - HEADER_SIZE
        if (firstChunk <= 0 || plaintext.size <= firstChunk) {
            System.arraycopy(cipher.crypt(plaintext, sequenceId), 0, frame, HEADER_SIZE, plaintext.size)
            return frame
        }
        // Reference chunked TX quirk (spec §9): each chunk is encrypted
        // independently with the same sequence id, so the keystream restarts at
        // chunk boundaries (`U1/e.java:85–105`) while RX decrypts the whole
        // payload in one call. Oversized encrypted frames therefore do NOT
        // round-trip here; auth/config frames fit one chunk.
        var offset = 0
        var firstChunkSent = false
        while (offset < plaintext.size) {
            val size = minOf(if (firstChunkSent) maxWriteBytes else firstChunk, plaintext.size - offset)
            val chunk = cipher.crypt(plaintext, offset, size, sequenceId)
            System.arraycopy(chunk, 0, frame, HEADER_SIZE + offset, size)
            offset += size
            firstChunkSent = true
        }
        return frame
    }

    /** Frame bytes have the transport magic and are not ACK frames. */
    fun looksLikeDataHeader(frame: ByteArray): Boolean =
        frame.size >= HEADER_SIZE &&
            (frame[0].toInt() and 0xFF) == MAGIC &&
            (frame[1].toInt() and 0xFF) and FLAG_ACK == 0

    /** Split a frame into ATT-write sized pieces ([maxWriteBytes] per write). */
    fun splitForWrite(frame: ByteArray, maxWriteBytes: Int): List<ByteArray> {
        require(maxWriteBytes > 0)
        if (frame.size <= maxWriteBytes) return listOf(frame)
        val pieces = ArrayList<ByteArray>((frame.size + maxWriteBytes - 1) / maxWriteBytes)
        var offset = 0
        while (offset < frame.size) {
            val size = minOf(maxWriteBytes, frame.size - offset)
            pieces.add(frame.copyOfRange(offset, offset + size))
            offset += size
        }
        return pieces
    }
}

/**
 * Stateful inbound parser for the notification characteristic - mirror of the
 * `U1.e` reassembler (`e()`/`c()`/`d()`).
 */
class P6TransportDecoder {

    /** A useful result of feeding one notification chunk into [feed]. */
    sealed interface Event {
        /** Chunk was consumed; more bytes needed before anything can be decided. */
        data object Pending : Event

        /** The chunk was a header-only ACK. */
        data class AckEvent(val ack: P6TransportPacket.Ack) : Event

        /**
         * A full, CRC-valid frame was received (payload decrypted when needed).
         * [encrypted] is the frame's own 0x40 flag — the connector mirrors it into
         * [P6SessionState.inboundEncryptedMirror] for its own TX encoding.
         */
        data class Complete(
            val payload: ByteArray,
            val sequenceId: Int,
            val encrypted: Boolean,
        ) : Event

        /** Corrupt frame (CRC mismatch, overflow, or bad structure). */
        data class Invalid(val sequenceId: Int, val reason: String) : Event
    }

    private var expectedLength = 0
    private var declaredCrc = 0
    private var declaredSequence = 0
    private var encrypted = false
    private var accumulated = ByteArray(0)

    /** Number of payload bytes still expected by a partially received frame, or 0. */
    val missing: Int
        get() = if (expectedLength == 0) 0 else expectedLength - accumulated.size

    /** True while a multi-notification frame is being reassembled. */
    val reassembling: Boolean
        get() = expectedLength > 0

    /** Forget any partial frame state (reference resets between send attempts). */
    fun reset() {
        expectedLength = 0
        accumulated = ByteArray(0)
    }

    /**
     * Feed one notification payload. Decryption of an encrypted payload requires the
     * matching [cipher]; when [cipher] is null the payload bytes are not available
     * to the stack yet and the frame is reported as encrypted-incomplete by
     * [missing] > 0 semantics, then completed only with a cipher supplied.
     */
    fun feed(chunk: ByteArray, cipher: P6SessionCipher?): Event {
        if (!reassembling) {
            if (chunk.size < P6TransportPacket.HEADER_SIZE) {
                return Event.Invalid(sequenceId = -1, reason = "frame shorter than header")
            }
            if (chunk.first().toInt() and 0xFF != P6TransportPacket.MAGIC) {
                return Event.Invalid(sequenceId = -1, reason = "bad magic")
            }
            val flags = chunk[1].toInt() and 0xFF
            if (flags and P6TransportPacket.FLAG_ACK != 0) {
                val ack = P6TransportPacket.decodeAck(chunk)
                if (ack == null) {
                    return Event.Invalid(-1, "malformed ack frame")
                }
                return Event.AckEvent(ack)
            }
            expectedLength = ((chunk[2].toInt() shl 8) or (chunk[3].toInt() and 0xFF)) and 0xFFFF
            declaredCrc = ((chunk[4].toInt() shl 8) or (chunk[5].toInt() and 0xFF)) and 0xFFFF
            declaredSequence = ((chunk[6].toInt() shl 8) or (chunk[7].toInt() and 0xFF)) and 0xFFFF
            encrypted = flags and P6TransportPacket.FLAG_ENCRYPTED != 0
            accumulated = ByteArray(0)
            if (expectedLength == 0) {
                // Defensive (not reference-verified): ACK frames are filtered above,
                // so a zero-length DATA frame has nothing to deliver; reject it
                // instead of wedging the reassembler (and sending) forever.
                val sequenceId = declaredSequence
                reset()
                return Event.Invalid(sequenceId, "zero length data frame")
            }
            if (chunk.size > P6TransportPacket.HEADER_SIZE) {
                val payloadPortion = chunk.copyOfRange(P6TransportPacket.HEADER_SIZE, chunk.size)
                return accumulate(payloadPortion, cipher)
            }
            return Event.Pending
        }
        return accumulate(chunk, cipher)
    }

    private fun accumulate(portion: ByteArray, cipher: P6SessionCipher?): Event {
        if (accumulated.size + portion.size > expectedLength ||
            expectedLength > P6TransportPacket.MAX_REASSEMBLY_LENGTH
        ) {
            val sequenceId = declaredSequence
            reset()
            return Event.Invalid(sequenceId, "payload overflow")
        }
        accumulated += portion
        if (accumulated.size < expectedLength) {
            return Event.Pending
        }
        // Decrypt FIRST (reference decrypts the reassembled frame in one call),
        // then verify the CRC over the PLAINTEXT bytes — the declared CRC field
        // is always computed over plaintext on both sides (§2.2, `U1/e.java`).
        val decrypted: ByteArray = if (encrypted) {
            if (cipher == null) {
                val result = Event.Invalid(declaredSequence, "encrypted payload without session key")
                reset()
                return result
            }
            cipher.crypt(accumulated, declaredSequence)
        } else {
            accumulated
        }
        val receivedCrc = P6Crc16.compute(decrypted)
        reset()
        return if (receivedCrc == declaredCrc) {
            Event.Complete(decrypted, declaredSequence, encrypted)
        } else {
            Event.Invalid(declaredSequence, "crc mismatch")
        }
    }
}
