package com.fitbit.goldengate.bindings.coap.data

class OutgoingRequestBuilder(
    private val path: String,
    private val method: Method
) : BaseOutgoingMessageBuilder<OutgoingRequest>() {

    private var ackTimeout: Int = 0
    private var expectSuccess: Boolean = false
    private var forceNonBlockwise: Boolean = false
    private var maxResendCount: Int = -1

    init {
        addPathToOptions(path)
    }

    override fun body(bArr: ByteArray): OutgoingRequestBuilder {
        super.body(bArr)
        return this
    }

    private fun addPathToOptions(path: String) {
        path.split("/").filter { it.isNotEmpty() }.forEach { segment ->
            options.add(UriPathOption(segment))
        }
    }

    fun ackTimeout(i: Int): OutgoingRequestBuilder {
        this.ackTimeout = i
        return this
    }

    fun expectSuccess(z: Boolean): OutgoingRequestBuilder {
        this.expectSuccess = z
        return this
    }

    fun forceNonBlockwise(z: Boolean): OutgoingRequestBuilder {
        this.forceNonBlockwise = z
        return this
    }

    fun maxResendCount(i: Int): OutgoingRequestBuilder {
        this.maxResendCount = i
        return this
    }

    fun getPath(): String = path

    override fun build(): OutgoingRequest {
        return object : OutgoingRequest {
            override fun getAckTimeout(): Int = ackTimeout
            override fun getExpectSuccess(): Boolean = expectSuccess
            override fun getForceNonBlockwise(): Boolean = forceNonBlockwise
            override fun getMaxResendCount(): Int = maxResendCount
            override fun getMethod(): Method = method
            override fun getOptions(): java.util.LinkedList<Option> = options
            override fun getBody(): OutgoingBody = body
            override fun getProgressObserver(): ProgressObserver = progressObserver
        }
    }
}
