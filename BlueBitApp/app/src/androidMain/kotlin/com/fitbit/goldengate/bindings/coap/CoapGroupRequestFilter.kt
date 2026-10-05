package com.fitbit.goldengate.bindings.coap

import android.util.Log
import com.fitbit.goldengate.bindings.NativeReferenceCreationResult
import com.fitbit.goldengate.bindings.NativeReferenceWithCallback

class CoapGroupRequestFilter : NativeReferenceWithCallback {
    companion object {
        private const val TAG = "BlueBitGG_CoapFilter"
    }

    private var thisPointerWrapper: Long = 0

    private external fun create(): NativeReferenceCreationResult
    private external fun destroy(j: Long): Int
    private external fun setGroup(j: Long, group: Byte): Int

    init {
        val result = create()
        if (!result.isSuccess) {
            throw RuntimeException("CoapGroupRequestFilter create failed: ${result.resultCode}")
        }
        thisPointerWrapper = result.pointer
        setGroupTo(CoapGroupRequestFilterMode.NONE)
        Log.i(TAG, "CoapGroupRequestFilter created, ptr=0x${thisPointerWrapper.toString(16)}")
    }

    override fun getThisPointer(): Long = thisPointerWrapper

    override fun getThisPointerWrapper(): Long = thisPointerWrapper

    override fun onFree() {
        Log.i(TAG, "onFree called")
        thisPointerWrapper = 0
    }

    override fun setThisPointerWrapper(j: Long) {
        thisPointerWrapper = j
    }

    fun close() {
        if (thisPointerWrapper != 0L) {
            destroy(thisPointerWrapper)
            thisPointerWrapper = 0
            Log.i(TAG, "CoapGroupRequestFilter destroyed")
        }
    }

    fun setGroupTo(mode: CoapGroupRequestFilterMode) {
        if (thisPointerWrapper != 0L) {
            setGroup(thisPointerWrapper, mode.value)
        }
    }
}
