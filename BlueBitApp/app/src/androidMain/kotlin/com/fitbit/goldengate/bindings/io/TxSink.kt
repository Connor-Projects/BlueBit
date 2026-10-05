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

    @Suppress("unused")
    fun putData(data: ByteArray): Int {
        val hex = data.joinToString("") { "%02X".format(it) }
        Log.i(TAG, "Native TX: ${data.size} bytes: $hex")
        txListener?.invoke(data)
        // Notify native that data was consumed — without this, native queue fills and stalls.
        notifyListener(_thisPointer)
        return 0 // GG_SUCCESS
    }

    fun close() {
        txListener = null
        destroy(_thisPointer)
    }
}
