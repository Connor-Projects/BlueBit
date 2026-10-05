package com.fitbit.goldengate.bindings.coap.data

import java.util.LinkedList

class RawResponseMessage(
    val responseCode: ResponseCode,
    val options: LinkedList<Option>,
    val data: ByteArray
)
