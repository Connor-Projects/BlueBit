package bluebit.bluetooth

import bluebit.fitbit.FitbitGattUuids
import bluebit.fitbit.transport.FitbitGattlinkServiceSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GattlinkServiceSelectorTest {

    @Test
    fun `selects modern service when both are present`() {
        val services = listOf(
            FitbitGattUuids.GATTLINK_SERVICE_UUID,
            FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID,
        )
        assertEquals(FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID, FitbitGattlinkServiceSelector.select(services))
    }

    @Test
    fun `falls back to legacy service when modern is absent`() {
        val services = listOf(
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
            FitbitGattUuids.GATTLINK_SERVICE_UUID,
        )
        assertEquals(FitbitGattUuids.GATTLINK_SERVICE_UUID, FitbitGattlinkServiceSelector.select(services))
    }

    @Test
    fun `returns null when neither service is present`() {
        val services = listOf(
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
            "0000180A-0000-1000-8000-00805F9B34FB",
        )
        assertNull(FitbitGattlinkServiceSelector.select(services))
    }

    @Test
    fun `is case sensitive`() {
        val services = listOf(
            FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID.lowercase(),
        )
        // The selector does exact string matching, so lowercase should not match
        assertNull(FitbitGattlinkServiceSelector.select(services))
    }
}
