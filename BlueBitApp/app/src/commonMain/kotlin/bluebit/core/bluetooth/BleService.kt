package bluebit.core.bluetooth

data class BleService(
    val uuid: String,
    val characteristics: List<BleCharacteristic>
)

data class BleCharacteristic(
    val uuid: String,
    val properties: Int
)
