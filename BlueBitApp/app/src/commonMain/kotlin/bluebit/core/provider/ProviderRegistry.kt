package bluebit.core.provider

import bluebit.core.bluetooth.BleScanResult
import bluebit.core.devices.DeviceProfile

class ProviderRegistry(providers: List<WearableProvider>) {
    private val providers = providers.toList()

    fun resolve(scanResult: BleScanResult): WearableProvider? =
        providers.firstOrNull { it.canHandle(scanResult) }

    fun allScanFilters(): List<ProviderScanFilter> =
        providers.map { it.scanFilter() }

    fun findById(id: String): WearableProvider? =
        providers.firstOrNull { it.id == id }

    fun all(): List<WearableProvider> = providers.toList()

    fun identify(scanResult: BleScanResult): Pair<WearableProvider, DeviceProfile>? {
        val provider = resolve(scanResult) ?: return null
        val profile = provider.identify(scanResult) ?: return null
        return provider to profile
    }
}
