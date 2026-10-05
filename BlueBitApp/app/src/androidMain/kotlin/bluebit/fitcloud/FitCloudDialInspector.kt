package bluebit.fitcloud

import bluebit.core.bluetooth.BleLogTag
import bluebit.core.bluetooth.bleLogWatchdog
import com.topstep.fitcloud.sdk.v2.FcSDK
import com.topstep.fitcloud.sdk.v2.model.config.FcDeviceInfo
import com.topstep.fitcloud.sdk.v2.model.settings.dial.FcDialPushInfo
import com.topstep.fitcloud.sdk.v2.model.settings.dial.FcDialSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.rx3.await

/**
 * Read-only inspection of the connected FitCloud device's identity, capabilities,
 * and watch-face (dial) state. All operations are queries; nothing is pushed,
 * deleted, flashed, or reset.
 */
class FitCloudDialInspector(private val fcSDK: FcSDK) {

    private val _deviceReport = MutableStateFlow<String?>(null)
    val deviceReport: StateFlow<String?> = _deviceReport.asStateFlow()

    private val _dialReport = MutableStateFlow<String?>(null)
    val dialReport: StateFlow<String?> = _dialReport.asStateFlow()

    /** Feature flags that decide which watch-face / UI customization paths exist. */
    private val uiFeatures = listOf(
        "DIAL_PUSH" to FcDeviceInfo.Feature.DIAL_PUSH,
        "DIAL_CUSTOM" to FcDeviceInfo.Feature.DIAL_CUSTOM,
        "DIAL_COMPONENT" to FcDeviceInfo.Feature.DIAL_COMPONENT,
        "SETTING_DIAL_COMPONENT" to FcDeviceInfo.Feature.SETTING_DIAL_COMPONENT,
        "GUI" to FcDeviceInfo.Feature.GUI,
        "DELETE_DIAL" to FcDeviceInfo.Feature.DELETE_DIAL,
        "SPORT_PUSH" to FcDeviceInfo.Feature.SPORT_PUSH,
        "GAME_PUSH" to FcDeviceInfo.Feature.GAME_PUSH,
        "SCREEN_LOCK" to FcDeviceInfo.Feature.SCREEN_LOCK,
        "ALBUM" to FcDeviceInfo.Feature.ALBUM,
        "NOTIFICATION" to FcDeviceInfo.Feature.NOTIFICATION,
        "EXTRA_FIRMWARE_INFO" to FcDeviceInfo.Feature.EXTRA_FIRMWARE_INFO,
    )

    suspend fun queryDeviceInfo() {
        runCatching {
            val config = fcSDK.connector.configFeature()
            val info = config.getDeviceInfo()
            buildString {
                appendLine("Project: ${info.getProject()}")
                appendLine("Patch: ${info.getPatch()}")
                appendLine("Flash: ${info.getFlash()}")
                appendLine("App: ${info.getApp()}")
                appendLine("IC type: ${info.getIcType()}")
                runCatching { config.getExtraFirmwareInfo() }.getOrNull()?.let { extra ->
                    appendLine("GNSS GPS ver: ${extra.gnssGpsVersion}")
                    appendLine("4G modem ver: ${extra.modem4GVersion}")
                }
                val lcdShape = runCatching {
                    fcSDK.connector.settingsFeature().requestLcdShape().await()
                }.getOrNull()
                if (lcdShape != null) {
                    val s = lcdShape.shape
                    appendLine(
                        "LCD ${lcdShape.lcd}: ${s.width}x${s.height}" +
                            (if (s.isShapeCircle) " circle" else " rect corners=${s.corners}")
                    )
                } else {
                    appendLine("LCD shape: not reported")
                }
                appendLine("-- UI feature flags --")
                uiFeatures.forEach { (name, flag) ->
                    appendLine("$name: ${info.isSupportFeature(flag)}")
                }
            }
        }.onSuccess { report ->
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud device info:\n$report")
            _deviceReport.value = report.trim()
        }.onFailure { e ->
            val msg = "Device info query failed: ${e.message}"
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, msg)
            _deviceReport.value = msg
        }
    }

    suspend fun queryDialInfo() {
        runCatching {
            val settings = fcSDK.connector.settingsFeature()
            val pushInfo: FcDialPushInfo? = runCatching {
                settings.requestDialPushInfo().await()
            }.getOrNull()
            val uiInfo = runCatching { settings.requestUiInfo().await() }.getOrNull()
            buildString {
                if (uiInfo != null) {
                    appendLine("UI num: ${uiInfo.uiNum}, serial: ${uiInfo.uiSerial}, style: ${uiInfo.styleIndex}")
                } else {
                    appendLine("UI info: not reported")
                }
                if (pushInfo == null) {
                    appendLine("Dial push info: not reported (DIAL_PUSH likely unsupported)")
                } else {
                    val shape = pushInfo.shape
                    if (shape != null) {
                        appendLine(
                            "Dial shape: ${shape.width}x${shape.height}" +
                                (if (shape.isShapeCircle) " circle" else " rect corners=${shape.corners}")
                        )
                    } else {
                        appendLine("Dial shape: unknown (SDK too old)")
                    }
                    appendLine("Tool version: ${pushInfo.toolVersion}")
                    appendLine("Current dial num: ${pushInfo.currentDialNum}")
                    appendLine("Current bin version: ${pushInfo.currentBinVersion}")
                    appendLine("Current position: ${pushInfo.currentPosition}")
                    val spaces = pushInfo.dialSpaces
                    if (spaces.isNullOrEmpty()) {
                        appendLine("Dial spaces: none (single fixed dial)")
                    } else {
                        appendLine("-- Dial spaces (${spaces.size}) --")
                        spaces.forEachIndexed { index, space ->
                            appendLine(formatSpace(index, space))
                        }
                    }
                    appendLine("Note: SDK has no select-installed-dial API; pushing a dial replaces and activates it.")
                }
            }
        }.onSuccess { report ->
            bleLogWatchdog.info(BleLogTag.PROVIDER_CONNECTOR, "FitCloud dial info:\n$report")
            _dialReport.value = report.trim()
        }.onFailure { e ->
            val msg = "Dial info query failed: ${e.message}"
            bleLogWatchdog.error(BleLogTag.PROVIDER_CONNECTOR, msg)
            _dialReport.value = msg
        }
    }

    private fun formatSpace(index: Int, space: FcDialSpace): String {
        val type = when (space.dialType.toInt()) {
            FcDialSpace.DIAL_TYPE_NONE.toInt() -> "none"
            FcDialSpace.DIAL_TYPE_NORMAL.toInt() -> "normal"
            FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_WHITE.toInt() -> "custom-white"
            FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_BLACK.toInt() -> "custom-black"
            FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_YELLOW.toInt() -> "custom-yellow"
            FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_GREEN.toInt() -> "custom-green"
            FcDialSpace.DIAL_TYPE_CUSTOM_STYLE_GRAY.toInt() -> "custom-gray"
            else -> "type-${space.dialType}"
        }
        val sb = StringBuilder()
        sb.append("[$index] num=${space.dialNum} binVer=${space.binVersion} type=$type")
        sb.append(" pushable=${space.isPushable} size=${space.spaceSize}KB binFlag=${space.binFlag}")
        space.components?.forEachIndexed { ci, c ->
            sb.append(
                " | comp$ci style=${c.styleCurrent}/${c.styleCount}" +
                    " @(${c.positionX},${c.positionY}) ${c.width}x${c.height}"
            )
        }
        return sb.toString()
    }
}
