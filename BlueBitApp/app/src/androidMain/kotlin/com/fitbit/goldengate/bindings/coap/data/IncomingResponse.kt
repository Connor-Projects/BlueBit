package com.fitbit.goldengate.bindings.coap.data

interface IncomingResponse : IncomingMessage, BaseResponse {
	fun getExtendedError(): ExtendedError?
}
