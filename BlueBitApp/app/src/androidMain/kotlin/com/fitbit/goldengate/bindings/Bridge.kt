package com.fitbit.goldengate.bindings

import android.util.Log
import com.fitbit.goldengate.bindings.io.RxSource
import com.fitbit.goldengate.bindings.io.TxSink

class Bridge(
    private val onTxData: (ByteArray) -> Unit,
    private val onRxData: (ByteArray) -> Unit,
    // Phase 14: optional status-returning TX path. When set, it is used instead of
    // onTxData and its result is returned to the native stack through TxSink.putData,
    // so write failures propagate instead of unconditional GG_SUCCESS. Probes keep
    // the two-argument form and are unaffected.
    private val onTxDataStatus: ((ByteArray) -> Int)? = null
) {

    companion object {
        private const val TAG = "BlueBitGG_Bridge"
    }

    val txSink = TxSink()
    val rxSource = RxSource()
    private var isReady = false

    init {
        if (onTxDataStatus != null) {
            txSink.setTxStatusListener { data ->
                val hex = data.joinToString("") { "%02X".format(it) }
                Log.i(TAG, "TX → BLE: ${data.size} bytes: $hex")
                onTxDataStatus(data)
            }
        } else {
            txSink.setTxListener { data ->
                val hex = data.joinToString("") { "%02X".format(it) }
                Log.i(TAG, "TX → BLE: ${data.size} bytes: $hex")
                onTxData(data)
            }
        }
    }

    fun start() {
        isReady = true
        Log.i(TAG, "Bridge started, isReady=true")
    }

    fun receiveFromBle(data: ByteArray) {
        if (!isReady) {
            Log.w(TAG, "Bridge not ready, dropping ${data.size} bytes")
            return
        }
        val hex = data.joinToString("") { "%02X".format(it) }
        Log.i(TAG, "BLE → RX: ${data.size} bytes: $hex")
        onRxData(data)
        val result = rxSource.receiveData(data)
        Log.i(TAG, "rxSource.receiveData() returned $result")
    }

    fun close() {
        isReady = false
        txSink.close()
        rxSource.close()
        Log.i(TAG, "Bridge closed")
    }
}
