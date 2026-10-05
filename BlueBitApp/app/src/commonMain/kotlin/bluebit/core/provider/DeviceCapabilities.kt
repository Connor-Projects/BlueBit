package bluebit.core.provider

data class DeviceCapabilities(
    val supportsSteps: Boolean = false,
    val supportsHeartRate: Boolean = false,
    val supportsSleep: Boolean = false,
    val supportsBatteryQuery: Boolean = false,
    val supportsOta: Boolean = false,
    val supportsNotifications: Boolean = false,
    val supportsWeather: Boolean = false,
    val diagnostics: List<ProviderDiagnostic> = emptyList(),
)
