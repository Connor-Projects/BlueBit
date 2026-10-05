package com.fitbit.goldengate.bindings.coap.data

interface ProgressObserver {
    fun onComplete()
    fun onError(t: Throwable)
    fun onNext(value: Any?)
}

object NoOpProgressObserver : ProgressObserver {
    override fun onComplete() {}
    override fun onError(t: Throwable) {}
    override fun onNext(value: Any?) {}
}
