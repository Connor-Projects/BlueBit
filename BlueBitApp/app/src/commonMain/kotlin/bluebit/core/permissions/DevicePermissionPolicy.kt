package bluebit.core.permissions

/**
 * Classifies a runtime permission result by feature impact.
 *
 * Bluetooth-critical permissions (scan/connect, plus fine location on Android 11 and lower)
 * gate every BLE feature. The runtime notification permission only affects whether this
 * app's own notifications (e.g. the BLE diagnostics foreground-service notification) are
 * visible to the user, so it must never disable the connection flow.
 */
class DevicePermissionPolicy(
    private val bluetoothCriticalPermissions: Set<String>,
) {

    /** True when every Bluetooth-critical permission is granted. Notification permission never blocks BLE features. */
    fun bluetoothReady(permissions: Map<String, Boolean>): Boolean =
        bluetoothCriticalPermissions.all { permissions[it] == true }

    /** Bluetooth-critical permissions that are not granted; empty exactly when [bluetoothReady] is true. */
    fun deniedBluetoothPermissions(permissions: Map<String, Boolean>): List<String> =
        bluetoothCriticalPermissions.filter { permissions[it] != true }
}
