package com.fitbit.goldengate.bindings.dtls

import com.fitbit.goldengate.bindings.node.NodeKey

/**
 * Resolves the "BOOTSTRAP" PSK identity used during DTLS handshake.
 *
 * Key ID: "BOOTSTRAP" (ASCII)
 * Key:    [0x81, 0x06, 0x54, 0xE3, 0x36, 0xAD, 0xCA, 0xB0,
 *          0xA0, 0x3C, 0x60, 0xF7, 0x4A, 0xA0, 0xB6, 0xFB]
 *
 * Source: Fitbit APK com.fitbit.goldengate.bindings.dtls.BootstrapTlsKeyResolverKt
 */
class BootstrapTlsKeyResolver : TlsKeyResolver() {

    companion object {
        private val BOOTSTRAP_KEY_ID = "BOOTSTRAP".toByteArray(Charsets.UTF_8)
        private val BOOTSTRAP_KEY = byteArrayOf(
            0x81.toByte(), 0x06, 0x54, 0xE3.toByte(), 0x36, 0xAD.toByte(),
            0xCA.toByte(), 0xB0.toByte(), 0xA0.toByte(), 0x3C, 0x60,
            0xF7.toByte(), 0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte()
        )
    }

    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        return if (keyId.contentEquals(BOOTSTRAP_KEY_ID)) BOOTSTRAP_KEY else null
    }
}
