package com.fitbit.goldengate.bindings.dtls

/**
 * Registry that chains [TlsKeyResolver] instances in the same order Fitbit uses.
 *
 * Fitbit static initializer:
 *   1. Create HelloTlsKeyResolver (head)
 *   2. Register BootstrapTlsKeyResolver (appended to chain)
 *
 * The resulting chain is: Hello → Bootstrap
 */
object TlsKeyResolverRegistry {

    private val firstResolver: HelloTlsKeyResolver = HelloTlsKeyResolver()
    private var lastResolver: TlsKeyResolver = firstResolver

    init {
        register(BootstrapTlsKeyResolver())
    }

    /** Returns the head of the resolver chain (HelloTlsKeyResolver). */
    fun getResolvers(): HelloTlsKeyResolver = firstResolver

    /** Append a resolver to the end of the chain. */
    fun register(resolver: TlsKeyResolver) {
        lastResolver.setNext(resolver)
        lastResolver = resolver
    }
}
