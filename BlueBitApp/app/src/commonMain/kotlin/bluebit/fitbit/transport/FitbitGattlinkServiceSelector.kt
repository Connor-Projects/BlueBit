package bluebit.fitbit.transport

import bluebit.fitbit.FitbitGattUuids

object FitbitGattlinkServiceSelector {
    private const val MODERN = FitbitGattUuids.FITBIT_GATTLINK_SERVICE_UUID
    private const val LEGACY = FitbitGattUuids.GATTLINK_SERVICE_UUID

    fun select(serviceUuids: List<String>): String? {
        return when {
            serviceUuids.contains(MODERN) -> MODERN
            serviceUuids.contains(LEGACY) -> LEGACY
            else -> null
        }
    }
}
