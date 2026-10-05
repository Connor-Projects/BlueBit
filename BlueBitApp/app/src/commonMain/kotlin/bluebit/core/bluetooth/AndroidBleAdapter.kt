package bluebit.core.bluetooth

/**
 * Adapter interface for converting an Android `android.bluetooth.le.ScanResult`
 * into the platform-agnostic [BleScanResult].
 *
 * A typical implementation extracts:
 * - `scanResult.device.address`
 * - `scanResult.device.name`
 * - `scanResult.rssi`
 * - `scanRecord.serviceUuids` mapped to string UUIDs
 * - `scanRecord.serviceData` mapped to string UUID → ByteArray
 * - `scanResult.timestampNanos`
 *
 * Example implementation in the Android source set:
 * ```
 * class AndroidBleAdapterImpl : AndroidBleAdapter {
 *     override fun adapt(scanResult: Any): BleScanResult {
 *         val androidResult = scanResult as android.bluetooth.le.ScanResult
 *         val record = androidResult.scanRecord
 *         return BleScanResult(
 *             address = androidResult.device.address,
 *             name = androidResult.device.name,
 *             rssi = androidResult.rssi,
 *             serviceUuids = record.serviceUuids?.map { it.toString() } ?: emptyList(),
 *             serviceData = record.serviceData?.mapKeys { it.key.toString() } ?: emptyMap(),
 *             timestampNanos = androidResult.timestampNanos
 *         )
 *     }
 * }
 * ```
 */
interface AndroidBleAdapter {
    fun adapt(scanResult: Any): BleScanResult
}
