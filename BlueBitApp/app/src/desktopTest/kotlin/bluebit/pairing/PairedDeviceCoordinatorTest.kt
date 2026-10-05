package bluebit.pairing

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.bluetooth.NoOpBleConnection
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.DeviceProfile
import bluebit.core.pairing.PairedDeviceCoordinator
import bluebit.core.pairing.PairedDeviceRecord
import bluebit.core.pairing.PairedDeviceStore
import bluebit.core.pairing.ReconnectPolicy
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.provider.ProviderRegistry
import bluebit.core.provider.ProviderScanFilter
import bluebit.core.provider.WearableProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairedDeviceCoordinatorTest {

    private class FakeProvider(
        override val id: String,
    ) : WearableProvider {
        override val name: String = id
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _pairedEvents = MutableSharedFlow<PairedDeviceRecord>(extraBufferCapacity = 1)
        override val pairedDeviceEvents: Flow<PairedDeviceRecord> = _pairedEvents.asSharedFlow()

        val reconnectCalls = mutableListOf<String>()

        fun setState(state: ConnectionState) {
            _connectionState.value = state
        }

        suspend fun emitPaired(record: PairedDeviceRecord) {
            _pairedEvents.emit(record)
        }

        override suspend fun reconnect(device: BleDevice): Result<Unit> {
            reconnectCalls.add(device.address)
            return Result.success(Unit)
        }

        override fun canHandle(scanResult: BleScanResult): Boolean = false
        override fun scanFilter(): ProviderScanFilter = ProviderScanFilter(emptyList())
        override fun identify(scanResult: BleScanResult): DeviceProfile? = null
        override fun createConnection(device: BleDevice): BleConnection = NoOpBleConnection()
        override suspend fun connect(device: BleDevice, connection: BleConnection) = Result.success(Unit)
        override suspend fun disconnect(connection: BleConnection) {}
        override fun capabilities(device: BleDevice): DeviceCapabilities = DeviceCapabilities()
        override suspend fun sync(device: BleDevice): Flow<Int?> = flowOf(null)
        override fun diagnostics(): List<ProviderDiagnostic> = emptyList()
    }

    private class InMemoryStore : PairedDeviceStore {
        val records = mutableListOf<PairedDeviceRecord>()
        override suspend fun load(): List<PairedDeviceRecord> = records.toList()
        override suspend fun upsert(record: PairedDeviceRecord) {
            records.removeAll { it.providerId == record.providerId && it.address == record.address }
            records.add(record)
        }
        override suspend fun remove(providerId: String, address: String) {
            records.removeAll { it.providerId == providerId && it.address == address }
        }
    }

    private fun TestScope.setup(
        vararg providers: FakeProvider,
        policy: ReconnectPolicy = ReconnectPolicy(maxAttempts = 3, baseDelayMillis = 1_000),
    ): Triple<PairedDeviceCoordinator, InMemoryStore, ProviderRegistry> {
        val store = InMemoryStore()
        val registry = ProviderRegistry(providers.toList())
        // backgroundScope: the coordinator's infinite collectors must not block test completion
        val coordinator = PairedDeviceCoordinator(registry, store, backgroundScope, policy).also { it.start() }
        return Triple(coordinator, store, registry)
    }

    @Test
    fun `restoreOnLaunch routes each record through its owning provider`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val fitbit = FakeProvider("fitbit")
        val (coordinator, store, _) = setup(fitcloud, fitbit)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        store.records += PairedDeviceRecord("fitbit", "11:22:33:44:55:66", "Inspire 3")
        store.records += PairedDeviceRecord("unknown-provider", "99:88:77:66:55:44", "Ghost")

        coordinator.restoreOnLaunch()

        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), fitcloud.reconnectCalls)
        assertEquals(listOf("11:22:33:44:55:66"), fitbit.reconnectCalls)
    }

    @Test
    fun `restoreOnLaunch skips device that is already connected`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        fitcloud.setState(ConnectionState.Connected)

        coordinator.restoreOnLaunch()

        assertTrue(fitcloud.reconnectCalls.isEmpty())
    }

    @Test
    fun `paired event is persisted`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        val record = PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_", mapOf("project" to "P6"))

        runCurrent() // let the paired-events collector subscribe
        fitcloud.emitPaired(record)
        runCurrent()

        assertEquals(listOf(record), store.records)
    }

    @Test
    fun `unexpected disconnect retries are bounded by policy`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        coordinator.restoreOnLaunch()

        // Initial restore reconnect succeeded; now the device drops unexpectedly.
        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        fitcloud.setState(ConnectionState.Disconnected)
        runCurrent()

        // Spaced retries: 1s, 2s, 3s (baseDelay * attempt), never tight-looped.
        // +1ms: advanceTimeBy does not run tasks scheduled exactly at the destination instant.
        advanceTimeBy(1_001) // attempt 1 fires
        assertEquals(2, fitcloud.reconnectCalls.size)
        advanceTimeBy(2_001) // attempt 2 fires
        assertEquals(3, fitcloud.reconnectCalls.size)
        advanceTimeBy(3_001) // attempt 3 fires — the last allowed
        assertEquals(4, fitcloud.reconnectCalls.size)
        advanceTimeBy(60_000) // well past any further delay
        assertEquals(4, fitcloud.reconnectCalls.size)
    }

    @Test
    fun `successful reconnect cancels pending retries and resets the budget`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        coordinator.restoreOnLaunch()

        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        fitcloud.setState(ConnectionState.Disconnected)
        runCurrent()
        advanceTimeBy(500) // retry loop is waiting, not yet fired
        fitcloud.setState(ConnectionState.Connected) // reconnect landed in time
        runCurrent()

        advanceTimeBy(120_000)
        assertEquals(1, fitcloud.reconnectCalls.size) // no retry fired after recovery
    }

    @Test
    fun `user disconnect does not trigger retry`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        coordinator.restoreOnLaunch()

        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        coordinator.userDisconnect("fitcloud", "AA:BB:CC:DD:EE:FF")
        fitcloud.setState(ConnectionState.Disconnected)
        runCurrent()

        advanceTimeBy(120_000)
        assertEquals(1, fitcloud.reconnectCalls.size) // only the initial restore
    }

    @Test
    fun `userConnect re-arms retry after user disconnect`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        store.records += PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        coordinator.restoreOnLaunch()

        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        coordinator.userDisconnect("fitcloud", "AA:BB:CC:DD:EE:FF")
        fitcloud.setState(ConnectionState.Disconnected)
        runCurrent()
        coordinator.userConnect("fitcloud", "AA:BB:CC:DD:EE:FF")
        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        fitcloud.setState(ConnectionState.Disconnected) // unexpected this time
        runCurrent()

        advanceTimeBy(1_001)
        assertEquals(2, fitcloud.reconnectCalls.size)
    }

    @Test
    fun `forget removes record and suppresses further reconnects`() = runTest {
        val fitcloud = FakeProvider("fitcloud")
        val (coordinator, store, _) = setup(fitcloud)
        val record = PairedDeviceRecord("fitcloud", "AA:BB:CC:DD:EE:FF", "P6_")
        store.records += record
        coordinator.restoreOnLaunch()

        coordinator.forget("fitcloud", "AA:BB:CC:DD:EE:FF")

        assertTrue(store.records.isEmpty())
        fitcloud.setState(ConnectionState.Connected)
        runCurrent()
        fitcloud.setState(ConnectionState.Disconnected)
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(1, fitcloud.reconnectCalls.size) // only the initial restore
    }
}
