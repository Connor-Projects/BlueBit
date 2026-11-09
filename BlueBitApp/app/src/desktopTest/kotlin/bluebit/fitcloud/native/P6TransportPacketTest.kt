package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class P6TransportPacketTest {

    private val identity = P6Identity("26544037", "90:EB:99:D6:5F:99")
    private val cipher = P6KeyDerivation.derive(identity)!!

    //-- encode -------------------------------------------------------------

    @Test
    fun `login probe frame layout matches the spec arithmetic`() {
        // payload 03 00 14 00 00, crc 0x0404 (independently verified in P6Crc16Test)
        val frame = P6TransportPacket.encode(byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x00), sequenceId = 1, encrypted = false)
        assertEquals(
            listOf(
                0xAB, 0x00, 0x00, 0x05, // magic, flags, length BE16
                0x04, 0x04,             // crc BE16
                0x00, 0x01,             // SN BE16
                0x03, 0x00, 0x14, 0x00, 0x00, // payload
            ),
            frame.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `auth submit frames are forced plaintext even when requested encrypted`() {
        val payload = P6AuthPackets.submit("26544037", bind = true) // (3, 0x11, uidBlock)
        val frame = P6TransportPacket.encode(payload, sequenceId = 2, encrypted = true)
        assertEquals(0x00, frame[1].toInt(), "0x11/0x13 auth submits must clear the encrypted flag")

        val loginPayload = P6AuthPackets.submit("26544037", bind = false) // keyId 0x13
        assertEquals(0x00, P6TransportPacket.encode(loginPayload, 3, encrypted = true)[1].toInt())
    }

    @Test
    fun `non auth payload requested encrypted keeps the 0x40 flag`() {
        val frame = P6TransportPacket.encode(byteArrayOf(0x02, 0x00, 0x30, 0x00, 0x01, 0x01), 9, encrypted = true)
        assertEquals(0x40, frame[1].toInt())
    }

    @Test
    fun `encodeEncrypted single chunk equals crypt then build with 0x40`() {
        val plaintext = ByteArray(20) { it.toByte() }
        val sequenceId = 4
        val frame = P6TransportPacket.encodeEncrypted(plaintext, sequenceId, cipher, maxWriteBytes = 247)

        assertEquals(0xAB, frame[0].toInt() and 0xFF)
        assertEquals(0x40, frame[1].toInt())
        assertEquals(20, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
        assertEquals(sequenceId, ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF))
        // the encrypted payload bytes: crc computed over plaintext, body is cipher output
        assertEquals(P6Crc16.compute(plaintext), ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF))
        assertEquals(cipher.crypt(plaintext, sequenceId).toList(), frame.copyOfRange(8, 28).toList())
    }

    /**
     * Documented reference quirk (spec 9): TX restarts the keystream per chunk
     * (first chunk maxWriteBytes-8), while RX decrypts the whole payload in one
     * call. A frame whose encrypted payload exceeds the chunk boundary therefore
     * does NOT round-trip through whole-frame decrypt. The implementation keeps
     * the reference's byte-exact TX behaviour; this test pins that behaviour so
     * nobody "fixes" it silently — P6 auth/config frames fit one chunk.
     */
    @Test
    fun `chunked encrypted frames mirror the reference quirk and do not whole frame decrypt`() {
        val plaintext = ByteArray(300) { (it and 0xFF).toByte() }
        val sequenceId = 10
        val frame = P6TransportPacket.encodeEncrypted(plaintext, sequenceId, cipher, maxWriteBytes = 247)

        assertEquals(300 + 8, frame.size)
        val decoded = P6TransportDecoder().feed(frame, cipher)
        // RX applies one keystream over all 300 bytes -> CRC computed on plaintext
        // (verified over the encrypted bytes) no longer matches -> invalid.
        assertTrue(decoded is P6TransportDecoder.Event.Invalid, "quirk: not whole-frame decryptable")
    }

    //-- ack ----------------------------------------------------------------

    @Test
    fun `ack frames are built and parsed with 0x10 ok and 0x30 failure`() {
        assertEquals(
            listOf(0xAB, 0x10, 0, 0, 0, 0, 0x00, 0x01),
            P6TransportPacket.buildAck(1, failed = false).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0xAB, 0x30, 0, 0, 0, 0, 0x00, 0x02),
            P6TransportPacket.buildAck(2, failed = true).map { it.toInt() and 0xFF },
        )
        assertEquals(
            P6TransportPacket.Ack(failed = false, sequenceId = 1),
            P6TransportPacket.decodeAck(P6TransportPacket.buildAck(1, failed = false)),
        )
        assertEquals(
            P6TransportPacket.Ack(failed = true, sequenceId = 0x0C),
            P6TransportPacket.decodeAck(P6TransportPacket.buildAck(0x0C, failed = true)),
        )
    }

    @Test
    fun `decodeAck rejects non ack frames`() {
        val dataFrame = P6TransportPacket.encode(byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x00), 1, encrypted = false)
        assertNull(P6TransportPacket.decodeAck(dataFrame))
        assertNull(P6TransportPacket.decodeAck(ByteArray(8)))
        val wrongLength = byteArrayOf(0xAB.toByte(), 0x10, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01)
        assertNull(P6TransportPacket.decodeAck(wrongLength))
        val notMagic = P6TransportPacket.buildAck(1, false).also { it[0] = 0x55 }
        assertNull(P6TransportPacket.decodeAck(notMagic))
    }

    //-- splitting ------------------------------------------------------------

    @Test
    fun `splitForWrite keeps frames under the limit whole`() {
        assertEquals(1, P6TransportPacket.splitForWrite(ByteArray(5), 6).size)
        assertEquals(1, P6TransportPacket.splitForWrite(ByteArray(6), 6).size)
    }

    @Test
    fun `splitForWrite divides oversized frames into limit sized pieces`() {
        val pieces = P6TransportPacket.splitForWrite(ByteArray(13), 5)
        assertEquals(3, pieces.size)
        assertEquals(listOf(5, 5, 3), pieces.map { it.size })
        assertEquals(ByteArray(13).toList(), pieces.fold(ByteArray(0)) { a, b -> a + b }.toList())
    }

    //-- decoder --------------------------------------------------------------

    private fun frame(payload: ByteArray, sn: Int, encrypted: Boolean = false): ByteArray =
        P6TransportPacket.encode(payload, sn, encrypted)

    @Test
    fun `complete frame in one notification is delivered`() {
        val payload = byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x01, 0x00)
        val decoder = P6TransportDecoder()
        val event = decoder.feed(frame(payload, sn = 5), null)

        assertTrue(event is P6TransportDecoder.Event.Complete)
        event as P6TransportDecoder.Event.Complete
        assertEquals(payload.toList(), event.payload.toList())
        assertEquals(5, event.sequenceId)
        assertFalse(event.encrypted)
        assertFalse(decoder.reassembling)
    }

    @Test
    fun `frame split over notifications reassembles`() {
        val payload = byteArrayOf(0x02, 0x00, 0x21, 0x00, 0x05, 0x11, 0x03, 0x41, 0x42, 0x43)
        val full = frame(payload, sn = 12) // 18 bytes: 8 header + 10 payload
        val decoder = P6TransportDecoder()

        val headerChunk = decoder.feed(full.copyOfRange(0, 8), null)
        assertTrue(headerChunk is P6TransportDecoder.Event.Pending)
        assertTrue(decoder.reassembling)
        assertEquals(10, decoder.missing)

        val rest = decoder.feed(full.copyOfRange(8, full.size), null)
        assertTrue(rest is P6TransportDecoder.Event.Complete)
        rest as P6TransportDecoder.Event.Complete
        assertEquals(payload.toList(), rest.payload.toList())
        assertEquals(12, rest.sequenceId)
        assertFalse(decoder.reassembling)
    }

    @Test
    fun `crc mismatch is reported as invalid with the declared sn`() {
        val good = frame(byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x01, 0x00), 5)
        val corrupted = good.copyOf().also { it[good.size - 1] = 0x7F } // break the payload
        val event = P6TransportDecoder().feed(corrupted, null)
        assertTrue(event is P6TransportDecoder.Event.Invalid)
        event as P6TransportDecoder.Event.Invalid
        assertEquals(5, event.sequenceId)
        assertEquals("crc mismatch", event.reason)
    }

    @Test
    fun `bad magic and short chunks are invalid without an sn`() {
        assertTrue(P6TransportDecoder().feed(byteArrayOf(0x55, 0x00, 0x00, 0x00, 0, 0, 0, 0), null)
            is P6TransportDecoder.Event.Invalid)
        assertTrue(P6TransportDecoder().feed(byteArrayOf(0xAB.toByte(), 0x00), null)
            is P6TransportDecoder.Event.Invalid)
    }

    /**
     * Build a spec-faithful encrypted frame fixture: the CRC field is over the
     * PLAINTEXT (like `encodeEncrypted` and the device both produce), while the
     * body is the session-transformed bytes.
     */
    private fun encryptedFrame(plaintext: ByteArray, sn: Int): ByteArray {
        val crc = P6Crc16.compute(plaintext)
        val body = cipher.crypt(plaintext, sn)
        val wire = ByteArray(8 + body.size)
        wire[0] = 0xAB.toByte()
        wire[1] = 0x40.toByte()
        wire[2] = ((plaintext.size shr 8) and 0xFF).toByte()
        wire[3] = (plaintext.size and 0xFF).toByte()
        wire[4] = ((crc shr 8) and 0xFF).toByte()
        wire[5] = (crc and 0xFF).toByte()
        wire[6] = ((sn shr 8) and 0xFF).toByte()
        wire[7] = (sn and 0xFF).toByte()
        System.arraycopy(body, 0, wire, 8, body.size)
        return wire
    }

    @Test
    fun `encrypted frames decrypt with the session cipher and surface the flag`() {
        val plaintext = byteArrayOf(0x02, 0x00, 0x30, 0x00, 0x02, 0x01, 0x02)
        val sequenceId = 6
        val wire = encryptedFrame(plaintext, sequenceId)

        val event = P6TransportDecoder().feed(wire, cipher)
        assertTrue(event is P6TransportDecoder.Event.Complete)
        event as P6TransportDecoder.Event.Complete
        assertEquals(plaintext.toList(), event.payload.toList())
        assertTrue(event.encrypted)
    }

    @Test
    fun `encrypted frames without a session cipher are rejected`() {
        val plaintext = byteArrayOf(0x02, 0x00, 0x30, 0x00, 0x02, 0x01, 0x02)
        val sequenceId = 6
        val wire = encryptedFrame(plaintext, sequenceId)

        val event = P6TransportDecoder().feed(wire, cipher = null)
        assertTrue(event is P6TransportDecoder.Event.Invalid)
        event as P6TransportDecoder.Event.Invalid
        assertEquals("encrypted payload without session key", event.reason)
    }

    @Test
    fun `oversized declared payloads overflow the reassembly cap`() {
        // declare 1500 bytes, feed 1025 -> above the 1024 cap
        val header = byteArrayOf(0xAB.toByte(), 0x00, 0x05, 0xDC.toByte(), 0x00, 0x00, 0x00, 0x01)
        val body = ByteArray(1025)
        val event = P6TransportDecoder().feed(header + body, null)
        assertTrue(event is P6TransportDecoder.Event.Invalid)
        event as P6TransportDecoder.Event.Invalid
        assertEquals("payload overflow", event.reason)
        assertEquals(1, event.sequenceId)
    }

    @Test
    fun `feeding more bytes than declared overflows`() {
        val declared = byteArrayOf(0xAB.toByte(), 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x07) // 10 bytes
        val event = P6TransportDecoder().feed(declared + ByteArray(44), null) // 44 > 10
        assertTrue(event is P6TransportDecoder.Event.Invalid)
        assertEquals("payload overflow", (event as P6TransportDecoder.Event.Invalid).reason)
    }

    @Test
    fun `zero length data frames are rejected defensively`() {
        // ACK frames (flags 0x10) are filtered out before this branch; a
        // zero-length DATA frame is not a protocol shape seen on the wire, so
        // the decoder rejects it instead of wedging the reassembler. The
        // rejection reason is defensive engineering, not reference evidence.
        val empty = byteArrayOf(0xAB.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03)
        val event = P6TransportDecoder().feed(empty, null)
        assertTrue(event is P6TransportDecoder.Event.Invalid)
        event as P6TransportDecoder.Event.Invalid
        assertEquals(3, event.sequenceId)
        assertEquals("zero length data frame", event.reason)
    }

    @Test
    fun `reset clears partial reassembly state`() {
        val full = frame(ByteArray(48), sn = 2)
        val decoder = P6TransportDecoder()
        decoder.feed(full.copyOfRange(0, 10), null)
        assertTrue(decoder.reassembling)
        decoder.reset()
        assertFalse(decoder.reassembling)
        assertEquals(0, decoder.missing)
        // decoder works normally afterwards
        assertTrue(decoder.feed(full, null) is P6TransportDecoder.Event.Complete)
    }

    @Test
    fun `ack frames inside data stream surface as AckEvent`() {
        val decoder = P6TransportDecoder()
        val ack = P6TransportPacket.buildAck(9, failed = true)
        val event = decoder.feed(ack, null)
        assertEquals(P6TransportDecoder.Event.AckEvent(P6TransportPacket.Ack(true, 9)), event)
    }

    @Test
    fun `looksLikeDataHeader distinguishes data frames from ack frames`() {
        assertTrue(P6TransportPacket.looksLikeDataHeader(frame(byteArrayOf(1, 2, 3), 1)))
        assertFalse(P6TransportPacket.looksLikeDataHeader(P6TransportPacket.buildAck(1, false)))
        assertFalse(P6TransportPacket.looksLikeDataHeader(ByteArray(2)))
    }
}
