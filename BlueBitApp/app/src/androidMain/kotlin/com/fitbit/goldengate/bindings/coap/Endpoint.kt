package com.fitbit.goldengate.bindings.coap

import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequest

interface Endpoint {
    fun responseFor(outgoingRequest: OutgoingRequest): Any
}
