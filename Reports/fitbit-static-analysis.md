# Fitbit Android App – Static Analysis Report

**Scope:** Fitbit Android APK (`Fitbit-base.apk` + `Fitbit-arm64.apk`)  
**Goal:** Understand how the official app discovers and communicates with Fitbit wearables, with focus on the Inspire 3.  
**Method:** Static decompilation with JADX (v1.5.6); no runtime instrumentation or access-control bypass.

---

## 1. APK / Package Information

| Field | Value |
|-------|-------|
| Package | `com.fitbit.FitbitMobile` |
| Version Name | `5.06.1.health-mobile-1104101009-966217170` |
| Version Code | `1104101009` |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 32 |

### Key BLE-related manifest entries
- Permissions:
  - `android.permission.BLUETOOTH_SCAN` (neverForLocation)
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.ACCESS_FINE_LOCATION`
  - `android.permission.ACCESS_BACKGROUND_LOCATION`
  - `android.permission.REQUEST_COMPANION_PROFILE_WATCH`
  - `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE`
- Receivers:
  - `com.fitbit.bluetooth.fbgatt.HandleIntentBasedScanResult`
  - `com.fitbit.bluetooth.fbgatt.LowEnergyAclListener`
- Service:
  - `com.google.android.apps.fitbit.app.device.management.impl.cdm.service.CompanionService` (`BIND_COMPANION_DEVICE_SERVICE`)

---

## 2. Relevant Libraries

| Library / Package | Role |
|-------------------|------|
| `com.fitbit.bluetooth.fbgatt` | Fitbit’s own BLE GATT abstraction layer (FitbitGatt). Wraps `BluetoothGatt`, `BluetoothGattServer`, scan filters, bonding, and transactions. |
| `com.fitbit.goldengate` | Protocol stack: **Gattlink** (reliable BLE stream) + **DTLS** (PSK-based) + **CoAP** for application commands. |
| `com.fitbit.linkcontroller` | Link-configuration GATT service/characteristics used during setup. |
| `com.fitbit.protocol.io` | Contains `SLIPInputStream` – SLIP framing, possibly used for legacy serial-style BLE payloads. |
| Native `libxp.so` | GoldenGate/Gattlink native implementation (mbedtls DTLS handshake, Gattlink protocol engine, CoAP endpoint handling). |

---

## 3. Inspire 3 Evidence

- **No hardcoded references** to `"Inspire 3"`, `"Inspire3"`, `"Inspire_3"`, or model number `FB515` were found in:
  - Decompiled Java/Kotlin sources (`com.fitbit.*` packages)
  - Resource strings / XML (`resources/res/values/`)
  - Native libraries (`libxp.so`, `libalexa-android-native.so`, etc.)
- The APK **does not** list "Inspire" in the BLE scan-filter device-name whitelist (see §6).
- Device model information appears to be **server-driven**:
  - `DevicePairingInfo.java` holds `productId`, `modelNumber`, `serialNumber`, `hardwareVersion`, `firmwareVersion`, and `mac`.
  - `DeviceInfo` protobuf (see §9) contains `productId`, `hwRevision`, `edition`, etc., which the app likely uses to identify the exact model post-connection.
- **Conclusion:** The Inspire 3 is discovered using the same generic Fitbit service UUIDs as other trackers, and its exact identity is resolved after the BLE link is established (likely via cloud/API lookup against `productId`).

---

## 4. BLE Service UUIDs

### Scan-filter services (from `defpackage.kpg` case 9 → `defpackage.pgs`)
The app scans for advertisements matching **any** of the following service UUIDs:

| Service UUID | Mask | Notes |
|--------------|------|-------|
| `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA` | `FFFF0000-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | Fitbit service family (only top 16 bits checked) |
| `0000FD63-0000-1000-8000-00805F9B34FB` | `FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | Fitbit-assigned 16-bit UUID |
| `ABBAFB00-E56A-484C-B832-8B17CF6CBFE8` | `FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | Fitbit proprietary service |
| `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` | `FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | **GattlinkService** |
| `0000FD62-0000-1000-8000-00805F9B34FB` | `FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | **FitbitGattlinkService** |
| `0000181D-0000-1000-8000-00805F9B34FB` | `FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF` | Weight Scale (likely for Aria scales) |

### GATT server services (app exposes these)
| Service UUID | Class File |
|--------------|------------|
| `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` | `com.fitbit.goldengate.bt.gatt.server.services.gattlink.GattlinkService` |
| `0000FD62-0000-1000-8000-00805F9B34FB` | `com.fitbit.goldengate.bt.gatt.server.services.gattlink.FitbitGattlinkService` |
| `ABBAFC00-E56A-484C-B832-8B17CF6CBFE8` | `com.fitbit.linkcontroller.services.configuration.LinkConfigurationService` |
| `AC2F0045-8182-4BE5-91E0-2992E6B40EBB` | `com.fitbit.goldengate.bt.gatt.server.services.gattcache.GattCacheService` |
| `00001801-0000-1000-8000-00805f9b34fb` | `com.fitbit.goldengate.bt.gatt.client.services.GenericAttributeService` |

### Other UUIDs
| UUID | Role |
|------|------|
| `16bcfd04-253f-c348-e831-0db3e334d580` | `LocationStatusQualityCharacteristic` (DNCs) |
| `00002902-0000-1000-8000-00805f9b34fb` | Client Characteristic Configuration descriptor (`UUIDConstantsKt`) |
| `00002904-0000-1000-8000-00805f9b34fb` | Characteristic Namespace descriptor |

---

## 5. BLE Characteristic UUIDs

### Gattlink / FitbitGattlinkService
| Characteristic | UUID |
|----------------|------|
| ReceiveCharacteristic | `ABBAFF01-E56A-484C-B832-8B17CF6CBFE8` |
| TransmitCharacteristic | `ABBAFF02-E56A-484C-B832-8B17CF6CBFE8` |

### LinkConfigurationService
| Characteristic | UUID |
|----------------|------|
| ClientPreferredConnectionConfigurationCharacteristic | `ABBAFC01-E56A-484C-B832-8B17CF6CBFE8` |
| ClientPreferredConnectionModeCharacteristic | `ABBAFC02-E56A-484C-B832-8B17CF6CBFE8` |
| GeneralPurposeCommandCharacteristic | `ABBAFC03-E56A-484C-B832-8B17CF6CBFE8` |

### GattCacheService
| Characteristic | UUID |
|----------------|------|
| EphemeralCharacteristicPointer | `AC2F0145-8182-4BE5-91E0-2992E6B40EBB` |
| EphemeralCharacteristic | `AC2F0345-8182-4BE5-91E0-2992E6B40EBB` |

### GenericAttributeService
| Characteristic | UUID |
|----------------|------|
| ServiceChanged | `00002a05-0000-1000-8000-00805f9b34fb` |

---

## 6. Device Identification Logic

### Scan-filter configuration
- **File:** `defpackage/pgs.java`
- **Method:** `public static final void a(nqw nqwVar)`
- What it does:
  1. Resets existing scan filters.
  2. Adds all service UUID filters returned by `kpg(9)` (see §4).
  3. Adds **device-name** filters:
     - `"One"`
     - `"Flex"`
     - `"Charge"`
     - `"Charge HR"`
     - `"Force"`
- The resulting filter list (`pgs.b`) is passed to `PeripheralScanner.scanForDevices` from:
  - `defpackage/qel.java` (line ~1197)
  - `defpackage/qfx.java` (line ~63)

### Post-connection identification
- After a BLE connection is established, the app reads device metadata via the **DeviceInfo** protobuf message (see §9), which includes:
  - `productId`
  - `modelNumber` (from `DevicePairingInfo`)
  - `hwRevision`, `sysVerMajor`, `sysVerMinor`, `fwBuildType`
- This suggests the exact model name (e.g., Inspire 3) is resolved dynamically, not hardcoded in the APK.

---

## 7. Relevant Source-File Paths / Classes

| File | Significance |
|------|--------------|
| `com/fitbit/bluetooth/fbgatt/FitbitGattImpl.java` | Core GATT client/server manager; scan-filter API; bonding helpers. |
| `com/fitbit/bluetooth/fbgatt/rx/scanner/PeripheralScanner.java` | RxJava wrapper around BLE scanning (`scanForDevices`). |
| `com/fitbit/bluetooth/fbgatt/rx/scanner/ServiceUuidPeripheralScannerFilter.java` | Implements `PeripheralScannerFilter` using service UUID + mask. |
| `com/fitbit/bluetooth/fbgatt/receivers/CreateBondTransactionBroadcastReceiver.java` | Listens for `BOND_STATE_CHANGED` broadcasts and maps Android bond states to Fitbit bond-reason enums. |
| `com/fitbit/bluetooth/fbgatt/rx/client/BitGattPeer.java` | High-level GATT peer read/write API. |
| `com/fitbit/goldengate/bt/GlobalBluetoothGattInitializer.java` | Sets up the local GATT server with FitbitGattlinkService / GattlinkService. |
| `com/fitbit/goldengate/bt/gatt/server/services/gattlink/FitbitGattlinkService.java` | GATT server service using UUID `0000FD62...` |
| `com/fitbit/goldengate/bt/gatt/server/services/gattlink/GattlinkService.java` | GATT server service using UUID `ABBAFF00...` |
| `com/fitbit/goldengate/bt/gatt/server/services/gattcache/*.java` | Gatt cache service & characteristics. |
| `com/fitbit/linkcontroller/services/configuration/*.java` | LinkConfigurationService and its three characteristics. |
| `com/fitbit/goldengate/commands/PairCommandsHandler.java` | CoAP `pair/display` PUT commands (show / clear pairing code). |
| `com/fitbit/goldengate/commands/GoldenGateCommandHandler.java` | Wraps `PairCommandsHandler`; handles `showPairingCodeOnDevice` / `clearPairingCodeOnDevice`. |
| `com/fitbit/goldengate/bindings/dtls/DtlsProtocolStatus.java` | DTLS state machine (`TLS_STATE_INIT`, `TLS_STATE_HANDSHAKE`, `TLS_STATE_SESSION`). |
| `com/fitbit/goldengate/bindings/stack/Stack.java` | Native stack wrapper (creates Gattlink + DTLS elements). |
| `com/fitbit/goldengate/bindings/gattlink/GattlinkPacket.java` | Kotlin data class describing Gattlink packet fields (`size`, `isControl`, `hasAck`, `ackPsn`, `dataPsn`, `firstByte`). |
| `com/fitbit/protocol/io/SLIPInputStream.java` | SLIP frame decoder. |
| `defpackage/pgs.java` | **Scan-filter definition** (service UUIDs + legacy device names). |
| `defpackage/kpg.java` (case 9) | **UUID list provider** used by `pgs`. |
| `defpackage/qel.java` & `defpackage/qfx.java` | **Scan invocation** using `pgs.b`. |

---

## 8. Native-Library Findings

**File:** `lib/arm64-v8a/libxp.so` (from `Fitbit-arm64.apk`)

- **Primary role:** GoldenGate protocol engine (native Gattlink + DTLS + CoAP).
- **Key strings observed:**
  - `GG_GattlinkProtocol_*` (HandleControlPacket, SendNextPackets, ConsumeIncomingData, etc.)
  - `mbedtls_ssl_handshake_step`
  - `ssl handshake completed`
  - `state = GG_TLS_STATE_HANDSHAKE`
  - `com/fitbit/goldengate/bindings/coap/CoapEndpoint`
  - `com/fitbit/goldengate/bindings/dtls/TlsKeyResolver`
  - `creating gattlink client - buffer_size=%d, tx_window=%d, rx_window=%d, initial_max_fragment_size=%d`
- **Other libraries in split APK:**
  - `libalexa-android-native.so` – Alexa voice integration.
  - `libopuscodec.so` – Opus audio codec.
  - `libnative_crash_handler_jni.so` – Crash reporting.
  - `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libimage_processing_util_jni.so`, `libminimp3.so`, `libsurface_util_jni.so` – standard/support libraries.

---

## 9. Protocol / Message-Format Findings

### 9.1 Transport Stack
The app uses a **three-layer stack** over BLE:
1. **Gattlink** – reliable, windowed packet stream over two GATT characteristics (Receive / Transmit).
2. **DTLS** – PSK-based TLS handshake (mbedtls) running inside the Gattlink tunnel.
3. **CoAP** – request/response application protocol carried over DTLS.

### 9.2 Gattlink Packet Structure
- Defined in `com.fitbit.goldengate.bindings.gattlink.GattlinkPacket`
- Fields:
  - `size` (int)
  - `isControl` (boolean)
  - `hasAck` (boolean)
  - `ackPsn` (Integer, optional ack sequence)
  - `dataPsn` (Integer, optional data sequence)
  - `firstByte` (int)

### 9.3 Pairing Commands (CoAP)
- **Endpoint:** `pair/display`
- **Method:** `PUT`
- **Request body:** protobuf `PairDisplay.Display`
  - `Command` enum:
    - `CLEAR_CODE(0)`
    - `SHOW_CODE(1)`
- **Handler classes:**
  - `com.fitbit.goldengate.commands.PairCommandsHandler`
  - `com.fitbit.goldengate.commands.GoldenGateCommandHandler`

### 9.4 Bond Control (CoAP / Protobuf)
- Message: `BondControl.Control`
- Field: `operationTimeout` (int)

### 9.5 Device Info (Protobuf)
- Message: `DeviceInfo.Info`
- Fields:
  - `productId`, `hwRevision`, `edition`, `color`
  - `sysVerMajor`, `sysVerMinor`, `fwBuildType`, `fwLang`
  - `batteryLevel`, `voltage`, `onCharger`
  - `btAddress`, `gitDescribe`
  - `devicePeripherals` (repeated)

### 9.6 Sync Request Types (Protobuf)
- Message: `SyncRequestOuterClass`
- Event types include:
  - `WEAR_PAIRING_KEY_REQUEST(15)`
  - `WEAR_ONBOARDING(3)`
  - `WEAR_TODAY_SYNC_BUTTON(5)`
  - `WEAR_SCHEDULED_WORK(14)`
  - `WEAR_USER_INITIATED(24)`

### 9.7 SLIP Framing
- Class: `com.fitbit.protocol.io.SLIPInputStream`
- Suggests some legacy or debug paths use Serial Line Internet Protocol framing over raw byte streams.

---

## 10. What We Can Establish

1. **Discovery is UUID-centric.** The app does not rely on device-name matching for newer trackers; it scans for a small set of Fitbit-specific BLE service UUIDs (notably `FD62`, `FD63`, `ABBAFF00`, `ABBAFB00`, and the `ADAB` family).
2. **The app is a GATT server as well as a client.** It hosts `GattlinkService` / `FitbitGattlinkService` locally, indicating the wearable may connect to the phone’s GATT server.
3. **Security is DTLS-over-Gattlink with PSK.** The presence of `DtlsProtocolStatus`, `TlsKeyResolver`, and mbedtls handshake strings in `libxp.so` confirms BLE traffic is encrypted inside a DTLS tunnel, not just relying on standard BLE pairing.
4. **Pairing is CoAP-driven.** The app sends a CoAP PUT to `pair/display` to tell the wearable to show (or clear) a pairing code.
5. **No Inspire 3 specifics are baked into the APK.** Any Inspire 3 support is generic, with model identification deferred to post-connection protobuf exchange and/or server API.
6. **Message formats are well-defined protobufs.** Classes for `PairDisplay`, `BondControl`, `DeviceInfo`, and `SyncRequest` give concrete schema for building compatible messages.

---

## 11. What Remains Unknown

1. **PSK establishment.** How the pre-shared key for DTLS is derived or exchanged before the secure session is established.
2. **Gattlink wire format.** Exact byte-level encoding of Gattlink packets (the Kotlin class gives logical fields, not the serialized layout).
3. **Inspire 3 service selection.** Whether the Inspire 3 advertises `FD62` or `ABBAFF00` (or both) and which path the app takes during link-up.
4. **Model mapping.** The exact `productId` value that corresponds to the Inspire 3; this mapping is likely returned by Fitbit’s cloud API (`/v1/user/-/device/pairing-preview`).
5. **Post-handshake authentication.** Any additional application-level challenge/response beyond DTLS.
6. **Full CoAP resource tree.** Only `pair/display` was positively identified; the complete set of CoAP endpoints exposed by the wearable is not visible in the APK.

---

## 12. Recommended Next Investigation

1. **Live BLE capture**  
   Enable Android **Bluetooth HCI snoop log** while pairing an Inspire 3 with the official app. Analyze the capture in Wireshark to see:
   - Which service UUID is present in advertisement packets.
   - GATT service discovery flow.
   - Raw data written to `ABBAFF01/ABBAFF02` or `FD62` characteristics (Gattlink frames).

2. **Protobuf field harvesting**  
   Feed captured Gattlink payloads into the generated protobuf classes (`PairDisplay`, `DeviceInfo`, `SyncRequest`, etc.) to deserialize real device responses and extract the Inspire 3 `productId`.

3. **Native reverse engineering**  
   Load `libxp.so` into Ghidra/IDA and examine:
   - `GG_GattlinkProtocol_*` functions for packet parsing.
   - `mbedtls_ssl_handshake_step` callers to locate the PSK callback and identity string format.

4. **Server API inspection**  
   Inspect HTTPS traffic from the app to Fitbit’s backend (`api.fitbit.com`) during device setup. The endpoint `v1/user/-/device/pairing-preview` and related calls may return Inspire 3 model metadata.

5. **Google Fast Pair analysis**  
   The app includes `FastPairLandingActivity` and companion-device service bindings. Check whether Inspire 3 leverages Google Fast Pair (advertising `0xFE2C`) in addition to the custom Fitbit UUIDs.

---

*Report generated from static analysis of Fitbit Android app v5.06.1 (base + arm64 splits). No APK files were modified.*
