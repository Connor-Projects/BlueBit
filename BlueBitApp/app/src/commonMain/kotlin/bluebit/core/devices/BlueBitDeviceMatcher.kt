package bluebit.core.devices

import bluebit.core.bluetooth.BleScanResult
import bluebit.core.bluetooth.BleServiceData
import bluebit.core.devices.DeviceProfile
import bluebit.core.devices.DeviceProfileRepository

class BlueBitDeviceMatcher(
    private val profileRepository: DeviceProfileRepository
) {
    fun match(scanResult: BleScanResult): DeviceProfile? {
        return matchByServiceData(scanResult) ?: matchByName(scanResult)
    }

    fun matchByServiceData(scanResult: BleScanResult): DeviceProfile? {
        val payload = scanResult.deviceInformationServicePayload() ?: return null
        val serviceData = BleServiceData.parse(payload) ?: return null
        return profileRepository.findByIdentifier(serviceData.deviceTypeId)
    }

    fun matchByName(scanResult: BleScanResult): DeviceProfile? {
        val name = scanResult.name ?: return null
        return profileRepository.findByName(name)
    }
}
