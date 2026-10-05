package com.fitbit.goldengate.bindings.stack

import java.net.Inet4Address

/**
 * DTLS + Socket + Netif + Gattlink stack configuration.
 *
 * Fitbit uses this config (descriptor "DSNG") for tracker connections.
 *
 * @param localAddress Local IP address (default 0.0.0.0)
 * @param localPort Local port (default 0)
 * @param remoteAddress Remote IP address (default 0.0.0.0)
 * @param remotePort Remote port (default 0)
 * @param gattlinkRxWindowSize Gattlink RX window size (null = native default)
 * @param gattlinkTxWindowSize Gattlink TX window size (null = native default)
 * @param gattlinkBufferThresholdHysteresis Buffer threshold (null = native default)
 * @param gattlinkExpectedAckTimeout Expected ACK timeout ms (null = native default)
 */
class DtlsSocketNetifGattlinkStackConfig(
    localAddress: Inet4Address = Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)) as Inet4Address,
    localPort: Int = 0,
    remoteAddress: Inet4Address = Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)) as Inet4Address,
    remotePort: Int = 0,
    gattlinkRxWindowSize: Int? = null,
    gattlinkTxWindowSize: Int? = null,
    gattlinkBufferThresholdHysteresis: Double? = null,
    gattlinkExpectedAckTimeout: Int? = null
) : StackConfig(
    configDescriptor = "DSNG",
    localAddress = localAddress,
    localPort = localPort,
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    gattlinkRxWindowSize = gattlinkRxWindowSize,
    gattlinkTxWindowSize = gattlinkTxWindowSize,
    gattlinkBufferThresholdHysteresis = gattlinkBufferThresholdHysteresis,
    gattlinkExpectedAckTimeout = gattlinkExpectedAckTimeout
)
