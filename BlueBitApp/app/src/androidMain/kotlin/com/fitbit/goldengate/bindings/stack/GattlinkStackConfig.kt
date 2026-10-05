package com.fitbit.goldengate.bindings.stack

import java.net.Inet4Address

object GattlinkStackConfig : StackConfig(
    configDescriptor = "G",
    localAddress = Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)) as Inet4Address,
    localPort = 0,
    remoteAddress = Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)) as Inet4Address,
    remotePort = 0
)
