package bluebit.core.ui.screens

/**
 * Pure presentation mapping for the device-detail connection flow, kept JVM-testable.
 * Labels mirror the real connection state only — Connected is never faked.
 */

/** Label for the connection toggle: the same button acts as Connect, Retry (after a failure), and Disconnect. */
fun connectionStatusLabel(connected: Boolean, connecting: Boolean): String = when {
    connected -> "Disconnect"
    connecting -> "Connecting…"
    else -> "Connect"
}

/** Human-readable connection-failure note; [error] comes from the actual connect Result. */
fun connectFailureNote(error: Throwable?): String = when {
    error is SecurityException -> "Bluetooth permission not granted — BLE features disabled until granted in Settings."
    error?.message != null -> "Connection failed: ${error.message}. Tap Connect to retry."
    else -> "Connection failed. Tap Connect to retry."
}
