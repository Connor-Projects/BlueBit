package com.fitbit.goldengate.bindings.coap

import android.util.Log
import com.fitbit.goldengate.bindings.coap.data.IncomingBody
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.Option
import com.fitbit.goldengate.bindings.coap.data.RawResponseMessage
import com.fitbit.goldengate.bindings.coap.data.ResponseCode
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean

class SingleCoapResponseListener(
    private val onResult: (Result<IncomingResponse>) -> Unit
) : CoapResponseListener {

    companion object {
        private const val TAG = "BlueBitGG_CoapListener"
    }

    private var nativeRef: Long = 0
    private val completed = AtomicBoolean(false)

    private external fun cancelResponse(nativeResponseListenerReference: Long): Int

    override fun cleanupNativeListener() {
        if (nativeRef != 0L && !completed.get()) {
            Log.i(TAG, "Cancelling native listener ref=$nativeRef")
            cancelResponse(nativeRef)
        }
    }

    override fun isComplete(): Boolean = completed.get()

    override fun onAck() {
        Log.i(TAG, "onAck")
    }

    override fun onComplete() {
        Log.i(TAG, "onComplete")
    }

    override fun onError(i: Int, str: String) {
        Log.e(TAG, "onError code=$i message=$str")
        completed.set(true)
        onResult(Result.failure(RuntimeException("CoAP error $i: $str")))
        cleanupNativeListener()
    }

    override fun onNext(rawResponseMessage: RawResponseMessage) {
        val code = rawResponseMessage.responseCode
        Log.i(TAG, "onNext responseClass=${code.responseClass} detail=${code.detail} dataLen=${rawResponseMessage.data.size}")
        completed.set(true)
        val response = object : IncomingResponse {
            override fun getBody(): IncomingBody = object : IncomingBody {
                override fun asData(): ByteArray = rawResponseMessage.data
            }
            override fun getExtendedError() = null
            override fun getOptions(): LinkedList<Option> = rawResponseMessage.options
            override fun getResponseCode(): ResponseCode = rawResponseMessage.responseCode
        }
        onResult(Result.success(response))
        cleanupNativeListener()
    }

    override fun setNativeListenerReference(j: Long) {
        Log.i(TAG, "setNativeListenerReference ref=$j")
        nativeRef = j
    }
}
