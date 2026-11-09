package bluebit.fitcloud

import com.topstep.fitcloud.sdk.v2.FcConnector
import com.topstep.wearkit.base.connector.AutoReconnectMode
import com.topstep.wearkit.base.connector.ConnectorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await

/**
 * Production [FitCloudConnector] backed by the WearKit `FcConnector` obtained
 * from the `FcSDK` singleton. Pure delegation — no behaviour changes: the same
 * connector instance, the same `bindOrLogin` semantics (true=BIND,
 * false=LOGIN), and the same SDK-managed auth/retry lifecycle.
 */
class SdkFitCloudConnector(
    private val sdkConnector: FcConnector,
) : FitCloudConnector {

    override val states: Flow<FitCloudConnectorState> =
        sdkConnector.observerConnectorState()
            .asFlow()
            .map { it.toFitCloudConnectorState() }

    override val connectedDevice: FitCloudConnectedDevice?
        get() = runCatching {
            sdkConnector.getDevice()?.let {
                FitCloudConnectedDevice(address = it.address, name = it.name)
            }
        }.getOrNull()

    override fun connect(
        address: String,
        userId: String,
        bindOrLogin: Boolean,
        sex: Boolean,
        age: Int,
        height: Float,
        weight: Float,
    ) {
        sdkConnector.connect(address, userId, bindOrLogin, sex, age, height, weight)
    }

    override fun close() {
        sdkConnector.close()
    }

    override fun setAutoReconnectMode(mode: FitCloudAutoReconnectMode) {
        sdkConnector.setAutoReconnectMode(mode.toSdkAutoReconnectMode())
    }

    override fun deviceInfo(): FitCloudDeviceInfo {
        val info = sdkConnector.configFeature().getDeviceInfo()
        return FitCloudDeviceInfo(
            project = info.getProject(),
            patch = info.getPatch(),
            flash = info.getFlash(),
            app = info.getApp(),
            icType = info.getIcType().toString(),
        )
    }

    override suspend fun lcdShapeInfo(): FitCloudLcdShapeInfo {
        val info = sdkConnector.settingsFeature().requestLcdShape().await()
        val shape = info.shape
        return FitCloudLcdShapeInfo(
            lcd = info.lcd,
            width = shape.width,
            height = shape.height,
            isCircle = shape.isShapeCircle,
            corners = shape.corners,
        )
    }

    override suspend fun dialPushInfo(): FitCloudDialPushInfo {
        val info = sdkConnector.settingsFeature().requestDialPushInfo().await()
        return FitCloudDialPushInfo(
            currentDialNum = info.currentDialNum,
            currentBinVersion = info.currentBinVersion,
            currentPosition = info.currentPosition,
            toolVersion = info.toolVersion,
            dialSpaceCount = info.dialSpaces?.size ?: 0,
        )
    }

    override suspend fun uiInfo(): FitCloudUiInfo {
        val info = sdkConnector.settingsFeature().requestUiInfo().await()
        return FitCloudUiInfo(
            uiNum = info.uiNum,
            uiSerial = info.uiSerial,
            styleIndex = info.styleIndex,
        )
    }
}

private fun ConnectorState.toFitCloudConnectorState(): FitCloudConnectorState = when (this) {
    ConnectorState.DISCONNECTED -> FitCloudConnectorState.DISCONNECTED
    ConnectorState.PRE_CONNECTING -> FitCloudConnectorState.PRE_CONNECTING
    ConnectorState.CONNECTING -> FitCloudConnectorState.CONNECTING
    ConnectorState.PRE_CONNECTED -> FitCloudConnectorState.PRE_CONNECTED
    ConnectorState.CONNECTED -> FitCloudConnectorState.CONNECTED
}

private fun FitCloudAutoReconnectMode.toSdkAutoReconnectMode(): AutoReconnectMode = when (this) {
    FitCloudAutoReconnectMode.NEVER -> AutoReconnectMode.NEVER
    FitCloudAutoReconnectMode.LOW_POWER -> AutoReconnectMode.LOW_POWER
    FitCloudAutoReconnectMode.BALANCED -> AutoReconnectMode.BALANCED
    FitCloudAutoReconnectMode.LOW_LATENCY -> AutoReconnectMode.LOW_LATENCY
}
