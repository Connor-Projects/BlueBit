package com.fitbit.goldengate.bindings.coap.data

interface OutgoingMessage : Message {
    fun getBody(): OutgoingBody
}
