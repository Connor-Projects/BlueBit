package com.fitbit.goldengate.bindings.coap.block

interface BlockDataSource {
    fun getData(i: Int, i2: Int): ByteArray
    fun getDataSize(i: Int, i2: Int): BlockSize

    class BlockSize(
        val size: Int = 0,
        val more: Boolean = false,
        val requestInRange: Boolean = false
    )
}
