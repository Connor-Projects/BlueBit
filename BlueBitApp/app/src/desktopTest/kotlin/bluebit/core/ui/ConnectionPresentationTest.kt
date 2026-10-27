package bluebit.core.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline tests for the device-detail connection presentation mapping:
 * labels must mirror the real connection state only, and failures must surface as notes
 * with a retry hint. Notification/Bluetooth permission denials explain the actual remedy.
 */
class ConnectionPresentationTest {

    @Test
    fun `disconnected state shows Connect`() {
        assertEquals("Connect", connectionStatusLabel(connected = false, connecting = false))
    }

    @Test
    fun `connecting state shows Connecting`() {
        assertEquals("Connecting…", connectionStatusLabel(connected = false, connecting = true))
    }

    @Test
    fun `connected state wins over connecting and shows Disconnect`() {
        assertEquals("Disconnect", connectionStatusLabel(connected = true, connecting = true))
        assertEquals("Disconnect", connectionStatusLabel(connected = true, connecting = false))
    }

    @Test
    fun `security exception explains the permission remedy`() {
        val note = connectFailureNote(SecurityException("BLUETOOTH_CONNECT not granted"))
        assertTrue(note.contains("permission"))
        assertTrue(note.contains("Settings"))
    }

    @Test
    fun `failure with a message includes the message and the retry hint`() {
        val note = connectFailureNote(IllegalStateException("Inspire 3 pairing sequence returned false"))
        assertTrue(note.contains("Inspire 3 pairing sequence returned false"))
        assertTrue(note.contains("Tap Connect to retry"))
        assertTrue(note.startsWith("Connection failed:"))
    }

    @Test
    fun `failure without a message falls back to the generic note`() {
        assertEquals("Connection failed. Tap Connect to retry.", connectFailureNote(IllegalStateException(null as String?)))
        assertEquals("Connection failed. Tap Connect to retry.", connectFailureNote(null))
    }
}
