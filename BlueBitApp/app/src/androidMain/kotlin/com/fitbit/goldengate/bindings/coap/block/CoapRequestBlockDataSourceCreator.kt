package com.fitbit.goldengate.bindings.coap.block

import com.fitbit.goldengate.bindings.coap.data.*

class CoapRequestBlockDataSourceCreator {

    private fun sourceWithOptionalBodyFromRequest(outgoingRequest: OutgoingRequest): BlockDataSource {
        val body = outgoingRequest.getBody()
        return when (body) {
            is InputStreamOutgoingBody -> InputStreamBlockDataSource(body.data, outgoingRequest.getProgressObserver())
            is BytesArrayOutgoingBody -> BytesArrayBlockDataSource(body.data, outgoingRequest.getProgressObserver())
            is FileUriOutgoingBody -> FileUriBlockDataSource(body.data, outgoingRequest.getProgressObserver())
            is EmptyOutgoingBody -> BytesArrayBlockDataSource(body.data, outgoingRequest.getProgressObserver())
            else -> throw IllegalArgumentException("Unknown body type: ${body::class.java.name}")
        }
    }

    private fun sourceWithRequiredEmptyBody(outgoingBody: OutgoingBody): BlockDataSource? {
        if (outgoingBody is EmptyOutgoingBody) {
            return null
        }
        throw IllegalArgumentException("Sending Body is only supported by PUT and POST methods")
    }

    fun create(outgoingRequest: OutgoingRequest): BlockDataSource? {
        return when (outgoingRequest.getMethod()) {
            Method.GET -> {
                sourceWithRequiredEmptyBody(outgoingRequest.getBody())
                null
            }
            Method.POST -> sourceWithOptionalBodyFromRequest(outgoingRequest)
            Method.PUT -> sourceWithOptionalBodyFromRequest(outgoingRequest)
            Method.DELETE -> {
                sourceWithRequiredEmptyBody(outgoingRequest.getBody())
                null
            }
        }
    }
}
