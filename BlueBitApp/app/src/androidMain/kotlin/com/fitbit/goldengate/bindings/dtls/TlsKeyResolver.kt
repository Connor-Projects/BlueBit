package com.fitbit.goldengate.bindings.dtls

import com.fitbit.goldengate.bindings.node.NodeKey

/**
 * Abstract key resolver for DTLS PSK lookups.
 *
 * Fitbit chains multiple resolvers via [next]. The [resolve] method walks the chain
 * until a key is found or the chain is exhausted.
 */
abstract class TlsKeyResolver {

    private var next: TlsKeyResolver? = null

    fun getNext(): TlsKeyResolver? = next

    fun setNext(resolver: TlsKeyResolver?) {
        this.next = resolver
    }

    /**
     * Walk the resolver chain looking for a key that matches [keyId].
     *
     * @param nodeKey The node key (BluetoothAddressNodeKey) passed to Stack.create()
     * @param keyId The PSK identity bytes requested by the DTLS peer
     * @return The pre-shared key bytes, or null if no resolver has this key
     */
    fun resolve(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        val keyIdStr = keyId.toString(Charsets.UTF_8)
        android.util.Log.i("BlueBitGG_Tls", "resolve called keyId='$keyIdStr' nodeKey=${nodeKey.getValue()}")
        val result = resolveKey(nodeKey, keyId)
        android.util.Log.i("BlueBitGG_Tls", "resolve result: ${if (result != null) "FOUND (${result.size} bytes)" else "NOT FOUND"}")
        if (result != null) return result
        return next?.resolve(nodeKey, keyId)
    }

    /**
     * Subclasses override this to provide a specific key.
     *
     * @param nodeKey The node key
     * @param keyId The PSK identity bytes
     * @return The key bytes if this resolver owns [keyId], otherwise null
     */
    abstract fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray?
}
