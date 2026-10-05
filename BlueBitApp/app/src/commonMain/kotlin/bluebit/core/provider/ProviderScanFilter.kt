package bluebit.core.provider

data class ProviderScanFilter(
    val serviceUuids: List<String>,
    val manufacturerIds: List<Int> = emptyList(),
)
