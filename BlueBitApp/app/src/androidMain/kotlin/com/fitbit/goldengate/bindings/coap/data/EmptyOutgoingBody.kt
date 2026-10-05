package com.fitbit.goldengate.bindings.coap.data

class EmptyOutgoingBody : OutgoingBody() {
    val data: ByteArray = byteArrayOf()
}
