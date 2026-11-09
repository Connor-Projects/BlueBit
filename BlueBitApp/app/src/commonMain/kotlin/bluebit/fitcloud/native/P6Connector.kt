package bluebit.fitcloud.native

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** UUIDs verified for the P6 transport service (`p150kd/j.java:155-161` + GATT dump). */
object P6Uuids {
    const val SERVICE = "000001ff-3c17-d293-8e48-14fe2e4da212"
    const val WRITE_CHARACTERISTIC = "0000ff02-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_CHARACTERISTIC = "0000ff03-0000-1000-8000-00805f9b34fb"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Android `BluetoothGattCharacteristic#WRITE_TYPE_DEFAULT`. */
    const val WRITE_TYPE_DEFAULT = 2

    /** Enable-notify value for the CCCD write (not hardware-verified yet [A]). */
    val CCCD_NOTIFY_ENABLE = byteArrayOf(0x01, 0x00)
}

/** Connection lifecycle of the native P6 stack. */
enum class P6ConnectionState {
    DISCONNECTED,
    CONNECTING,
    SERVICE_DISCOVERY,
    SUBSCRIBING,
    AUTHENTICATING,
    AUTHENTICATED,
    FAILED,
}

/** Errors surfaced by the connector; [resultCode] carries auth result codes when known. */
class P6ProtocolException(message: String, val resultCode: Int = -1) : Exception(message)

/** Events a session consumer can observe (config pushes, protocol data, failures). */
sealed interface P6SessionEvent {
    data class Data(val packet: P6ProtocolPacket) : P6SessionEvent

    data class InvalidFrame(val sequenceId: Int, val reason: String) : P6SessionEvent

    data class Ended(val cause: String) : P6SessionEvent
}

/**
 * Independent (non-SDK) FitCloud transport session over a [BleConnection].
 *
 * Behaviour mirrors the decompiled `Fc#ProtocolQueue` stack (`p363zd/e.java`,
 * `i9/b.java`):
 * - connect -> discover -> verify transport service characteristics -> subscribe
 *   `ff03` with an explicit CCCD write -> derive session cipher -> auth probe +
 *   submit -> collect config pushes,
 * - send = frame with SN + CRC (+ AES when the session mirror flags it), up to 3
 *   attempts with a 500 ms ack deadline each; the same SN/bytes are retried, and
 *   sending waits while an inbound frame is reassembling,
 * - receive = reassemble/decrypt/verify, deliver on [events], ACK every valid data
 *   frame with flags 0x10, NAK corrupt frames with flags 0x30, dedup on SN but
 *   re-ACK duplicates.
 *
 * Auth sequencing is the hardware-verified submit-first flow (2026-11-04 P6
 * capture): the auth submit `(3,0x11|0x13, uidBlock)` is sent first, the device
 * answers with an ACK and the result push `(3,0x14,[code])` within ~20 ms, and
 * `data[0]` carries the result code. PROBES `(3,0x12|0x14)` are ACKed silently
 * by this firmware/state — the decompiled probe-first flow (`p026be/f.java`)
 * never produces a result push on hardware, so the submit carries the auth. The
 * submit completes on its own ack; the result pushes define the outcome.
 *
 * The connector itself is logging-free; callers pass a [log] lambda to capture
 * diagnostics. The negotiated ATT MTU is supplied via the [connectAndAuthenticate]
 * configureSession hook by the android caller after `requestMtu` (reference
 * splits at the negotiated MTU).
 */
class P6Connector(
    private val connection: BleConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val log: (String) -> Unit = { },
    /**
     * ATT write ceiling for frame writes and encrypted chunking. The reference
     * acts as if this is the negotiated ATT MTU; the connectAndAuthenticate
     * configureSession hook replaces it after MTU negotiation (e.g. `mtu - 3`);
     * desktop tests pass a fixed value and fall back to [DEFAULT_WRITE_LIMIT]
     * when no MTU context exists.
     */
    initialWriteLimit: Int = DEFAULT_WRITE_LIMIT,
) {

    private val _state = MutableStateFlow(P6ConnectionState.DISCONNECTED)
    val state: StateFlow<P6ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<P6SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<P6SessionEvent> = _events.asSharedFlow()

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics.asStateFlow()

    val sessionState = P6SessionState()
    private var cipher: P6SessionCipher? = null

    /**
     * ATT write ceiling for frame writes and encrypted chunking; replaced by the
     * connectAndAuthenticate configureSession hook once the caller has negotiated
     * the MTU (e.g. `negotiatedMtu - 3`).
     */
    var sessionWriteLimit: Int = initialWriteLimit
        private set

    /** Auth-result and push packets consumed by the auth state machine. */
    private val dataChannel = Channel<P6ProtocolPacket>(capacity = Channel.UNLIMITED)
    private var receiverJob: kotlinx.coroutines.Job? = null

    /**
     * Run the full link-up for [device]: connect, discover, verify the transport
     * service/characteristics, subscribe, then authenticate [identity] with [mode].
     * Throws [P6ProtocolException] on any failure.
     *
     * [configureSession] runs after the ACL link is up and before service
     * discovery, so Android callers can negotiate the ATT MTU (the reference
     * negotiates 247) and return the resulting write ceiling (`mtu - 3`).
     * Returning [DEFAULT_WRITE_LIMIT] (or anything else) is logged — the caller
     * decides what failure means; the connector never fakes a negotiated MTU.
     */
    suspend fun connectAndAuthenticate(
        device: BleDevice,
        identity: P6Identity,
        mode: P6AuthMode,
        configureSession: suspend () -> Int = { DEFAULT_WRITE_LIMIT },
    ) {
        sessionState.reset()
        val derived = P6KeyDerivation.derive(identity)
        if (derived == null) {
            throwProtocol("cannot parse MAC ${identity.macAddress}")
        }
        cipher = derived
        _state.value = P6ConnectionState.CONNECTING
        try {
            connection.connect(device)
            sessionWriteLimit = configureSession()
            logLine("session write limit = $sessionWriteLimit")
            _state.value = P6ConnectionState.SERVICE_DISCOVERY
            connection.discoverServices()
            assertTransportPresent(connection.discoveredServices.value)
            _state.value = P6ConnectionState.SUBSCRIBING
            connection.setCharacteristicNotification(P6Uuids.SERVICE, P6Uuids.NOTIFY_CHARACTERISTIC, true)
            connection.writeDescriptor(
                P6Uuids.SERVICE,
                P6Uuids.NOTIFY_CHARACTERISTIC,
                P6Uuids.CCCD,
                P6Uuids.CCCD_NOTIFY_ENABLE,
            )
            startReceiver()
            authenticate(identity, mode)
            _state.value = P6ConnectionState.AUTHENTICATED
            logLine("link up, $mode authentication complete")
        } catch (failure: Throwable) {
            receiverJob?.cancel()
            _state.value = P6ConnectionState.FAILED
            emit(P6SessionEvent.Ended("link failed: ${failure.message}"))
            throw failure
        }
    }

    /** Disconnect and clean up (reference nulls the session crypto at this point). */
    suspend fun disconnect() {
        receiverJob?.cancel()
        receiverJob = null
        sessionState.reset()
        cipher = null
        if (connection.state.value != ConnectionState.Disconnected) {
            runCatching { connection.disconnect() }
                .onFailure { logLine("disconnect error: ${it.message}") }
        }
        _state.value = P6ConnectionState.DISCONNECTED
        emit(P6SessionEvent.Ended("disconnected"))
    }

    /** Send one protocol packet; returns the transport SN it was assigned. */
    suspend fun sendPacket(cmdId: Int, keyId: Int, data: ByteArray?): Int =
        sendPayload(P6ProtocolPacketCodec.serialize(cmdId, keyId, data))

    /**
     * Core send loop: frame the payload, attempt up to [MAX_SEND_ATTEMPTS] writes
     * waiting [ACK_WAIT_MS] for an accepted ACK each time. Retries reuse the same
     * SN and the same bytes (reference `p363zd/e.java:245-266`).
     */
    private suspend fun sendPayload(payload: ByteArray): Int {
        val localCipher = cipher
        val sequenceId = sessionState.claimTxSequenceId()

        // Auth submits (payload[0]==3, payload[2] in {0x11,0x13}) are always sent
        // plaintext (`U1/e.java:76-78`). P6TransportPacket.encode force-clears the
        // flag for them too; the guard here keeps them off the encrypted path so
        // their bytes are never AES-transformed before framing.
        val isAuthSubmit = payload.size >= 3 && payload[0].toInt() == 3 &&
            (payload[2].toInt() == P6AuthPackets.KEY_ID_BIND_SUBMIT ||
                payload[2].toInt() == P6AuthPackets.KEY_ID_LOGIN_SUBMIT)

        val encryptedOutgoing = localCipher != null &&
            sessionState.inboundEncryptedMirror &&
            !isAuthSubmit
        val frame = if (encryptedOutgoing) {
            P6TransportPacket.encodeEncrypted(
                payload,
                sequenceId,
                localCipher!!,
                maxWriteBytes = sessionWriteLimit,
            )
        } else {
            P6TransportPacket.encode(payload, sequenceId, encrypted = false)
        }

        var lastError: String? = null
        try {
            for (attempt in 1..MAX_SEND_ATTEMPTS) {
                sessionState.beginPendingSend(sequenceId)
                waitUntilSendable()
                lastError = try {
                    for (piece in P6TransportPacket.splitForWrite(frame, sessionWriteLimit)) {
                        connection.writeCharacteristic(
                            P6Uuids.SERVICE,
                            P6Uuids.WRITE_CHARACTERISTIC,
                            piece,
                            P6Uuids.WRITE_TYPE_DEFAULT,
                        )
                    }
                    null
                } catch (error: Exception) {
                    "write failed on attempt $attempt: ${error.message}"
                }
                if (lastError == null) {
                    when (sessionState.awaitPendingAck(ACK_WAIT_MS)) {
                        true -> return sequenceId
                        false -> lastError = "device NAKed SN $sequenceId (attempt $attempt)"
                        null -> lastError = "ack wait timed out for SN $sequenceId (attempt $attempt)"
                    }
                }
            }
        } finally {
            sessionState.endPendingSend()
        }
        throw P6ProtocolException(
            "SN $sequenceId failed after $MAX_SEND_ATTEMPTS attempts ($lastError)",
        )
    }

    /** Reference pauses sending while the decoder still reassembles an inbound frame. */
    private suspend fun waitUntilSendable() {
        val completed = withTimeoutOrNull(REASSEMBLY_WAIT_LIMIT_MS) {
            while (sessionState.decoder.reassembling) delay(50)
            true
        }
        if (completed != true) {
            logLine("waited ${REASSEMBLY_WAIT_LIMIT_MS}ms for inbound reassembly; giving up")
        }
    }

    private fun assertTransportPresent(services: List<BleService>) {
        val service = services.firstOrNull { it.uuid.equals(P6Uuids.SERVICE, ignoreCase = true) }
        val writePresent = service?.characteristics
            ?.any { it.uuid.equals(P6Uuids.WRITE_CHARACTERISTIC, ignoreCase = true) } == true
        val notifyPresent = service?.characteristics
            ?.any { it.uuid.equals(P6Uuids.NOTIFY_CHARACTERISTIC, ignoreCase = true) } == true
        if (service == null || !writePresent || !notifyPresent) {
            throw P6ProtocolException(
                "transport service/characteristics missing after discovery " +
                    "(service=${service?.uuid}, write=$writePresent, notify=$notifyPresent)",
            )
        }
    }

    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob = scope.launch {
            connection.notifications.collect { notification ->
                if (notification.characteristicUuid.equals(P6Uuids.NOTIFY_CHARACTERISTIC, ignoreCase = true)) {
                    handleInbound(notification)
                }
            }
        }
    }

    private suspend fun handleInbound(notification: BleNotification) {
        val localCipher = cipher
        when (val event = sessionState.decoder.feed(notification.value, localCipher)) {
            is P6TransportDecoder.Event.Pending -> Unit

            is P6TransportDecoder.Event.AckEvent -> {
                val matched = sessionState.onAck(event.ack)
                logLine("ack sn=${event.ack.sequenceId} failed=${event.ack.failed} matched=$matched")
            }

            is P6TransportDecoder.Event.Complete -> {
                // Mirror the encrypted flag the inbound frame carried; the reference
                // shares this state between RX decryption and TX encryption
                // (`U1.e.f12360g`, set at `U1/e.java:167`, consulted at line 79).
                sessionState.inboundEncryptedMirror = event.encrypted
                writeAck(event.sequenceId, failed = false)
                if (sessionState.onDataDelivered(event.sequenceId)) {
                    val packet = P6ProtocolPacketCodec.parse(event.payload)
                    if (packet != null) {
                        dataChannel.trySend(packet)
                        emit(P6SessionEvent.Data(packet))
                    } else {
                        logLine("unparseable payload sn=${event.sequenceId}")
                    }
                } else {
                    // Duplicate SN: reference re-ACKs without re-delivery.
                    logLine("duplicate sn=${event.sequenceId} re-acked")
                }
            }

            is P6TransportDecoder.Event.Invalid -> {
                // ACK only when the frame's own SN was readable (header-level
                // failures have nothing meaningful to answer).
                if (event.sequenceId >= 0) writeAck(event.sequenceId, failed = true)
                logLine("invalid sn=${event.sequenceId} (${event.reason})")
                emit(P6SessionEvent.InvalidFrame(event.sequenceId, event.reason))
            }
        }
    }

    /** Send an ACK frame for the received [sequenceId]; [failed] = NAK flags 0x30. */
    private suspend fun writeAck(sequenceId: Int, failed: Boolean) {
        runCatching {
            connection.writeCharacteristic(
                P6Uuids.SERVICE,
                P6Uuids.WRITE_CHARACTERISTIC,
                P6TransportPacket.buildAck(sequenceId, failed),
                P6Uuids.WRITE_TYPE_DEFAULT,
            )
        }.onFailure { logLine("ack write failed: ${it.message}") }
    }

    /** Auth handshake — the hardware-verified submit-first sequencing. */
    private suspend fun authenticate(identity: P6Identity, mode: P6AuthMode) {
        _state.value = P6ConnectionState.AUTHENTICATING

        // The auth submit goes out FIRST, as SN 1 (2026-11-04 hardware capture:
        // device acks it and pushes `(3,0x14,[code])` immediately; probes alone
        // are acked with no result ever pushed on this firmware/state).
        val submitPayload = P6AuthPackets.submit(identity.userId, bind = mode == P6AuthMode.BIND)
        sendPayload(submitPayload)

        // Await the result push (the device's data push after our submit), 15 s
        // outer window per the reference `p026be/f` timeout.
        val resultPacket = try {
            withTimeout(AUTH_RESULT_TIMEOUT_MS) { dataChannel.receive() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw P6ProtocolException("auth result not received in ${AUTH_RESULT_TIMEOUT_MS}ms")
        }
        val data = resultPacket.firstData
        val code = if (data == null || data.isEmpty()) -1 else data[0].toInt() and 0xFF
        logLine("${mode.name.lowercase()}Auth result:${P6AuthPackets.describeResult(code)}")
        when {
            code == P6AuthPackets.RESULT_OK -> Unit
            code == P6AuthPackets.RESULT_LOGIN_BIND_REQUIRED && mode == P6AuthMode.LOGIN ->
                throw P6ProtocolException("device returned doLoginAuth result:1; bind step required", code)
            else -> throw P6ProtocolException(
                "auth submit returned ${P6AuthPackets.describeResult(code)}",
                code,
            )
        }
    }

    /** Mark the session failed and surface the end event without throwing. */
    private fun throwProtocol(message: String): Nothing {
        receiverJob?.cancel()
        _state.value = P6ConnectionState.FAILED
        emit(P6SessionEvent.Ended(message))
        throw P6ProtocolException(message)
    }

    private fun emit(event: P6SessionEvent) {
        _events.tryEmit(event)
    }

    private fun logLine(message: String) {
        _diagnostics.value = message
        log(message)
    }

    companion object {
        /** Reference send loop: three attempts, 500 ms ack deadline each. */
        const val MAX_SEND_ATTEMPTS = 3
        const val ACK_WAIT_MS = 500L
        const val AUTH_RESULT_TIMEOUT_MS = 15_000L
        const val REASSEMBLY_WAIT_LIMIT_MS = 5_000L

        /**
         * Fallback write ceiling when the caller cannot negotiate an MTU. Matches
         * the largest legal single ATT write (MTU 520-3). Real callers pass the
         * negotiated value (P6 negotiates 247 on the reference app session).
         */
        const val DEFAULT_WRITE_LIMIT = 517
    }
}
