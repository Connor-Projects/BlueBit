package com.fitbit.goldengate.bindings.dtls

import com.fitbit.goldengate.bindings.node.NodeKey

/**
 * Resolves the "hello" PSK identity used during early DTLS handshake.
 *
 * Key ID: "hello" (ASCII)
 * Key:    [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
 *          0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F]
 *
 * Source: Fitbit APK com.fitbit.goldengate.bindings.dtls.HelloTlsKeyResolverKt
 */
class HelloTlsKeyResolver : TlsKeyResolver() {

    companion object {
        private val HELLO_KEY_ID = "hello".toByteArray(Charsets.UTF_8)
        private val HELLO_KEY = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
    }

    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        return if (keyId.contentEquals(HELLO_KEY_ID)) HELLO_KEY else null
    }
}
