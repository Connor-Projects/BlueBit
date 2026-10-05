package com.fitbit.goldengate.bindings.coap.data

interface BaseRequest {
    fun getMethod(): Method
    fun getProgressObserver(): ProgressObserver
}
