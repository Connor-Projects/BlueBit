# BlueBit Code Inventory

Generated from `G:\\Dev\\BlueBit\\BlueBitApp\\app\\src` on 2026-10-17.
This is a read-only inventory; no source files were modified.

## 1. Source directory layout

```
G:/Dev/BlueBit/BlueBitApp/app/src
├── androidMain/
│   ├── AndroidManifest.xml
│   ├── jniLibs/arm64-v8a/libxp.so
│   └── kotlin/
│       ├── bluebit/
│       │   ├── MainActivity.kt
│       │   ├── bluetooth/android/DirectBondExperiment.kt
│       │   ├── core/bluetooth/android/
│       │   │   ├── AndroidBleConnection.kt
│       │   │   └── AndroidBleScanner.kt
│       │   ├── fitbit/
│       │   │   ├── Inspire3PairingHandler.kt
│       │   │   ├── diagnostics/
│       │   │   │   ├── CoapReadOnlyProbe.kt
│       │   │   │   ├── DtlsBootstrapProbe.kt
│       │   │   │   ├── FitbitGattCacheDiagnostic.kt
│       │   │   │   ├── FitbitGattDiagnostic.kt
│       │   │   │   ├── FitbitGattlinkDiagnostic.kt
│       │   │   │   ├── FitbitGoldenGateDiagnostic.kt
│       │   │   │   ├── GattlinkStartupDiagnostic.kt
│       │   │   │   └── ServerPairingExperiment.kt
│       │   │   └── ...
│       │   ├── fitcloud/
│       │   │   ├── AndroidFitCloudProvider.kt
│       │   │   ├── FitCloudDialInspector.kt
│       │   │   ├── FitCloudSdkBleConnection.kt
│       │   │   └── FitCloudSdkInit.kt
│       │   └── pairing/SharedPreferencesPairedDeviceStore.kt
│       └── com/fitbit/goldengate/bindings/
│           ├── Bridge.kt
│           ├── DataSinkDataSource.kt
│           ├── GoldenGate.kt
│           ├── GoldenGateNativeException.kt
│           ├── GoldenGateNativeResult.kt
│           ├── NativeReference.kt
│           ├── NativeReferenceCreationResult.kt
│           ├── NativeReferenceWithCallback.kt
│           ├── RunLoop.kt
│           ├── coap/
│           │   ├── CoapEndpoint.kt
│           │   ├── CoapGroupRequestFilter.kt
│           │   ├── CoapGroupRequestFilterMode.kt
│           │   ├── CoapResponseListener.kt
│           │   ├── Endpoint.kt
│           │   ├── SingleCoapResponseListener.kt
│           │   ├── block/
│           │   └── data/
│           │       ├── BaseOutgoingMessageBuilder.kt
│           │       ├── OptionsBuilder.kt
│           │       ├── OutgoingMessageBuilder.kt
│           │       ├── OutgoingRequestBuilder.kt
│           │       ├── Method.kt
│           │       ├── ResponseCode.kt
│           │       └── ...
│           ├── dtls/
│           │   ├── BootstrapTlsKeyResolver.kt
│           │   ├── HelloTlsKeyResolver.kt
│           │   ├── TlsKeyResolver.kt
│           │   └── TlsKeyResolverRegistry.kt
│           ├── io/
│           │   ├── RxSource.kt
│           │   └── TxSink.kt
│           ├── node/
│           │   ├── BluetoothAddressNodeKey.kt
│           │   └── NodeKey.kt
│           └── stack/
│               ├── DtlsSocketNetifGattlinkStackConfig.kt
│               ├── GattlinkStackConfig.kt
│               ├── Stack.kt
│               └── StackConfig.kt
├── commonMain/kotlin/bluebit/
│   ├── BlueBitAppShell.kt
│   ├── core/bluetooth/
│   │   ├── BleConnection.kt
│   │   ├── BleDevice.kt
│   │   ├── BleLogWatchdog.kt
│   │   ├── BleNotification.kt
│   │   ├── BleScanResult.kt
│   │   ├── BleScanner.kt
│   │   ├── BleService.kt
│   │   ├── BleServiceData.kt
│   │   └── DeviceServiceDataParser.kt
│   ├── core/connection/
│   │   ├── DeviceConnectionManager.kt
│   │   └── DiscoveryState.kt
│   ├── core/crypto/
│   │   ├── AdvertisementVerifier.kt
│   │   └── KeyProvider.kt
│   ├── core/devices/
│   │   ├── BlueBitDeviceMatcher.kt
│   │   ├── DevicePhylum.kt
│   │   ├── DeviceProfile.kt
│   │   ├── DeviceProfileRepository.kt
│   │   └── PairingMethod.kt
│   ├── core/diagnostics/
│   │   └── CoapReadOnlyResult.kt
│   ├── core/pairing/
│   │   ├── PairedDeviceCoordinator.kt
│   │   ├── PairedDeviceRecord.kt
│   │   └── PairedDeviceStore.kt
│   ├── core/provider/
│   │   ├── DeviceCapabilities.kt
│   │   ├── ProviderContext.kt
│   │   ├── ProviderDiagnostic.kt
│   │   ├── ProviderRegistry.kt
│   │   ├── ProviderScanFilter.kt
│   │   └── WearableProvider.kt
│   ├── core/ui/
│   │   ├── components/
│   │   └── screens/
│   │       ├── DeviceDetailScreen.kt
│   │       └── ...
│   ├── fitbit/
│   │   ├── FitbitGattUuids.kt
│   │   ├── FitbitPairingApi.kt
│   │   ├── FitbitProvider.kt
│   │   ├── diagnostics/CoapReadOnlyProbeLogic.kt
│   │   ├── diagnostics/DtlsProbeStateMachine.kt
│   │   └── transport/
│   │       ├── FitbitGattlinkConnector.kt
│   │       ├── FitbitGattlinkServiceSelector.kt
│   │       └── FitbitGattlinkTransport.kt
│   └── fitcloud/
│       ├── FitCloudProvider.kt
│       └── FitCloudUuids.kt
├── desktopMain/kotlin/bluebit/
│   ├── BlueBitApp.kt
│   └── fitbit/PairingApiTest.kt
└── desktopTest/kotlin/bluebit/
    └── (unit tests)
```

## 2. File inventory by topic

Line counts are approximate (`wc -l`) and include blanks/comments.

### 2.1 BLE scanning, connection, GATT, ATT

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleConnection.kt` | 64 | KMP `BleConnection` interface plus a `NoOpBleConnection` stub; defines connect, discover services, read/write/notify, disconnect, and state Flows. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleScanner.kt` | 22 | KMP `BleScanner` interface plus `NoOpBleScanner`; exposes scan results, scanning state, start/stop. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleDevice.kt` | 6 | Simple `BleDevice` data class: address and name. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleScanResult.kt` | 18 | Advertisement model: name, address, RSSI, service UUIDs, manufacturer/service data, helpers for DIS payload. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleService.kt` | 11 | `BleService`/`BleCharacteristic`/`BleDescriptor` wrappers. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleNotification.kt` | 22 | Notification/indication value model with service/characteristic UUIDs. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleServiceData.kt` | 30 | Parser for the Device Information Service payload used to identify Fitbit device type IDs. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/DeviceServiceDataParser.kt` | 9 | Convenience parser wrapper around `BleServiceData`. |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/AndroidBleAdapter.kt` | 35 | Android BLE adapter helper (enable/check). |
| `app/src/commonMain/kotlin/bluebit/core/bluetooth/BleLogWatchdog.kt` | 84 | Logging helper/tag system for BLE operations. |
| `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/AndroidBleScanner.kt` | 243 | Android implementation of `BleScanner` using `BluetoothLeScanner`; filtered and unfiltered scan modes. |
| `app/src/androidMain/kotlin/bluebit/core/bluetooth/android/AndroidBleConnection.kt` | 353 | Android implementation of `BleConnection` using raw `BluetoothGattCallback`; connect, bond, discover, read/write descriptors, enable notifications, request MTU/PHY. |
| `app/src/androidMain/kotlin/bluebit/bluetooth/android/DirectBondExperiment.kt` | 121 | Diagnostic experiment calling `BluetoothDevice.createBond()` directly, without GoldenGate/CoAP, to test Android SMP pairing. |

### 2.2 Fitbit Golden Gate / Gattlink / DTLS / CoAP

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/commonMain/kotlin/bluebit/fitbit/FitbitGattUuids.kt` | 34 | UUID constants for Fitbit/GoldenGate services and characteristics: `ABBAFF00...`, `ABBAFF01...`, `ABBAFF02...`, `AC2F0045...`, `AC2F0145...`. |
| `app/src/commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkTransport.kt` | 8 | Minimal interface abstraction for Gattlink byte transport. |
| `app/src/commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkConnector.kt` | 35 | Bridges raw BLE `BluetoothGatt` writes/notifications into `FitbitGattlinkTransport`. |
| `app/src/commonMain/kotlin/bluebit/fitbit/transport/FitbitGattlinkServiceSelector.kt` | 16 | Selects the GoldenGate Gattlink service/characteristics from discovered GATT services. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/GoldenGate.kt` | 102 | Loads `libxp.so`, exposes `init()`/`check()`, and holds the native library state. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/RunLoop.kt` | 39 | Native run-loop thread (`startLoopJNI`/`stopLoopJNI`) required by the GoldenGate native stack. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/Bridge.kt` | 51 | Joins a native `TxSink` (outgoing to BLE) and `RxSource` (incoming from BLE) into one `DataSinkDataSource`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/io/TxSink.kt` | 51 | Native TX sink: native code calls `putData(byte[])` when it has bytes to send over BLE. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/io/RxSource.kt` | 40 | Native RX source: Kotlin pushes received BLE bytes into native via `receiveData(byte[])`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/DataSinkDataSource.kt` | 6 | Interface combining data-source and data-sink native pointers. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/NativeReference.kt` | 5 | Base interface returning the native pointer (`getThisPointer(): Long`). |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/NativeReferenceCreationResult.kt` | 12 | Wraps native object creation result code and pointer. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/GoldenGateNativeResult.kt` | 22 | Result-code enum for native calls: `GG_SUCCESS`, `GG_FAILURE`, `GG_ERROR_WOULD_BLOCK`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/StackConfig.kt` | 15 | Abstract IPv4/UDP stack configuration (local/remote address/port, window options). |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/GattlinkStackConfig.kt` | 11 | Pure-Gattlink stack config with `0.0.0.0` endpoints (descriptor `G`). |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/DtlsSocketNetifGattlinkStackConfig.kt` | 38 | `DSNG` stack config combining DTLS socket network interface + Gattlink; used for live bootstrap. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt` | 123 | Creates a native stack from a `NodeKey`, bridge sink/source, stack config, and `isNode` flag; exposes `close()`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolver.kt` | 45 | Abstract DTLS key resolver (`resolveKey(nodeKey, keyId): ByteArray?`). |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/TlsKeyResolverRegistry.kt` | 29 | Chains key resolvers (hello → bootstrap) and returns the first non-null key. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/HelloTlsKeyResolver.kt` | 27 | Returns the 16-byte `hello` PSK when key ID equals ASCII `"hello"`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt` | 28 | Returns the 16-byte `BOOTSTRAP` PSK when key ID equals ASCII `"BOOTSTRAP"`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/node/NodeKey.kt` | 5 | Generic node-key interface. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/node/BluetoothAddressNodeKey.kt` | 5 | Node-key backed by a BLE MAC address. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapEndpoint.kt` | 140 | Native CoAP endpoint wrapper; sends `OutgoingRequest`s and wires `SingleCoapResponseListener`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/Endpoint.kt` | 8 | Minimal `Endpoint` interface returning a response for a request. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapResponseListener.kt` | 13 | Listener callbacks: `onAck`, `onComplete`, `onError`, `onNext`, native ref management. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/SingleCoapResponseListener.kt` | 69 | One-shot listener that completes a Kotlin `Result<IncomingResponse>` and cancels the native listener on completion/error. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequestBuilder.kt` | 62 | Fluent builder for CoAP requests given a path and method, producing an `OutgoingRequest`. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/BaseOutgoingMessageBuilder.kt` | 42 | Shared base builder adding body and CoAP option support. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingMessageBuilder.kt` | 11 | Builder interface for message body (InputStream/URI/ByteArray) and options. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OptionsBuilder.kt` | 73 | Type-safe CoAP option builder covering URI path/query, content format, accept, block, ETag, etc. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/Method.kt` | 18 | CoAP method enum: GET/POST/PUT/DELETE. |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/ResponseCode.kt` | 36 | CoAP response code constants (`content`, `unauthorized`, `created`, etc.). |
| `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/CoapGroupRequestFilter.kt` | 54 | Filter for multicast/group CoAP requests. |
| `app/src/androidMain/jniLibs/arm64-v8a/libxp.so` | n/a | Native GoldenGate library (`xp`) loaded by `GoldenGate.init()`. |

### 2.3 Fitbit diagnostics and pairing

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/DtlsProbeStateMachine.kt` | 161 | State machine for DTLS bootstrap probe: defines `ProbeStage`, `ProbeResult`, interprets DTLS state 2 as `SESSION_ESTABLISHED`. |
| `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt` | 176 | Shared logic for running a read-only CoAP probe after DTLS is established. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/DtlsBootstrapProbe.kt` | 459 | End-to-end diagnostic: BLE connect, GoldenGate init, stack creation, DTLS bootstrap, and cleanup. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt` | 546 | Extends `DtlsBootstrapProbe` pattern and sends a single CoAP GET (default `/.well-known/core`). |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/GattlinkStartupDiagnostic.kt` | 379 | Diagnostic focused on establishing the Gattlink transport layer. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt` | 703 | Large diagnostic combining multiple GoldenGate experiments and logs. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGattDiagnostic.kt` | 208 | BLE/GATT service discovery diagnostic. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGattCacheDiagnostic.kt` | 228 | Diagnostic for reading/caching GATT attributes. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGattlinkDiagnostic.kt` | 270 | Diagnostic for Gattlink framing/notification behavior. |
| `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/ServerPairingExperiment.kt` | 136 | Experiment calling mock Fitbit pairing server endpoints (`validate.json`, `pair.json`) before the bond/control flow. |
| `app/src/androidMain/kotlin/bluebit/fitbit/Inspire3PairingHandler.kt` | 544 | Pairing UI flow / handler for Inspire 3 bond/control. |
| `app/src/commonMain/kotlin/bluebit/fitbit/FitbitPairingApi.kt` | 155 | Mock/placeholder Fitbit pairing server HTTP client. |
| `app/src/commonMain/kotlin/bluebit/fitbit/FitbitProvider.kt` | 103 | Fitbit `WearableProvider` implementation exposing diagnostics and capabilities. |

### 2.4 Core provider, connection, and UI wiring

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/commonMain/kotlin/bluebit/BlueBitAppShell.kt` | 204 | Main Compose app shell: navigation, scan result collection, device list, provider resolution, connection toggling, diagnostic buttons. |
| `app/src/commonMain/kotlin/bluebit/core/provider/WearableProvider.kt` | 36 | Provider interface: identify, create/connect/disconnect, reconnect, capabilities, sync, diagnostics. |
| `app/src/commonMain/kotlin/bluebit/core/provider/ProviderContext.kt` | 8 | Holds the `ProviderRegistry` and `BleScanner`. |
| `app/src/commonMain/kotlin/bluebit/core/provider/ProviderRegistry.kt` | 25 | Resolves providers from scan results and finds providers by ID. |
| `app/src/commonMain/kotlin/bluebit/core/provider/ProviderDiagnostic.kt` | 11 | Data class for a diagnostic button: id, label, suspend action, optional result Flow. |
| `app/src/commonMain/kotlin/bluebit/core/provider/DeviceCapabilities.kt` | 12 | Flags a provider supports (steps, HR, sleep, battery, OTA, etc.) plus diagnostics list. |
| `app/src/commonMain/kotlin/bluebit/core/provider/ProviderScanFilter.kt` | 6 | Scan filter descriptor. |
| `app/src/commonMain/kotlin/bluebit/core/connection/DiscoveryState.kt` | 37 | `DiscoveryState` and `ConnectionState` sealed classes; `ConnectionState` includes `GattlinkReady`, `Syncing`, `Error`, `Failed`. |
| `app/src/commonMain/kotlin/bluebit/core/connection/DeviceConnectionManager.kt` | 70 | Orchestrates discovery via `BleScanner` + `BlueBitDeviceMatcher`, and generic connect/disconnect (several flows still `TODO`). |
| `app/src/commonMain/kotlin/bluebit/core/pairing/PairedDeviceCoordinator.kt` | 160 | Persists paired devices, restores connections on launch, and performs bounded backoff reconnects after unexpected drops. |
| `app/src/commonMain/kotlin/bluebit/core/pairing/PairedDeviceRecord.kt` | 13 | Persisted record: providerId, address, name, extra metadata. |
| `app/src/commonMain/kotlin/bluebit/core/pairing/PairedDeviceStore.kt` | 8 | CRUD interface for paired-device records. |
| `app/src/commonMain/kotlin/bluebit/core/devices/BlueBitDeviceMatcher.kt` | 25 | Matches scan results to a `DeviceProfile` by service data or advertised name. |
| `app/src/commonMain/kotlin/bluebit/core/devices/DeviceProfile.kt` | 11 | Device profile model: name, identifier, pairing method, phylum. |
| `app/src/commonMain/kotlin/bluebit/core/devices/DeviceProfileRepository.kt` | 16 | In-memory profile repository; currently ships `defaultProfiles = emptyList()`. |
| `app/src/commonMain/kotlin/bluebit/core/ui/screens/DeviceDetailScreen.kt` | 221 | Compose screen rendering device details, connect/disconnect, sync, and a dynamic list of `ProviderDiagnostic` buttons; displays `CoapReadOnlyResult`. |
| `app/src/androidMain/kotlin/bluebit/MainActivity.kt` | 650 | Android entry point; sets up providers, scanner, pairing coordinator, instantiates `DtlsBootstrapProbe`, `CoapReadOnlyProbe`, `FitbitGoldenGateDiagnostic`, and wires them into the UI. |
| `app/src/androidMain/kotlin/bluebit/pairing/SharedPreferencesPairedDeviceStore.kt` | 71 | Android `SharedPreferences` implementation of `PairedDeviceStore`. |

### 2.5 FitCloud provider (comparison)

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudProvider.kt` | 74 | KMP FitCloud provider skeleton. |
| `app/src/commonMain/kotlin/bluebit/fitcloud/FitCloudUuids.kt` | 7 | FitCloud primary service UUID constant. |
| `app/src/androidMain/kotlin/bluebit/fitcloud/AndroidFitCloudProvider.kt` | 219 | Full FitCloud SDK provider: scan/identify, connect/bind/login, reconnect, capabilities, diagnostics. |
| `app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudSdkBleConnection.kt` | 37 | No-op `BleConnection` wrapper because the FitCloud SDK manages GATT internally. |
| `app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudSdkInit.kt` | 97 | FitCloud SDK initialization. |
| `app/src/androidMain/kotlin/bluebit/fitcloud/FitCloudDialInspector.kt` | 157 | Diagnostic helper for FitCloud watch-face/device info queries. |

### 2.6 Desktop entry and tests

| Relative path | Lines | Purpose |
|---------------|-------|---------|
| `app/src/desktopMain/kotlin/bluebit/BlueBitApp.kt` | 43 | Desktop Compose entry point. |
| `app/src/desktopMain/kotlin/bluebit/fitbit/PairingApiTest.kt` | 55 | Desktop-side test for the mock Fitbit pairing API. |
| `app/src/desktopTest/kotlin/bluebit/` | varies | Unit tests (not individually read for this inventory). |

## 3. Detailed summaries of requested files

### 3.1 `BootstrapTlsKeyResolver.kt`
- **Path:** `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/dtls/BootstrapTlsKeyResolver.kt`
- **Lines:** ~28
- **Role:** Implements the DTLS bootstrap PSK resolver used after the initial `hello` handshake.
- **Details:**
  - Companion object defines `BOOTSTRAP_KEY_ID = "BOOTSTRAP".toByteArray(Charsets.UTF_8)` and a fixed 16-byte key literal.
  - Extends `TlsKeyResolver()`.
  - `resolveKey(nodeKey, keyId)` returns the 16-byte bootstrap key only when `keyId.contentEquals(BOOTSTRAP_KEY_ID)`.
  - Registered in `TlsKeyResolverRegistry` after `HelloTlsKeyResolver` so the native stack can fetch the bootstrap PSK on demand.

### 3.2 `CoapReadOnlyProbe.kt` and `CoapReadOnlyProbeLogic.kt`
- **Paths:**
  - `app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbe.kt` (~546 lines)
  - `app/src/commonMain/kotlin/bluebit/fitbit/diagnostics/CoapReadOnlyProbeLogic.kt` (~176 lines)
- **Role:** Reusable, one-shot read-only CoAP probe running over a freshly bootstrapped DTLS link.
- **Details:**
  - `CoapReadOnlyProbeLogic` (KMP) owns the high-level state machine: wait for DTLS session established, build a CoAP GET with `OutgoingRequestBuilder`, attach a `SingleCoapResponseListener`, parse the response code/body, and return a `CoapReadOnlyResult`.
  - `CoapReadOnlyProbe` (Android) handles the Android BLE side: scan/connect to the target, discover services, validate the GattDatabase against `FitbitGattUuids`, enable notifications on the GoldenGate TX characteristic, set up `RxSource`/`TxSink`, start `GoldenGate`/`RunLoop`, create `Stack` with `DtlsSocketNetifGattlinkStackConfig`, register DTLS status listener, run the shared probe logic, and clean everything up.
  - Default CoAP target is `GET /.well-known/core`; the path is configurable via `runProbe(path)`.
  - Produces `CoapReadOnlyResult(success, dtlsEstablished, coapSuccess, responseCode, responseLength, resourceSummary, error)`.

### 3.3 Gattlink / GoldenGate bridge classes
- **Key files:**
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/GoldenGate.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/Bridge.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/io/TxSink.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/io/RxSource.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/Stack.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/stack/DtlsSocketNetifGattlinkStackConfig.kt`
- **Role:** Glue Kotlin BLE I/O to the native GoldenGate CoAP/DTLS/Gattlink stack.
- **Details:**
  - `GoldenGate.init()` loads `libxp.so` and marks the library ready; all native object constructors call `GoldenGate.check()`.
  - `RunLoop` starts a dedicated native run-loop thread via JNI and blocks with a `CountDownLatch` until `onLoopCreated()` fires.
  - `TxSink` is registered with native so native code can push outbound bytes to Kotlin; Kotlin forwards them to BLE and then calls `notifyListener()` so native can free/continue.
  - `RxSource` receives inbound BLE bytes from Kotlin and passes them into native via `receiveData(byte[])`.
  - `Bridge` combines one `TxSink` and one `RxSource` into a single `DataSinkDataSource` usable by the native stack.
  - `Stack` constructs the native stack from a `NodeKey`, the bridge, a `StackConfig`, and an `isNode` boolean; exposes `close()`.
  - `DtlsSocketNetifGattlinkStackConfig` provides the `DSNG` descriptor and `0.0.0.0:0` UDP endpoints for DTLS-over-Gattlink.

### 3.4 CoAP request builders (`OutgoingRequestBuilder`)
- **Paths:**
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OutgoingRequestBuilder.kt` (~62 lines)
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/BaseOutgoingMessageBuilder.kt`
  - `app/src/androidMain/kotlin/com/fitbit/goldengate/bindings/coap/data/OptionsBuilder.kt`
- **Role:** Type-safe construction of native CoAP `OutgoingRequest` objects.
- **Details:**
  - `OutgoingRequestBuilder(path, method)` builds a request with URI path segments auto-added as `UriPathOption`s.
  - Inherits `body(ByteArray)` and `option(Option)` from `BaseOutgoingMessageBuilder`.
  - `OptionsBuilder` maps standard CoAP option numbers (`URI_PATH`, `URI_QUERY`, `CONTENT_FORMAT`, `ACCEPT`, `BLOCK1`, `BLOCK2`, `ETAG`, etc.) to typed option objects.
  - `CoapEndpoint.responseFor(request)` sends the built request and returns a native listener reference.

### 3.5 `MainActivity` diagnostic wiring
- **Path:** `app/src/androidMain/kotlin/bluebit/MainActivity.kt` (~650 lines)
- **Role:** Android entry point that assembles providers, scanner, pairing store, and diagnostics and launches `BlueBitAppShell`.
- **Details:**
  - Loads `libxp.so` and initializes `GoldenGate` early.
  - Builds a `ProviderRegistry` containing `FitbitProvider` and `AndroidFitCloudProvider`.
  - Creates `SharedPreferencesPairedDeviceStore`, `PairedDeviceCoordinator`, `AndroidBleScanner`, `AndroidBleConnection`.
  - Instantiates diagnostics: `DtlsBootstrapProbe`, `CoapReadOnlyProbe`, `FitbitGoldenGateDiagnostic`, `GattlinkStartupDiagnostic`, `FitbitGattDiagnostic`, etc.
  - `FitbitProvider.capabilities()` exposes provider-specific diagnostics including the CoAP read-only probe.
  - `BlueBitAppShell` renders `DeviceDetailScreen`, passing `lastCoapReadOnlyResult` so the UI can show the latest probe result card.

## 4. Reusable components for a live bootstrap implementation

The following building blocks are already exercised by working diagnostics and can be reused for a production live bootstrap path:

1. **BLE transport**
   - `AndroidBleScanner` / `AndroidBleConnection` provide generic BLE primitives.
   - `FitbitGattlinkConnector` + `FitbitGattlinkServiceSelector` handle GoldenGate-specific service/characteristic selection and byte routing.
2. **GoldenGate native initialization**
   - `GoldenGate.init()` and `System.loadLibrary("xp")`.
   - `RunLoop.startAndWaitUntilReady()`.
3. **Bridge / sink / source**
   - `TxSink` for native → BLE.
   - `RxSource` for BLE → native.
   - `Bridge(txSink, rxSource)` to present a single `DataSinkDataSource`.
4. **DTLS keying**
   - `TlsKeyResolverRegistry` chaining `HelloTlsKeyResolver` and `BootstrapTlsKeyResolver`.
   - `BootstrapTlsKeyResolver` returns the fixed 16-byte `BOOTSTRAP` PSK.
5. **Stack creation**
   - `Stack(nodeKey = BluetoothAddressNodeKey(address), transportSinkPtr, transportSourcePtr, DtlsSocketNetifGattlinkStackConfig(), isNode = true/false)`.
   - DTLS state listener: wait for state `2` (`SESSION_ESTABLISHED`) as interpreted by `DtlsProbeStateMachine`.
6. **CoAP requests**
   - `CoapEndpoint.responseFor(OutgoingRequestBuilder(path, Method.GET).build())`.
   - `SingleCoapResponseListener` converts native callbacks into a Kotlin `Result<IncomingResponse>`.
7. **UUID constants**
   - `FitbitGattUuids` for the GoldenGate service (`ABBAFF00...`), RX (`ABBAFF01...`), TX (`ABBAFF02...`), GattDatabase confirmation (`AC2F0045...`), and ephemeral pointer (`AC2F0145...`).
8. **Working reference flows**
   - `DtlsBootstrapProbe` is the cleanest end-to-end reference for BLE → Gattlink → DTLS bootstrap.
   - `CoapReadOnlyProbe` extends that reference with a single authenticated CoAP GET.

## 5. Notes and observations

- **Native dependency:** The GoldenGate stack depends on `app/src/androidMain/jniLibs/arm64-v8a/libxp.so`; no x86 desktop equivalent is present.
- **Provider abstraction:** Fitbit and FitCloud both implement `WearableProvider`, but Fitbit diagnostics currently use raw `BluetoothGattCallback` inside the diagnostic classes rather than the generic `BleConnection` abstraction, presumably for fine-grained ordering.
- **Device matching:** `BlueBitDeviceMatcher` supports service-data and name matching, but `InMemoryDeviceProfileRepository.defaultProfiles` is currently `emptyList()`.
- **Pairing server:** `FitbitPairingApi` and `ServerPairingExperiment` point to a local mock server (`10.0.2.2:8080`); real Fitbit server integration is not present.
- **Connection manager:** `DeviceConnectionManager.connect(profile)` and `sync()` are `TODO("Not yet implemented")`; the live path is driven directly by diagnostic/probe classes and `BlueBitAppShell`.
