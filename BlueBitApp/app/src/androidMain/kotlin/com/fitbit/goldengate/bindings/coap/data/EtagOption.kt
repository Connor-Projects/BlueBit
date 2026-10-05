package com.fitbit.goldengate.bindings.coap.data

class EtagOption(opaqueValue: ByteArray) : Option(OptionNumber.ETAG, OpaqueOptionValue(opaqueValue))
