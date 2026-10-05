package com.fitbit.goldengate.bindings.coap.data

class IfMatchOption(opaqueValue: ByteArray) : Option(OptionNumber.IF_MATCH, OpaqueOptionValue(opaqueValue))
