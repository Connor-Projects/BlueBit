package com.fitbit.goldengate.bindings.io

import android.util.Log
import com.fitbit.goldengate.bindings.GoldenGate
import com.fitbit.goldengate.bindings.NativeReference
import com.fitbit.goldengate.bindings.NativeReferenceCreationResult

class RxSource : NativeReference {

    companion object {
        private const val TAG = "BlueBitGG_RxSource"
    }

    private val _thisPointer: Long

    init {
        GoldenGate.check()
        val result = create()
        if (result == null || !result.isSuccess) {
            throw RuntimeException("RxSource creation failed: ${result?.resultCode}")
        }
        _thisPointer = result.pointer
        Log.i(TAG, "RxSource created, pointer=0x${_thisPointer.toString(16)}")
    }

    private external fun create(): NativeReferenceCreationResult?
    private external fun destroy(ptr: Long): Int
    private external fun receiveData(data: ByteArray, ptr: Long): Int

    override fun getThisPointer(): Long = _thisPointer

    fun receiveData(data: ByteArray): Int {
        Log.i(TAG, "RX to native: ${data.size} bytes: ${data.joinToString("") { "%02X".format(it) }}")
        return receiveData(data, _thisPointer)
    }

    fun close() {
        destroy(_thisPointer)
    }
}
