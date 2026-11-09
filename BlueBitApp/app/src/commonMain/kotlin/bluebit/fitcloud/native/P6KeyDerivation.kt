package bluebit.fitcloud.native

/**
 * Platform model for a FitCloud device identity that BlueBit authenticated with.
 * Stored/derived values come from the android provider layer; this snapshot is what
 * [P6KeyDerivation] consumes in tests and callers.
 */
data class P6Identity(
    /** FitCloud user id the tracker was bound with (e.g. `26544037`). */
    val userId: String,
    /** Tracker Bluetooth MAC in `AA:BB:CC:DD:EE:FF` form. */
    val macAddress: String,
)

/**
 * Session key / IV derivation for the FitCloud transport, decompiled at
 * `p150kd/j.java:382–424` (the `connect()` path of `Fc#Connector`).
 *
 * Inputs are only ever the [P6Identity.userId] (UTF-8 — `p110hg/a.java:26`),
 * the tracker MAC bytes, and a fixed constant block:
 * ```
 * uidBlock      = UTF-8(userId) truncated to 28 bytes, zero-padded to 32
 * constBlock    = 4 x { 0x4D 0x4A 0x42 0x54 0x55 0xAA 0xA5 0x5A }
 * key[i]        = (i < 6 ? mac[i] : 0x00) ^ uidBlock[i] ^ constBlock[i]   // 32 bytes
 * iv[0..6]      = { 0xD4 0xEF 0xF0 0xD3 0xF4 0xE5 0xF0 }
 * iv[7..12]     = mac[0..5]
 * iv[13]        = 0xA1,  iv[14..15] = 0x00 (later overwritten by the SN)
 * ```
 */
object P6KeyDerivation {

    /** Length of the zero-padded user id block. */
    const val USER_BLOCK_LENGTH = 32

    /** The user id contributes at most 28 bytes (p334x9/b.java:456–460). */
    const val USER_BLOCK_LIMIT = 28

    /** Fixed 8-byte block repeated 4x to build the 32-byte constant block. */
    val CONST_UNIT = byteArrayOf(0x4D, 0x4A, 0x42, 0x54, 0x55, 0xAA.toByte(), 0xA5.toByte(), 0x5A.toByte())

    private val CONST_BLOCK: ByteArray = ByteArray(USER_BLOCK_LENGTH) { CONST_UNIT[it % CONST_UNIT.size] }

    private val IV_HEAD = byteArrayOf(
        0xD4.toByte(), 0xEF.toByte(), 0xF0.toByte(), 0xD3.toByte(),
        0xF4.toByte(), 0xE5.toByte(), 0xF0.toByte(),
    )
    private const val IV_TAIL_13 = 0xA1

    /** 32-byte user-id block: UTF-8 bytes, truncated at 28, zero-padded. */
    fun userBlock(userId: String): ByteArray {
        val utf8 = userId.encodeToByteArray()
        val block = ByteArray(USER_BLOCK_LENGTH)
        var bytes = utf8.size.coerceAtMost(USER_BLOCK_LIMIT)
        if (bytes > 0) System.arraycopy(utf8, 0, block, 0, bytes)
        return block
    }

    /** Parse `"AA:BB:CC:DD:EE:FF"` (or six bare octets) into bytes; null on failure. */
    fun parseMac(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        for (index in 0 until 6) {
            val piece = parts[index]
            if (piece.length != 2) return null
            val value = piece.toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    /** Derive the 32-byte session key for [macBytes] and the user id block. */
    fun sessionKey(macBytes: ByteArray, userBlock: ByteArray): ByteArray {
        require(macBytes.size == 6) { "MAC must be 6 bytes" }
        require(userBlock.size == USER_BLOCK_LENGTH) { "user block must be 32 bytes" }
        return ByteArray(USER_BLOCK_LENGTH) { index ->
            val macByte = if (index < 6) macBytes[index] else 0.toByte()
            (macByte.toInt() xor userBlock[index].toInt() xor CONST_BLOCK[index].toInt()).toByte()
        }
    }

    /** Derive the 16-byte session IV for [macBytes]. */
    fun sessionIv(macBytes: ByteArray): ByteArray {
        require(macBytes.size == 6) { "MAC must be 6 bytes" }
        val iv = ByteArray(16)
        IV_HEAD.copyInto(iv, 0, 0, IV_HEAD.size)
        macBytes.copyInto(iv, 7, 0, 6)
        iv[13] = IV_TAIL_13.toByte()
        return iv
    }

    /**
     * Derive the full session cipher for a freshly connected device. Returns null if
     * the MAC cannot be parsed. The reference derives these at connect time and
     * nulls them on disconnect (`p150kd/j.java:256–257`), so every GATT session
     * restarts with a fresh cipher.
     */
    fun derive(identity: P6Identity): P6SessionCipher? {
        val macBytes = parseMac(identity.macAddress) ?: return null
        return P6SessionCipher(
            sessionKey(macBytes, userBlock(identity.userId)),
            sessionIv(macBytes),
        )
    }
}
