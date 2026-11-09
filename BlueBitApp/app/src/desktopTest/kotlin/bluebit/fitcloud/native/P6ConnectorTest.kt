package bluebit.fitcloud.native

import bluebit.bluetooth.FakeBleConnection
import bluebit.core.bluetooth.BleCharacteristic
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Connector behaviour driven over [FakeBleConnection]. All frame bytes below are
 * explicit fixtures computed per the verified spec arithmetic (CRC table cross-
 * checked entry-for-entry against the decompiled APK literal, layout from
 * `U1/e.java`); see `research/p6/PROTOCOL_SPECIFICATION.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P6ConnectorTest {

    private val device = BleDevice("90:EB:99:D6:5F:99", "P6_TEST")
    private val identity = P6Identity(userId = "26544037", macAddress = "90:EB:99:D6:5F:99")
    private val equivalentCipher = P6KeyDerivation.derive(identity)!!

    //-- explicit frame fixtures --------------------------------------------

    /** LOGIN Auth submit (3,0x13,uidBlock) plaintext, SN 1, CRC 0xEDC3. */
    private val submitFrame = byteArrayOf(
        0xAB.toByte(), 0x00, 0x00, 0x25, 0xED.toByte(), 0xC3.toByte(), 0x00, 0x01,
        0x03, 0x00, 0x13, 0x00, 0x20,
    ) + identity.userId.encodeToByteArray() + ByteArray(24)

    /** BIND Auth submit (3,0x11,uidBlock) plaintext, SN 1, CRC 0x2D98. */
    private val bindSubmitFrame = byteArrayOf(
        0xAB.toByte(), 0x00, 0x00, 0x25, 0x2D.toByte(), 0x98.toByte(), 0x00, 0x01,
        0x03, 0x00, 0x11, 0x00, 0x20,
    ) + identity.userId.encodeToByteArray() + ByteArray(24)

    /** Device LOGIN result (3,0x14,[0x00]), device SN 5, CRC 0x5304. */
    private val loginResultOk = byteArrayOf(
        0xAB.toByte(), 0x00, 0x00, 0x06, 0x53, 0x04, 0x00, 0x05,
        0x03, 0x00, 0x14, 0x00, 0x01, 0x00,
    )

    /** Device LOGIN result data[0]=1 (bind required), device SN 5, CRC 0x93C5. */
    private val loginResultBindRequired = byteArrayOf(
        0xAB.toByte(), 0x00, 0x00, 0x06, 0x93.toByte(), 0xC5.toByte(), 0x00, 0x05,
        0x03, 0x00, 0x14, 0x00, 0x01, 0x01,
    )

    /** Device BIND result (3,0x12,[0x00]), device SN 5, CRC 0xDB04. */
    private val bindResultOk = byteArrayOf(
        0xAB.toByte(), 0x00, 0x00, 0x06, 0xDB.toByte(), 0x04, 0x00, 0x05,
        0x03, 0x00, 0x12, 0x00, 0x01, 0x00,
    )

    /** App-side ACK for the device's SN 5 result frame (flags 0x10). */
    private val ackForDeviceResult = byteArrayOf(0xAB.toByte(), 0x10, 0, 0, 0, 0, 0x00, 0x05)

    private fun acknowledge(txSn: Int, failed: Boolean = false): ByteArray =
        P6TransportPacket.buildAck(txSn, failed)

    private fun connectionWithTransport(): FakeBleConnection {
        val connection = FakeBleConnection()
        connection.setDiscoveredServices(
            listOf(
                BleService(
                    P6Uuids.SERVICE,
                    listOf(
                        BleCharacteristic(P6Uuids.WRITE_CHARACTERISTIC, properties = 0x0C),
                        BleCharacteristic(P6Uuids.NOTIFY_CHARACTERISTIC, properties = 0x10),
                    ),
                ),
            ),
        )
        return connection
    }

    private fun notifyValue(connection: FakeBleConnection, value: ByteArray) {
        connection.emitNotification(BleNotification(P6Uuids.SERVICE, P6Uuids.NOTIFY_CHARACTERISTIC, value))
    }

    /**
     * Drive the submit-first LOGIN handshake: submit (SN 1) -> device ack ->
     * result(ok) push. The connector writes the ack for the device's result
     * frame itself. The caller must already have subscribed to
     * [P6Connector.events].
     */
    private fun TestScope.completeLogin(connection: FakeBleConnection, connector: P6Connector): Job {
        val authJob = launch { connector.connectAndAuthenticate(device, identity, P6AuthMode.LOGIN) }
        runCurrent()
        notifyValue(connection, acknowledge(1))
        runCurrent()
        notifyValue(connection, loginResultOk)
        runCurrent()
        return authJob
    }

    @Test
    fun `login submit is sent first then the result ack and authenticates`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)

        val authJob = completeLogin(connection, connector)
        authJob.join()

        assertEquals(P6ConnectionState.AUTHENTICATED, connector.state.value)
        assertTrue(authJob.isCompleted && !authJob.isCancelled)

        // notifications on ff03 enabled, with an explicit CCCD write of 01 00
        assertTrue(
            connection.notificationCalls.any {
                it.first == P6Uuids.SERVICE && it.second == P6Uuids.NOTIFY_CHARACTERISTIC && it.third
            },
        )
        assertEquals(1, connection.descriptorWriteCalls.size)
        val cccd = connection.descriptorWriteCalls.single()
        assertEquals(P6Uuids.CCCD, cccd.descriptorUuid)
        assertEquals(listOf(0x01, 0x00), cccd.value.map { it.toInt() })

        // wire order: submit first (SN 1), then our ack for the device's result
        // frame — the hardware-verified submit-first sequence
        val writes = connection.characteristicWriteCalls
        assertEquals(2, writes.size)
        assertEquals(submitFrame.toList(), writes[0].value.toList())
        assertEquals(ackForDeviceResult.toList(), writes[1].value.toList())
    }

    @Test
    fun `bind flow submits the uid block under key 0x11`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)

        val authJob = launch { connector.connectAndAuthenticate(device, identity, P6AuthMode.BIND) }
        runCurrent()
        notifyValue(connection, acknowledge(1))
        runCurrent()
        notifyValue(connection, bindResultOk) // device SN 5
        runCurrent()
        authJob.join()

        assertEquals(P6ConnectionState.AUTHENTICATED, connector.state.value)
        assertEquals(2, connection.characteristicWriteCalls.size)
        assertEquals(bindSubmitFrame.toList(), connection.characteristicWriteCalls[0].value.toList())
        assertEquals(ackForDeviceResult.toList(), connection.characteristicWriteCalls[1].value.toList())
    }

    @Test
    fun `doLoginAuth result 1 requires the bind step and fails the session`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        var thrown: Throwable? = null
        val authJob = launch {
            runCatching { connector.connectAndAuthenticate(device, identity, P6AuthMode.LOGIN) }
                .onFailure { thrown = it }
        }
        runCurrent()
        notifyValue(connection, acknowledge(1))
        runCurrent()
        notifyValue(connection, loginResultBindRequired)
        runCurrent()
        authJob.join()

        assertTrue(thrown is P6ProtocolException)
        assertEquals(1, (thrown as P6ProtocolException).resultCode)
        assertEquals(P6ConnectionState.FAILED, connector.state.value)
        // the device's result frame was still ACKed before the failure surfaced
        assertTrue(
            connection.characteristicWriteCalls.any { ackForDeviceResult.contentEquals(it.value) },
        )
    }

    @Test
    fun `auth result timeout fails the session after the reference window`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        var thrown: Throwable? = null
        val authJob = launch {
            runCatching { connector.connectAndAuthenticate(device, identity, P6AuthMode.LOGIN) }
                .onFailure { thrown = it }
        }
        runCurrent()
        // the submit was written and the device acked it; no result ever arrives
        notifyValue(connection, acknowledge(1))
        runCurrent()
        advanceUntilIdle()
        authJob.join()

        assertTrue(thrown is P6ProtocolException)
        assertTrue((thrown as P6ProtocolException).message!!.contains("auth result not received"))
        assertEquals(P6ConnectionState.FAILED, connector.state.value)
    }

    @Test
    fun `missing transport service fails with a discovery diagnostic`() = runTest {
        val connection = FakeBleConnection() // services never provided
        connection.setDiscoveredServices(
            listOf(BleService(P6Uuids.SERVICE, emptyList())),
        )
        val connector = P6Connector(connection, backgroundScope)
        val outcome = runCatching { connector.connectAndAuthenticate(device, identity, P6AuthMode.LOGIN) }
        assertTrue(outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(error is P6ProtocolException)
        assertTrue(error!!.message!!.contains("transport service/characteristics missing"))
        assertEquals(P6ConnectionState.FAILED, connector.state.value)
    }

    @Test
    fun `send without device ack retries three attempts with the same sn and fails`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        completeLogin(connection, connector).join()

        // (2,0x21,[01]) plaintext frame, SN 3, CRC 0x8ECB — independently computed
        val expected = byteArrayOf(
            0xAB.toByte(), 0x00, 0x00, 0x06, 0x8E.toByte(), 0xCB.toByte(), 0x00, 0x03,
            0x02, 0x00, 0x21, 0x00, 0x01, 0x01,
        )
        val sent = runCatching { connector.sendPacket(2, 0x21, byteArrayOf(0x01)) }

        assertTrue(sent.isFailure)
        val error = sent.exceptionOrNull()
        assertTrue(error is P6ProtocolException)
        assertTrue(error!!.message!!.contains("SN 3 failed after 3 attempts"))
        // three identical retransmits
        assertEquals(
            3,
            connection.characteristicWriteCalls.count { expected.contentEquals(it.value) },
        )
    }

    @Test
    fun `nacks retransmit with the same sn until an accepted ack arrives`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        completeLogin(connection, connector).join()

        val expected = byteArrayOf(
            0xAB.toByte(), 0x00, 0x00, 0x06, 0x8E.toByte(), 0xCB.toByte(), 0x00, 0x03,
            0x02, 0x00, 0x21, 0x00, 0x01, 0x01,
        )
        var sentSn = -1
        var failure: Throwable? = null
        val sendJob = launch {
            runCatching { connector.sendPacket(2, 0x21, byteArrayOf(0x01)) }
                .onSuccess { sentSn = it }
                .onFailure { failure = it }
        }
        runCurrent() // attempt 1 written
        notifyValue(connection, acknowledge(3, failed = true))
        runCurrent() // attempt 2 written
        notifyValue(connection, acknowledge(3, failed = true))
        runCurrent() // attempt 3 written
        notifyValue(connection, acknowledge(3, failed = false))
        runCurrent()
        sendJob.join()

        assertNull(failure)
        assertEquals(3, sentSn)
        assertEquals(3, connection.characteristicWriteCalls.count { expected.contentEquals(it.value) })
        assertEquals(3, connector.sessionState.nextTxSequenceId - 1) // probe + submit + send claimed
    }

    @Test
    fun `sequence ids start at one per session and reset on disconnect`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        completeLogin(connection, connector).join()

        assertEquals(2, connector.sessionState.nextTxSequenceId - 1) // probe + submit only
        assertFalse(connection.disconnected)
        connector.disconnect()
        assertTrue(connection.disconnected)
        assertEquals(P6ConnectionState.DISCONNECTED, connector.state.value)
        assertEquals(1, connector.sessionState.nextTxSequenceId)
        assertEquals(-1, connector.sessionState.lastDeliveredSequenceId)
        assertFalse(connector.sessionState.inboundEncryptedMirror)
    }

    @Test
    fun `duplicate inbound sn is re acked but not re delivered`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        val events = mutableListOf<P6SessionEvent>()
        backgroundScope.launch { connector.events.collect { events.add(it) } }
        runCurrent()

        completeLogin(connection, connector).join()
        val dataEventsBefore = events.count { it is P6SessionEvent.Data }
        assertEquals(1, dataEventsBefore) // the auth result packet

        notifyValue(connection, loginResultOk) // same SN 5 again
        runCurrent()
        notifyValue(connection, loginResultOk)
        runCurrent()

        assertEquals(dataEventsBefore, events.count { it is P6SessionEvent.Data })
        // every duplicate got the 0x10 ack (one per receipt, plus the original)
        assertEquals(
            3,
            connection.characteristicWriteCalls.count { ackForDeviceResult.contentEquals(it.value) },
        )
    }

    @Test
    fun `corrupt inbound frames are nacked with flags 0x30 and never delivered`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        val events = mutableListOf<P6SessionEvent>()
        backgroundScope.launch { connector.events.collect { events.add(it) } }
        runCurrent()

        val corrupt = loginResultOk.copyOf().also { it[it.size - 1] = 0x7F } // break CRC byte 0x00
        val authJob = launch { connector.connectAndAuthenticate(device, identity, P6AuthMode.LOGIN) }
        runCurrent()
        notifyValue(connection, acknowledge(1))
        runCurrent()
        notifyValue(connection, corrupt) // device SN 5, corrupted
        runCurrent()
        notifyValue(connection, loginResultOk) // retransmission arrives clean
        runCurrent()
        notifyValue(connection, acknowledge(2))
        runCurrent()
        authJob.join()

        assertEquals(P6ConnectionState.AUTHENTICATED, connector.state.value)
        val nak = byteArrayOf(0xAB.toByte(), 0x30, 0, 0, 0, 0, 0x00, 0x05)
        assertEquals(
            1,
            connection.characteristicWriteCalls.count { nak.contentEquals(it.value) },
        )
        assertTrue(events.any { it is P6SessionEvent.InvalidFrame && it.sequenceId == 5 })
        // the corrupt frame was not delivered as data: exactly one Data event (auth result)
        assertEquals(1, events.count { it is P6SessionEvent.Data })
    }

    @Test
    fun `encrypted inbound frames mirror the flag into outgoing sends`() = runTest {
        val connection = connectionWithTransport()
        val connector = P6Connector(connection, backgroundScope, initialWriteLimit = 247)
        completeLogin(connection, connector).join()
        assertFalse(connector.sessionState.inboundEncryptedMirror)

        // the device sends an ENCRYPTED frame: header per fixture, body is the
        // session-encrypted payload (CRC field is over the PLAINTEXT per protocol)
        val plainPayload = byteArrayOf(0x02, 0x00, 0x30, 0x00, 0x01, 0x05)
        val encryptedHeader = byteArrayOf(0xAB.toByte(), 0x40.toByte(), 0x00, 0x06, 0x71, 0xCF.toByte(), 0x00, 0x14)
        notifyValue(connection, encryptedHeader + equivalentCipher.crypt(plainPayload, 20))
        runCurrent()
        assertTrue(connector.sessionState.inboundEncryptedMirror)

        // a plain (2,0x30,[05]) send under SN 3 must now carry the 0x40 flag and
        // its body must be the session-encrypted bytes of the same payload
        var sentSn = -1
        val sendJob = launch {
            sentSn = connector.sendPacket(2, 0x30, byteArrayOf(0x05))
        }
        runCurrent() // frame written, ack pending
        notifyValue(connection, acknowledge(3))
        runCurrent()
        sendJob.join()
        assertEquals(3, sentSn)

        val sentFrame = connection.characteristicWriteCalls.last().value
        assertEquals(0x40, sentFrame[1].toInt() and 0xFF)
        assertTrue(
            equivalentCipher.crypt(sentFrame.copyOfRange(8, sentFrame.size), 3)
                .contentEquals(plainPayload),
        )

        // auth-submits are NEVER encrypted, even with the mirror set (U1/e.java:76-78)
        var submitSn = -1
        val submitJob = launch {
            submitSn = connector.sendPacket(3, P6AuthPackets.KEY_ID_LOGIN_SUBMIT, P6KeyDerivation.userBlock(identity.userId))
        }
        runCurrent()
        notifyValue(connection, acknowledge(4))
        runCurrent()
        submitJob.join()
        assertEquals(4, submitSn)
        val submitWire = connection.characteristicWriteCalls.last().value
        assertEquals(0x00, submitWire[1].toInt())
        assertEquals(P6KeyDerivation.userBlock(identity.userId).toList(), submitWire.copyOfRange(13, 45).toList())
    }
}

