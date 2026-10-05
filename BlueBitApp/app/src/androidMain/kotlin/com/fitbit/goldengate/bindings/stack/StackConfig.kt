package com.fitbit.goldengate.bindings.stack

import java.net.Inet4Address

abstract class StackConfig(
    val configDescriptor: String,
    val localAddress: Inet4Address,
    val localPort: Int,
    val remoteAddress: Inet4Address,
    val remotePort: Int,
    val gattlinkRxWindowSize: Int? = null,
    val gattlinkTxWindowSize: Int? = null,
    val gattlinkBufferThresholdHysteresis: Double? = null,
    val gattlinkExpectedAckTimeout: Int? = null
)
