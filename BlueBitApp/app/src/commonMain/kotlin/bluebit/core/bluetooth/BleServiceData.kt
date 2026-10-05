package bluebit.core.bluetooth

data class BleServiceData(
    val deviceTypeId: Int,
    val secret: ByteArray
) {
    companion object {
        fun parse(payload: ByteArray): BleServiceData? {
            if (payload.size != 8) return null
            val deviceTypeId = payload[0].toInt() and 0xFF
            val secret = payload.copyOfRange(1, 8)
            return BleServiceData(deviceTypeId, secret)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleServiceData) return false
        return deviceTypeId == other.deviceTypeId && secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int {
        var result = deviceTypeId
        result = 31 * result + secret.contentHashCode()
        return result
    }

    override fun toString(): String =
        "BleServiceData(deviceTypeId=$deviceTypeId, secret=<redacted>)"
}
