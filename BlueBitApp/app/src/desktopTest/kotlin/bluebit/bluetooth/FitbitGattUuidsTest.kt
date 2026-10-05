package bluebit.bluetooth

import bluebit.fitbit.FitbitGattUuids
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FitbitGattUuidsTest {

    @Test
    fun `Gattlink service UUID is correct`() {
        assertEquals(
            "ABBAFF00-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.GATTLINK_SERVICE_UUID,
        )
    }

    @Test
    fun `Receive characteristic UUID is correct`() {
        assertEquals(
            "ABBAFF01-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.RECEIVE_CHARACTERISTIC_UUID,
        )
    }

    @Test
    fun `Transmit characteristic UUID is correct`() {
        assertEquals(
            "ABBAFF02-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.TRANSMIT_CHARACTERISTIC_UUID,
        )
    }

    @Test
    fun `Fitbit Gattlink service UUID is correct`() {
        assertEquals(
            "0000FD62-0000-1000-8000-00805F9B34FB",
            FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID,
        )
    }

    @Test
    fun `LinkConfiguration service UUID is correct`() {
        assertEquals(
            "ABBAFC00-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.LINK_CONFIGURATION_SERVICE_UUID,
        )
    }

    @Test
    fun `ClientPreferredConnectionConfiguration UUID is correct`() {
        assertEquals(
            "ABBAFC01-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_CONFIGURATION_CHARACTERISTIC_UUID,
        )
    }

    @Test
    fun `ClientPreferredConnectionMode UUID is correct`() {
        assertEquals(
            "ABBAFC02-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.CLIENT_PREFERRED_CONNECTION_MODE_CHARACTERISTIC_UUID,
        )
    }

    @Test
    fun `GeneralPurposeCommand UUID is correct`() {
        assertEquals(
            "ABBAFC03-E56A-484C-B832-8B17CF6CBFE8",
            FitbitGattUuids.GENERAL_PURPOSE_COMMAND_CHARACTERISTIC_UUID,
        )
    }
}
