package bluebit.core.pairing

/**
 * A wearable that was successfully paired/bound and should be reconnected on future launches.
 * [extra] holds provider-specific stable identifiers already exposed by the provider's SDK;
 * core never invents or interprets them.
 */
data class PairedDeviceRecord(
    val providerId: String,
    val address: String,
    val name: String,
    val extra: Map<String, String> = emptyMap(),
)
