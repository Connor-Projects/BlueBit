package bluebit.core.bluetooth

data class BleNotification(
    val serviceUuid: String,
    val characteristicUuid: String,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleNotification) return false
        return serviceUuid == other.serviceUuid &&
            characteristicUuid == other.characteristicUuid &&
            value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}
