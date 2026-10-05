package bluebit.core.pairing

/** Provider-neutral persistence for paired-device records. */
interface PairedDeviceStore {
    suspend fun load(): List<PairedDeviceRecord>
    suspend fun upsert(record: PairedDeviceRecord)
    suspend fun remove(providerId: String, address: String)
}
