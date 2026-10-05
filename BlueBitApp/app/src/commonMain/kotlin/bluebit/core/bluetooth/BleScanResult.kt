package bluebit.core.bluetooth

data class BleScanResult(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val serviceData: Map<String, ByteArray>,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val rawBytes: ByteArray? = null,
    val timestampNanos: Long
) {
    companion object {
        const val DEVICE_INFORMATION_SERVICE_UUID = "0000180A-0000-1000-8000-00805F9B34FB"
    }

    fun deviceInformationServicePayload(): ByteArray? = serviceData[DEVICE_INFORMATION_SERVICE_UUID]
}
