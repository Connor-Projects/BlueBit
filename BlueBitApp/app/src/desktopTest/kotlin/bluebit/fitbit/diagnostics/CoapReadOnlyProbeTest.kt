package bluebit.fitbit.diagnostics

import bluebit.core.diagnostics.CoapReadOnlyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

/**
 * Desktop tests for the hardware-independent parts of the CoAP read-only probe.
 *
 * The Android-only [CoapReadOnlyProbe] is not exercised here; instead these
 * tests cover [CoapReadOnlyProbeLogic] helpers for request construction,
 * response handling, timeout handling, and cleanup failure handling.
 */
class CoapReadOnlyProbeTest {

    @Test
    fun `request spec uses well-known core and GET`() {
        val spec = CoapReadOnlyProbeLogic.createRequestSpec()

        assertEquals(".well-known/core", spec.path)
        assertEquals(CoapReadOnlyMethod.GET, spec.method)
    }

    @Test
    fun `processResponse returns success for 2-05 Content`() {
        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(2, 5),
            "</sensor/temp>,</sensor/hum>"
        )

        assertTrue(summary.coapSuccess)
        assertEquals("2.05", summary.responseCode)
        assertEquals(28, summary.responseLength)
        assertEquals("/sensor/temp, /sensor/hum", summary.resourceSummary)
        assertNull(summary.error)
    }

    @Test
    fun `processResponse truncates resource summary`() {
        val body = (1..100).joinToString(", ") { "</resource$it>" }

        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(2, 5),
            body,
            maxSummaryLength = 50
        )

        assertTrue(summary.coapSuccess)
        assertEquals(50, summary.resourceSummary.length)
        assertTrue(summary.resourceSummary.endsWith("..."))
    }

    @Test
    fun `processResponse redacts secrets from summary`() {
        val body = "</public>,</private?token=abc123&psk=secret>"

        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(2, 5),
            body
        )

        assertFalse(summary.resourceSummary.contains("abc123"))
        assertFalse(summary.resourceSummary.contains("secret"))
        assertTrue(summary.resourceSummary.contains("token=***"))
        assertTrue(summary.resourceSummary.contains("psk=***"))
    }

    @Test
    fun `processResponse handles empty body`() {
        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(2, 5),
            ""
        )

        assertFalse(summary.coapSuccess)
        assertEquals("2.05", summary.responseCode)
        assertEquals(0, summary.responseLength)
        assertEquals("", summary.resourceSummary)
        assertEquals("Empty CoAP response body", summary.error)
    }

    @Test
    fun `processResponse handles error response code`() {
        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(4, 1),
            "unauthorized"
        )

        assertFalse(summary.coapSuccess)
        assertEquals("4.01", summary.responseCode)
        assertNotNull(summary.error)
        assertTrue(summary.error!!.contains("4.01"))
    }

    @Test
    fun `response code formats with zero-padded detail`() {
        assertEquals("4.04", CoapResponseCode(4, 4).toString())
        assertEquals("2.05", CoapResponseCode(2, 5).toString())
        assertEquals("4.01", CoapResponseCode(4, 1).toString())
    }

    @Test
    fun `processResponse handles 4-04 Not Found`() {
        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(4, 4),
            ""
        )

        assertFalse(summary.coapSuccess)
        assertEquals("4.04", summary.responseCode)
        assertNotNull(summary.error)
        assertTrue(summary.error!!.contains("4.04"))
    }

    @Test
    fun `processResponse logs warning and returns raw for malformed link-format`() {
        var warning: String? = null

        val summary = CoapReadOnlyProbeLogic.processResponse(
            CoapResponseCode(2, 5),
            "this is not a link-format payload",
            onWarning = { warning = it }
        )

        assertNotNull(warning)
        assertTrue(warning!!.contains("link-format", ignoreCase = true))
        assertEquals("this is not a link-format payload", summary.resourceSummary)
    }

    @Test
    fun `timeout result has coapSuccess false and timeout error`() {
        val summary = CoapReadOnlyProbeLogic.createTimeoutResult(10_000L)

        assertFalse(summary.coapSuccess)
        assertNull(summary.responseCode)
        assertEquals(0, summary.responseLength)
        assertEquals("", summary.resourceSummary)
        assertNotNull(summary.error)
        assertTrue(summary.error!!.contains("timed out"))
        assertTrue(summary.error!!.contains("10000"))
    }

    @Test
    fun `cleanup actions swallow exceptions and continue`() {
        val errors = mutableListOf<Pair<String, Throwable>>()
        var reachedSecondAction = false

        val result = CoapReadOnlyProbeLogic.runCleanupActions(
            listOf(
                { throw RuntimeException("boom") },
                { reachedSecondAction = true }
            ),
            onError = { msg, e -> errors.add(msg to e) }
        )

        assertFalse(result)
        assertTrue(reachedSecondAction)
        assertEquals(1, errors.size)
        assertEquals("boom", errors[0].second.message)
    }

    @Test
    fun `request spec uses default path`() {
        val spec = CoapReadOnlyProbeLogic.createRequestSpec()

        assertEquals(".well-known/core", spec.path)
        assertEquals(CoapReadOnlyMethod.GET, spec.method)
    }

    @Test
    fun `request spec strips leading slash`() {
        val spec = CoapReadOnlyProbeLogic.createRequestSpec("/device/info")

        assertEquals("device/info", spec.path)
        assertEquals(CoapReadOnlyMethod.GET, spec.method)
    }

    @Test
    fun `request spec rejects blank path`() {
        assertFailsWith<IllegalArgumentException> {
            CoapReadOnlyProbeLogic.createRequestSpec("  ")
        }
    }

    @Test
    fun `request spec rejects query string`() {
        assertFailsWith<IllegalArgumentException> {
            CoapReadOnlyProbeLogic.createRequestSpec(".well-known/core?foo=bar")
        }
    }
}
