package com.fitbit.goldengate.bindings.coap.data

class ResponseCode(
    val responseClass: Byte,
    val detail: Byte
) {
    companion object {
        @JvmField val created = ResponseCode(2, 1)
        @JvmField val deleted = ResponseCode(2, 2)
        @JvmField val valid = ResponseCode(2, 3)
        @JvmField val changed = ResponseCode(2, 4)
        @JvmField val content = ResponseCode(2, 5)
        @JvmField val continueCode = ResponseCode(2, 31)
        @JvmField val badRequest = ResponseCode(4, 0)
        @JvmField val unauthorized = ResponseCode(4, 1)
        @JvmField val badOption = ResponseCode(4, 2)
        @JvmField val forbidden = ResponseCode(4, 3)
        @JvmField val notFound = ResponseCode(4, 4)
        @JvmField val methodNotAllowed = ResponseCode(4, 5)
        @JvmField val notAcceptable = ResponseCode(4, 6)
        @JvmField val requestEntityIncomplete = ResponseCode(4, 8)
        @JvmField val preconditionFailed = ResponseCode(4, 12)
        @JvmField val requestEntityTooLarge = ResponseCode(4, 13)
        @JvmField val unsupportedContentFormat = ResponseCode(4, 15)
        @JvmField val internalServerError = ResponseCode(5, 0)
        @JvmField val notImplemented = ResponseCode(5, 1)
        @JvmField val badGateway = ResponseCode(5, 2)
        @JvmField val serviceUnavailable = ResponseCode(5, 3)
        @JvmField val gatewayTimeout = ResponseCode(5, 4)
        @JvmField val proxyingNotSupported = ResponseCode(5, 5)
    }
}
