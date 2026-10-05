package com.fitbit.goldengate.bindings.coap.block

import com.fitbit.goldengate.bindings.coap.data.ProgressObserver
import java.io.InputStream

class InputStreamBlockDataSource(
    private val stream: InputStream,
    private val progressObserver: ProgressObserver
) : BlockDataSource {
    private val data: ByteArray = stream.readBytes()

    override fun getData(i: Int, i2: Int): ByteArray {
        val end = minOf(i + i2, data.size)
        return data.copyOfRange(i, end)
    }

    override fun getDataSize(i: Int, i2: Int): BlockDataSource.BlockSize {
        val remaining = data.size - i
        val size = minOf(remaining, i2)
        val more = remaining > i2
        return BlockDataSource.BlockSize(size, more, i < data.size)
    }
}
