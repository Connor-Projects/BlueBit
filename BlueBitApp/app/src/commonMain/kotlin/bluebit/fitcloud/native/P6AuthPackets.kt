package bluebit.fitcloud.native

/**
 * FitCloud authentication packets and result codes.
 *
 * Sequencing verified against `p026be/f.java` (`doBindAuth`/`doLoginAuth`),
 * the response handlers `Fd/g.java:222–248` (bind result) and `Id/h.java:30–66`
 * (login result), plus the user-block builder `p334x9/b.java:452–460`.
 */
object P6AuthPackets {

    /** Auth command id (`a((byte) 3, ...)`, always with cfgId 3). */
    const val CFG_ID = 3

    /** BIND probe: ask device to prepare binding (`p026be/f.java:115`). */
    const val KEY_ID_BIND_PROBE = 0x12

    /** BIND submit: send the user-id block (`p334x9/b.java:459`, `z7=true`). */
    const val KEY_ID_BIND_SUBMIT = 0x11

    /** LOGIN probe (`p026be/f.java:122`). */
    const val KEY_ID_LOGIN_PROBE = 0x14

    /** LOGIN submit: send the user-id block (`z7=false`). */
    const val KEY_ID_LOGIN_SUBMIT = 0x13

    /**
     * Result codes in the data of the reply to the probe packets
     * (`Fd/g.java:229–248`, `Id/h.java:45–53`).
     */
    const val RESULT_OK = 0
    const val RESULT_LOGIN_BIND_REQUIRED = 1
    const val RESULT_BIND_ERROR_SESSION = 2
    const val RESULT_BIND_ERROR_PROTOCOL = 3 // byte 3 mapped from result 3 by the reference
    const val RESULT_BIND_ERROR_STATE = 5

    /** Build the `(3, BIND_PROBE)` empty auth probe packet. */
    fun bindProbe(): ByteArray = P6ProtocolPacketCodec.serialize(CFG_ID, KEY_ID_BIND_PROBE, null)

    /** Build the `(3, LOGIN_PROBE)` empty auth probe packet. */
    fun loginProbe(): ByteArray = P6ProtocolPacketCodec.serialize(CFG_ID, KEY_ID_LOGIN_PROBE, null)

    /**
     * Build the submit packet carrying the 32-byte zero-padded user-id block
     * (`p334x9.b.k()`); [bind] selects the keyId `0x11` / `0x13`. These frames are
     * always transmitted plaintext — `P6TransportPacket.encode` force-clears their
     * encryption flag.
     */
    fun submit(userId: String, bind: Boolean): ByteArray =
        P6ProtocolPacketCodec.serialize(
            CFG_ID,
            if (bind) KEY_ID_BIND_SUBMIT else KEY_ID_LOGIN_SUBMIT,
            P6KeyDerivation.userBlock(userId),
        )

    /** Human-readable result code, mirroring the reference's doBindAuth/doLoginAuth logs. */
    fun describeResult(code: Int): String = when (code) {
        RESULT_OK -> "0"
        RESULT_LOGIN_BIND_REQUIRED -> "1 (bind required)"
        RESULT_BIND_ERROR_SESSION -> "2 (session error)"
        RESULT_BIND_ERROR_PROTOCOL -> "3 (protocol error)"
        RESULT_BIND_ERROR_STATE -> "5 (state error)"
        else -> "$code (unknown)"
    }
}

/** Caller-selected authentication mode for a session. */
enum class P6AuthMode { BIND, LOGIN }
