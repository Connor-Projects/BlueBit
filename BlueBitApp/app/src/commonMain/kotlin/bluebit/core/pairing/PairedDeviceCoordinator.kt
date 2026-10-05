package bluebit.core.pairing

import bluebit.core.bluetooth.BleDevice
import bluebit.core.connection.ConnectionState
import bluebit.core.provider.ProviderRegistry
import bluebit.core.provider.WearableProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded backoff for unexpected-disconnect retries. */
data class ReconnectPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 5_000L,
    val maxDelayMillis: Long = 30_000L,
) {
    fun delayFor(attempt: Int): Long =
        (baseDelayMillis * attempt).coerceAtMost(maxDelayMillis)
}

/**
 * Provider-neutral persistence and reconnection for paired wearables.
 *
 * - Collects [WearableProvider.pairedDeviceEvents] and persists them via [store].
 * - [restoreOnLaunch] reconnects every remembered device through the provider that owns it,
 *   using each provider's non-destructive [WearableProvider.reconnect].
 * - Watches connection states; an unexpected drop of a managed device triggers a bounded,
 *   backoff-spaced retry loop. [userDisconnect] marks an intentional disconnect (no retry);
 *   [forget] removes the persisted record (explicit unpair).
 */
class PairedDeviceCoordinator(
    private val registry: ProviderRegistry,
    private val store: PairedDeviceStore,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    private val started = AtomicBoolean(false)

    /** Devices this coordinator may retry, keyed by "providerId|address". */
    private val managed = ConcurrentHashMap<String, PairedDeviceRecord>()
    private val userDisconnectedKeys = ConcurrentHashMap.newKeySet<String>()
    private val retryJobs = ConcurrentHashMap<String, Job>()

    private fun key(providerId: String, address: String) = "$providerId|$address"

    /** Start collecting paired events and observing connection states. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        registry.all().forEach { provider ->
            scope.launch { collectPairedEvents(provider) }
            scope.launch { observeConnectionState(provider) }
        }
    }

    /** Reconnect all remembered devices whose provider is available. Safe to call once per launch. */
    suspend fun restoreOnLaunch() {
        for (record in store.load()) {
            val provider = registry.findById(record.providerId) ?: continue
            val key = key(record.providerId, record.address)
            managed[key] = record
            userDisconnectedKeys.remove(key)
            if (isConnectedOrConnecting(provider)) continue
            provider.reconnect(BleDevice(record.address, record.name))
        }
    }

    /** Mark a disconnect as user-initiated: cancels any pending retry, no new retry. */
    fun userDisconnect(providerId: String, address: String) {
        val key = key(providerId, address)
        userDisconnectedKeys.add(key)
        retryJobs.remove(key)?.cancel()
    }

    /** Mark a user-initiated connect: re-arms automatic retry after drops. */
    fun userConnect(providerId: String, address: String) {
        userDisconnectedKeys.remove(key(providerId, address))
    }

    /** Explicit unpair: drop the persisted record and stop managing the device. */
    suspend fun forget(providerId: String, address: String) {
        store.remove(providerId, address)
        val key = key(providerId, address)
        managed.remove(key)
        userDisconnectedKeys.add(key)
        retryJobs.remove(key)?.cancel()
    }

    private suspend fun collectPairedEvents(provider: WearableProvider) {
        provider.pairedDeviceEvents.collect { record ->
            store.upsert(record)
            val key = key(record.providerId, record.address)
            managed[key] = record
            userDisconnectedKeys.remove(key)
        }
    }

    private suspend fun observeConnectionState(provider: WearableProvider) {
        var wasConnected = false
        provider.connectionState.collect { state ->
            if (state == ConnectionState.Connected) {
                wasConnected = true
                // Success cancels pending retries for this provider's devices.
                val prefix = "${provider.id}|"
                retryJobs.keys.filter { it.startsWith(prefix) }.forEach { retryJobs.remove(it)?.cancel() }
                return@collect
            }
            if (state == ConnectionState.Disconnected && wasConnected) {
                wasConnected = false
                onUnexpectedDisconnect(provider)
            }
        }
    }

    private suspend fun onUnexpectedDisconnect(provider: WearableProvider) {
        val prefix = "${provider.id}|"
        for ((key, record) in managed) {
            if (!key.startsWith(prefix)) continue
            if (userDisconnectedKeys.contains(key)) continue
            if (retryJobs.containsKey(key)) continue // a retry loop is already running
            retryJobs[key] = scope.launch { retryLoop(provider, key, record) }
        }
    }

    /**
     * Bounded retry: at most [ReconnectPolicy.maxAttempts] reconnects, spaced by
     * [ReconnectPolicy.delayFor]. Stops early once the provider is no longer disconnected,
     * and is cancelled when the device reconnects or the user disconnects/unpairs.
     */
    private suspend fun retryLoop(
        provider: WearableProvider,
        key: String,
        record: PairedDeviceRecord,
    ) {
        try {
            var attempt = 0
            while (attempt < policy.maxAttempts && !userDisconnectedKeys.contains(key)) {
                attempt++
                delay(policy.delayFor(attempt))
                if (provider.connectionState.value != ConnectionState.Disconnected) break
                if (userDisconnectedKeys.contains(key)) break
                provider.reconnect(BleDevice(record.address, record.name))
            }
        } finally {
            retryJobs.remove(key)
        }
    }

    private fun isConnectedOrConnecting(provider: WearableProvider): Boolean =
        when (provider.connectionState.value) {
            ConnectionState.Connected,
            ConnectionState.Connecting,
            ConnectionState.DiscoveringServices,
            -> true
            else -> false
        }
}
