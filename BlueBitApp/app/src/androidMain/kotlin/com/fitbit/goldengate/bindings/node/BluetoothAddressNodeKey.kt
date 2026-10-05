package com.fitbit.goldengate.bindings.node

class BluetoothAddressNodeKey(private val value: String) : NodeKey<String> {
    override fun getValue(): String = value
}
