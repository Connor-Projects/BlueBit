package bluebit.fitbit.phase17

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 17 / T5+T6 — a replica of the app-side CoAP -> Airlink error mapping.
 *
 * Source of truth: `Decoded/Fitbit-base/sources/com/fitbit/goldengate/CoapResponseUtils.java`
 * lines 174-186 (`getResponseCode`) and lines 232-235 (`getIntCode`):
 *
 *     getIntCode(rc) = rc.class * 100 + rc.detail
 *     4.01 -> 5378 (RF_ERR_KEY_EXPIRED)          # unconditional; ExtendedError ignored
 *     5.03 -> 4353 (RF_ERR_STREAM_SYSTEM_NOT_READY)
 *     else -> ExtendedError.code, or 0 when absent
 *
 * This is a *replica of the documented rule*. The real GoldenGate bindings are
 * Android-only, so they are not on the `desktopTest` classpath; this replica lets
 * the rule be regression-checked on the JVM.
 */
class Phase17MappingTest {

    private object Mirror {
        const val RF_ERR_STREAM_SYSTEM_NOT_READY = 4353
        const val RF_ERR_KEY_EXPIRED = 5378

        fun intCode(responseClass: Int, detail: Int): Int = responseClass * 100 + detail

        fun getResponseCode(responseClass: Int, detail: Int, extendedErrorCode: Int?): Int {
            val intCode = intCode(responseClass, detail)
            if (intCode == 503) return RF_ERR_STREAM_SYSTEM_NOT_READY
            if (intCode == 401) return RF_ERR_KEY_EXPIRED
            return extendedErrorCode ?: 0
        }
    }

    @Test
    fun `intCode is class times 100 plus detail`() {
        assertEquals(401, Mirror.intCode(4, 1))
        assertEquals(503, Mirror.intCode(5, 3))
        assertEquals(204, Mirror.intCode(2, 4))
    }

    @Test
    fun `unauthorized masks any extended error`() {
        assertEquals(Mirror.RF_ERR_KEY_EXPIRED, Mirror.getResponseCode(4, 1, 0x1234))
        assertEquals(Mirror.RF_ERR_KEY_EXPIRED, Mirror.getResponseCode(4, 1, null))
    }

    @Test
    fun `service unavailable maps to stream not ready`() {
        assertEquals(Mirror.RF_ERR_STREAM_SYSTEM_NOT_READY, Mirror.getResponseCode(5, 3, 0x1234))
    }

    @Test
    fun `other codes pass the extended error through`() {
        assertEquals(0x1234, Mirror.getResponseCode(4, 3, 0x1234))
        assertEquals(0x1234, Mirror.getResponseCode(5, 0, 0x1234))
    }

    @Test
    fun `other codes without an extended error yield zero`() {
        assertEquals(0, Mirror.getResponseCode(2, 4, null))
        assertEquals(0, Mirror.getResponseCode(4, 3, null))
    }

    @Test
    fun `the key-expired mapping cannot prove a real expiry`() {
        // Same result with and without an ExtendedError => not evidence of expiry.
        assertEquals(
            Mirror.getResponseCode(4, 1, 0x0AAA),
            Mirror.getResponseCode(4, 1, null),
        )
    }
}
