package com.fitbit.goldengate.bindings.coap.data

import java.io.InputStream
import java.net.URI

interface OutgoingMessageBuilder<T : Message> {
    fun body(inputStream: InputStream): OutgoingMessageBuilder<T>
    fun body(uri: URI): OutgoingMessageBuilder<T>
    fun body(bArr: ByteArray): OutgoingMessageBuilder<T>
    fun option(option: Option): OutgoingMessageBuilder<T>
    fun build(): T
}
