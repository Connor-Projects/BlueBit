# BlueBit FitCloud Pro Integration — Final Report

## 1. Executive Summary

This report evaluates the FitCloud Pro SDK as the BLE wearable backend for BlueBit and proposes a provider-based architecture that decouples the generic Bluetooth layer from device-specific logic. By introducing a `WearableProvider` abstraction, BlueBit can support both Fitbit and FitCloud families without hardcoded UUIDs, scan filters, or UI coupling. The migration is staged across four phases—package restructure, provider abstraction, UI decoupling, and cleanup—delivering an MVP focused on device scanning, connection, battery, and heart-rate before moving to advanced features such as sleep, notifications, and OTA.

---

## 2. FitCloud Pro SDK Analysis

### 2.1 Repository Information

| Attribute | Value |
|-----------|-------|
| Repository | `https://github.com/htangsmart/FitCloudPro-SDK-Android` |
| Stars / Forks / Issues | 48 / 33 / 7 |
| Author | htangsmart (Topstep, Shenzhen, China) |
| License | Not explicitly stated (assume proprietary) |
| Latest Version | v3.0.2.4 (2026-07-02) |

### 2.2 Dependencies and Maven Setup

The SDK is published to a private Chinese Maven repository over HTTP:

```groovy
maven {
    url "http://120.78.153.20:8081/repository/maven-public/"
    allowInsecureProtocol = true
}
```

Core dependencies:

```groovy
def weakit_version = "3.0.2.4"
implementation("com.topstep.wearkit:sdk-base:$weakit_version")
implementation("com.topstep.wearkit:sdk-fitcloud:$weakit_version")
```

Optional Realtek modules (may cause duplicate-class issues):

```groovy
exclude group: "com.topstep.wearkit", module: "ext-realtek-bbpro"
exclude group: "com.topstep.wearkit", module: "ext-realtek-file"
```

### 2.3 SDK Modules

| Module | Package | Purpose |
|--------|---------|---------|
| `sdk-base` | `com.topstep.wearkit.base` | BLE primitives, connector states, auth, error handling |
| `sdk-fitcloud` | `com.topstep.wearkit.base.connector` | FitCloud-specific BLE connection abstractions |

### 2.4 BLE Connector Architecture

Key classes identified from generated Dokka documentation:

| Class / Enum | Role |
|--------------|------|
| `SimpleConnection` | BLE connection abstraction |
| `ConnectorState` | `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `PRE_CONNECTING`, `PRE_CONNECTED` |
| `AuthMode` | `AUTO`, `BIND`, `LOGIN` |
| `AutoReconnectMode` | `NEVER`, `BALANCED`, `LOW_LATENCY`, `LOW_POWER` |
| `ConnectorError` | Error wrapper with retry logic |

### 2.5 Feature Matrix

| Feature | Status |
|---------|--------|
| Sync data (steps, sleep, heart rate, sport) | Supported |
| Real-time data streaming | Supported |
| Notifications & messages (SMS, app, telephony) | Supported |
| Device info & configs (display, wear hand, time format, units, health monitor, sedentary, HR alarm, BP alarm, turn-wrist lighting, DND, women's health, protection reminder, hand washing, screen/vibrate) | Supported |
| Weather sync | Supported |
| Contacts | Supported |
| Alarms | Supported |
| Battery query | Supported |
| Device reset / reboot / shutdown | Supported |
| QR codes | Supported |
| Sport data (rope skipping, rowing, swimming) | Supported |
| Sleep (REM, nap support) | Supported |
| Heart rate monitoring | Supported |
| Blood pressure monitoring | Supported |
| OTA / Firmware update (DFU) | Supported |
| Dial / watch-face customization | Supported |
| Music control | Supported |
| E-book | Supported |
| Album | Supported |
| AI health guidance (speech AIGC) | Supported |
| Advanced reminders | Supported |
| Schedules and habits | Supported |
| Sport connectivity | Supported |
| Customized features (photovoltaic, cricket match) | Supported |
| WeChat Pay authentication | Supported |
| ZALO business card | Supported |
| GPS & 4G firmware info | Supported |
| Task function | Supported |
| 16 k page size support (Android 15) | Supported |

### 2.6 Limitations and Risks

| Limitation / Risk | Impact | Mitigation |
|-------------------|--------|------------|
| Requires Chinese Maven repository over HTTP (`allowInsecureProtocol = true`) | Security & trust concern | Isolate Maven repo config in a dedicated `repositories.gradle` file; document as vendor dependency |
| Proprietary SDK (not open source) | Long-term vendor lock-in; no source-level debugging | Wrap SDK calls behind `WearableProvider` interface so vendor can be swapped later |
| Realtek dependencies may cause duplicate-class issues | Build breakage on some devices | Explicitly exclude `ext-realtek-bbpro` and `ext-realtek-file` unless file-transfer/OTA is required |
| No explicit BLE service UUIDs documented publicly | Discovery logic must be empirically determined | Capture UUIDs from working sample app or packet trace; store in `FitCloudUuids.kt` |
| Authentication may require device binding with official app first | User friction | Implement `AuthMode.BIND` flow in `FitCloudProvider.connect()` and document prerequisite |

---

## 3. Proposed Architecture

### 3.1 Provider-Based Design

BlueBit is refactored into three layers:

- **`bluebit.core.*`** — Generic BLE abstractions, connection state machines, device profiles, crypto, UI screens, and the provider framework.
- **`bluebit.fitbit.*`** — Fitbit-specific provider, UUIDs, diagnostics, transport (Gattlink / GoldenGate), and pairing API.
- **`bluebit.fitcloud.*`** — FitCloud-specific provider, UUIDs, scan filter, and connection logic.

**Dependency rule:** `core` never imports from `fitbit` or `fitcloud`. UI depends only on `core`.

### 3.2 Directory Structure

```
app/src/commonMain/kotlin/bluebit/
  core/
    bluetooth/
    connection/
    provider/
    devices/
    crypto/
    ui/
  fitbit/
    diagnostics/
    transport/
  fitcloud/

app/src/androidMain/kotlin/bluebit/
  core/bluetooth/
  fitbit/
  fitcloud/

app/src/desktopMain/kotlin/bluebit/
  core/bluetooth/
```

### 3.3 Key Interfaces

#### `WearableProvider`

The sole integration point for a wearable family:

```kotlin
interface WearableProvider {
    val name: String
    val id: String

    fun canHandle(scanResult: BleScanResult): Boolean
    fun scanFilter(): ProviderScanFilter
    fun identify(scanResult: BleScanResult): DeviceProfile?
    fun createConnection(device: BleDevice): BleConnection

    suspend fun connect(device: BleDevice, connection: BleConnection): Result<Unit>
    suspend fun disconnect(connection: BleConnection)

    val connectionState: StateFlow<ConnectionState>
    fun capabilities(device: BleDevice): DeviceCapabilities
    suspend fun sync(device: BleDevice): Flow<Int?>
    fun diagnostics(): List<ProviderDiagnostic>
}
```

#### `ProviderRegistry`

Routes scan results to the correct provider and aggregates scan filters:

```kotlin
class ProviderRegistry(providers: List<WearableProvider>) {
    fun resolve(scanResult: BleScanResult): WearableProvider?
    fun allScanFilters(): List<ProviderScanFilter>
    fun identify(scanResult: BleScanResult): Pair<WearableProvider, DeviceProfile>?
}
```

#### `ProviderContext`

Scoped container passed to UI and connection layers, replacing direct scanner / connection-manager references:

```kotlin
class ProviderContext(
    val registry: ProviderRegistry,
    val scanner: BleScanner,
)
```

#### Supporting Data Classes

- `DeviceCapabilities` — exposes what a device can do (steps, HR, sleep, battery, OTA, diagnostics).
- `ProviderDiagnostic` — dynamic diagnostic action exposed to the UI (no hardcoded buttons).
- `ProviderScanFilter` — platform-agnostic scan-filter descriptor (`serviceUuids`, `manufacturerIds`).

---

## 4. Migration Plan

### Phase 1: Package Restructure (no behaviour change)

1. Create new packages: `bluebit.core.*`, `bluebit.fitbit.*`, `bluebit.fitcloud.*`.
2. Move shared files (`bluetooth`, `connection`, `devices`, `crypto`, `ui`) into `bluebit.core.*`.
3. Move Fitbit-specific files (diagnostics, Gattlink transport, UUIDs, pairing API) into `bluebit.fitbit.*`.
4. Update all `package` declarations and imports.
5. Verify build compiles.

### Phase 2: Provider Abstraction

1. Create `WearableProvider` interface and supporting classes in `core/provider/`.
2. Create `ProviderRegistry` and `ProviderContext`.
3. Implement `FitbitProvider`:
   - `canHandle()` → Fitbit UUID check.
   - `scanFilter()` → returns Fitbit UUID list.
   - `connect()` → bond, link config, optional GoldenGate.
   - `diagnostics()` → the 6 existing diagnostic actions.
4. Implement `FitCloudProvider` (stub/minimal):
   - `canHandle()` returns `false` initially.
   - Empty scan filter and diagnostics.
5. Update `AndroidBleScanner` to accept `List<ProviderScanFilter>` and build filters dynamically.
6. Delete `DeviceConnectionManager`; responsibilities move to `WearableProvider`.

### Phase 3: UI Decoupling

1. Enrich `UiDevice` with `providerId` and `capabilities`.
2. Update `BlueBitAppShell`:
   - Accept `ProviderContext` instead of 6 Fitbit-specific callbacks.
   - Use `ProviderRegistry` to enrich scan results.
3. Update `DevicesScreen`:
   - Remove hardcoded `onPairDevice`.
   - Show provider chip / recognised badge.
4. Update `DeviceDetailScreen`:
   - Remove 6 hardcoded diagnostic callbacks.
   - Render diagnostic buttons dynamically from `capabilities.diagnostics`.
5. Update `MainActivity`:
   - Instantiate providers + registry.
   - Build `ProviderContext`.
   - Remove direct diagnostic instantiation.

### Phase 4: Cleanup

1. Delete empty old packages (`bluebit.bluetooth`, `bluebit.connection`, `bluebit.devices`, `bluebit.crypto`, `bluebit.api`).
2. Delete `AndroidBleAdapter.kt` (unused interface).
3. Verify Desktop build with NoOp providers.
4. Run full build and smoke tests.

---

## 5. File Modification List

### Moves (Package Renames)

| From | To |
|------|-----|
| `bluebit/bluetooth/*.kt` | `bluebit/core/bluetooth/*.kt` |
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
| `androidMain/core/bluetooth/AndroidBleScanner.kt` | Moved + modified |
| `androidMain/core/bluetooth/AndroidBleConnection.kt` | Moved |
| `androidMain/fitbit/AndroidFitbitProviderFactory.kt` | Factory for Android Fitbit provider |
| `androidMain/fitcloud/AndroidFitCloudProviderFactory.kt` | Factory for Android FitCloud provider |
| `desktopMain/core/bluetooth/DesktopBleScanner.kt` | NoOp desktop scanner |
| `desktopMain/core/bluetooth/DesktopBleConnection.kt` | NoOp desktop connection |

### Modified Files

| File | Changes |
|------|---------|
| `AndroidBleScanner.kt` | Remove hardcoded Fitbit UUIDs; accept `List<ProviderScanFilter>`; build filters dynamically |
| `BlueBitAppShell.kt` | Accept `ProviderContext`; remove 6 Fitbit callbacks; use `ProviderRegistry` |
| `DevicesScreen.kt` | Remove `onPairDevice`; show provider info |
| `DeviceDetailScreen.kt` | Remove 6 diagnostic callbacks; render dynamic diagnostics |
| `MainActivity.kt` | Instantiate providers + registry; pass `ProviderContext`; remove direct diagnostic instantiation |
| `UiDevice` (in `Screen.kt`) | Add `providerId`, `capabilities` fields |
| `desktopMain/BlueBitApp.kt` | Pass `ProviderContext` with NoOp providers |

### Deleted Files

| File | Reason |
|------|--------|
| `bluebit/connection/DeviceConnectionManager.kt` | Replaced by `WearableProvider` lifecycle |
| `bluebit/bluetooth/AndroidBleAdapter.kt` | Unused interface |

---

## 6. MVP Feature Priority

### P0 — Foundation
- Device scanning with provider-agnostic filters
- Connect / disconnect lifecycle
- Device info query
- Battery query

### P1 — Core Health
- Heart rate (live + historical sync)
- Steps / basic activity tracking

### P2 — Lifestyle
- Sleep data (light, deep, REM, nap)
- Notifications and messages (SMS, app, telephony)
- Weather sync

### P3 — Advanced
- Dial / watch-face customization
- OTA / firmware update (DFU)
- Sport data (rope skipping, rowing, swimming)
- Music control, alarms, contacts
- AI health guidance and advanced reminders

---

## 7. Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Import/package churn breaks build | High | High | Do Phase 1 in a single commit; use IDE "Move" refactoring; verify build after every phase |
| GoldenGate native dependencies break | High | Medium | Keep class contents unchanged except package declaration; verify ProGuard / R8 rules and native lib loading paths |
| Desktop build breaks | Medium | Medium | Ensure `ProviderContext` is created with empty provider list or NoOp providers in `BlueBitApp.kt` |
| UI state loss during migration | Low | Low | Composable state lives in `remember` blocks; parameter changes require call-site updates only |
| Provider abstraction is too generic | Medium | Low | Start with only two providers (Fitbit + FitCloud stub); iterate `WearableProvider` after FitCloud integration begins |
| Scan filter performance regression | Low | Low | Android BLE scan filter limit is high (~1000); document limit in `ProviderRegistry` |
| Lost diagnostic functionality | High | Low | Move 6 Fitbit diagnostics into `FitbitProvider.diagnostics()`; verify each is still callable from UI |
| Circular dependencies between core and providers | Medium | Low | `core.provider` depends only on `core.bluetooth` and `core.devices`; providers depend on `core.provider`; `core` never imports from `fitbit` or `fitcloud` |
| FitCloud SDK HTTP Maven repository | Medium | Medium | Isolate repo config; wrap SDK calls behind provider interface; prepare for future vendor swap |
| FitCloud authentication requires prior app binding | Medium | Medium | Implement `AuthMode.BIND` flow; document user prerequisite in onboarding |

---

## 8. Next Steps

1. **Approve architecture** — Review this report and the `architecture_proposal.md` with the team.
2. **Create feature branch** — Branch off `main` for the four-phase migration.
3. **Execute Phase 1** — Restructure packages using IDE safe-move; open a PR with zero behavioural changes.
4. **Execute Phase 2** — Introduce `WearableProvider`, `ProviderRegistry`, `FitbitProvider`, and `FitCloudProvider` stub; merge after CI passes.
5. **Execute Phase 3** — Decouple UI from Fitbit specifics; merge after manual smoke test on Android.
6. **Execute Phase 4** — Delete obsolete files; verify Desktop build; final QA.
7. **Begin FitCloud integration** — Add Maven repository config, pull in `sdk-base` and `sdk-fitcloud`, implement `FitCloudProvider.canHandle()`, `scanFilter()`, and `connect()` against a real device.
8. **Iterate MVP** — Deliver P0 features first, then P1, P2, and P3 in fortnightly sprints.
