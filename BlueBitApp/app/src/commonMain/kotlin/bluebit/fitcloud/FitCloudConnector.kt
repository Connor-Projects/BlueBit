package bluebit.fitcloud

import kotlinx.coroutines.flow.Flow

/**
 * SDK-free abstraction over the FitCloud connector, exposing only the
 * operations [bluebit.fitcloud.AndroidFitCloudProvider] actually uses.
 *
 * The production implementation ([SdkFitCloudConnector]) delegates to the
 * WearKit `FcConnector`; tests use [bluebit.fitcloud.FakeFitCloudConnector].
 * This interface intentionally mirrors SDK semantics 1:1 — it must never
 * expose or select `AuthMode.AUTO`, and `bindOrLogin` keeps its meaning:
 * `true` = BIND, `false` = LOGIN.
 */
interface FitCloudConnector {

    /** Connection lifecycle states, mirroring WearKit `ConnectorState`. */
    val states: Flow<FitCloudConnectorState>

    /** Bluetooth device currently attached to the connector, if any. */
    val connectedDevice: FitCloudConnectedDevice?

    /**
     * Request a connection. Fire-and-forget: the SDK accepts the call and the
     * outcome arrives asynchronously via [states]. `bindOrLogin` selects the
     * auth mode (true=BIND, false=LOGIN) together with [userId].
     */
    fun connect(
        address: String,
        userId: String,
        bindOrLogin: Boolean,
        sex: Boolean,
        age: Int,
        height: Float,
        weight: Float,
    )

    /** Close the connector and any in-flight connection attempt. */
    fun close()

    /** Set the SDK transport-level auto-reconnect policy. */
    fun setAutoReconnectMode(mode: FitCloudAutoReconnectMode)

    /** Device info for the connected device. Throws on SDK failure (callers wrap). */
    fun deviceInfo(): FitCloudDeviceInfo

    /** LCD shape info for the connected device. */
    suspend fun lcdShapeInfo(): FitCloudLcdShapeInfo

    /** Watch-face (dial) push info for the connected device. */
    suspend fun dialPushInfo(): FitCloudDialPushInfo

    /** UI customization info for the connected device. */
    suspend fun uiInfo(): FitCloudUiInfo
}

/** Mirrors `com.topstep.wearkit.base.connector.ConnectorState` (5 values). */
enum class FitCloudConnectorState {
    DISCONNECTED,
    PRE_CONNECTING,
    CONNECTING,
    PRE_CONNECTED,
    CONNECTED,
}

/** Mirrors `com.topstep.wearkit.base.connector.AutoReconnectMode`. */
enum class FitCloudAutoReconnectMode {
    NEVER,
    LOW_POWER,
    BALANCED,
    LOW_LATENCY,
}

/** Bluetooth identity of the connected device (SDK-free). */
data class FitCloudConnectedDevice(
    val address: String,
    val name: String?,
)

/** Device-info fields used by pairing records and the device-info diagnostic. */
data class FitCloudDeviceInfo(
    val project: String?,
    val patch: String?,
    val flash: String?,
    val app: String?,
    val icType: String,
)

/** LCD shape fields used by the provider's read-only diagnostic report. */
data class FitCloudLcdShapeInfo(
    val lcd: Int,
    val width: Int,
    val height: Int,
    val isCircle: Boolean,
    val corners: Int,
)

/** Watch-face push fields used by the provider's read-only diagnostic report. */
data class FitCloudDialPushInfo(
    val currentDialNum: Int,
    val currentBinVersion: Int,
    val currentPosition: Int,
    val toolVersion: String,
    val dialSpaceCount: Int,
)

/** UI info fields used by the provider's read-only diagnostic report. */
data class FitCloudUiInfo(
    val uiNum: Int,
    val uiSerial: Int,
    val styleIndex: Int,
)
