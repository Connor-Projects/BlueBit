package bluebit.fitcloud.native

/**
 * Parser for the device-information container the tracker pushes after auth:
 * transport frame payload `(cfgId=2, keyId=0x21)` whose item value is itself an
 * inner TLV container `[key:u8][len:u8][value(len)...]` (verified in `Hd/q.java`,
 * `_deviceInfo`; the `0x11` TLV key stores the Qd.d device-info blob).
 *
 * The 0x11 blob's field layout is deliberately NOT decoded here (behind the
 * reference's `Qd.d` model). BlueBit surfaces the raw bytes so a hardware capture
 * can be compared field-for-field with the physically verified values
 * (Project=0000000052B6, Patch=000000000000, Flash=00000000, App=00000101,
 * IC=IC_8762C). Decode field-by-field only after that capture.
 */
data class P6DeviceInfoItem(
    val key: Int,
    val value: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is P6DeviceInfoItem && other.key == key && other.value.contentEquals(value)

    override fun hashCode(): Int = key * 31 + value.contentHashCode()
}

data class P6DeviceInfoContainer(
    val items: List<P6DeviceInfoItem>,
) {
    /** The TLV item for [key], or null when absent. */
    fun item(key: Int): P6DeviceInfoItem? = items.firstOrNull { it.key == key }

    /** Raw device-info blob (TLV key 0x11), or null when the push omitted it. */
    val rawDeviceInfoBlob: ByteArray? get() = item(ITEM_KEY_DEVICE_INFO)?.value

    companion object {
        /** TLV key of the Qd.d device-info block (verified `Hd/q.java` key 17). */
        const val ITEM_KEY_DEVICE_INFO = 0x11
    }
}

object P6DeviceInfoParser {

    /**
     * Parse the inner TLV container carried by a `(2, 0x21)` config-push item.
     *
     * Lenient mirroring of the reference scan: items are read sequentially; a
     * truncated element ends the scan and the items read so far are returned
     * (null only when no complete item exists). Never throws on malformed input.
     */
    fun parse(tlvPayload: ByteArray): P6DeviceInfoContainer? {
        val items = ArrayList<P6DeviceInfoItem>()
        var offset = 0
        while (offset + 2 <= tlvPayload.size) {
            val key = tlvPayload[offset].toInt() and 0xFF
            val length = tlvPayload[offset + 1].toInt() and 0xFF
            val valueStart = offset + 2
            val valueEnd = valueStart + length
            if (valueEnd > tlvPayload.size) break // truncated value: stop the scan
            items.add(P6DeviceInfoItem(key, tlvPayload.copyOfRange(valueStart, valueEnd)))
            offset = valueEnd
        }
        return if (items.isEmpty()) null else P6DeviceInfoContainer(items)
    }
}
