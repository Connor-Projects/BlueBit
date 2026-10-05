# BlueBit Provider-Based Architecture Proposal

## 1. Current State Analysis

### 1.1 What Exists Now

BlueBit is a Kotlin Multiplatform (Android + Desktop JVM) application for communicating with BLE wearables. The current architecture has three layers:

| Layer | Location | Responsibility |
|-------|----------|----------------|
| **Shared BLE** | `bluebit.bluetooth.*` | Platform-agnostic BLE abstractions (`BleScanner`, `BleConnection`, `BleDevice`, `BleService`, `BleScanResult`, `BleNotification`) |
| **Android BLE** | `bluebit.bluetooth.android` | Android-specific implementations (`AndroidBleScanner`, `AndroidBleConnection`) |
| **UI** | `bluebit.ui.screens` | Compose screens (`HomeScreen`, `DevicesScreen`, `DeviceDetailScreen`, `ActivityScreen`, `SettingsScreen`) |
| **Connection** | `bluebit.connection` | State machines (`DeviceConnectionManager`, `ConnectionState`, `DiscoveryState`) |
| **Devices** | `bluebit.devices` | Device identification (`DeviceProfile`, `DeviceProfileRepository`, `BlueBitDeviceMatcher`) |
| **Crypto** | `bluebit.crypto` | Advertisement verification (`AdvertisementVerifier`, `KeyProvider`) |
| **API** | `bluebit.api` | Fitbit pairing server client (`FitbitPairingApi`) |

### 1.2 What Is Fitbit-Specific

The following elements are tightly coupled to Fitbit:

1. **Scan Filters** — `AndroidBleScanner.startScanInternal()` hardcodes 6 Fitbit service UUIDs (`ADABFB00`, `FD63`, `ABBAFB00`, `ABBAFF00`, `FD62`, `181D`).
2. **UUID Constants** — `FitbitGattUuids` defines 15 Fitbit-specific service/characteristic UUIDs.
3. **Gattlink Abstractions** — `GattlinkConnector`, `GattlinkTransport`, `GattlinkServiceSelector` all reference `FitbitGattUuids` constants.
4. **Diagnostics** — 6 Android-only diagnostic/experiment classes are Fitbit-specific:
   - `FitbitGattDiagnostic`
   - `FitbitGattCacheDiagnostic`
   - `FitbitGattlinkDiagnostic`
   - `FitbitGoldenGateDiagnostic`
   - `Inspire3PairingHandler`
   - `ServerPairingExperiment`
   - `DirectBondExperiment`
5. **Pairing API** — `FitbitPairingApi` calls Fitbit server endpoints (`validate.json`, `pair.json`, `ack.json`, `sync.json`).
6. **MainActivity** — Directly instantiates all 6 Fitbit diagnostics and passes 6 Fitbit-specific callbacks into `BlueBitAppShell`.
7. **UI Coupling** — `DeviceDetailScreen` exposes 6 Fitbit-specific diagnostic buttons (`onInspectGatt`, `onBondAndInspectGatt`, `onInspectGattCache`, `onTestGattlink`, `onTestGoldenGate`).
8. **Desktop Entry** — `PairingApiTest.kt` is a Fitbit-specific smoke test.

### 1.3 What Is Reusable

- `BleScanner`, `BleConnection`, `BleDevice`, `BleService`, `BleCharacteristic`, `BleScanResult`, `BleNotification` — pure data/interfaces.
- `AndroidBleConnection` — generic Android GATT bridge; no Fitbit logic.
- `ConnectionState`, `DiscoveryState`, `VerificationResult` — generic state machines.
- `DeviceProfile`, `DeviceProfileRepository`, `BlueBitDeviceMatcher` — generic device identification framework.
- `BleServiceData`, `DeviceServiceDataParser` — generic DIS payload parser.
- `AdvertisementVerifier` — generic AES verification.

---

## 2. Proposed Directory Structure

```
app/src/commonMain/kotlin/bluebit/
  core/
    bluetooth/
      BleScanner.kt
      BleConnection.kt
      BleDevice.kt
      BleService.kt
      BleCharacteristic.kt
      BleScanResult.kt
      BleNotification.kt
      BleServiceData.kt
      DeviceServiceDataParser.kt
    connection/
      ConnectionState.kt
      DiscoveryState.kt
      VerificationResult.kt
      DeviceConnectionManager.kt
    provider/
      WearableProvider.kt
      ProviderRegistry.kt
      ProviderContext.kt
      DeviceCapabilities.kt
      ProviderScanFilter.kt
    devices/
      DeviceProfile.kt
      DeviceProfileRepository.kt
      BlueBitDeviceMatcher.kt
      DevicePhylum.kt
      PairingMethod.kt
    crypto/
      AdvertisementVerifier.kt
      KeyProvider.kt
    ui/
      theme/
      components/
      screens/
        HomeScreen.kt
        DevicesScreen.kt
        DeviceDetailScreen.kt
        ActivityScreen.kt
        SettingsScreen.kt
        Screen.kt

  fitbit/
    FitbitProvider.kt
    FitbitScanFilter.kt
    FitbitPairingHandler.kt
    FitbitGattUuids.kt
    FitbitPairingApi.kt
    diagnostics/
      FitbitGattDiagnostic.kt
      FitbitGattCacheDiagnostic.kt
      FitbitGattlinkDiagnostic.kt
      FitbitGoldenGateDiagnostic.kt
      ServerPairingExperiment.kt
      DirectBondExperiment.kt
    transport/
      FitbitGattlinkConnector.kt      (migrated from GattlinkConnector)
      FitbitGattlinkTransport.kt      (migrated from GattlinkTransport)
      FitbitGattlinkServiceSelector.kt (migrated from GattlinkServiceSelector)

  fitcloud/
    FitCloudProvider.kt
    FitCloudUuids.kt
    FitCloudScanFilter.kt
    FitCloudConnection.kt

app/src/androidMain/kotlin/bluebit/
  core/
    bluetooth/
      AndroidBleScanner.kt
      AndroidBleConnection.kt
  fitbit/
    AndroidFitbitProviderFactory.kt
  fitcloud/
    AndroidFitCloudProviderFactory.kt
  MainActivity.kt

app/src/desktopMain/kotlin/bluebit/
  core/
    bluetooth/
      DesktopBleScanner.kt          (NoOp or simulated)
      DesktopBleConnection.kt       (NoOp or simulated)
  Main.kt
```

**Package mapping (exact packages):**

| New Location | Old Location |
|-------------|--------------|
| `bluebit.core.bluetooth.*` | `bluebit.bluetooth.*` |
| `bluebit.core.connection.*` | `bluebit.connection.*` |
| `bluebit.core.devices.*` | `bluebit.devices.*` |
| `bluebit.core.crypto.*` | `bluebit.crypto.*` |
| `bluebit.core.ui.*` | `bluebit.ui.*` |
| `bluebit.fitbit.*` | scattered in `bluebit.bluetooth.android`, `bluebit.api` |
| `bluebit.fitcloud.*` | new |

---

## 3. Provider Interface Design

### 3.1 Core Interface

```kotlin
package bluebit.core.provider

import bluebit.core.bluetooth.BleConnection
import bluebit.core.bluetooth.BleDevice
import bluebit.core.bluetooth.BleScanResult
import bluebit.core.connection.ConnectionState
import bluebit.core.devices.DeviceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A WearableProvider is the sole integration point for a wearable family
 * (e.g. Fitbit, FitCloud, Garmin, Polar).
 *
 * Each provider owns:
 * - Scan filtering (does this advertisement belong to me?)
 * - Device identification (what model is this?)
 * - Connection lifecycle
 * - Capability exposure (what can this device do?)
 * - Sync / data retrieval
 */
interface WearableProvider {

    /** Human-readable provider name, e.g. "Fitbit", "FitCloud". */
    val name: String

    /** Unique provider identifier, e.g. "fitbit", "fitcloud". */
    val id: String

    /** Returns true if this provider recognises the scan result. */
    fun canHandle(scanResult: BleScanResult): Boolean

    /** Returns a scan filter configuration for the platform scanner. */
    fun scanFilter(): ProviderScanFilter

    /** Attempt to identify the device model from the scan result. */
    fun identify(scanResult: BleScanResult): DeviceProfile?

    /** Create a connection handle for the given device. */
    fun createConnection(device: BleDevice): BleConnection

    /** Connect and perform provider-specific handshake (pairing, auth, feature discovery). */
    suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit>

    /** Disconnect and clean up provider-specific state. */
    suspend fun disconnect(connection: BleConnection)

    /** Current connection state for the active device. */
    val connectionState: StateFlow<ConnectionState>

    /** What this device can do (steps, heart rate, sleep, etc.). */
    fun capabilities(device: BleDevice): DeviceCapabilities

    /** Start a data sync. Emits progress (0-100) or null if indeterminate. */
    suspend fun sync(device: BleDevice): Flow<Int?>

    /** Provider-specific diagnostic actions exposed to the UI. */
    fun diagnostics(): List<ProviderDiagnostic>
}

/**
 * A diagnostic action that a provider exposes for debugging.
 * The UI renders these dynamically — no hardcoded buttons.
 */
data class ProviderDiagnostic(
    val id: String,
    val label: String,
    val action: suspend (deviceAddress: String) -> Unit
)

/**
 * Describes what a connected device can do.
 * UI uses this to show/hide features.
 */
data class DeviceCapabilities(
    val supportsSteps: Boolean,
    val supportsHeartRate: Boolean,
    val supportsSleep: Boolean,
    val supportsBatteryQuery: Boolean,
    val supportsOta: Boolean,
    val diagnostics: List<ProviderDiagnostic>,
)

/**
 * Scan filter configuration. The core scanner uses this
 * to build platform-specific filters.
 */
data class ProviderScanFilter(
    val serviceUuids: List<String>,
    val manufacturerIds: List<Int> = emptyList(),
)
```

### 3.2 Provider Registry

```kotlin
package bluebit.core.provider

import bluebit.core.bluetooth.BleScanResult
import bluebit.core.devices.DeviceProfile

/**
 * Holds all registered providers and routes scan results.
 *
 * Order matters: providers are checked in registration order.
 * First provider that returns `canHandle=true` wins.
 */
class ProviderRegistry(
    providers: List<WearableProvider>
) {
    private val providers = providers.toList()

    /** Find the provider that owns this scan result. */
    fun resolve(scanResult: BleScanResult): WearableProvider? =
        providers.firstOrNull { it.canHandle(scanResult) }

    /** Aggregate scan filters from all providers. */
    fun allScanFilters(): List<ProviderScanFilter> =
        providers.map { it.scanFilter() }

    /** Attempt to identify device across all providers. */
    fun identify(scanResult: BleScanResult): Pair<WearableProvider, DeviceProfile>? {
        val provider = resolve(scanResult) ?: return null
        val profile = provider.identify(scanResult) ?: return null
        return provider to profile
    }
}
```

### 3.3 Provider Context

```kotlin
package bluebit.core.provider

/**
 * Scoped container passed to UI and connection layers.
 * Replaces the current direct scanner/connection manager references.
 */
class ProviderContext(
    val registry: ProviderRegistry,
    val scanner: bluebit.core.bluetooth.BleScanner,
)
```

---

## 4. Device Discovery Flow

### 4.1 Current Flow

```
AndroidBleScanner.startScan() → hardcoded Fitbit UUIDs
  ↓
BleScanResult emitted
  ↓
BlueBitAppShell collects → builds UiDevice (always recognised=false)
  ↓
DevicesScreen shows list
```

Problems:
- Scanner knows about Fitbit UUIDs.
- `DeviceConnectionManager` exists but is not wired into the UI.
- `BlueBitDeviceMatcher` is never used in the active UI flow.

### 4.2 Proposed Flow

```
ProviderRegistry.allScanFilters()
  ↓
AndroidBleScanner.startScan(filters: List<ProviderScanFilter>)
  ↓
BleScanResult emitted
  ↓
ProviderRegistry.resolve(result) → WearableProvider?
  ↓
YES: provider.identify(result) → DeviceProfile?
       ↓
       UiDevice(recognised=true, deviceType=provider.name)
  ↓
DevicesScreen shows list with recognised badge + provider chip
```

Key changes:
- `AndroidBleScanner` becomes filter-agnostic; accepts `List<ProviderScanFilter>`.
- `BlueBitAppShell` uses `ProviderRegistry` to enrich `UiDevice`.
- `DeviceConnectionManager` is deleted; its responsibilities move into `WearableProvider`.

---

## 5. Connection Management

### 5.1 Current State

`DeviceConnectionManager` has two `connect()` methods:
- `connect(profile: DeviceProfile)` — TODO, unimplemented.
- `connect(device: BleDevice, connection: BleConnection)` — naive wrapper around `BleConnection.connect()`.

It does not manage provider-specific handshake, pairing, or auth.

### 5.2 Proposed State

Each `WearableProvider` owns its full connection lifecycle:

```
User taps "Connect" on a device
  ↓
UI looks up ProviderRegistry for the device's provider
  ↓
provider.createConnection(device) → BleConnection
  ↓
provider.connect(device, connection)
  ├─ connection.connect(device)          // generic BLE connect
  ├─ connection.discoverServices()       // generic GATT discovery
  ├─ provider.handshake(connection)      // provider-specific (pair, auth, subscribe)
  ↓
ConnectionState.Connected
```

**Fitbit handshake** (extracted from current diagnostic code):
1. Bond if needed (`Inspire3PairingHandler` logic).
2. Subscribe to Link Configuration characteristics.
3. Optionally initialize GoldenGate / Gattlink.

**FitCloud handshake** (new):
1. Standard BLE pairing.
2. Authenticate with device key.
3. Enable data notifications.

The generic `BleConnection` interface remains unchanged. Provider-specific logic lives in the provider's `connect()` implementation.

---

## 6. UI Abstraction

### 6.1 Current Problems

- `BlueBitAppShell` accepts 6 Fitbit-specific callbacks: `onInspectGatt`, `onBondAndInspectGatt`, `onInspectGattCache`, `onTestGattlink`, `onTestGoldenGate`, `onPairDevice`.
- `DeviceDetailScreen` has 6 hardcoded diagnostic button parameters.
- `DevicesScreen` has a hardcoded "Pair" button that calls `onPairDevice`.

### 6.2 Proposed Design

**`UiDevice` enrichment:**

```kotlin
data class UiDevice(
    val name: String,
    val address: String,
    val status: String,
    val battery: Int?,
    val deviceType: String,       // e.g. "Fitbit Inspire 3"
    val providerId: String,       // e.g. "fitbit"
    val lastSeen: String,
    val rssi: Int?,
    val recognised: Boolean,
    val manufacturerDataSummary: String,
    val capabilities: DeviceCapabilities, // NEW
)
```

**`DeviceDetailScreen` becomes dynamic:**

```kotlin
@Composable
fun DeviceDetailScreen(
    device: UiDevice,
    connected: Boolean,
    bondState: String,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onToggleConnection: () -> Unit,
    onSettings: () -> Unit,
    // REMOVED: all 6 hardcoded diagnostic callbacks
) {
    // ... existing fields ...

    // Dynamic diagnostic buttons from provider
    if (device.capabilities.diagnostics.isNotEmpty()) {
        SectionHeader(title = "Diagnostics")
        device.capabilities.diagnostics.forEach { diagnostic ->
            SecondaryButton(
                text = diagnostic.label,
                onClick = { diagnostic.action(device.address) }
            )
        }
    }
}
```

**`BlueBitAppShell` becomes provider-agnostic:**

```kotlin
@Composable
fun BlueBitAppShell(
    providerContext: ProviderContext,
    bondState: String = "UNKNOWN",
) {
    // No more onInspectGatt, onTestGattlink, etc.
    // Discovery uses providerContext.registry
    // Connection uses provider from registry
}
```

**`DevicesScreen` pair button:**

The "Pair" button should be conditional on `capabilities.supportsPairing` (added to `DeviceCapabilities`), or simply removed from the list row and moved into `DeviceDetailScreen` where the provider context is known.

---

## 7. Migration Plan

### Phase 1: Restructure Packages (no behaviour change)

1. Create new packages:
   - `bluebit.core.bluetooth`
   - `bluebit.core.connection`
   - `bluebit.core.devices`
   - `bluebit.core.crypto`
   - `bluebit.core.provider`
   - `bluebit.core.ui.*`
   - `bluebit.fitbit.*`
   - `bluebit.fitcloud.*`

2. Move shared files to `bluebit.core.*`:
   - `bluetooth/*.kt` → `core/bluetooth/`
   - `connection/*.kt` → `core/connection/`
   - `devices/*.kt` → `core/devices/`
   - `crypto/*.kt` → `core/crypto/`
   - `ui/**/*.kt` → `core/ui/`

3. Move Fitbit-specific files to `bluebit.fitbit.*`:
   - `bluetooth/android/FitbitGattDiagnostic.kt` → `fitbit/diagnostics/`
   - `bluetooth/android/FitbitGattCacheDiagnostic.kt` → `fitbit/diagnostics/`
   - `bluetooth/android/FitbitGattlinkDiagnostic.kt` → `fitbit/diagnostics/`
   - `bluetooth/android/FitbitGoldenGateDiagnostic.kt` → `fitbit/diagnostics/`
   - `bluetooth/android/Inspire3PairingHandler.kt` → `fitbit/`
   - `bluetooth/android/ServerPairingExperiment.kt` → `fitbit/diagnostics/`
   - `bluetooth/android/DirectBondExperiment.kt` → `fitbit/diagnostics/`
   - `bluetooth/FitbitGattUuids.kt` → `fitbit/`
   - `api/FitbitPairingApi.kt` → `fitbit/`
   - `bluetooth/GattlinkConnector.kt` → `fitbit/transport/FitbitGattlinkConnector.kt`
   - `bluetooth/GattlinkTransport.kt` → `fitbit/transport/FitbitGattlinkTransport.kt`
   - `bluetooth/GattlinkServiceSelector.kt` → `fitbit/transport/FitbitGattlinkServiceSelector.kt`

4. Update all `package` declarations and import statements.

5. Verify build compiles.

### Phase 2: Introduce Provider Abstraction

1. Create `WearableProvider` interface in `core/provider/`.
2. Create `ProviderRegistry`, `ProviderContext`, `DeviceCapabilities`, `ProviderDiagnostic`, `ProviderScanFilter`.
3. Implement `FitbitProvider`:
   - `canHandle()` checks for Fitbit service UUIDs.
   - `scanFilter()` returns Fitbit UUID list.
   - `identify()` uses `BlueBitDeviceMatcher` logic (or Fitbit-specific name/UUID matching).
   - `connect()` performs Fitbit-specific handshake (bond, link config, optional GoldenGate).
   - `diagnostics()` returns the 6 diagnostic actions.
4. Implement `FitCloudProvider` (stub/minimal implementation):
   - `canHandle()` returns false for now.
   - `scanFilter()` returns empty list.
   - `diagnostics()` returns empty list.
5. Update `AndroidBleScanner`:
   - Remove hardcoded Fitbit UUIDs.
   - Accept `List<ProviderScanFilter>` in constructor or `startScan()`.
   - Build `ScanFilter` list dynamically from provider filters.
6. Delete `DeviceConnectionManager`; its responsibilities move to `WearableProvider`.

### Phase 3: UI Decoupling

1. Update `UiDevice` to include `providerId` and `capabilities`.
2. Update `BlueBitAppShell`:
   - Accept `ProviderContext` instead of individual callbacks.
   - Use `ProviderRegistry` to enrich scan results.
   - Remove all Fitbit-specific callback parameters.
3. Update `DevicesScreen`:
   - Remove hardcoded `onPairDevice` parameter.
   - Show provider chip / recognised badge based on `UiDevice.providerId`.
4. Update `DeviceDetailScreen`:
   - Remove all 6 hardcoded diagnostic callbacks.
   - Render diagnostic buttons dynamically from `capabilities.diagnostics`.
5. Update `MainActivity`:
   - Instantiate `FitbitProvider` and `FitCloudProvider`.
   - Build `ProviderRegistry`.
   - Build `ProviderContext`.
   - Remove direct instantiation of all Fitbit diagnostics from `MainActivity`.
   - Pass `ProviderContext` to `BlueBitAppShell`.

### Phase 4: Cleanup

1. Delete empty old packages (`bluebit.bluetooth`, `bluebit.connection`, `bluebit.devices`, `bluebit.crypto`, `bluebit.api`).
2. Delete `AndroidBleAdapter.kt` (unused interface).
3. Verify Desktop build:
   - `DesktopBleScanner` and `DesktopBleConnection` are NoOp implementations.
   - `PairingApiTest.kt` is moved to `fitbit/` package or deleted.
4. Run full build and smoke tests.

---

## 8. File Modification List

### Moves (package renames)

| From | To |
|------|-----|
| `bluebit/Bluetooth/*.kt` | `bluebit/core/bluetooth/*.kt` |
| `bluebit/connection/*.kt` | `bluebit/core/connection/*.kt` |
| `bluebit/devices/*.kt` | `bluebit/core/devices/*.kt` |
| `bluebit/crypto/*.kt` | `bluebit/core/crypto/*.kt` |
| `bluebit/ui/**/*.kt` | `bluebit/core/ui/**/*.kt` |
| `bluebit/bluetooth/android/FitbitGattDiagnostic.kt` | `bluebit/fitbit/diagnostics/FitbitGattDiagnostic.kt` |
| `bluebit/bluetooth/android/FitbitGattCacheDiagnostic.kt` | `bluebit/fitbit/diagnostics/FitbitGattCacheDiagnostic.kt` |
| `bluebit/bluetooth/android/FitbitGattlinkDiagnostic.kt` | `bluebit/fitbit/diagnostics/FitbitGattlinkDiagnostic.kt` |
| `bluebit/bluetooth/android/FitbitGoldenGateDiagnostic.kt` | `bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt` |
| `bluebit/bluetooth/android/Inspire3PairingHandler.kt` | `bluebit/fitbit/Inspire3PairingHandler.kt` |
| `bluebit/bluetooth/android/ServerPairingExperiment.kt` | `bluebit/fitbit/diagnostics/ServerPairingExperiment.kt` |
| `bluebit/bluetooth/android/DirectBondExperiment.kt` | `bluebit/fitbit/diagnostics/DirectBondExperiment.kt` |
| `bluebit/bluetooth/FitbitGattUuids.kt` | `bluebit/fitbit/FitbitGattUuids.kt` |
| `bluebit/api/FitbitPairingApi.kt` | `bluebit/fitbit/FitbitPairingApi.kt` |
| `bluebit/bluetooth/GattlinkConnector.kt` | `bluebit/fitbit/transport/FitbitGattlinkConnector.kt` |
| `bluebit/bluetooth/GattlinkTransport.kt` | `bluebit/fitbit/transport/FitbitGattlinkTransport.kt` |
| `bluebit/bluetooth/GattlinkServiceSelector.kt` | `bluebit/fitbit/transport/FitbitGattlinkServiceSelector.kt` |
| `desktopMain/PairingApiTest.kt` | `desktopMain/fitbit/PairingApiTest.kt` |

### New Files

| File | Purpose |
|------|---------|
| `bluebit/core/provider/WearableProvider.kt` | Provider interface |
| `bluebit/core/provider/ProviderRegistry.kt` | Provider routing |
| `bluebit/core/provider/ProviderContext.kt` | Scoped container |
| `bluebit/core/provider/DeviceCapabilities.kt` | Capability descriptor |
| `bluebit/core/provider/ProviderDiagnostic.kt` | Diagnostic action descriptor |
| `bluebit/core/provider/ProviderScanFilter.kt` | Scan filter descriptor |
| `bluebit/fitbit/FitbitProvider.kt` | Fitbit provider implementation |
| `bluebit/fitbit/FitbitScanFilter.kt` | Fitbit scan filter config |
| `bluebit/fitcloud/FitCloudProvider.kt` | FitCloud provider stub |
| `bluebit/fitcloud/FitCloudUuids.kt` | FitCloud UUID constants |
| `bluebit/fitcloud/FitCloudScanFilter.kt` | FitCloud scan filter config |
| `bluebit/fitcloud/FitCloudConnection.kt` | FitCloud connection stub |
| `androidMain/core/bluetooth/AndroidBleScanner.kt` | moved + modified |
| `androidMain/core/bluetooth/AndroidBleConnection.kt` | moved |
| `androidMain/fitbit/AndroidFitbitProviderFactory.kt` | Factory for Android Fitbit provider |
| `androidMain/fitcloud/AndroidFitCloudProviderFactory.kt` | Factory for Android FitCloud provider |
| `desktopMain/core/bluetooth/DesktopBleScanner.kt` | NoOp desktop scanner |
| `desktopMain/core/bluetooth/DesktopBleConnection.kt` | NoOp desktop connection |

### Modified Files

| File | Changes |
|------|---------|
| `AndroidBleScanner.kt` | Remove hardcoded Fitbit UUIDs; accept `List<ProviderScanFilter>`; build filters dynamically |
| `BlueBitAppShell.kt` | Accept `ProviderContext`; remove 6 Fitbit callbacks; use `ProviderRegistry` for device enrichment |
| `DevicesScreen.kt` | Remove `onPairDevice`; show provider info |
| `DeviceDetailScreen.kt` | Remove 6 diagnostic callbacks; render dynamic diagnostics from `DeviceCapabilities` |
| `MainActivity.kt` | Instantiate providers + registry; pass `ProviderContext`; remove direct diagnostic instantiation |
| `UiDevice` (in `Screen.kt`) | Add `providerId`, `capabilities` fields |
| `desktopMain/BlueBitApp.kt` | Pass `ProviderContext` with NoOp providers |

### Deleted Files

| File | Reason |
|------|--------|
| `bluebit/connection/DeviceConnectionManager.kt` | Replaced by `WearableProvider` lifecycle |
| `bluebit/bluetooth/AndroidBleAdapter.kt` | Unused interface |

---

## 9. Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Import/package churn breaks build** | High | High | Do Phase 1 in a single commit. Use IDE "Move" refactoring. Verify build after every phase. |
| **GoldenGate native dependencies break** | High | Medium | `FitbitGoldenGateDiagnostic` uses `com.fitbit.goldengate.bindings.*`. Ensure package move does not affect ProGuard/R8 rules or native lib loading paths. Keep the class unchanged except for package declaration. |
| **Desktop build breaks** | Medium | Medium | Desktop currently uses `NoOpBleScanner`. After Phase 2, ensure `ProviderContext` is created with empty provider list or NoOp providers in `BlueBitApp.kt`. |
| **UI state loss during migration** | Low | Low | `BlueBitAppShell` is a Composable; state is held in `remember` blocks. Parameter changes require updating call sites but do not affect runtime state semantics. |
| **Provider abstraction is too generic** | Medium | Low | Start with only two providers (Fitbit + FitCloud stub). Iterate the `WearableProvider` interface after FitCloud integration begins. Do not over-abstract prematurely. |
| **Scan filter performance regression** | Low | Low | Currently 6 filters. Aggregating filters from providers may produce more. Android BLE scan filter limit is high (~1000). Document this limit in `ProviderRegistry`. |
| **Lost diagnostic functionality** | High | Low | The 6 Fitbit diagnostics move into `FitbitProvider.diagnostics()`. Verify each diagnostic action is still callable from the UI after migration. |
| **Circular dependencies between core and providers** | Medium | Low | `core.provider` must depend on `core.bluetooth` and `core.devices`. Providers depend on `core.provider`. Ensure `core` never imports from `fitbit` or `fitcloud`. |

---

## Appendix: Dependency Diagram (After Migration)

```
┌─────────────────────────────────────────┐
│           bluebit.core.*                │
│  ┌─────────┐ ┌──────────┐ ┌─────────┐  │
│  │bluetooth│ │ provider │ │ devices │  │
│  │  (BLE)  │ │(Registry)│ │(Profile)│  │
│  └────┬────┘ └────┬─────┘ └────┬────┘  │
│       │           │            │        │
│  ┌────┴───────────┴────────────┴────┐  │
│  │         connection (states)       │  │
│  └───────────────────────────────────┘  │
└────────────┬────────────────────────────┘
             │
       ┌─────┴─────┐
       ▼           ▼
┌────────────┐ ┌────────────┐
│bluebit.    │ │bluebit.    │
│fitbit.*    │ │fitcloud.*  │
│(Fitbit     │ │(FitCloud   │
│ Provider)  │ │ Provider)  │
└────────────┘ └────────────┘
       │
       ▼
┌─────────────────────────────────────────┐
│         bluebit.core.ui.*               │
│    (Screens, Components, Theme)         │
└─────────────────────────────────────────┘
```

**Rule:** `core` never depends on `fitbit` or `fitcloud`. UI depends only on `core`.
