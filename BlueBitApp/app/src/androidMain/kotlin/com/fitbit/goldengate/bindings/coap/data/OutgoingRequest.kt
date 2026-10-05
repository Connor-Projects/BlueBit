package com.fitbit.goldengate.bindings.coap.data

interface OutgoingRequest : OutgoingMessage, BaseRequest {
    fun getAckTimeout(): Int
    fun getExpectSuccess(): Boolean
    fun getForceNonBlockwise(): Boolean
    fun getMaxResendCount(): Int
}
