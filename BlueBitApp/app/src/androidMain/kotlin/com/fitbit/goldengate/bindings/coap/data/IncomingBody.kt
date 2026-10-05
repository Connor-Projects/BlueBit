package com.fitbit.goldengate.bindings.coap.data

interface IncomingBody {
    fun asData(): ByteArray
}
