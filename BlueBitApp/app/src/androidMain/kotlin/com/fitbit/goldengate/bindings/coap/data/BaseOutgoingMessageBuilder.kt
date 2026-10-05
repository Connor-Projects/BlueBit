package com.fitbit.goldengate.bindings.coap.data

import java.io.InputStream
import java.net.URI
import java.util.LinkedList

abstract class BaseOutgoingMessageBuilder<T : Message> : OutgoingMessageBuilder<T> {
    protected val options = LinkedList<Option>()
    protected var body: OutgoingBody = EmptyOutgoingBody()
    var progressObserver: ProgressObserver = NoOpProgressObserver

    override fun body(inputStream: InputStream): OutgoingMessageBuilder<T> {
        throw NotImplementedError("InputStream body not supported")
    }

    override fun body(uri: URI): OutgoingMessageBuilder<T> {
        throw NotImplementedError("URI body not supported")
    }

    override fun body(bArr: ByteArray): OutgoingMessageBuilder<T> {
        this.body = BytesArrayOutgoingBody(bArr)
        return this
    }

    override fun option(option: Option): OutgoingMessageBuilder<T> {
        options.add(option)
        return this
    }

    fun progressObserver(observer: ProgressObserver): OutgoingMessageBuilder<T> {
        this.progressObserver = observer
        return this
    }
}
