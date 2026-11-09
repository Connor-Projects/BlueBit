package bluebit.fitcloud.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class P6Crc16Test {

    /**
     * The 256-entry table literal embedded in the FitCloudPro APK
     * (`p339xe/j.java:35`, field `f54196a`). Four entries are symbolic in the
     * decompiled output and were resolved from their defining sources:
     * `MicrophoneServer.S_LENGTH` = 640 (index 6), `CameraManager.DEFAULTWIDTH`
     * = 1280 (index 12), `BmLocated.HALF_LEFT_TOP` = 5120 (index 48),
     * `BmLocated.HALF_RIGHT_BOTTOM` = 10240 (index 96).
     */
    private val decompiledTable = intArrayOf(
        0, 49345, 49537, 320, 49921, 960, 640, 49729, 50689, 1728, 1920, 51009, 1280, 50625, 50305, 1088,
        52225, 3264, 3456, 52545, 3840, 53185, 52865, 3648, 2560, 51905, 52097, 2880, 51457, 2496, 2176, 51265,
        55297, 6336, 6528, 55617, 6912, 56257, 55937, 6720, 7680, 57025, 57217, 8000, 56577, 7616, 7296, 56385,
        5120, 54465, 54657, 5440, 55041, 6080, 5760, 54849, 53761, 4800, 4992, 54081, 4352, 53697, 53377, 4160,
        61441, 12480, 12672, 61761, 13056, 62401, 62081, 12864, 13824, 63169, 63361, 14144, 62721, 13760, 13440, 62529,
        15360, 64705, 64897, 15680, 65281, 16320, 16000, 65089, 64001, 15040, 15232, 64321, 14592, 63937, 63617, 14400,
        10240, 59585, 59777, 10560, 60161, 11200, 10880, 59969, 60929, 11968, 12160, 61249, 11520, 60865, 60545, 11328,
        58369, 9408, 9600, 58689, 9984, 59329, 59009, 9792, 8704, 58049, 58241, 9024, 57601, 8640, 8320, 57409,
        40961, 24768, 24960, 41281, 25344, 41921, 41601, 25152, 26112, 42689, 42881, 26432, 42241, 26048, 25728, 42049,
        27648, 44225, 44417, 27968, 44801, 28608, 28288, 44609, 43521, 27328, 27520, 43841, 26880, 43457, 43137, 26688,
        30720, 47297, 47489, 31040, 47873, 31680, 31360, 47681, 48641, 32448, 32640, 48961, 32000, 48577, 48257, 31808,
        46081, 29888, 30080, 46401, 30464, 47041, 46721, 30272, 29184, 45761, 45953, 29504, 45313, 29120, 28800, 45121,
        20480, 37057, 37249, 20800, 37633, 21440, 21120, 37441, 38401, 22208, 22400, 38721, 21760, 38337, 38017, 21568,
        39937, 23744, 23936, 40257, 24320, 40897, 40577, 24128, 23040, 39617, 39809, 23360, 39169, 22976, 22656, 38977,
        34817, 18624, 18816, 35137, 19200, 35777, 35457, 19008, 19968, 36545, 36737, 20288, 36097, 19904, 19584, 35905,
        17408, 33985, 34177, 17728, 34561, 18368, 18048, 34369, 33281, 17088, 17280, 33601, 16640, 33217, 32897, 16448,
    )

    @Test
    fun `table is 256 entries`() {
        assertEquals(256, decompiledTable.size)
        assertEquals(256, P6Crc16.TABLE.size)
    }

    @Test
    fun `table matches the decompiled APK literal entry for entry`() {
        for (index in 0..255) {
            assertEquals(
                decompiledTable[index],
                P6Crc16.TABLE[index],
                "table mismatch at index $index",
            )
        }
    }

    @Test
    fun `resolved symbolic constants sit at their expected table positions`() {
        // These four positions were verified by reading the constant definitions
        // in the decompiled sources; the switch-statement table uses them so the
        // generated table must equal them exactly.
        assertEquals(640, P6Crc16.TABLE[6])    // MicrophoneServer.S_LENGTH
        assertEquals(1280, P6Crc16.TABLE[12])  // CameraManager.DEFAULTWIDTH
        assertEquals(5120, P6Crc16.TABLE[48])  // BmLocated.HALF_LEFT_TOP
        assertEquals(10240, P6Crc16.TABLE[96]) // BmLocated.HALF_RIGHT_BOTTOM
    }

    @Test
    fun `crc of the auth probe payloads matches independent arithmetic`() {
        // Computed by hand via the spec 2.2 algorithm over the independently
        // verified table (script in research/p6), not by calling the code under test.
        assertEquals(0x0404, P6Crc16.compute(byteArrayOf(0x03, 0x00, 0x14, 0x00, 0x00)))
        assertEquals(0x05E4, P6Crc16.compute(byteArrayOf(0x03, 0x00, 0x12, 0x00, 0x00)))
    }

    @Test
    fun `crc of the auth submit payloads matches independent arithmetic`() {
        val uidBlock = ByteArray(32) { index -> "26544037".encodeToByteArray().getOrNull(index) ?: 0 }
        assertEquals(
            0x2D98,
            P6Crc16.compute(byteArrayOf(0x03, 0x00, 0x11, 0x00, 0x20) + uidBlock),
        )
        assertEquals(
            0xEDC3,
            P6Crc16.compute(byteArrayOf(0x03, 0x00, 0x13, 0x00, 0x20) + uidBlock),
        )
    }

    @Test
    fun `empty payload crc is zero`() {
        assertEquals(0, P6Crc16.compute(ByteArray(0)))
    }

    /**
     * Sanity check against the published CRC-16/ARC check value. [A] — the value
     * is not in the APK evidence bundle; the table cross-check above is the
     * strong test. If this ever fails while the table test passes, the
     * byte-order interpretation of the table would be wrong.
     */
    @Test
    fun `crc of 123456789 matches the published CRC-16-ARC check value`() {
        assertEquals(0xBB3D, P6Crc16.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun `slice overload matches computing over a copied range`() {
        val data = ByteArray(64) { it.toByte() }
        val slice = data.copyOfRange(8, 24)
        assertEquals(P6Crc16.compute(slice), P6Crc16.compute(data, 8, 24))
    }
}
