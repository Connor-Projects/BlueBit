package bluebit.core.bluetooth

object DeviceServiceDataParser {
    fun parse(scanResult: BleScanResult): BleServiceData? {
        val payload = scanResult.serviceData[BleScanResult.DEVICE_INFORMATION_SERVICE_UUID]
            ?: return null
        return BleServiceData.parse(payload)
    }
}
