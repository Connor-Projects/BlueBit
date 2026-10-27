package bluebit.core.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline tests for the permission classification used by the connect flow:
 * notification permission must never disable BLE features (the Phase 20 follow-up gate fix).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DevicePermissionPolicyTest {

    private val android12PlusPolicy = DevicePermissionPolicy(
        setOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
        ),
    )
    private val legacyPolicy = DevicePermissionPolicy(
        setOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_FINE_LOCATION",
        ),
    )

    @Test
    fun `all granted means bluetooth ready`() {
        val permissions = mapOf(
            "android.permission.BLUETOOTH_SCAN" to true,
            "android.permission.BLUETOOTH_CONNECT" to true,
            "android.permission.POST_NOTIFICATIONS" to true,
        )
        assertTrue(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(emptyList(), android12PlusPolicy.deniedBluetoothPermissions(permissions))
    }

    @Test
    fun `denied notification permission alone never blocks bluetooth`() {
        val permissions = mapOf(
            "android.permission.BLUETOOTH_SCAN" to true,
            "android.permission.BLUETOOTH_CONNECT" to true,
            "android.permission.POST_NOTIFICATIONS" to false,
        )
        assertTrue(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(emptyList(), android12PlusPolicy.deniedBluetoothPermissions(permissions))
    }

    @Test
    fun `denied bluetooth scan blocks bluetooth and is reported`() {
        val permissions = mapOf(
            "android.permission.BLUETOOTH_SCAN" to false,
            "android.permission.BLUETOOTH_CONNECT" to true,
            "android.permission.POST_NOTIFICATIONS" to true,
        )
        assertFalse(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN"),
            android12PlusPolicy.deniedBluetoothPermissions(permissions),
        )
    }

    @Test
    fun `denied bluetooth connect blocks bluetooth and is reported`() {
        val permissions = mapOf(
            "android.permission.BLUETOOTH_SCAN" to true,
            "android.permission.BLUETOOTH_CONNECT" to false,
            "android.permission.POST_NOTIFICATIONS" to false,
        )
        assertFalse(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT"),
            android12PlusPolicy.deniedBluetoothPermissions(permissions),
        )
    }

    @Test
    fun `missing permission entry counts as denied`() {
        val permissions = mapOf("android.permission.BLUETOOTH_CONNECT" to true)
        assertFalse(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN"),
            android12PlusPolicy.deniedBluetoothPermissions(permissions),
        )
    }

    @Test
    fun `everything denied reports every bluetooth-critical permission`() {
        val permissions = mapOf(
            "android.permission.BLUETOOTH_SCAN" to false,
            "android.permission.BLUETOOTH_CONNECT" to false,
            "android.permission.POST_NOTIFICATIONS" to false,
        )
        assertFalse(android12PlusPolicy.bluetoothReady(permissions))
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            android12PlusPolicy.deniedBluetoothPermissions(permissions),
        )
    }

    @Test
    fun `legacy policy treats fine location as bluetooth-critical`() {
        assertTrue(
            legacyPolicy.bluetoothReady(
                mapOf(
                    "android.permission.BLUETOOTH_CONNECT" to true,
                    "android.permission.ACCESS_FINE_LOCATION" to true,
                    "android.permission.POST_NOTIFICATIONS" to false,
                ),
            ),
        )
        assertFalse(
            legacyPolicy.bluetoothReady(
                mapOf(
                    "android.permission.BLUETOOTH_CONNECT" to true,
                    "android.permission.ACCESS_FINE_LOCATION" to false,
                    "android.permission.POST_NOTIFICATIONS" to false,
                ),
            ),
        )
    }
}
