package bluebit.fitbit.phase17

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 17 / T2 — resolver-chain semantics: `Hello -> Bootstrap`, `MD-* -> null`.
 *
 * IMPORTANT — this is a MIRROR of the documented rule, not the real chain:
 * the real resolvers (`com.fitbit.goldengate.bindings.dtls.*`) live in
 * `androidMain` and reference `android.util.Log`, so they are not on the
 * `desktopTest` classpath. See `jvm/README.md` for how to exercise the real
 * classes (move them to a JVM-visible source set, or use an `androidUnitTest`
 * source set). This test exists so the walk can be regression-checked on the JVM.
 *
 * Source of truth: `HelloTlsKeyResolver.kt:17-25`, `BootstrapTlsKeyResolver.kt`,
 * `TlsKeyResolverRegistry.kt:12-28`, `TlsKeyResolver.kt:28-45`.
 */
class Phase17ResolverWalkTest {

    private abstract class MirrorResolver(private val name: String) {
        var next: MirrorResolver? = null

        fun resolve(keyId: ByteArray, log: MutableList<String>): ByteArray? {
            val key = resolveKey(keyId)
            val id = keyId.decodeToString()
            log.add(if (key != null) "$name:$id:FOUND" else "$name:$id:miss")
            return key ?: next?.resolve(keyId, log)
        }

        protected abstract fun resolveKey(keyId: ByteArray): ByteArray?
    }

    private class HelloResolver : MirrorResolver("HelloTlsKeyResolver") {
        override fun resolveKey(keyId: ByteArray): ByteArray? =
            if (keyId.contentEquals(HELLO_KEY_ID)) HELLO_KEY else null
    }

    private class BootstrapResolver : MirrorResolver("BootstrapTlsKeyResolver") {
        override fun resolveKey(keyId: ByteArray): ByteArray? =
            if (keyId.contentEquals(BOOTSTRAP_KEY_ID)) BOOTSTRAP_KEY else null
    }

    private fun bluebitChain(): MirrorResolver = HelloResolver().apply { next = BootstrapResolver() }

    @Test
    fun `the bluebit chain is Hello then Bootstrap (no MobileData)`() {
        val chain = bluebitChain()
        assertTrue(chain is HelloResolver)
        assertTrue(chain.next is BootstrapResolver)
        assertNull(chain.next!!.next)
    }

    @Test
    fun `public identities resolve to their documented keys`() {
        assertContentEquals(HELLO_KEY, bluebitChain().resolve(HELLO_KEY_ID, mutableListOf()))
        assertContentEquals(BOOTSTRAP_KEY, bluebitChain().resolve(BOOTSTRAP_KEY_ID, mutableListOf()))
    }

    @Test
    fun `mobile data identity is not found`() {
        // The real (non-secret) device identifier observed on the wire.
        val md = "MD-A376B03C-6AF89570".encodeToByteArray()
        assertNull(bluebitChain().resolve(md, mutableListOf()))
    }

    @Test
    fun `the walk records every requested identity`() {
        val log = mutableListOf<String>()
        bluebitChain().resolve("MD-00000000-00000000".encodeToByteArray(), log)
        assertEquals(
            listOf(
                "HelloTlsKeyResolver:MD-00000000-00000000:miss",
                "BootstrapTlsKeyResolver:MD-00000000-00000000:miss",
            ),
            log,
        )
    }

    private companion object {
        val HELLO_KEY_ID = "hello".encodeToByteArray()
        val HELLO_KEY = ByteArray(16) { it.toByte() }
        val BOOTSTRAP_KEY_ID = "BOOTSTRAP".encodeToByteArray()
        val BOOTSTRAP_KEY = byteArrayOf(
            0x81.toByte(), 0x06, 0x54, 0xE3.toByte(), 0x36, 0xAD.toByte(),
            0xCA.toByte(), 0xB0.toByte(), 0xA0.toByte(), 0x3C, 0x60,
            0xF7.toByte(), 0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte(),
        )
    }
}
