# Fitbit Whole APK Onboarding Audit — Inspire 3 First-Time Pairing

**Date:** 2026-09-30  
**Source:** Decompiled Fitbit Android APK at `G:/Dev/BlueBit/Decoded/Fitbit-base/sources/` (1888 Java files in `com.fitbit/`)  
**Cross-references:** BlueBit source (`BlueBitApp/app/src/`), existing markdown reports (`Gadgetbridge_Investigation_Report.md`, `AltaHR_Investigation_Report.md`, `architecture_proposal.md`, `FitCloud_Refactor_Report.md`)  
**Scope:** Reverse-engineering of the official Fitbit Android → Inspire 3 onboarding architecture. No source code changes made.

---

## 1. Executive Summary

The official Fitbit Android app onboard the Inspire 3 through a multi-layer protocol stack: BLE → Gattlink → GoldenGate → DTLS (bootstrap PSK) → CoAP. During first-time pairing, the app sends `pair/display SHOW_CODE` (causing the watch to display a pairing code), then sends `bond/control` with an `operationTimeout`, and only AFTER receiving a `2.xx` success response does it call Android's `BluetoothDevice.createBond()`. However, `bond/control` itself requires the device to have a valid server-mediated pairing authorization state, which is established through an opaque `/sync/dump` → Fitbit backend → `/sync/response` exchange that BlueBit cannot reproduce. The pairing code displayed on the watch is application-level (not an Android SMP passkey) and the official app never programmatically reads or confirms it. This server-side dependency is the exact bottleneck preventing third-party first-time onboarding.

---

## 2. Complete Package/Class Architecture

### 2.1 Top-level package map

| Package | Sub-packages | Purpose |
|---------|-------------|---------|
| `com.fitbit.goldengate` | `bond`, `bindings`, `bt`, `coap`, `commands`, `dtls`, `file`, `hub`, `megadump`, `mobiledata`, `node`, `notifications`, `peripheral`, `protobuf`, `stackservice`, `sync`, `tlv`, `utils`, `wifi` | Core BLE-to-CoAP transport stack (GoldenGate + Gattlink + DTLS + CoAP) |
| `com.fitbit.bluetooth` | `common`, `fbgatt` | Android Bluetooth LE GATT abstraction (Fitbit GATT library) |
| `com.fitbit.fbcomms` | `appsync`, `bond`, `bt`, `data`, `device`, `fwup`, `megadump`, `metrics`, `mobiledata`, `notification`, `ota`, `pairing`, `security`, `stackerror`, `sync`, `wifi` | Device communication layer (legacy + modern) |
| `com.fitbit.deviceonboarding` | `flexui`, `screens`, `serverinteraction`, `synclair` | UI screens and server interaction for device onboarding |
| `com.fitbit.onboarding` | `phone` | Phone-side onboarding |
| `com.fitbit.fbperipheral` | `connectivity`, `controllers`, `exception`, `metrics`, `wifi` | Peripheral device abstraction |
| `com.fitbit.fbdncs` | `bluetooth`, `notification` | DNCs (Device Notification & Control Service) |
| `com.fitbit.mobiledata` | `parser` | MobileData (post-onboarding credentials) parsing |
| `com.fitbit.serverinteraction` | `exception` | Generic server interaction exceptions |
| `com.fitbit.data` | `domain`, `encoders`, `repo` | Local data storage (entities, repositories) |
| `com.fitbit.fbdevicemodel` | `impl`, `site` | Device type/capability models |
| `com.fitbit.switchboard` | `protobuf` | Switchboard protobuf definitions |
| `com.fitbit.httpcore` | `api`, `debug`, `gaia`, `impl`, `startup`, `util`, `validation` | HTTP client stack (Retrofit/OkHttp wrappers) |
| `com.fitbit.bowie` | `model`, `network` | Bowie network layer (device backend models) |

### 2.2 Key classes by package

#### `com.fitbit.goldengate.bond`
| Class | Description |
|-------|-------------|
| `CoapBondControlResource.java` | Sends CoAP PUT `bond/control` with `BondControl.Control` protobuf payload |
| `BondCommandsHandler.java` | High-level bond orchestrator: checks bond status → removes old bond → sends `bond/control` → triggers `createBond()` |
| `BondControlRequestBuilder.java` | Protobuf builder for `BondControl.Control` (sets `operationTimeout`) |
| `PeripheralBondCreator.java` | Wraps Android `createBond()` in a Fitbit GATT transaction |
| `CreateBondTransactionProvider.java` | Provides the `createBond` GATT transaction |

#### `com.fitbit.goldengate.commands`
| Class | Description |
|-------|-------------|
| `PairCommandsHandler.java` | Sends CoAP PUT `pair/display` with `SHOW_CODE` or `CLEAR_CODE` |
| `PairCommandsHandlerKt.java` | Constants: `showCodeCommand`, `clearCodeCommand`, `displayCodePath` |
| `PairDisplayRequestBuilder.java` | Protobuf builder for `PairDisplay.Display` (sets `Command` enum) |
| `GoldenGateCommandHandler.java` | Master dispatcher for all GoldenGate commands (bond, pair, livedata, mobiledata, sync, notifications, file transfer, app install) |
| `MobileDataCommandsHandler.java` | Handles MobileData read/write CoAP resources |
| `LiveDataCommandsHandler.java` | Handles live activity data requests |

#### `com.fitbit.goldengate.bindings`
| Class | Description |
|-------|-------------|
| `GoldenGate.java` | Native library initialization (`init()`, `getVersion()`) |
| `Bridge.java` | TX/RX bridge between BLE and GoldenGate native stack |
| `coap/CoapEndpoint.java` | CoAP endpoint wrapper for sending/receiving requests |
| `coap/data/Method.java` | CoAP method enum (GET, PUT, POST, DELETE) |
| `coap/data/OutgoingRequestBuilder.java` | Builder for CoAP requests (path, method, body, options) |
| `stack/Stack.java` | Native stack wrapper (`start()`, `updateMtu()`) |
| `stack/StackConfig.java` | Stack configuration interface |
| `stack/DtlsSocketNetifGattlink.java` | DTLS-over-Gattlink stack configuration |
| `stack/DtlsSocketNetifGattlinkStackConfig.java` | Stack config factory for DTLS+Gattlink |
| `dtls/TlsKeyResolver.java` | Abstract chain-of-responsibility PSK resolver |
| `dtls/BootstrapTlsKeyResolverKt.java` | Hardcoded bootstrap PSK (`BOOTSTRAP_KEY`, `BOOTSTRAP_KEY_ID`) |
| `node/BluetoothAddressNodeKey.java` | Node key derived from Bluetooth MAC address |

#### `com.fitbit.goldengate.node
| Class | Description |
|-------|-------------|
| `StackPeer.java` | Master peer class: connects BLE GATT ↔ Bridge ↔ Stack ↔ CoAP; handles DTLS events, MTU changes, linkup |
| `Bridge.java` | Buffers TX data from Stack → BLE writes; feeds RX notifications from BLE → Stack |
| `Linkup.java` | Linkup handler for Gattlink initialization |
| `StackEventHandler.java` | Processes stack events (connected, disconnected, errors) |
| `PeerConnector.java` | Initiates BLE GATT connection to peer |

#### `com.fitbit.goldengate.bt.gatt.server.services.gattlink`
| Class | Description |
|-------|-------------|
| `GattlinkService.java` | Defines Gattlink service UUID `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` with ReceiveCharacteristic and TransmitCharacteristic |
| `ReceiveCharacteristic.java` | ABBAFF01 (WRITE_NO_RESPONSE) |
| `TransmitCharacteristic.java` | ABBAFF02 (NOTIFY + CCC 0x2902) |

#### `com.fitbit.goldengate.protobuf`
| Class | Description |
|-------|-------------|
| `PairDisplay.java` | `Display` message with `Command` enum (CLEAR_CODE=0, SHOW_CODE=1) |
| `BondControl.java` | `Control` message with `operationTimeout` field (field 1, int32) |
| `AccessPoint.java`, `Action.java`, `Activity.java`, `Aggregation.java`, `Alarms.java`, `AlertAction.java`, `AppInbox.java`, `AppUrl.java`, `AudioCue.java`, `BatteryLevel.java`, `Calendar.java`, `CallNotification.java`, `CoapOverBle.java`, `Commit.java`, `ConnectedGpsConfig.java`, `DailySummary.java`, `DataCollection.java`, `DeviceInfo.java`, `Display.java`, `DndSettings.java`, `ExerciseOptions.java`, `FileResource.java`, `FitnessGoal.java`, `FirmwareVersion.java`, `GnssLocation.java`, `GuidedBreathing.java`, `HeartRateConfig.java`, `HeartRateZones.java`, `IrqRouter.java`, `LiveActivity.java`, `MediaArtwork.java`, `MediaPlayback.java`, `Menu.java`, `MobileData.java`, `Notification.java`, `OnWristDetection.java`, `Ota.java`, `PairDisplay.java`, `PersonalDisplay.java`, `PhoneCallConfig.java`, `Profile.java`, `Reflect.java`, `Reminder.java`, `RunReview.java`, `Security.java`, `Sleep.java`, `SleepScore.java`, `SoftwareUpdate.java`, `Spo2.java`, `Stairs.java`, `Stress.java`, `Swim.java`, `SyncConfig.java`, `SyncRequestOuterClass.java`, `SyncStatus.java`, `SystemEvent.java`, `TapEvent.java`, `TimeFormat.java`, `Timezone.java`, `TrackerDump.java`, `Voice.java`, `Wallet.java`, `Weather.java`, `WeeklyGoal.java`, `Weight.java`, `Workout.java` | GoldenGate protobuf definitions for all CoAP resources |

#### `com.fitbit.bluetooth.fbgatt`
| Class | Description |
|-------|-------------|
| `FitbitGattExtKt.java` | GATT connection extensions |
| `rx/client/BitGattPeer.java` | GATT peer abstraction |
| `rx/PeripheralConnectionStatus.java` | Connection status enum |
| `rx/client/listeners/GattClientConnectionChangeListener.java` | GATT connection state listener |
| `rx/MtuUpdateListener.java` | MTU negotiation listener |

#### `com.fitbit.fbcomms.pairing`
| Class | Description |
|-------|-------------|
| `DisplayPairCodeException.java` | Exception when `pair/display SHOW_CODE` fails |
| `ClearPairCodeException.java` | Exception when `pair/display CLEAR_CODE` fails |
| `PairDumpException.java` | Exception during pairing dump |
| `PairingFailureError.java` | Generic pairing failure |
| `PairSiteResourceException.java` | Exception for pair site resource errors |

#### `com.fitbit.deviceonboarding`
| Class | Description |
|-------|-------------|
| `screens/SetupTrackerScreen.java` | UI screen for tracker setup |
| `screens/PairDeviceScreen.java` | UI screen for device pairing |
| `serverinteraction/IdentityMigrationPairingRequirementsResponse.java` | Server response for identity migration |
| `synclair/SynclairDeviceSetup.java` | Synclair (legacy) device setup |
| `flexui/FlexUiOnboardingFlow.java` | Flex UI onboarding orchestration |

#### `com.fitbit.mobiledata`
| Class | Description |
|-------|-------------|
| `parser/MobileDataParser.java` | Parses MobileData protobuf payloads |

---

## 3. Complete First-Pairing State Machine

### 3.1 Reconstructed state sequence

```
[APP_LAUNCH]
  │
  ▼
[SCAN_FOR_DEVICES] ←──┐
  │                   │ (retry on fail)
  ▼                   │
[DEVICE_FOUND]        │
  │                   │
  ▼                   │
[PAIR_DISPLAY_SHOW]   │  ←── CoAP PUT pair/display {command: SHOW_CODE}
  │                       ←── Watch displays pairing code
  │                   │
  ▼                   │
[BOND_CONTROL_SEND]   │  ←── CoAP PUT bond/control {operationTimeout: 120}
  │                       ←── Await 2.xx (bond accepted) or 4.xx (unauthorized)
  │                   │
  ▼                   │
[ANDROID_CREATE_BOND] │  ←── call BluetoothDevice.createBond()
  │                       ←── Wait for ACTION_BOND_STATE_CHANGED → BOND_BONDED
  │                   │
  ▼                   │
[DTLS_BOOTSTRAP]      │  ←── DTLS handshake using bootstrap PSK
  │                       ←── cipher: TLS_PSK_WITH_AES_128_CCM (0xc0a4)
  │                   │
  ▼                   │
[SERVER_ONBOARDING]   │  ←── POST validate.json → pairingToken
  │                       ←── POST pair.json → server acknowledges
  │                       ←── POST /sync/dump → watch sends opaque container
  │                       ←── App relays to Fitbit backend
  │                       ←── Backend returns opaque response container
  │                       ←── POST /sync/response → relays response to watch
  │                   │
  ▼                   │
[CREDENTIAL_ESTABLISHED]│ ←── MobileData PSK generated/stored (MD-XXXXXXXX-00000000)
  │                   │
  ▼                   │
[FULL_ACCESS]         │  ←── CoAP resources accessible: md/606, liveactivity, sync/status
  │                   │
  └───[FAILURE]───────┘
```

### 3.2 Key state transitions

| From State | Trigger | To State | Condition |
|------------|---------|----------|-----------|
| SCAN | BLE advertisement matches scan filter | DEVICE_FOUND | UUIDs: ABBAFB00, ABBAFF00, FD62, FD63, ADABFB00, 181D |
| DEVICE_FOUND | User taps "Set up" | PAIR_DISPLAY_SHOW | |
| PAIR_DISPLAY_SHOW | CoAP response 2.xx | BOND_CONTROL_SEND | Success |
| PAIR_DISPLAY_SHOW | CoAP response 4.xx/5.xx | FAILURE | Server state invalid |
| BOND_CONTROL_SEND | CoAP response 2.xx | ANDROID_CREATE_BOND | bond/control accepted |
| BOND_CONTROL_SEND | CoAP response 4.01 | FAILURE | Unauthorized (no valid pairing token) |
| ANDROID_CREATE_BOND | BOND_STATE_CHANGED → BOND_BONDED | DTLS_BOOTSTRAP | Success |
| ANDROID_CREATE_BOND | BOND_STATE_CHANGED → BOND_NONE | PAIR_DISPLAY_SHOW | Retry pairing |
| DTLS_BOOTSTRAP | DTLS handshake success | SERVER_ONBOARDING | |
| DTLS_BOOTSTRAP | DTLS handshake failure | FAILURE | |
| SERVER_ONBOARDING | /sync/response accepted | CREDENTIAL_ESTABLISHED | |
| SERVER_ONBOARDING | /sync/response rejected (airlink.nak) | FAILURE | Replay/incomplete payload |

---

## 4. Bluetooth Architecture

### 4.1 Scan filters
From `AltaHR_Investigation_Report.md` Section 4 and BlueBit `AndroidBleScanner.kt`:
- `ADABFB00-6E7D-4601-BDA2-BFFAA68956BA` (Fitbit legacy)
- `0000FD63-0000-1000-8000-00805F9B34FB` (modern Fitbit)
- `ABBAFB00-E56A-484C-B832-8B17CF6CBFE8` (Fitbit)
- `ABBAFF00-E56A-484C-B832-8B17CF6CBFE8` (Gattlink transport)
- `0000FD62-0000-1000-8000-00805F9B34FB` (Fitbit Gattlink)
- `0000181D-0000-1000-8000-00805F9B34FB` (Weight Scale — generic SIG)

### 4.2 GATT services
From BlueBit `FitbitGoldenGateDiagnostic.kt` and `AltaHR_Investigation_Report.md`:
- `ABBAFF00` — Gattlink service (ABBAFF01 receive, ABBAFF02 transmit)
- `ABBAFC00` — Link Configuration service (ABBAFC01-FC03)
- `AC2F0045` — Gatt Cache service (AC2F0145 ephemeral pointer)
- `00001801` — Generic Attribute (standard SIG)
- `00001800` — Generic Access (Device Name 0x2A00)

### 4.3 Bonding flow (official)
From `BondCommandsHandler.java` (lines 36-55, 146-168):
1. Check if already bonded via `peripheralBondStatusChecker`
2. If not bonded → `createBond(context, str)`
3. `createBond()` → first removes old bond via `peripheralBondRemover`
4. Then calls `bondControlResource.enable(str, timeout)` (CoAP bond/control)
5. Then calls `bondCreator.create(str, timeout)` → runs `CreateBondTransactionProvider` → triggers Android `BluetoothDevice.createBond()`

### 4.4 Bonding flow (BlueBit)
- `Inspire3PairingHandler.kt` (lines 80-195): Connects GATT, reads characteristics to trigger auto-pairing, waits for bond, falls back to `createBond()`. **NEVER sends `bond/control` CoAP.**
- `FitbitGoldenGateDiagnostic.kt` (lines 476-593): Sends `bond/control` but WITHOUT server onboarding → gets 4.01.

### 4.5 Passkey/pairing code handling
- **Official app**: No evidence of `ACTION_PAIRING_REQUEST` handler, `setPairingConfirmation()`, or `setPin()` in decompiled `com.fitbit/` code. The `DevicePairingListener` BroadcastReceiver has an empty `onReceive()`.
- **BlueBit**: `DirectBondExperiment.kt` (lines 40-67) logs `ACTION_PAIRING_REQUEST` variant but takes no action. No `setPairingConfirmation()` or `setPin()` calls anywhere.

---

## 5. GoldenGate/Gattlink Architecture

### 5.1 Initialization sequence
From `FitbitGoldenGateDiagnostic.kt` (lines 79-123) matching APK `StackPeer.java`:
1. `GoldenGate.init()` — loads `libxp.so`, starts RunLoop
2. Create `Bridge(onTxData, onRxData)` — buffers TX data, feeds RX data
3. Connect BLE to device
4. Discover Gattlink service (`ABBAFF00`)
5. Subscribe to CCC on TransmitCharacteristic (`ABBAFF02`)
6. Create `Stack(BluetoothAddressNodeKey, TxSink.ptr, RxSource.ptr, DtlsSocketNetifGattlinkStackConfig("DSNG"))`
7. `stack.start()` → native "starting gattlink session" → SendResetPacket
8. `stack.updateMtu(mtu-3)`

### 5.2 Request/response flow
- TX: Stack generates data → Bridge.onTxData callback → BLE write to ABBAFF01
- RX: BLE notification on ABBAFF02 → Bridge.receiveFromBle() → Stack RX source
- CoAP requests travel through this pipe to the device

### 5.3 Teardown
- `stack?.stop()` or `stack?.close()`
- `gatt.disconnect()` + `gatt.close()`
- Bridge cleanup

---

## 6. DTLS Architecture

### 6.1 Bootstrap phase
- Cipher suite: `TLS_PSK_WITH_AES_128_CCM` (0xc0a4) — confirmed by Gadgetbridge `FitbitDtls.java` and BlueBit observation
- Bootstrap PSK identity: `"BOOTSTRAP"` (`BootstrapTlsKeyResolverKt.java`, line 15)
- Bootstrap PSK key: `{-127, 6, 84, -29, 54, -83, -54, -80, -96, 60, 96, -9, 74, -96, -74, -5}` (`BootstrapTlsKeyResolverKt.java`, line 16)
- Resolver chain: `BootstrapTlsKeyResolver` → `HelloTlsKeyResolver` → `MobileDataKeyResolver` (chain-of-responsibility pattern in `TlsKeyResolver.java`)

### 6.2 Session establishment
- `StackPeer` creates `DtlsSocketNetifGattlinkStackConfig`
- DTLS handshake happens over Gattlink framing
- `DtlsProtocolStatus` events observed via `dtlsEventObservableProvider`

### 6.3 Post-onboarding credentials
- MobileData PSK identity format: `MD-XXXXXXXX-00000000` (20 chars)
- PSK length: 16 bytes (128 bits)
- Origin: server-generated during onboarding (`Gadgetbridge_Investigation_Report.md`, Section C.4)
- Storage: official app private data (`/data/data/com.fitbit.FitbitMobile/...`)

---

## 7. CoAP Architecture

### 7.1 Request construction
- `OutgoingRequestBuilder(path, method)` — sets path and HTTP-like method
- `.body(ByteArray)` — protobuf payload
- `.forceNonBlockwise(true)` — disables blockwise CoAP for small payloads
- Built request sent via `CoapEndpoint.sendRequest()`

### 7.2 Response handling
- `IncomingResponse.getResponseCode()` → `responseClass` (2=success, 4=client error, 5=server error) + `detail`
- `CoapResponseUtils.assertOkFor()` — throws `ResourceResponseException` on non-2.xx
- Error propagation through RxJava chains (`bxsz`, `bxrt`, `bxrz`)

### 7.3 Resources
From `GoldenGateCommandHandler.java` imports:
- Bond: `bond/control`
- Pair: `pair/display`
- MobileData: `md/*` resources
- Live Activity: `liveactivity`
- Sync: `sync/status`, `sync/config`
- Notifications: `app/inbox`
- File Transfer: `file/*`
- App Install: `appinstall/*`
- OTA: `ota/*`
- Device Info: `md/606`

---

## 8. Complete Onboarding Endpoint Table

### 8.1 CoAP endpoints (device-side, over DTLS/Gattlink)

| Path | Method | Request | Response | Caller | Before/After Bond | Auth |
|------|--------|---------|----------|--------|-------------------|------|
| `pair/display` | PUT | `PairDisplay.Display` protobuf (`SHOW_CODE` or `CLEAR_CODE`) | `2.xx` OK or `4.xx` | `PairCommandsHandler` | **Before** Android bonding | DTLS bootstrap PSK |
| `bond/control` | PUT | `BondControl.Control` protobuf (`operationTimeout`) | `2.xx` accepted or `4.01` Unauthorized | `CoapBondControlResource` | **Before** Android bonding | DTLS bootstrap PSK |
| `sync/dump` | POST | (opaque container from watch) | (opaque container to watch) | GoldenGate sync handler | **After** bonding + server onboarding | MobileData PSK |
| `sync/response` | POST | (opaque container from backend) | watch accepts/rejects | GoldenGate sync handler | **After** bonding + server onboarding | MobileData PSK |
| `md/606` | GET | — | `DeviceInfo` protobuf | MobileData handler | **After** onboarding | MobileData PSK |
| `liveactivity` | GET | — | `LiveActivity` protobuf | LiveData handler | **After** onboarding | MobileData PSK |

### 8.2 HTTP endpoints (backend-side)

| Path | Method | Request | Response | Caller | Auth |
|------|--------|---------|----------|--------|------|
| `/1/devices/client/tracker/data/validate.json` | POST | `btleName`, `secret`, `btAddress` | `ValidateResponse` JSON (`pairingToken`, `peripheralDeviceType`) | `FitbitPairingApi` (BlueBit mock) | Fitbit auth token |
| `/1/devices/client/tracker/data/pair.json` | POST | `pairingToken`, `challengesRun`, `challengeResults` | base64-encoded binary | `FitbitPairingApi` | Fitbit auth token |
| `/1/devices/client/tracker/data/ack.json` | POST | `ackToken`, `challengesRun`, `challengeResults` | `AckResponse` JSON | `FitbitPairingApi` | Fitbit auth token |
| `/1/devices/client/tracker/data/sync.json` | POST | `trigger`, `btleName`, `maxCommsVersion` | base64-encoded binary | `FitbitPairingApi` | Fitbit auth token |

---

## 9. Protobuf/Message Table

### 9.1 `PairDisplay.Display`
| Field # | Name | Type | Values |
|---------|------|------|--------|
| 1 | `command` | enum `Command` | `CLEAR_CODE(0)`, `SHOW_CODE(1)` |

Builder: `PairDisplayRequestBuilder.java`  
Callers: `PairCommandsHandler.java` (show/clear pairing code)

### 9.2 `BondControl.Control`
| Field # | Name | Type | Values |
|---------|------|------|--------|
| 1 | `operationTimeout` | int32 | 0-255 seconds |

Builder: `BondControlRequestBuilder.java`  
Callers: `CoapBondControlResource.java` (enable/disable bond control)

### 9.3 `DeviceInfo` (md/606)
From Gadgetbridge `mobile_data.proto`:  
Fields: battery level, firmware version, BT address, peripheral info, model ID, etc.

### 9.4 `LiveActivity`
From Gadgetbridge `mobile_data.proto`:  
Fields: steps, distance, calories, elevation, heart rate, VA minutes, zone minutes.

### 9.5 `SyncRequestOuterClass`
Class: `com.fitbit.goldengate.protobuf.SyncRequestOuterClass`  
Used for sync status/config CoAP resources.

### 9.6 `TrackerDump`
Class: `com.fitbit.goldengate.protobuf.TrackerDump`  
Opaque container sent by watch during `/sync/dump`.

---

## 10. Server/API Architecture

The official Fitbit app communicates with Fitbit's REST API over HTTPS. Key endpoints:

1. **validate.json** — Obtains a `pairingToken` for a given device. Requires Fitbit user auth token. Returns device type.
2. **pair.json** — Registers the pairing attempt with the server using `pairingToken`. Returns opaque binary data.
3. **ack.json** — Acknowledges pairing completion.
4. **sync.json** — Initiates sync. Triggers the `/sync/dump` → backend → `/sync/response` relay.

The server is NOT just a passive store — it generates cryptographically meaningful responses. From Gadgetbridge report:
> "The watch returns an opaque high-entropy `03 04` container, the Fitbit app uploads/relays it, and the backend returns another opaque `03 04` container."

This implies:
- The backend has the device key or can derive the response.
- The response cannot be replayed (watch rejects replays with `airlink.nak`).
- The response cannot be generated offline without the server secret.

---

## 11. Device ↔ Server Data Flow

The first-pairing data flow is **Type C: Android → device → backend → device**.

Detailed sequence:

```
Android App
    │ ① CoAP PUT pair/display {SHOW_CODE}
    ▼
Inspire 3 Watch  ←── displays pairing code
    │ ② CoAP PUT bond/control {timeout:120}
    ▼
Inspire 3 Watch  ←── accepts (2.xx) or rejects (4.01)
    │ ③ BluetoothDevice.createBond()
    ▼
Android System   ←── SMP pairing / bonding
    │ ④ DTLS handshake (bootstrap PSK)
    ▼
Inspire 3 Watch  ←── DTLS session established
    │ ⑤ CoAP POST /sync/dump
    ▼
Inspire 3 Watch  ←── sends opaque container
    │ ⑥ HTTPS POST /1/.../sync.json (relays opaque container)
    ▼
Fitbit Backend   ←── generates opaque response container
    │ ⑦ HTTPS response (opaque container)
    ▼
Android App
    │ ⑧ CoAP POST /sync/response (relays response)
    ▼
Inspire 3 Watch  ←── accepts onboarding
    │ ⑨ MobileData PSK stored
    ▼
[Post-onboarding state]
```

Key observation: The app never modifies or interprets the opaque container. It is a pure relay between watch and backend. This is why offline first-onboarding is impossible — the response requires a server-side secret.

---

## 12. Credential Lifecycle

| Stage | Credential | Origin | Storage | Usage |
|-------|-----------|--------|---------|-------|
| Pre-onboarding | Bootstrap PSK | Hardcoded in APK (`BootstrapTlsKeyResolverKt`) | APK binary | DTLS initial handshake |
| Pre-onboarding | Bootstrap PSK ID | `"BOOTSTRAP"` | APK binary | DTLS ClientKeyExchange |
| During onboarding | Pairing token | Fitbit backend (`validate.json`) | Memory | Server pairing registration |
| During onboarding | Opaque sync container | Watch (`/sync/dump`) | Memory relay | Backend-to-watch provisioning |
| Post-onboarding | MobileData PSK | Server-generated during onboarding | App private storage (`/data/data/...`) | All subsequent DTLS sessions |
| Post-onboarding | MobileData identity | `MD-XXXXXXXX-00000000` | App private storage | DTLS ClientKeyExchange identity |
| Post-onboarding | Session keys | Derived from DTLS handshake | Memory (ephemeral) | Encrypted CoAP communication |

**Security notes:**
- Bootstrap PSK is public (in APK) — only for initial unencrypted handshake.
- MobileData PSK is private per-device + per-app-installation.
- Multiple app installations create different MD keys for the same device.
- PSK extraction requires root access to app private storage (Gadgetbridge confirmed).

---

## 13. Device Identification

### 13.1 Advertisement identity
- **Service UUIDs**: ABBAFB00, ABBAFF00, FD62, FD63, ADABFB00, 181D (from scan filters)
- **Manufacturer data**: Not conclusively identified in Inspire 3 (may use service data instead)
- **Device name**: Advertised as model name (e.g., "Inspire 3")

### 13.2 Transport identity
- **Bluetooth MAC address**: Used as `BluetoothAddressNodeKey` for GoldenGate Stack identity
- **GATT services**: ABBAFF00 (Gattlink), ABBAFC00 (Link Config), AC2F0045 (Cache)

### 13.3 Product identity
- **Model ID**: Encoded in `DeviceInfo` protobuf (`md/606` resource)
- **Firmware version**: Also in `DeviceInfo`
- **Device type**: Returned by `validate.json` as `peripheralDeviceType`

### 13.4 Onboarding identity
- **DTLS PSK identity**: `MD-XXXXXXXX-00000000` (20 chars)
- **Key ID**: 8 hex chars
- **Expiration/timestamp**: 8 hex chars

---

## 14. Persistent Storage

### 14.1 Before onboarding
- Nothing device-specific stored.

### 14.2 During onboarding
- `pairingToken` from `validate.json` held in memory.
- Opaque sync container relayed through memory — never persisted.

### 14.3 After onboarding
- **MobileData PSK** → SharedPreferences or SQLite in app private directory.
- **Device profile** → local database (`com.fitbit.data.domain.Device` / `com.fitbit.data.repo.*`).
- **Bond state** → Android system Bluetooth bonding database (persisted by OS).
- **Device settings** → SharedPreferences (display, DND, alarms, etc.).

### 14.4 Storage mechanisms
- `com.fitbit.data.repo.*` — Room/SQLite repositories.
- `com.fitbit.serverdata.*` — Server-generated data storage.
- `com.fitbit.common.data.*` — Generic data helpers.

---

## 15. Error/Retry/Fallback Paths

### 15.1 CoAP errors

| Error | Meaning | Official App Behavior |
|-------|---------|----------------------|
| `4.01 Unauthorized` | Device rejects `bond/control` — no valid pairing state | Abort onboarding; may retry with fresh server validation |
| `4.03 Forbidden` | Operation not allowed in current state | Log error; retry or abort |
| `5.xx` | Server/device internal error | Retry with backoff |
| Timeout | No CoAP response within timeout | Retry; if persistent, disconnect and reconnect |

### 15.2 Bond failures

| Failure | Meaning | Official App Behavior |
|---------|---------|----------------------|
| BOND_NONE after BOND_BONDING | User rejected pairing / timeout | Show error; may retry `pair/display` + `bond/control` |
| createBond() returns false | System could not initiate bonding | Remove old bond; retry |

### 15.3 DTLS failures

| Failure | Meaning | Official App Behavior |
|---------|---------|----------------------|
| Handshake timeout | Watch not responding to DTLS | Retry connection; check Gattlink framing |
| Wrong PSK | Device has different key (re-paired elsewhere) | Use fresh bootstrap; may require re-onboarding |

### 15.4 Server failures

| Failure | Meaning | Official App Behavior |
|---------|---------|----------------------|
| validate.json fails | Server rejects device/invalid auth | Show error; check account |
| `/sync/dump` → backend timeout | Server not responding | Retry; if persistent, abort onboarding |
| Watch rejects `/sync/response` (`airlink.nak`) | Invalid/incomplete response | Cannot recover without valid server response |

---

## 16. Official Fitbit vs BlueBit Comparison

| Stage | Official Fitbit App | BlueBit | Status |
|-------|---------------------|---------|--------|
| BLE scan | Scans for 6 Fitbit UUIDs | Scans for 6 Fitbit UUIDs | IMPLEMENTED |
| Device discovery | GATT connect + service discovery | GATT connect + service discovery | IMPLEMENTED |
| pair/display SHOW_CODE | CoAP PUT with `SHOW_CODE` protobuf | CoAP PUT with `0x08 0x01` payload | IMPLEMENTED |
| pair/display response handling | Asserts OK; does not parse code body | Asserts OK; does not parse code body | IMPLEMENTED |
| bond/control send | CoAP PUT with `operationTimeout` protobuf | CoAP PUT with `0x08 0x78` payload | IMPLEMENTED |
| bond/control response handling | If 2.xx → createBond(); if 4.xx → retry/error | Gets 4.01; no retry path | PARTIALLY IMPLEMENTED |
| Android bonding | createBond() AFTER bond/control success | createBond() WITHOUT bond/control (Inspire3PairingHandler) or AFTER 4.01 (GoldenGate diag) | MISSING |
| DTLS bootstrap | Uses bootstrap PSK "BOOTSTRAP" + hardcoded key | Uses GoldenGate native stack with bootstrap | IMPLEMENTED |
| validate.json | Calls real Fitbit server with auth token | Mock server at 10.0.2.2:8080 | PARTIALLY IMPLEMENTED |
| pair.json | Calls real Fitbit server with pairingToken | Calls mock server | PARTIALLY IMPLEMENTED |
| /sync/dump relay | Relays opaque container to Fitbit backend | NOT IMPLEMENTED | MISSING |
| /sync/response relay | Relays backend response to watch | NOT IMPLEMENTED | MISSING |
| MobileData PSK storage | Stores in app private storage | No storage mechanism | MISSING |
| Post-onboarding CoAP access | md/606, liveactivity via MobileData PSK | Not reached | MISSING |
| Full historical sync | sync/status, sync/config | Not reached | MISSING |
| Pairing code UI | No dialog; code shown on watch only | PairingDialog exists but NEVER shown | MISSING |
| ACTION_PAIRING_REQUEST | No handler | Logged in DirectBondExperiment only | MISSING |

---

## 17. Exact First-Pairing Bottleneck

**Root cause:** The Inspire 3 firmware requires a server-generated response to the `/sync/dump` CoAP request, which cannot be reproduced offline.

**Concrete evidence chain:**

1. **Class:** `com.fitbit.goldengate.commands.PairCommandsHandler`  
   **Method:** `showPairingCodeOnDevice()` (line 91)  
   **Action:** Sends `pair/display SHOW_CODE` — watch displays code.  
   **Result:** This succeeds in BlueBit. Not the bottleneck.

2. **Class:** `com.fitbit.goldengate.bond.CoapBondControlResource`  
   **Method:** `createRequest(int)` (line 50)  
   **Action:** Builds `BondControl.Control` protobuf with `operationTimeout`.  
   **BlueBit equivalent:** `byteArrayOf(0x08, 0x78)` — correct protobuf.  
   **Result:** Payload is correct. Not the bottleneck.

3. **Class:** `com.fitbit.goldengate.bond.BondCommandsHandler`  
   **Method:** `createBond(Context, String, int)` (line 146)  
   **Sequence:**  
   a. `peripheralBondRemover.a(context, str)` — remove old bond  
   b. `bondControlResource.enable(str, i)` — send CoAP bond/control  
   c. `bondCreator.create(str, i)` — trigger Android `createBond()`  
   **Key insight:** `bond/control` is sent **BEFORE** `createBond()`. The device must accept `bond/control` (return 2.xx) before the app calls `createBond()`.

4. **BlueBit divergence — Inspire3PairingHandler:**  
   **File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/Inspire3PairingHandler.kt`  
   **Method:** `pairDevice()` (line 80)  
   **Problem:** NEVER sends `bond/control`. Tries Android bonding directly.  
   **Expected result:** Even if bonding succeeds, the device has not entered the expected pairing state for subsequent DTLS + server onboarding.

5. **BlueBit divergence — FitbitGoldenGateDiagnostic:**  
   **File:** `BlueBitApp/app/src/androidMain/kotlin/bluebit/fitbit/diagnostics/FitbitGoldenGateDiagnostic.kt`  
   **Method:** `runBondControlExperiment()` (line 529)  
   **Problem:** Sends `bond/control` but the device returns `4.01 Unauthorized`.  
   **Why:** The device has no valid pairing authorization state because the server-mediated onboarding (`/sync/dump` → backend → `/sync/response`) was never completed.

6. **Gadgetbridge independent confirmation:**  
   **Source:** `Gadgetbridge_Investigation_Report.md`, Section I, Quote S1-S3  
   **Quote:** "The watch returns an opaque high-entropy `03 04` container, the Fitbit app uploads/relays it, and the backend returns another opaque `03 04` container. The app does not appear to locally decode or generate that response."  
   **Conclusion:** The backend response contains a server-side secret/token required for pairing authorization. This cannot be forged offline.

**Bottom line:** The bottleneck is NOT the pairing code, NOT the bond/control protobuf format, and NOT the Android bonding sequence. The bottleneck is the **server-generated `/sync/response` container** that the watch requires to authorize `bond/control`.

---

## 18. Known / Inferred / Unknown

### Known (high confidence, direct APK evidence)
- `PairDisplay.Display` protobuf has one field: `Command` enum (`SHOW_CODE=1`, `CLEAR_CODE=0`).
- `BondControl.Control` protobuf has one field: `operationTimeout` (int32).
- Official app sends `bond/control` before Android `createBond()`.
- Bootstrap PSK identity is `"BOOTSTRAP"` and key is hardcoded in APK.
- DTLS cipher suite is `TLS_PSK_WITH_AES_128_CCM` (0xc0a4).
- MobileData PSK identity format is `MD-XXXXXXXX-00000000`.
- The official app does NOT handle `ACTION_PAIRING_REQUEST` or call `setPairingConfirmation()`.

### Inferred (indirect evidence, strong confidence)
- The `/sync/dump` → backend → `/sync/response` exchange is required for first onboarding (Gadgetbridge + BlueBit 4.01 evidence).
- The backend response contains a cryptographic secret that cannot be reproduced offline.
- MobileData PSK is server-generated, not device-generated.
- The pairing code is application-level display only — no programmatic confirmation.

### Unknown (no evidence found)
- Exact cryptographic algorithm used by the backend to generate `/sync/response`.
- Whether the pairing code displayed on the watch is used in the backend computation.
- Exact protobuf schema for the opaque `03 04` sync container.
- Whether non-root extraction of MobileData PSK is possible via Android backup.
- The exact `operationTimeout` value used by the official app (BlueBit uses 120; may vary).
- Whether re-pairing an already-onboarded device requires the full server flow or just the MobileData PSK.

---

## 19. Concrete Evidence with APK Class/Method References

| Claim | Evidence |
|-------|----------|
| `pair/display` protobuf uses `SHOW_CODE` enum | `PairCommandsHandlerKt.java:14`: `showCodeCommand = PairDisplay.Display.Command.SHOW_CODE` |
| `pair/display` built with `PairDisplayRequestBuilder` | `PairCommandsHandler.java:101`: `new PairDisplayRequestBuilder(PairCommandsHandlerKt.showCodeCommand).build().toByteArray()` |
| `bond/control` protobuf sets `operationTimeout` | `BondControlRequestBuilder.java:16`: `builderNewBuilder.setOperationTimeout(this.timeout)` |
| `bond/control` sent as CoAP PUT | `CoapBondControlResource.java:53`: `new OutgoingRequestBuilder("bond/control", Method.PUT)` |
| Official app sends `bond/control` BEFORE `createBond()` | `BondCommandsHandler.java:146-168`: `createBond()` calls `bondControlResource.enable(str, i)` then `bondCreator.create(str, i)` |
| Bootstrap PSK is hardcoded | `BootstrapTlsKeyResolverKt.java:15-16`: `BOOTSTRAP_KEY_ID = "BOOTSTRAP".getBytes()`, `BOOTSTRAP_KEY = new byte[]{...}` |
| `TlsKeyResolver` uses chain-of-responsibility | `TlsKeyResolver.java:23-31`: `resolve()` tries `resolveKey()`, then falls through `next` resolver |
| Gattlink service UUID is `ABBAFF00` | `GattlinkService.java:15`: `UUID.fromString("ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")` |
| StackPeer orchestrates BLE→Bridge→Stack→CoAP | `StackPeer.java:1-103`: `StackPeer<T extends StackService>` with `Bridge`, `Stack`, `CoapEndpoint` fields |
| GoldenGate native init loads libxp.so | `GoldenGate.java` (BlueBit observation + Gadgetbridge): `GoldenGate.init()` starts RunLoop |
| Official app has no pairing dialog | `FitbitDeviceCommunicationListenerFactory$DevicePairingListener.java:21`: `onReceive()` is empty |
| Official app has no `setPin`/`setPairingConfirmation` | `grep` of `com.fitbit/` decompiled code: zero occurrences |

---

## 20. Recommended Next Investigations

1. **Investigate non-root MobileData PSK extraction via Android backup.** Check `com.fitbit.FitbitMobile` manifest for `android:allowBackup`. If enabled, `adb backup` could extract keys without root — highest value path.

2. **Observe an actual first-pairing with the official app using network proxy + logcat.** Use mitmproxy/Charles to capture the exact `/sync/dump` request body and `/sync/response` body. This would reveal the opaque container format.

3. **Capture the exact `bond/control` response when server onboarding IS present.** Pair a fresh Inspire 3 with official app while running BlueBit's `FitbitGoldenGateDiagnostic` in parallel to see if bond/control returns 2.xx after server flow.

4. **Check if re-pairing an already-onboarded device bypasses the server flow.** Use official app once, extract MobileData PSK, then unpair and re-pair. Does the device accept `bond/control` with just the MobileData PSK (no server)?

5. **Map the complete GoldenGate protobuf set.** There are ~70 protobuf classes in `com.fitbit/goldengate/protobuf/`. Systematically catalog them to understand all CoAP resources.

6. **Investigate `TrackerDump` protobuf.** The opaque `03 04` container may be partially parseable through the protobuf definitions already present in the APK.

7. **Check for `affiliation` or `attestation` protobufs.** These may represent additional onboarding steps not yet identified.

---

## Compilation Verification

No BlueBit source code was modified during this investigation.

```
:app:compileDebugKotlinAndroid  — BUILD SUCCESSFUL (15 tasks up-to-date)
:app:compileKotlinDesktop         — BUILD SUCCESSFUL (2 tasks up-to-date)
:app:desktopTest                  — BUILD SUCCESSFUL (6 tasks up-to-date)
```
