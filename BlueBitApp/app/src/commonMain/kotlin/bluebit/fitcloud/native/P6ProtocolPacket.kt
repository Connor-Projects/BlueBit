package bluebit.fitcloud.native

/**
 * On-the-wire serialization of a FitCloud protocol packet (`p349yd.a`,
 * "FcProtocolPacket(cmdId, keyId, keyData)").
 *
 * Verified wire format:
 * - TX binding (`p363zd/e.java:163–182`):
 *   `[cmdId][0x00][keyId][lenHi][lenLo][keyData...]`, length bytes big-endian;
 *   `keyData == null` encodes to `[cmdId][0x00][keyId][0x00][0x00]`.
 * - RX container parse (`Hd/C0501l.java:21–53`):
 *   `[cfgId][reserved][keyId][lenHi][lenLo][value...]` with byte 1 skipped by the
 *   parser and further `[keyId][len][value]` items chained after the first.
 */
data class P6ProtocolItem(
    val keyId: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is P6ProtocolItem && other.keyId == keyId && other.data.contentEquals(data)

    override fun hashCode(): Int = keyId * 31 + data.contentHashCode()
}

data class P6ProtocolPacket(
    val cfgId: Int,
    val items: List<P6ProtocolItem>,
) {
    /** First item's key id, or null when the packet carries no items. */
    val keyId: Int? get() = items.firstOrNull()?.keyId

    /** First item's payload, or null when the packet carries no items. */
    val firstData: ByteArray? get() = items.firstOrNull()?.data
}

object P6ProtocolPacketCodec {

    /** Command ids observed in the FitCloud protocol (`p349yd.a` constructions). */
    const val CFG_ID_CONFIG = 2
    const val CFG_ID_AUTH = 3
    const val CFG_ID_SETTINGS = 5

    /** Config container item key carrying the device-information block (`Hd/q.java:59`). */
    const val ITEM_KEY_DEVICE_INFO = 0x11

    /** Serialize a TX command packet. */
    fun serialize(cmdId: Int, keyId: Int, data: ByteArray?): ByteArray {
        val header = byteArrayOf(
            (cmdId and 0xFF).toByte(), 0x00,
            (keyId and 0xFF).toByte(),
            ((data?.size ?: 0) shr 8).toByte(),
            ((data?.size ?: 0) and 0xFF).toByte(),
        )
        if (data == null) return header
        return header + data
    }

    /** Parse an RX payload (post reassembly/decryption); null when malformed. */
    fun parse(payload: ByteArray): P6ProtocolPacket? {
        if (payload.size < 2) return null
        val cfgId = payload[0].toInt() and 0xFF
        val items = ArrayList<P6ProtocolItem>()
        var itemsLength = payload.size - 2
        while (itemsLength > 0) {
            val start = payload.size - itemsLength
            if (itemsLength < 3) return finish(cfgId, items)
            val keyId = payload[start].toInt() and 0xFF
            val length = ((payload[start + 1].toInt() shl 8) or (payload[start + 2].toInt() and 0xFF)) and 0xFFFF
            if (itemsLength < length + 3) return finish(cfgId, items) // truncated item: keep the leading items
            items.add(P6ProtocolItem(keyId, payload.copyOfRange(start + 3, start + 3 + length)))
            itemsLength -= length + 3
        }
        return P6ProtocolPacket(cfgId, items)
    }

    private fun finish(cfgId: Int, items: List<P6ProtocolItem>): P6ProtocolPacket? =
        if (items.isEmpty()) null else P6ProtocolPacket(cfgId, items)
}
