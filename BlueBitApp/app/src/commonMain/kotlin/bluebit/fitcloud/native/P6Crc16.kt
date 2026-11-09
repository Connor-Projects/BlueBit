package bluebit.fitcloud.native

/**
 * CRC-16 used by the FitCloud transport frames (class `U1.e`, table `p339xe.j.f54196a`).
 *
 * Verified against the decompiled FitCloudPro APK:
 * - byte-wise table CRC: crc = (crc >> 8) ^ TABLE[(crc ^ byte) & 0xFF]
 * - initial value 0x0000 on both TX and RX (U1/e.java:69-70, 128-131)
 * - no final XOR; result masked to 16 bits
 * - table is the canonical reflected table for polynomial 0x8005 (0xA001), and the
 *   256 literals in `p339xe/j.java:35` match it exactly (the four opaque compile-time
 *   constants in the decompiled literal resolve at their positions: index 6 -> 640,
 *   index 12 -> 1280, index 48 -> 5120, index 96 -> 10240).
 */
object P6Crc16 {

    /** Polynomial 0x8005, reflected for XOR-in bit order. */
    private const val REFLECTED_POLY = 0xA001

    /**
     * Reflected CRC-16 table for polynomial 0x8005, identical entry-for-entry to the
     * table embedded in the FitCloudPro APK (`p339xe/j.java:35`).
     */
    val TABLE: IntArray = IntArray(256).also { table ->
        for (index in 0..255) {
            var crc = index
            repeat(8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor REFLECTED_POLY
                } else {
                    crc ushr 1
                }
            }
            table[index] = crc
        }
    }

    /** CRC over [data] with the FitCloud transport initial value (0x0000). */
    fun compute(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = (crc ushr 8) xor TABLE[(byte.toInt() xor crc) and 0xFF]
        }
        return crc and 0xFFFF
    }

    /** CRC over a slice of [data] from [fromIndex] (inclusive) to [toIndex] (exclusive). */
    fun compute(data: ByteArray, fromIndex: Int, toIndex: Int): Int {
        var crc = 0
        for (index in fromIndex until toIndex) {
            crc = (crc ushr 8) xor TABLE[(data[index].toInt() xor crc) and 0xFF]
        }
        return crc and 0xFFFF
    }
}
