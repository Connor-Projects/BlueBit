package bluebit.pairing

import android.content.Context
import bluebit.core.pairing.PairedDeviceRecord
import bluebit.core.pairing.PairedDeviceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * [PairedDeviceStore] backed by SharedPreferences. Provider-neutral; stores only
 * provider id, Bluetooth address, device name and provider-supplied extra identifiers.
 */
class SharedPreferencesPairedDeviceStore(context: Context) : PairedDeviceStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): List<PairedDeviceRecord> {
        val raw = prefs.getString(KEY_DEVICES, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val extra = buildMap {
                    val extraObj = obj.optJSONObject(KEY_EXTRA) ?: return@buildMap
                    extraObj.keys().forEach { k -> put(k, extraObj.getString(k)) }
                }
                add(
                    PairedDeviceRecord(
                        providerId = obj.getString(KEY_PROVIDER_ID),
                        address = obj.getString(KEY_ADDRESS),
                        name = obj.optString(KEY_NAME, ""),
                        extra = extra,
                    )
                )
            }
        }
    }

    override suspend fun upsert(record: PairedDeviceRecord) {
        val others = load().filterNot {
            it.providerId == record.providerId && it.address == record.address
        }
        save(others + record)
    }

    override suspend fun remove(providerId: String, address: String) {
        save(load().filterNot { it.providerId == providerId && it.address == address })
    }

    private fun save(records: List<PairedDeviceRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            val obj = JSONObject()
                .put(KEY_PROVIDER_ID, record.providerId)
                .put(KEY_ADDRESS, record.address)
                .put(KEY_NAME, record.name)
                .put(KEY_EXTRA, JSONObject(record.extra))
            array.put(obj)
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "bluebit_paired_devices"
        const val KEY_DEVICES = "devices"
        const val KEY_PROVIDER_ID = "providerId"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_EXTRA = "extra"
    }
}
