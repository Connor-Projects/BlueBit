package bluebit.core.devices

data class DeviceProfile(
    val id: Int,
    val name: String,
    val identifier: Int,
    val pairingMethod: PairingMethod,
    val devicePhylum: DevicePhylum,
    val supportsBluetooth: Boolean,
    val supportsWiFi: Boolean
)
