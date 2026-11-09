package bluebit.fitcloud.native

/**
 * FitCloud session cipher: the `Aes256CtrUtil` transform, decompiled at
 * `p334x9/b.java:476–516` (`p334x9.b.o(int sequence, byte[] data)`).
 *
 * Despite the util name it is not real AES-CTR — a single 16-byte block of keystream
 * is applied cyclically across the whole payload:
 * ```
 * iv      = sessionIv.copyOf(); iv[14] = sn_be_hi; iv[15] = sn_be_lo
 * keyStr  = AES-256-encrypt(sessionKey, iv)          // one block, ECB, no padding
 * out[i]  = in[i] xor keyStr[i % 16]
 * ```
 * The transform is its own inverse, so the same routine serves encrypt and decrypt.
 * Both the key and the IV are session-scoped (derived per GATT connection) and the
 * IV carries the frame's own transport sequence number in its last two bytes.
 */
class P6SessionCipher(private val key: ByteArray, private val iv: ByteArray) {

    init {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(iv.size == 16) { "IV must be 16 bytes" }
    }

    /** Apply the transform to [data] in place-serialized form for [sequenceId]. */
    fun crypt(data: ByteArray, sequenceId: Int): ByteArray = crypt(data, 0, data.size, sequenceId)

    /**
     * Apply the transform to [size] bytes of [data] starting at [offset]. Byte
     * indexing into the cyclic keystream restarts at zero for every call — exactly
     * like the decompiled util, which is why the reference chunked encryption
     * ([P6TransportPacket.encodeEncrypted]) restarts the keystream per chunk.
     */
    fun crypt(data: ByteArray, offset: Int, size: Int, sequenceId: Int): ByteArray {
        val counter = iv.copyOf()
        counter[14] = ((sequenceId shr 8) and 0xFF).toByte()
        counter[15] = (sequenceId and 0xFF).toByte()
        val keystream = Aes256BlockCipher.encryptBlock(key, counter)
        val out = ByteArray(size)
        for (index in 0 until size) {
            out[index] = (data[offset + index].toInt() xor keystream[index % 16].toInt()).toByte()
        }
        return out
    }
}
