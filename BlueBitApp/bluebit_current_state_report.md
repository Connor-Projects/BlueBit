# BlueBit Project Phase 1 Inspection Report

**Date:** 2026-10-10  
**Project:** `G:/Dev/BlueBit/BlueBitApp`  
**Scope:** Read-only inspection of Fitbit provider, Inspire 3 pairing code, BLE architecture, diagnostics, tests, and presence/absence of Golden Gate / Gattlink / DTLS / CoAP code.

---

## 1. Baseline Git Status

```text
git status --short
?? ../apk_analysis_8B01Fj/
?? ../fitbit_air_bridge_summary.md
```

- No modified files inside `BlueBitApp/`.
- Only untracked files are in the parent directory (`apk_analysis_8B01Fj/`, `fitbit_air_bridge_summary.md`).
- Recent commits: `7d2342fd4 Add project README`, `4a0232529 Add Git LFS tracking for large binaries`, `afd7756d6 Initial commit: transfer all BlueBit project files`.

---

## 2. Fitbit Provider Entry Points and Connection Flow

### Entry Point
- `bluebit.fitbit.FitbitProvider` — `commonMain/kotlin/bluebit/fitbit/FitbitProvider.kt`
- Implements `bluebit.core.provider.WearableProvider`.

### Instantiation
Created in `bluebit.MainActivity` (`androidMain/kotlin/bluebit/MainActivity.kt`, around line 155):

```kotlin
val fitbitProvider = FitbitProvider(
    profileRepository = profileRepository,
    connectionFactory = connectionFactory,
    diagnosticsFactory = { ... }
)
```

`connectionFactory` returns `AndroidBleConnection(context, bluetoothAdapter, logger)`.

### Discovery / Routing
1. `AndroidBleScanner.startScan()` uses hard-coded Fitbit service UUID filters.
2. `ProviderRegistry.resolve(scanResult)` calls each provider's `canHandle(scanResult)`.
3. `FitbitProvider.canHandle()` checks advertised UUIDs against:
   - `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA`
   - `0000FD63-0000-1000-8000-00805F9B34FB`
   - `ABBAFB00-E56A-484C-B832-8B17CF6CBFE8`
   - `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8`
   - `0000FD62-0000-1000-8000-00805F9B34FB`
   - `0000181D-0000-1000-8000-00805F9B34FB`

All UUIDs are centralized in `bluebit.fitbit.FitbitGattUuids`.

### Connection Flow
- `FitbitProvider.createConnection(device)` → `connectionFactory(device)` → `AndroidBleConnection`.
- `FitbitProvider.connect(device, connection)`:
  - Sets `ConnectionState.Connecting`
  - Calls `connection.connect(device)`
  - On success sets `ConnectionState.Connected`; on failure `ConnectionState.Disconnected`
- `FitbitProvider.disconnect(connection)` calls `connection.disconnect()`.

### Current Provider Limitations
- `FitbitProvider.sync()` returns `flowOf(null)` (not implemented).
- `DeviceConnectionManager.connect(profile)` is `TODO("Not yet implemented")`.
- `InMemoryDeviceProfileRepository.defaultProfiles` is `emptyList()`.

---

## 3. Existing Inspire 3 Specific Code

### Primary Pairing Handler
- **File:** `androidMain/kotlin/bluebit/fitbit/Inspire3PairingHandler.kt`
- **Class:** `Inspire3PairingHandler`
- **Method:** `suspend fun pairDevice(device: BluetoothDevice, extraCaptureMs: Long = 0L): Boolean`

Sequence implemented:
1. Connect GATT (`BluetoothDevice.TRANSPORT_LE`).
2. Discover services.
3. Read ephemeral pointer `AC2F0145` under service `AC2F0045`.
4. Parse 16-byte response as UUID (big-endian).
5. Write `0x00` to parsed ephemeral characteristic with `WRITE_TYPE_NO_RESPONSE`.
6. Enable notifications on Gattlink transmit characteristic `ABBAFF02`.
7. Listen for `RESET_REQUEST (0x80)` on `ABBAFF02`.
8. Respond with `RESET_COMPLETE (0x81 00 00 08 08)` on `ABBAFF01`.
9. Optionally capture extra traffic, then leave connection open on success.

Side effects:
- Logs every step to logcat with tag `BlueBitInspire3Pairing`.
- Writes captured Gattlink frames to `<filesDir>/gattlink_dump_<timestamp>.txt` via `flushDump()`.

### Inspire 3 Diagnostics
All in `androidMain/kotlin/bluebit/fitbit/diagnostics/`:

| File | Purpose |
|------|---------|
| `FitbitGoldenGateDiagnostic.kt` | Full GoldenGate native stack initialization + CoAP `bond/control` experiments on Inspire 3. |
| `GattlinkStartupDiagnostic.kt` | Standalone Gattlink startup sequence (cache read → ephemeral write → enable notifications → listen). |
| `FitbitGattlinkDiagnostic.kt` | Receive-only Gattlink traffic logger. |
| `FitbitGattCacheDiagnostic.kt` | Reproduces APK post-connection GATT cache validation read/write. |
| `FitbitGattDiagnostic.kt` | Generic GATT service discovery dumper. |
| `ServerPairingExperiment.kt` | Mock Fitbit server `validate.json` / `pair.json` experiment. |
| `DirectBondExperiment.kt` | Tests `BluetoothDevice.createBond()` without protocol assistance. |

### Inspire 3 UUID Constants
All in `commonMain/kotlin/bluebit/fitbit/FitbitGattUuids.kt`:
- Gattlink service: `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8`
- Gattlink receive (write): `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8`
- Gattlink transmit (notify): `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8`
- GATT cache service: `AC2F0045-8182-4BE5-91E0-2992E6B40EBB`
- Ephemeral pointer: `AC2F0145-8182-4BE5-91E0-2992E6B40EBB`
- Modern Fitbit Gattlink service: `0000FD62-0000-1000-8000-00805F9B34FB`

---

## 4. Presence / Absence of Golden Gate, Gattlink, DTLS, CoAP

### Present — Android-only (`androidMain/kotlin/com/fitbit/goldengate/bindings/`)

| Component | Files |
|-----------|-------|
| **GoldenGate native loader** | `GoldenGate.kt` — loads `libxp.so`, exposes `init()`, `getVersion()`, native `initModulesJNI()`, etc. |
| **Bridge / transport sink-source** | `Bridge.kt`, `io/RxSource.kt`, `io/TxSink.kt`, `DataSinkDataSource.kt` |
| **Gattlink stack configs** | `stack/StackConfig.kt`, `stack/GattlinkStackConfig.kt`, `stack/DtlsSocketNetifGattlinkStackConfig.kt` |
| **DTLS key resolution** | `dtls/TlsKeyResolver.kt`, `dtls/TlsKeyResolverRegistry.kt`, `dtls/BootstrapTlsKeyResolver.kt`, `dtls/HelloTlsKeyResolver.kt` |
| **CoAP endpoint** | `coap/CoapEndpoint.kt`, `coap/SingleCoapResponseListener.kt`, `coap/Endpoint.kt`, plus `coap/data/*` and `coap/block/*` message builders |
| **Node key** | `node/BluetoothAddressNodeKey.kt`, `node/NodeKey.kt` |
| **Native library** | `app/src/androidMain/jniLibs/arm64-v8a/libxp.so` (ELF aarch64, NDK r29, Android 30) |

### Active Usage
- `FitbitGoldenGateDiagnostic.startExperiment(device)` performs the complete link-up:
  1. `GoldenGate.init()`
  2. Creates `Bridge` (TxSink / RxSource)
  3. Connects BLE, discovers services, reads/writes GATT cache confirmation
  4. Creates `Stack` with `DtlsSocketNetifGattlinkStackConfig` (descriptor `"DSNG"`)
  5. Attaches `CoapEndpoint`
  6. Starts bridge + stack, updates MTU
  7. Sends CoAP `pair/display` and `bond/control` experiments

### Gattlink Transport Abstraction (Common)
- `commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkConnector.kt`
- `commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkServiceSelector.kt`
- `commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkTransport.kt`

These are currently **not wired into `FitbitProvider.connect()`**; they are exercised only by diagnostics/tests.

### Absent
- No generic KMP DTLS/CoAP implementation outside the Android GoldenGate JNI wrappers.
- No server-side DTLS bootstrap service in this repo.

---

## 5. Provider Architecture Summary

```
WearableProvider (interface)
├── FitbitProvider  ←→  FitbitGattUuids / Inspire3PairingHandler / diagnostics
└── FitCloudProvider

ProviderRegistry
├── resolve(scanResult)       // first provider with canHandle == true
├── identify(scanResult)      // provider + DeviceProfile
├── allScanFilters()          // aggregated BLE scan filters
└── findById(id)

ProviderContext(registry, scanner)

BLE abstraction (commonMain)
├── BleScanner / BleScanResult / BleDevice / BleService / BleNotification
├── BleConnection (interface)
│   └── AndroidBleConnection (androidMain)
└── BleLogWatchdog (global in-memory ring buffer)

Connection lifecycle
├── DeviceConnectionManager  (commonMain) — TODO for profile-based connect
└── PairedDeviceCoordinator  (commonMain) — persists records, reconnect, retry
```

### Key Classes
- `bluebit.core.provider.WearableProvider` — provider contract.
- `bluebit.core.provider.ProviderRegistry` — routing.
- `bluebit.core.provider.ProviderContext` — holds registry + scanner.
- `bluebit.core.bluetooth.BleConnection` / `bluebit.core.bluetooth.android.AndroidBleConnection`.
- `bluebit.core.bluetooth.BleScanner` / `bluebit.core.bluetooth.android.AndroidBleScanner`.
- `bluebit.core.pairing.PairedDeviceCoordinator` / `PairedDeviceRecord` / `PairedDeviceStore`.
- `bluebit.core.devices.BlueBitDeviceMatcher` / `DeviceProfileRepository` / `DeviceProfile`.

---

## 6. Diagnostic Locations

### Structured In-Memory Log
- `bluebit.core.bluetooth.BleLogWatchdog`
- Global singleton `bleLogWatchdog`
- Tags: `SCAN`, `CONNECTION`, `GATT_SERVICES`, `GATT_READ`, `GATT_WRITE`, `GATT_NOTIFY`, `GATT_DESCRIPTOR`, `BOND`, `PROVIDER_CONNECTOR`.
- Used by `AndroidBleScanner`, `AndroidBleConnection`, `GattlinkStartupDiagnostic`.

### Android Logcat Tags
- `BlueBitScan` — scan results, permissions, watchdog.
- `BlueBitBle` — `AndroidBleConnection` logger callback.
- `BlueBitGatt` — `FitbitGattDiagnostic`.
- `BlueBitGattCache` — `FitbitGattCacheDiagnostic`.
- `BlueBitGattlink` — `FitbitGattlinkDiagnostic`.
- `BlueBitGG_Diag` / `BlueBitGG` / `BlueBitGG_*` — GoldenGate diagnostics.
- `BlueBitInspire3Pairing` — `Inspire3PairingHandler`.
- `BlueBitDirectBond` — `DirectBondExperiment`.

### File Dumps
- `Inspire3PairingHandler` writes `gattlink_dump_<yyyyMMdd_HHmmss_SSS>.txt` to `context.filesDir`.

### Diagnostic UI Wiring
- `MainActivity` constructs diagnostics as `ProviderDiagnostic` instances and passes them to `FitbitProvider` via `diagnosticsFactory`.
- Diagnostic actions are surfaced through `DeviceCapabilities.diagnostics` → UI.

---

## 7. Test Files Found

All tests are under `app/src/desktopTest/kotlin/` and use JUnit 5 + kotlinx-coroutines-test.

| Test File | Coverage |
|-----------|----------|
| `bluebit/bluetooth/BleServiceDataTest.kt` | `BleServiceData.parse()` exact 8-byte parsing. |
| `bluebit/bluetooth/BlueBitDeviceMatcherTest.kt` | Service-data and name-based device matching. |
| `bluebit/bluetooth/DeviceServiceDataParserTest.kt` | Advertisement service-data parsing. |
| `bluebit/bluetooth/FakeBleConnection.kt` | Fake implementation of `BleConnection` for tests. |
| `bluebit/bluetooth/FitbitGattUuidsTest.kt` | UUID constant correctness. |
| `bluebit/bluetooth/GattlinkConnectorTest.kt` | `FitbitGattlinkConnector.runLinkUp()` subscriptions. |
| `bluebit/bluetooth/GattlinkServiceSelectorTest.kt` | Modern vs. legacy Gattlink service selection. |
| `bluebit/bluetooth/GattlinkStartupSequenceTest.kt` | Full Inspire 3 startup sequence against fake connection. |
| `bluebit/crypto/AdvertisementVerifierTest.kt` | Advertisement crypto verification. |
| `bluebit/pairing/PairedDeviceCoordinatorTest.kt` | Provider routing, persistence, reconnect, retry bounds. |

Also under `desktopMain/kotlin/bluebit/fitbit/PairingApiTest.kt` (executable test harness, not a unit test).

**No Android instrumentation tests were found.**

---

## 8. Recommended Integration Point for a DTLS Bootstrap Probe

The cleanest place to add a non-destructive DTLS bootstrap probe is alongside the existing diagnostic layer, because it already isolates experiments from the main provider flow.

### Recommended File
Create `androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt` modeled on `FitbitGoldenGateDiagnostic.kt`.

### Recommended Hook
In `MainActivity` (`androidMain/kotlin/bluebit/MainActivity.kt`, inside `diagnosticsFactory`), add:

```kotlin
ProviderDiagnostic(
    id = "dtls_bootstrap_probe",
    label = "Probe DTLS Bootstrap",
    action = { address ->
        val device = adapter.getRemoteDevice(address)
        DtlsBootstrapProbe(context).runProbe(device)
    }
)
```

### Probe Implementation Outline
1. Connect GATT using the same sequence as `Inspire3PairingHandler` (cache read/write, enable `ABBAFF02` notifications).
2. Build a `Bridge` and `Stack` with `DtlsSocketNetifGattlinkStackConfig` (`"DSNG"`).
3. Install a probe-only `TlsKeyResolver` that logs the requested `keyId` and returns a deterministic test PSK (or null to observe handshake rejection).
4. Start the stack and observe:
   - Gattlink `RESET_REQUEST`/`RESET_COMPLETE` exchange.
   - DTLS handshake messages via the resolver log.
   - First CoAP message type or error code.
5. Disconnect cleanly and report `BleLogWatchdog` / logcat results.

### Alternative: Provider-Level Probe
If the probe must run automatically during normal connection, insert it in `FitbitProvider.connect()` after `connection.connect(device)` but before setting `ConnectionState.Connected`. This is more invasive and risks breaking the core flow; the diagnostic path is preferred for Phase 1/2 experimentation.

---

*End of report.*
