package bluebit.core.ui.screens

data class UiDevice(
    val name: String,
    val address: String,
    val status: String,
    val battery: Int?,
    val deviceType: String,
    val lastSeen: String,
    val rssi: Int? = null,
    val recognised: Boolean = false,
    val manufacturerDataSummary: String = "",
    val providerId: String = "",
    val capabilities: bluebit.core.provider.DeviceCapabilities = bluebit.core.provider.DeviceCapabilities(),
)

sealed class Screen {
    data object Home : Screen()
    data object Devices : Screen()
    data object Activity : Screen()
    data object Settings : Screen()
    data class DeviceDetail(val deviceName: String, val deviceAddress: String) : Screen()
}
