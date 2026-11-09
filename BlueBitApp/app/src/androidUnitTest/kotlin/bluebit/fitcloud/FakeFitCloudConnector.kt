package bluebit.fitcloud

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test fake for [FitCloudConnector]. Records every call and allows tests to
 * drive [states] and stub return values. Suitable for provider unit tests.
 */
class FakeFitCloudConnector : FitCloudConnector {

    data class ConnectCall(
        val address: String,
        val userId: String,
        val bindOrLogin: Boolean,
        val sex: Boolean,
        val age: Int,
        val height: Float,
        val weight: Float,
    )

    val connectCalls = mutableListOf<ConnectCall>()
    var closeCount = 0
        private set
    var autoReconnectMode: FitCloudAutoReconnectMode? = null
        private set

    var connectedDeviceValue: FitCloudConnectedDevice? = null
    var deviceInfoValue: FitCloudDeviceInfo? = null
    var deviceInfoError: Throwable? = null
    var lcdShapeValue: FitCloudLcdShapeInfo? = null
    var lcdShapeError: Throwable? = null
    var dialPushValue: FitCloudDialPushInfo? = null
    var dialPushError: Throwable? = null
    var uiInfoValue: FitCloudUiInfo? = null
    var uiInfoError: Throwable? = null

    private val _states = MutableSharedFlow<FitCloudConnectorState>(extraBufferCapacity = 16)
    val statesFlow: SharedFlow<FitCloudConnectorState> = _states.asSharedFlow()
    override val states: SharedFlow<FitCloudConnectorState> = statesFlow

    /** Test helper: emit a connector state. */
    suspend fun emitState(state: FitCloudConnectorState) {
        _states.emit(state)
    }

    override val connectedDevice: FitCloudConnectedDevice?
        get() = connectedDeviceValue

    override fun connect(
        address: String,
        userId: String,
        bindOrLogin: Boolean,
        sex: Boolean,
        age: Int,
        height: Float,
        weight: Float,
    ) {
        connectCalls += ConnectCall(address, userId, bindOrLogin, sex, age, height, weight)
    }

    override fun close() {
        closeCount++
    }

    override fun setAutoReconnectMode(mode: FitCloudAutoReconnectMode) {
        autoReconnectMode = mode
    }

    override fun deviceInfo(): FitCloudDeviceInfo {
        deviceInfoError?.let { throw it }
        return deviceInfoValue ?: error("deviceInfo not stubbed")
    }

    override suspend fun lcdShapeInfo(): FitCloudLcdShapeInfo {
        lcdShapeError?.let { throw it }
        return lcdShapeValue ?: error("lcdShapeInfo not stubbed")
    }

    override suspend fun dialPushInfo(): FitCloudDialPushInfo {
        dialPushError?.let { throw it }
        return dialPushValue ?: error("dialPushInfo not stubbed")
    }

    override suspend fun uiInfo(): FitCloudUiInfo {
        uiInfoError?.let { throw it }
        return uiInfoValue ?: error("uiInfo not stubbed")
    }
}
