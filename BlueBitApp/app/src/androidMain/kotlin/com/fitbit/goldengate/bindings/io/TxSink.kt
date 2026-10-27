package com.fitbit.goldengate.bindings.io

import android.util.Log
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.NativeReference
import com.fitbit.goldengate.bindings.NativeReferenceCreationResult

class TxSink : NativeReference {

    companion object {
        private const val TAG = "BlueBitGG_TxSink"
    }

    private var txListener: ((ByteArray) -> Unit)? = null
    private var txStatusListener: ((ByteArray) -> Int)? = null
    private val _thisPointer: Long

    init {
        GoldenGate.check()
        val result = create(TxSink::class.java, "putData", "([B)I")
        if (result == null || !result.isSuccess) {
            throw RuntimeException("TxSink creation failed: ${result?.resultCode}")
        }
        _thisPointer = result.pointer
        Log.i(TAG, "TxSink created, pointer=0x${_thisPointer.toString(16)}")
    }

    private external fun create(cls: Class<TxSink>, methodName: String, methodSig: String): NativeReferenceCreationResult?
    private external fun destroy(ptr: Long): Int
    external fun notifyListener(ptr: Long): Int

    override fun getThisPointer(): Long = _thisPointer

    fun setTxListener(listener: (ByteArray) -> Unit) {
        txListener = listener
    }

    /**
     * Phase 14: optional status-returning TX listener. When set, [putData] returns
     * its result to the native stack instead of unconditional GG_SUCCESS, and only
     * calls notifyListener() when the data was accepted (status == 0) — so write
     * failures are propagated instead of the native stack being told a frame was
     * delivered when it never reached the air.
     */
    fun setTxStatusListener(listener: ((ByteArray) -> Int)?) {
        txStatusListener = listener
    }

    @Suppress("unused")
    fun putData(data: ByteArray): Int {
        val hex = data.joinToString("") { "%02X".format(it) }
        Log.i(TAG, "Native TX: ${data.size} bytes: $hex")
        val status = txStatusListener?.let { it(data) } ?: run {
            txListener?.invoke(data)
            0
        }
        if (status == 0) {
            // Notify native that data was consumed — without this, native queue fills and stalls.
            notifyListener(_thisPointer)
        } else {
            Log.e(TAG, "putData: transport returned status=$status — NOT acknowledging frame to native")
        }
        return status
    }

    fun close() {
        txListener = null
        txStatusListener = null
        destroy(_thisPointer)
    }
}
