# Inspire 3 — Fitbit Connection Reconstruction Report

## Fitbit → BlueBit Dependency Map

| Fitbit Class | Responsibility | BlueBit Equivalent | Missing Behaviour | Implementation Required |
|---|---|---|---|---|
| `BitGattPeer` | GATT peer abstraction; connect, discover, MTU, notifications | `AndroidBleConnection` | None — equivalent | No |
| `GattCharacteristicReader` | Queued GATT read via transaction | `AndroidBleConnection.readCharacteristic()` | Uses transaction queue (`runTxReactive`) | No — mutex+deferred equivalent |
| `GattCharacteristicWriter` | Queued GATT write via transaction | `AndroidBleConnection.writeCharacteristic()` | Uses transaction queue | No — mutex+deferred equivalent |
| `GattServiceDiscoverer` | Service discovery via transaction | `AndroidBleConnection.discoverServices()` | Uses transaction queue | No — equivalent |
| `GattServiceRefresher` | Service refresh via transaction | None | Rarely needed | No |
| `PeerConnector` | Orchestrates GATT connect | `AndroidBleConnection.connect()` | None | No |
| `LinkupWithPeerNodeHandler` | Linkup: discover → subscribe ServiceChanged → validate GattDB → subscribe Gattlink | `FitbitGoldenGateDiagnostic` | **Missing `setCharacteristicNotification` calls** | **YES** |
| `GattDatabaseValidator` | Read ephemeral pointer, write 0x00 | `FitbitGoldenGateDiagnostic` | Correctly implemented | No |
| `BondCommandsHandler` | Bond creation/removal | `Inspire3PairingHandler` / `createBond()` | Correct | No |
| `StackPeer` | Full connection lifecycle | `FitbitGoldenGateDiagnostic` | Missing MTU listener, missing `notifyListener` | **YES** |
| `Bridge` | TX/RX data plumbing | `Bridge` | Simplified but functionally equivalent | Partial |
| `TxSink` | Native → Java TX callback | `TxSink` | **Missing `notifyListener()` calls** | **YES** |
| `RxSource` | Java → Native RX feed | `RxSource` | Correct | No |
| `Stack` | Native stack lifecycle | `Stack` | Correct | No |
| `DtlsSocketNetifGattlinkStackConfig` | Stack config "DSNG" | `DtlsSocketNetifGattlinkStackConfig` | Correct | No |

## First Concrete Behavioural Divergence

### Divergence 1: Missing `setCharacteristicNotification(true)` before CCC descriptor write

**Location:** `FitbitGoldenGateDiagnostic.enableGattlinkNotifications()` and Service Changed enablement

**Fitbit behaviour:**
- `BitGattPeer.setupNotifications()` → `subscribeCharacteristic()` → `ntx` transaction calls `gatt.setCharacteristicNotification(char, true)`
- THEN `writeDescriptorCharacteristic()` writes CCC descriptor

**BlueBit behaviour:**
- Directly writes CCC descriptor (`0x0100` for notifications, `0x0200` for indications)
- **Never calls `setCharacteristicNotification(true)`**

**Impact:** Android's BluetoothGatt requires `setCharacteristicNotification(true)` to be called before writing the Client Characteristic Configuration descriptor. Without it, the Android Bluetooth stack does not register the callback mapping for `onCharacteristicChanged`, so **notifications from the Inspire 3 are silently dropped by Android**.

### Divergence 2: Missing `TxSink.notifyListener()` calls

**Location:** `TxSink.putData()`

**Fitbit behaviour:**
- `putData()` increments queue size
- RxJava `managedDataObservable` decrements queue size on consumption
- When queue drops below 32, `notifyListener()` is called to tell native it can send more

**BlueBit behaviour:**
- `putData()` calls `txListener?.invoke(data)` synchronously
- **Never calls `notifyListener()`**

**Impact:** The native `libxp.so` stack may stop calling `putData()` after an internal queue fills, because it never receives the "ready for more" signal.

### Divergence 3: Incorrect MTU passed to native stack

**Location:** `FitbitGoldenGateDiagnostic.startGoldenGateStack()`

**Fitbit behaviour:**
- Listens for `onMtuChanged` via `MtuUpdateListener`
- Calls `stack.updateMtu(actualMtu - 3)` with the **negotiated** MTU

**BlueBit behaviour:**
- Hardcodes `stack.updateMtu(247)` regardless of actual negotiated MTU
- If actual MTU is 185, native stack thinks it can send 244-byte frames

**Impact:** Gattlink frames may exceed the actual BLE ATT_MTU, causing fragmentation or device-side rejection.

## Implementation Plan

1. Fix `setCharacteristicNotification` calls in diagnostic
2. Fix `TxSink.notifyListener()` calls
3. Fix MTU to use negotiated value
4. Build, install, and test on Inspire 3
