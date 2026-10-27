package bluebit.fitbit

import bluebit.bluetooth.FakeBleConnection
import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleNotification
import bluebit.core.bluetooth.BleService
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.InMemoryDeviceProfileRepository
import bluebit.core.pairing.PairedDeviceCoordinator
import bluebit.core.pairing.PairedDeviceRecord
import bluebit.core.pairing.PairedDeviceStore
import bluebit.core.provider.ProviderRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline tests for the M20-A Fitbit persistence path: a successfully connected Fitbit
 * device must surface as a [PairedDeviceRecord] through [FitbitProvider.pairedDeviceEvents]
 * so [PairedDeviceCoordinator] can persist/restore it — exactly as the FitCloud provider
 * already does. No Bluetooth adapter or tracker involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitbitProviderPairedEventTest {

    /** Connect always fails, so the provider must not emit a paired-device record. */
    private class ThrowingBleConnection : BleConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()
        private val _discoveredServices = MutableStateFlow<List<BleService>>(emptyList())
        override val discoveredServices: StateFlow<List<BleService>> = _discoveredServices.asStateFlow()
        override val notifications: Flow<BleNotification> = emptyFlow()

        override suspend fun connect(device: BleDevice) {
            throw IOException("CONNECT_REFUSED")
        }
        override suspend fun disconnect() {
            _state.value = ConnectionState.Disconnected
        }
        override suspend fun discoverServices() {}
        override suspend fun setCharacteristicNotification(serviceUuid: String, characteristicUuid: String, enable: Boolean) {}
        override suspend fun writeDescriptor(serviceUuid: String, characteristicUuid: String, descriptorUuid: String, value: ByteArray) {}
        override suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: Int) {}
        override suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): ByteArray = ByteArray(16)
        override fun dumpServices(): String = "ThrowingBleConnection"
    }

    /** Mirrors the InMemoryStore of PairedDeviceCoordinatorTest (same upsert dedupe rule). */
    private class RecordingStore : PairedDeviceStore {
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

    private fun TestScope.makeProvider(connection: (BleDevice) -> BleConnection = { FakeBleConnection() }) =
        FitbitProvider(
            profileRepository = InMemoryDeviceProfileRepository(emptyList()),
            connectionFactory = connection,
            diagnosticsFactory = { emptyList() },
            inspire3PairingHandler = null, // generic connect path only; no pairing interaction in tests
        )

    private val inspire3 = BleDevice("D8:9E:C1:BA:3D:E4", "Inspire 3")

    private val expectedRecord = PairedDeviceRecord(
        providerId = "fitbit",
        address = "D8:9E:C1:BA:3D:E4",
        name = "Inspire 3",
    )

    @Test
    fun `successful connect emits a fitbit paired-device record`() = runTest {
        val provider = makeProvider()
        val emitted = mutableListOf<PairedDeviceRecord>()
        backgroundScope.launch { provider.pairedDeviceEvents.collect { emitted += it } }
        runCurrent()

        val result = provider.connect(inspire3, FakeBleConnection())
        runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(listOf(expectedRecord), emitted)
        assertEquals(ConnectionState.Connected, provider.connectionState.value)
    }

    @Test
    fun `failed connect emits no paired-device record`() = runTest {
        val provider = makeProvider()
        val emitted = mutableListOf<PairedDeviceRecord>()
        backgroundScope.launch { provider.pairedDeviceEvents.collect { emitted += it } }
        runCurrent()

        val result = provider.connect(inspire3, ThrowingBleConnection())
        runCurrent()

        assertTrue(result.isFailure)
        assertTrue(emitted.isEmpty())
        assertEquals(ConnectionState.Disconnected, provider.connectionState.value)
    }

    @Test
    fun `coordinator persists the fitbit record through the store`() = runTest {
        val provider = makeProvider()
        val store = RecordingStore()
        PairedDeviceCoordinator(ProviderRegistry(listOf(provider)), store, backgroundScope).also { it.start() }
        runCurrent()

        provider.connect(inspire3, FakeBleConnection())
        runCurrent()

        assertEquals(listOf(expectedRecord), store.records)
    }

    @Test
    fun `repeated connects upsert the same record instead of duplicating it`() = runTest {
        val provider = makeProvider()
        val store = RecordingStore()
        PairedDeviceCoordinator(ProviderRegistry(listOf(provider)), store, backgroundScope).also { it.start() }
        runCurrent()

        repeat(3) { // connects (and any subsequent restore reconnect) re-emit; the store must hold exactly one record
            provider.connect(inspire3, FakeBleConnection())
            runCurrent()
        }

        assertEquals(listOf(expectedRecord), store.records)
    }

    @Test
    fun `store records for different providers or addresses are preserved when upserting`() = runTest {
        val provider = makeProvider()
        val store = RecordingStore()
        store.records += PairedDeviceRecord("fitcloud", "90:EB:99:D6:5F:99", "P6_")
        PairedDeviceCoordinator(ProviderRegistry(listOf(provider)), store, backgroundScope).also { it.start() }
        runCurrent()

        provider.connect(inspire3, FakeBleConnection())
        runCurrent()

        assertEquals(
            listOf(
                PairedDeviceRecord("fitcloud", "90:EB:99:D6:5F:99", "P6_"),
                expectedRecord,
            ),
            store.records,
        )
    }

    @Test
    fun `restored fitbit record routes through the provider's own connect`() = runTest {
        val provider = makeProvider()
        val store = RecordingStore()
        store.records += expectedRecord
        val coordinator = PairedDeviceCoordinator(ProviderRegistry(listOf(provider)), store, backgroundScope).also { it.start() }

        coordinator.restoreOnLaunch() // provider is Disconnected -> reconnect via FitbitProvider.connect
        runCurrent()

        // Connecting through the real provider proves the routing, and the re-emitted
        // event re-upserts the identical record without adding a duplicate.
        assertEquals(ConnectionState.Connected, provider.connectionState.value)
        assertEquals(listOf(expectedRecord), store.records)
    }

    @Test
    fun `provider reports the fitbit id and a name fallback when the scan result is anonymous`() = runTest {
        val provider = makeProvider()
        val emitted = mutableListOf<PairedDeviceRecord>()
        backgroundScope.launch { provider.pairedDeviceEvents.collect { emitted += it } }
        runCurrent()

        provider.connect(BleDevice("AA:BB:CC:DD:EE:01", name = null), FakeBleConnection())
        runCurrent()

        assertEquals(
            listOf(PairedDeviceRecord(providerId = "fitbit", address = "AA:BB:CC:DD:EE:01", name = "Fitbit Device")),
            emitted,
        )
    }
}
