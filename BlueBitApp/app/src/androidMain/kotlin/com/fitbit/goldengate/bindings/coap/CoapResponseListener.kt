package com.fitbit.goldengate.bindings.coap

import com.fitbit.goldengate.bindings.coap.data.RawResponseMessage

interface CoapResponseListener {
    fun cleanupNativeListener()
    fun isComplete(): Boolean
    fun onAck()
    fun onComplete()
    fun onError(i: Int, str: String)
    fun onNext(rawResponseMessage: RawResponseMessage)
    fun setNativeListenerReference(j: Long)
}
