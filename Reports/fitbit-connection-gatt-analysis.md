# Fitbit Android App — Connection, GATT Discovery, and Authentication Flow Analysis

**APK:** `com.fitbit.FitbitMobile`  
**Version:** 5.06.1 (`versionCode` 1104101009)  
**Analysis date:** 2026-08-30  
**Scope:** Runtime path from `BluetoothDevice.connectGatt()` through service discovery, session establishment, and synchronization entry point.

---

## Executive Summary

After a Fitbit wearable is discovered and recognized (scan-result → service-data → profile → advertisement verification), the app initiates a BLE connection through the **FitbitGatt** (`fbgatt`) library. The connection uses `autoConnect=false` and `TRANSPORT_LE`. Once connected, the **GoldenGate** stack performs a deterministic link-up sequence: refresh services, discover services, subscribe to three **Link Configuration** characteristics, and wait for the remote device to enable notifications on the **Gattlink** transmit characteristic. All higher-layer communication (pairing, bonding, sync, battery info, device info) is carried over **CoAP** inside the **Gattlink** tunnel; no standard BLE Battery Service (0x180F) reads were observed in the Java layer, and tracker-level challenge/response authentication (`authTracker()`) is hard-coded to return `false` in the version under analysis.

---

## Connection Entry Points

### 1. `FitbitGattImpl` singleton
- **File:** `com/fitbit/bluetooth/fbgatt/FitbitGattImpl.java`
- **Class:** `FitbitGattImpl`
- **Method:** `addOrUpdateDeviceInMap(nqt, nrg)`
- **Finding:** Creates a new `nrs` connection object for each unique scanned `nqt` (device token) and stores it in a `ConcurrentHashMap<nqt, nrq>`.

### 2. `nrs.q()` — the actual `connectGatt()` call
- **File:** `defpackage/nrs.java`
- **Class:** `nrs` (implements `nrt`)
- **Method:** `public synchronized int q()`
- **Code:**
  ```java
  if (nqv.b(23)) {
      this.f = bluetoothDevice.connectGatt(
          nrhVar.getAppContext(),
          false,                              // autoConnect = false
          (BluetoothGattCallback) nrhVar.getClientCallbackInternal(),
          2                                   // TRANSPORT_LE
      );
  } else {
      this.f = bluetoothDevice2.connectGatt(
          nrhVar2.getAppContext(),
          false,
          (BluetoothGattCallback) nrhVar2.getClientCallbackInternal()
      );
  }
  ```
- **Confidence:** CONFIRMED

### 3. Application-level connection initiators
- **File:** `com/fitbit/goldengate/bt/PeerConnector.java`
- **Class:** `PeerConnector`
- **Method:** `connect()`
- **Finding:** Retrieves the `nrq` connection for a Bluetooth address via `FitbitGattExtKt.getGattConnection(fitbitGatt, address)`, wraps it in a `BitGattPeer`, and calls `bitGattPeer.connect()`.

- **File:** `com/fitbit/goldengate/node/stack/StackPeer.java`
- **Class:** `StackPeer`
- **Method:** `connect()` (private)
- **Finding:** The GoldenGate stack peer drives the connection. It calls `peerConnector.connect()`, then waits for `PeripheralConnectionStatus.CONNECTED`, then optionally negotiates DTLS when `DtlsSocketNetifGattlink` is configured.

---

## GATT Callback Architecture

### Primary callback wrapper
- **File:** `defpackage/nrm.java`
- **Class:** `nrm extends BluetoothGattCallback implements nrn`
- **Role:** This is the *only* object registered with Android's `BluetoothGattCallback`. It receives all Android GATT events and fan-outs to two listener sets:
  1. `List<nro> b` — transaction listeners (read/write/discover operations)
  2. `nrh a` — the `FitbitGattImpl` instance that manages connection state and notifies `nqr` listeners.

### Callback dispatch flow
```
Android BluetoothGattCallback
    └── nrm (FitbitGatt central callback)
            ├── onConnectionStateChange → nro.i() / nqr.onClientConnectionStateChanged()
            ├── onServicesDiscovered    → nro.m() / nqr.onServicesDiscovered()
            ├── onCharacteristicRead    → nro.gL()
            ├── onCharacteristicWrite   → nro.h()
            ├── onDescriptorWrite       → nro.s()
            ├── onMtuChanged            → nro.k() / nqr.onMtuChanged()
            └── onCharacteristicChanged → nro.j() / nqr.onClientCharacteristicChanged()
```
- **Confidence:** CONFIRMED (directly observed in `nrm.java`)

### Key `nqr` listeners observed
| Listener | File | Handles |
|---|---|---|
| `pnk` | `defpackage/pnk.java` | Connection up/down, services discovered |
| `qfq` | `defpackage/qfq.java` | Connection state, logging, dev-metrics |
| `pcv` | `defpackage/pcv.java` | (stub) |
| `spz` | `defpackage/spz.java` | (stub) |

---

## Service Discovery Sequence

### Link-up handler (`LinkupWithPeerHubHandler`)
- **File:** `com/fitbit/goldengate/hub/LinkupWithPeerHubHandler.java`
- **Class:** `LinkupWithPeerHubHandler`
- **Method:** `link(BitGattPeer)`
- **Sequence (CONFIRMED):**
  1. `bitGattPeer.connect().ignoreElement()`
  2. `refreshServices(bitGattPeer)` — calls `GattServiceRefresher.refresh(gattConnection)`
  3. `timer(2, SECONDS)` — hard-coded 2-second delay
  4. `discoverServices(bitGattPeer)` — calls `GattServiceDiscoverer.discover(gattConnection)`
  5. `subscribeToPreferredConnectionConfigurationCharacteristic(bitGattPeer)`
  6. `subscribeToPreferredConnectionModeCharacteristic(bitGattPeer)`
  7. `subscribeToGeneralPurposeCommandCharacteristic(bitGattPeer)`
  8. `waitForGattlinkSubscription(bitGattPeer)` — waits until the remote side enables notifications on the Gattlink **Transmit** characteristic.

### Services subscribed immediately after discovery
| Service UUID | Name | Characteristic UUID | Name | Purpose |
|---|---|---|---|---|
| `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` | **LinkConfigurationService** | `ABBAFC01-…` | `ClientPreferredConnectionConfigurationCharacteristic` | Connection params |
| `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` | **LinkConfigurationService** | `ABBAFC02-…` | `ClientPreferredConnectionModeCharacteristic` | Connection mode |
| `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` | **LinkConfigurationService** | `ABBAFC03-…` | `GeneralPurposeCommandCharacteristic` | Generic commands |

*Note:* The exact characteristic UUIDs are compiled into `LinkConfigurationService` static initialisers; the full 128-bit values follow the same vendor base as the service (`ABBAFCxx…`).

---

## Authentication Flow

### Tracker challenge/response (`authTracker`)
- **File:** `com/fitbit/goldengate/commands/GoldenGateCommandHandler.java`
- **Class:** `GoldenGateCommandHandler`
- **Method:** `public bxsz<Boolean> authTracker()`
- **Code:** `return bxsz.just(false);`
- **Finding:** **No tracker-level cryptographic handshake is performed by the GoldenGate command handler in this build.**
- **Confidence:** CONFIRMED

### Pairing (display/clear secret code)
- **File:** `com/fitbit/goldengate/commands/PairCommandsHandler.java`
- **Class:** `PairCommandsHandler`
- **Methods:** `showPairingCodeOnDevice()`, `clearPairingCodeOnDevice()`
- **Protocol:** CoAP `PUT` to endpoint `"pair/display"` with a protobuf body (`PairDisplayRequestBuilder`) containing `SHOW_CODE` or `CLEAR_CODE`.
- **Confidence:** CONFIRMED

### Bonding (Android-level)
- **File:** `com/fitbit/goldengate/bond/BondCommandsHandler.java`
- **Class:** `BondCommandsHandler`
- **Method:** `create(Context, String)`
- **Flow:**
  1. Checks whether a bond already exists (short-circuits if true).
  2. Calls `createBond(context, address, timeout)`.
  3. Uses `PeripheralBondCreator` → `CreateBondTransactionProvider` to enqueue a `ntf` (create-bond transaction) on the `FitbitGatt` connection.
- **Confidence:** CONFIRMED

### `DeviceCipher` metadata
- **File:** `com/google/android/apps/fitbit/app/datalayer/devicetype/api/DeviceCipher.java`
- **Enum:** `AES`, `XTEA`
- **Finding:** The cipher type is stored per-device-type in the local database (`device_types` table) and parsed from server JSON. It is **not** referenced in the BLE/GoldenGate connection code path observed.
- **XTEA implementation:** `defpackage/cbql.java` contains a full 32-round XTEA engine (SpongyCastle style). No callers were found within the GoldenGate or fbgatt packages.
- **Confidence:** STRONG EVIDENCE (enum present, XTEA engine present, but no observed linkage to live connection auth)

---

## DeviceCipher Usage

| Cipher | Evidence | Active in this path? |
|---|---|---|
| **AES** | `DeviceCipher.AES` enum value; referenced in JSON adapters | Unknown — not observed in GATT/CoAP flow |
| **XTEA** | `DeviceCipher.XTEA` enum value; `cbql.java` implementation | Unknown — not observed in GATT/CoAP flow |

The authentication model for modern Fitbit devices (Inspire 3 era) appears to rely on **CoAP-over-Gattlink + DTLS** rather than a legacy per-packet XTEA/AES challenge/response at the BLE layer.

---

## Battery Service Usage

### Standard BLE Battery Service (`0x180F`)
- **Search result:** No Java source references to `0000180F-0000-1000-8000-00805F9B34FB` were found in `com/fitbit`.
- **Conclusion:** The app does **not** appear to read the standard BLE Battery Service characteristic directly.

### Battery level source
- **File:** `com/fitbit/goldengate/protobuf/DeviceInfo.java`
- **Field:** `batteryLevel_` (field number 13)
- **File:** `com/fitbit/goldengate/mobiledata/protobuftomobiledata/DeviceInfoTranslator.java`
- **Finding:** Battery level is extracted from a `DeviceInfo` protobuf message received over the GoldenGate/CoAP channel and mapped to the `BATTERY_LEVEL_KEY` mobile-data field.
- **Confidence:** CONFIRMED

---

## Device Information Service (0x180A) Post-Connection Usage

### Scan-time usage (already known from prior report)
- `0000180A` service data is parsed during scanning to extract the 8-byte device-identifier payload (`BleServiceData`).

### Post-connection usage
- No direct GATT read of `0x180A` characteristics (Model Number, Serial Number, etc.) was observed in the GoldenGate link-up sequence.
- Device metadata (model IDs, firmware version, battery, etc.) is instead retrieved via:
  - **CoAP endpoints** (e.g., `sync/request`, `pair/display`)
  - **Protobuf `DeviceInfo`** pushed by the tracker over Gattlink
- **Confidence:** STRONG EVIDENCE

---

## Proprietary Fitbit Services

| Service UUID | Service Name | Role |
|---|---|---|
| `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` | **GattlinkService** | Primary data pipe (legacy) |
| `0000FD62-0000-1000-8000-00805F9B34FB` | **FitbitGattlinkService** | Primary data pipe (modern, registered 16-bit alias) |
| `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8` | **ReceiveCharacteristic** | Host → device |
| `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8` | **TransmitCharacteristic** | Device → host (notifications) |
| `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` | **LinkConfigurationService** | Connection tuning |
| `AC2F0045-8182-4BE5-91E0-2992E6B40EBB` | **GattCacheService** | GATT cache sync |
| `AC2F0145-8182-4BE5-91E0-2992E6B40EBB` | **EphemeralCharacteristicPointer** | Cache pointer |
| `00001801-0000-1000-8000-00805f9b34fb` | **GenericAttributeService** | Standard service changed |

*Note:* `FitbitGattlinkService` (UUID `0000FD62…`) is a 16-bit-aliased vendor service. The app dynamically selects between `GattlinkService` and `FitbitGattlinkService` depending on which one is present on the remote peripheral (`NodeDataSenderProvider.java`, `NodeDataReceiverProvider.java`).

---

## Synchronization Flow

### Entry point
- **File:** `com/fitbit/goldengate/commands/GoldenGateCommandHandler.java`
- **Class:** `GoldenGateCommandHandler`
- **Method:** `getSyncRequestObservable()`
- **Code:**
  ```java
  return this.backgroundSyncHandler.updateRemoteSyncConfigIfNeeded()
         .onErrorComplete()
         .andThen(this.eventConsumer.getShouldSync());
  ```

### Background sync handler
- **File:** `com/fitbit/goldengate/sync/BackgroundSyncHandler.java`
- **Class:** `BackgroundSyncHandler`
- **Method:** `getAndUpdateConfigIfNeeded()`
- **Flow:**
  1. Fetches sync configuration from the device via `CoapSyncConfigResource.getSyncConfig()` (CoAP GET).
  2. Compares local vs remote config; updates the tracker if needed.
  3. `EventConsumer` emits a `shouldSync` observable when sync conditions are met (background events, on-wrist state, etc.).

### Event-based sync
- **File:** `com/fitbit/goldengate/commands/GoldenGateCommandHandler.java`
- **Resource endpoint:** `"sync/request"`
- **Handler:** `CoapEventBasedSync` (registered in `registerResourceHandlers()`)
- **Confidence:** CONFIRMED

---

## Class / Method Reference Index

| Symbol | File | Role |
|---|---|---|
| `FitbitGattImpl` | `com/fitbit/bluetooth/fbgatt/FitbitGattImpl.java` | Singleton GATT client/server manager |
| `nrs` | `defpackage/nrs.java` | Internal connection object; calls `connectGatt()` |
| `nrm` | `defpackage/nrm.java` | `BluetoothGattCallback` wrapper |
| `nro` | `defpackage/nro.java` | Transaction listener interface |
| `nqr` | `defpackage/nqr.java` | High-level connection listener interface |
| `BitGattPeer` | `com/fitbit/bluetooth/fbgatt/rx/client/BitGattPeer.java` | Public RX wrapper around `nrq` |
| `PeerConnector` | `com/fitbit/goldengate/bt/PeerConnector.java` | Initiates `BitGattPeer.connect()` |
| `StackPeer` | `com/fitbit/goldengate/node/stack/StackPeer.java` | GoldenGate stack peer; owns DTLS + linkup |
| `LinkupWithPeerHubHandler` | `com/fitbit/goldengate/hub/LinkupWithPeerHubHandler.java` | Post-connect service discovery & subscription |
| `GoldenGateCommandHandler` | `com/fitbit/goldengate/commands/GoldenGateCommandHandler.java` | Top-level command dispatcher (sync, pair, bond, connect) |
| `PairCommandsHandler` | `com/fitbit/goldengate/commands/PairCommandsHandler.java` | CoAP pairing code display/clear |
| `BondCommandsHandler` | `com/fitbit/goldengate/bond/BondCommandsHandler.java` | Android BLE bond creation |
| `PeripheralBondCreator` | `com/fitbit/goldengate/bond/PeripheralBondCreator.java` | Enqueues bond transaction on fbgatt |
| `BackgroundSyncHandler` | `com/fitbit/goldengate/sync/BackgroundSyncHandler.java` | CoAP sync config negotiation |
| `EventConsumer` | `com/fitbit/goldengate/sync/EventConsumer.java` | Emits sync requests based on tracker events |
| `DeviceCipher` | `com/google/android/apps/fitbit/app/datalayer/devicetype/api/DeviceCipher.java` | Device-type metadata enum {AES, XTEA} |
| `cbql` | `defpackage/cbql.java` | XTEA block cipher implementation |

---

## What We Can Establish

1. **Connection parameters:** `autoConnect=false`, `TRANSPORT_LE` (LE-only, no classic fallback).
2. **Callback model:** Single Android `BluetoothGattCallback` (`nrm`) fans out to Fitbit's internal listener interfaces (`nro`, `nqr`).
3. **Post-connect sequence is deterministic:** refresh → discover → subscribe to 3 × LinkConfiguration characteristics → wait for Gattlink TX subscription.
4. **Higher-layer transport is CoAP-over-Gattlink:** All pairing, bonding, sync, and device-info traffic runs inside the Gattlink tunnel, not as discrete BLE characteristic reads/writes.
5. **No legacy tracker auth handshake in this build:** `authTracker()` unconditionally returns `false`.
6. **Battery level is protobuf/CoAP-based:** No evidence of direct `0x180F` characteristic access.
7. **Device type metadata includes AES/XTEA flags:** But the live connection path does not reference them; DTLS is used instead when `DtlsSocketNetifGattlink` is configured.

---

## What Remains Unknown

1. **DTLS handshake details:** The `StackPeer` branches on `DtlsSocketNetifGattlink`, but the native DTLS code (`.so` libraries) was not reverse-engineered.
2. **CoAP payload encryption inside Gattlink:** Whether an additional AES/XTEA layer is applied to CoAP frames after Gattlink is established is not visible in the Java layer.
3. **Inspire 3-specific model ID mapping:** The Inspire 3 product ID and its `DeviceCipher` assignment in the server response model were not isolated in this pass.
4. **Exact 128-bit UUIDs for LinkConfiguration characteristics:** Only the service UUID (`ABBAFC00…`) was fully visible; the three characteristic UUIDs are referenced by short names in decompiled code (`ClientPreferredConnectionConfigurationCharacteristic.a`, etc.). Their full values can be recovered by reading the respective class static initialisers.
5. **Native library role:** `libgoldengate.so` (or similarly named native libs in the arm64 split) likely contains the Gattlink framing and DTLS logic; strings were not extracted in this task.

---

## Recommended Next Investigation

1. **Read the three `LinkConfigurationService` characteristic classes** to capture the exact `ABBAFC01/02/03` UUIDs.
2. **Inspect native libraries** (`libgoldengate.so`, `libfitbit*.so`, etc.) from `Fitbit-arm64.apk` for:
   - Gattlink frame format
   - DTLS cipher suites / PSK derivation
   - References to `XTEA`, `AES`, `pair`, `auth`, `session`
3. **Trace `StackPeer` DTLS branch** more deeply (`getDtlsEventObservable`, `DtlsSocketNetifGattlink`) to understand when and how DTLS is enabled.
4. **Search the device-type database schema** (`device_types` table) for Inspire 3 `productId` and correlate with `DeviceCipher` and `PairingMethod` fields.
5. **Dump CoAP resource handler registrations** in `GoldenGateCommandHandler.registerResourceHandlers()` to build a complete endpoint map (`sync/request`, `pair/display`, `bond/control`, etc.).
