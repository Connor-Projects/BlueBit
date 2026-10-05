package com.fitbit.goldengate.bindings.coap.data

interface IncomingMessage : Message {
	fun getBody(): IncomingBody
}
