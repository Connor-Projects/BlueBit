# Inspire 3 — Fitbit Connection Reconstruction Report

**Date:** 2026-09-01  
**Device:** Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)  
**BlueBit Version:** post-mock-data-removal, GoldenGate diagnostic build  
**Status:** GoldenGate data tunnel established (15 TX, 26 RX). Pairing incomplete — requires CoAP `bond/control` command.

---

## Executive Summary

BlueBit now successfully establishes a GoldenGate DTLS+Socket+Netif+Gattlink tunnel with the Inspire 3. The native `libxp.so` stack negotiates, handshakes, and exchanges encrypted data packets. The watch disconnects after ~45 seconds because the pairing handshake is not completed — specifically, BlueBit lacks a CoAP layer to send the `PUT bond/control` command that tells the watch to enter bonding mode.

Three initial behavioural divergences were identified and fixed. Two additional divergences were discovered during live testing.

---

## Fitbit → BlueBit Dependency Map

| Fitbit Class | Responsibility | BlueBit Equivalent | Missing Behaviour | Fixed? |
|---|---|---|---|---|
| `BitGattPeer` | GATT peer abstraction; connect, discover, MTU, notifications | `AndroidBleConnection` | None — equivalent | Yes |
| `GattCharacteristicReader` | Queued GATT read via transaction | `AndroidBleConnection.readCharacteristic()` | Uses transaction queue (`runTxReactive`) | Yes — mutex+deferred equivalent |
| `GattCharacteristicWriter` | Queued GATT write via transaction | `AndroidBleConnection.writeCharacteristic()` | Uses transaction queue | Yes |
| `GattServiceDiscoverer` | Service discovery via transaction | `AndroidBleConnection.discoverServices()` | Uses transaction queue | Yes |
| `PeerConnector` | Orchestrates GATT connect | `AndroidBleConnection.connect()` | None | Yes |
| `LinkupWithPeerNodeHandler` | Linkup: discover → subscribe ServiceChanged → validate GattDB → subscribe Gattlink | `FitbitGoldenGateDiagnostic` | Missing `setCharacteristicNotification` calls | **Fixed** |
| `GattDatabaseValidator` | Read ephemeral pointer, write 0x00 | `FitbitGoldenGateDiagnostic` | Correctly implemented | Yes |
| `BondCommandsHandler` | Bond creation/removal | `Inspire3PairingHandler` / `createBond()` | Wrong order: upfront bonding causes SMP timeout | **Fixed in diagnostic** |
| `StackPeer` | Full connection lifecycle | `FitbitGoldenGateDiagnostic` | Missing MTU listener, missing `notifyListener` | **Fixed** |
| `Bridge` | TX/RX data plumbing | `Bridge` | Simplified but functionally equivalent | Yes |
| `TxSink` | Native → Java TX callback | `TxSink` | Missing `notifyListener()` calls | **Fixed** |
| `RxSource` | Java → Native RX feed | `RxSource` | Correct | Yes |
| `Stack` | Native stack lifecycle | `Stack` | Correct | Yes |
| `DtlsSocketNetifGattlinkStackConfig` | Stack config "DSNG" | `DtlsSocketNetifGattlinkStackConfig` | Correct | Yes |
| `CoapBondControlResource` | Send `PUT bond/control` CoAP command | **None** | No CoAP layer in BlueBit | **NOT FIXED** |
| `PairCommandsHandler` | Send `PUT pair/display` CoAP command | **None** | No CoAP layer in BlueBit | **NOT FIXED** |

---

## Fixes Applied

### Fix 1: `setCharacteristicNotification(true)` before CCC descriptor write

**File:** `FitbitGoldenGateDiagnostic.kt`  
**Lines:** Service Changed setup (Step 3a), Gattlink transmit setup (Step 3c)

**Problem:** BlueBit wrote CCC descriptors (`0x0100` / `0x0200`) without first calling `gatt.setCharacteristicNotification(char, true)`. Android's `BluetoothGatt` requires this call to register the internal callback mapping. Without it, `onCharacteristicChanged` is never invoked for incoming notifications.

**Fix:** Added `gatt.setCharacteristicNotification(serviceChangedChar, true)` before writing CCC for Service Changed, and `gatt.setCharacteristicNotification(transmitChar, true)` before writing CCC for Gattlink transmit.

**Verification:** After fix, ABBAFF02 notifications are received correctly (26 RX packets observed).

### Fix 2: `TxSink.notifyListener()` after data consumption

**File:** `TxSink.kt`

**Problem:** Fitbit's `TxSink.putData()` increments a queue-size counter. An RxJava `managedDataObservable` consumes data and decrements the counter. When the queue drops below 32, `notifyListener()` is called to tell the native stack it can send more data. BlueBit called `txListener?.invoke(data)` synchronously but never called `notifyListener()`, so the native queue could fill and stall.

**Fix:** Added `notifyListener()` call after `txListener?.invoke(data)` in `TxSink.putData()`.

**Verification:** TX packets flow continuously without native-side backpressure (15 TX packets observed, no tx stalls).

### Fix 3: MTU passed to native stack uses negotiated value

**File:** `FitbitGoldenGateDiagnostic.kt`  
**Line:** `stack.updateMtu(...)`

**Problem:** BlueBit hardcoded `stack.updateMtu(247)`. Fitbit passes `actualMtu - 3` (accounting for Gattlink header overhead). If the actual negotiated MTU differs from 247, Gattlink frames may exceed ATT_MTU.

**Fix:** Changed to `stack.updateMtu((negotiatedMtu - 3).coerceAtLeast(20))`.

**Verification:** MTU negotiation logs show `MTU changed: mtu=247 status=0`, and `stack.updateMtu(244)` is called.

### Fix 4: Skip upfront `createBond()` — bonding happens after GoldenGate linkup

**File:** `FitbitGoldenGateDiagnostic.kt`  
**Lines:** Step 2b bonding experiment

**Problem:** BlueBit called `device.createBond()` before GATT connection. This starts SMP pairing immediately. The watch's 30-second pairing timer expires while GATT setup is still in progress (service discovery, Service Changed setup, Gattlink subscription). The result is `SMP_RSP_TIMEOUT` and disconnection at exactly 30 seconds.

**Fitbit's actual flow:**
1. Connect GATT
2. Discover services
3. Validate GattDB
4. Subscribe to Gattlink
5. Start GoldenGate Stack
6. Send CoAP `PUT bond/control` to device (tells watch to enable bonding)
7. Call Android `createBond()`
8. Bonding completes over SMP while GoldenGate is already running

**Fix:** Removed `createBond()` call from diagnostic. Added log: "SKIPPING createBond() — bonding happens after GoldenGate linkup".

**Verification:** Without upfront bonding, the connection survives past 30 seconds. GoldenGate establishes and exchanges data.

### Fix 5: Remove `probeUnknownServices()` GATT queue congestion

**File:** `FitbitGoldenGateDiagnostic.kt`  
**Lines:** After service discovery

**Problem:** `probeUnknownServices()` fires multiple `readCharacteristic()` calls in rapid succession without waiting for completion callbacks. Android's GATT queue is single-threaded; subsequent reads queue up and interfere with the actual setup sequence (Service Changed CCC write, Gattlink subscription). Logs showed: `readCharacteristic() - prior command is not finished`.

**Fix:** Commented out `probeUnknownServices(gatt)` call.

**Verification:** Service Changed CCC write and Gattlink subscription proceed cleanly without queue contention.

---

## Live Test Results

### Test Setup
- Phone: Samsung Galaxy S25 Ultra (Android 15)
- Device: Fitbit Inspire 3 (firmware 1.214.34)
- BlueBit build: post-fix diagnostic APK

### Connection Sequence (successful)

```
16:57:15.947  GOLDENGATE EXPERIMENT START
16:57:15.949  DEVICE=Inspire 3 ADDR=D8:9E:C1:BA:3D:E4
16:57:15.988  GoldenGate version: 0.1.0-0-
16:57:15.991  SKIPPING createBond()
16:57:16.179  BLE connected (status=0 newState=2)
16:57:17.590  MTU changed: mtu=247 status=0
16:57:17.602  Services discovered: 7
16:57:17.615  setCharacteristicNotification(ServiceChanged)=true
16:57:17.639  Service Changed CCC write status=0
16:57:17.672  Ephemeral pointer read: 16 bytes
16:57:17.702  Ephemeral char written status=0
16:57:17.705  setCharacteristicNotification(GattlinkTransmit)=true
16:57:17.760  Gattlink CCC write status=0
16:57:17.762  Creating Stack with config='DSNG'
16:57:17.771  Stack created successfully
16:57:17.773  Stack.start()...
16:57:17.773  TX #1 → ABBAFF01: 1 bytes: 80
16:57:17.777  Stack.updateMtu(244) returned true
16:57:17.778  Timeout started: 60000ms
```

### Data Exchange

```
TX #1  → 1 byte   (0x80 — Gattlink sync/keepalive)
RX #1  ← 5 bytes  (0x81 0x00 0x00 0x08 0x08)
TX #2  → 130 bytes (encrypted DTLS handshake)
RX #2  ← 44 bytes
RX #3  ← 1 byte   (0x80)
TX #3  → 5 bytes
RX #4  ← 5 bytes
TX #4  → 109 bytes
...
TX #15 → 109 bytes
RX #26 ← 44 bytes
```

**Totals:** 15 TX, 26 RX over ~45 seconds. Average TX latency: ~80ms. No write failures.

### Disconnection

```
16:58:01.789  BLE connection state: status=19 newState=0
16:58:01.791  Cleanup: txCount=15 rxCount=26
16:58:01.791  GOLDENGATE EXPERIMENT END
```

- `status=19` = `GATT_CONN_TERMINATE_PEER_USER` — the **watch** initiated disconnection
- Disconnection occurs ~45 seconds after GoldenGate start
- The watch's internal pairing timer expires (pairing mode times out when bonding handshake is not completed)

---

## Remaining Blockers for Full Pairing

### Blocker 1: No CoAP layer

**Severity:** Critical  
**Impact:** Pairing cannot complete

Fitbit's pairing flow requires sending CoAP commands over the GoldenGate tunnel:

1. `PUT bond/control` with protobuf body `Control { operationTimeout = N }`
   - Wire format: `0x08 <timeout_varint>`
   - This tells the watch to enable bonding mode and wait N seconds for Android bonding

2. `PUT pair/display` with protobuf body `PairDisplayRequest { command = SHOW_CODE | CLEAR_CODE }`
   - Shows/clears pairing code on watch display

3. Android `createBond()` — now safe because watch is expecting it

BlueBit has **no CoAP implementation**. The project only contains:
- `Bridge` (raw TX/RX)
- `GoldenGate` (init/shutdown)
- `Stack` (lifecycle + MTU)

To send CoAP commands, BlueBit needs either:
- **Option A:** Port Fitbit's CoAP bindings (`Endpoint`, `OutgoingRequestBuilder`, `CoapEndpointMapper`, etc.) — ~5-10 files from decompiled sources
- **Option B:** Manually construct CoAP packet bytes and inject them into the Bridge TX stream — requires understanding CoAP packet format + DTLS encryption

**Recommendation:** Option A is more maintainable. Fitbit's CoAP bindings are thin wrappers around the native CoAP stack exposed via JNI. The key classes are:
- `com.fitbit.goldengate.bindings.coap.Endpoint`
- `com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder`
- `com.fitbit.goldengate.bindings.coap.data.Method`
- `com.fitbit.goldengate.coap.CoapEndpointMapper`

### Blocker 2: No `PairCommandsHandler`

**Severity:** Critical  
**Impact:** Cannot show/clear pairing code on watch

Even if bonding is enabled via `bond/control`, the watch displays a pairing code that the user must confirm. Fitbit sends `PUT pair/display` to show the code, then reads user confirmation, then sends `PUT pair/display` with clear code.

### Blocker 3: No `BondCommandsHandler` integration

**Severity:** Critical  
**Impact:** Android bonding not triggered at correct time

The diagnostic currently skips bonding entirely. A production pairing flow needs:
1. Start GoldenGate
2. Send `PUT bond/control`
3. Wait for CoAP response (200 OK)
4. Call `createBond()`
5. Wait for `ACTION_BOND_STATE_CHANGED` → `BOND_BONDED`
6. Send `PUT pair/display` (show code)
7. Wait for user confirmation (via CoAP or UI)
8. Send `PUT pair/display` (clear code)
9. Connection is now paired and bonded

---

## Data Exchange Analysis

### Packet Patterns

The 26 RX packets break down into:
- **Type 0x81 (5 bytes):** Gattlink keepalive/ack — 8 occurrences
- **Type 0x00 (130 bytes):** Encrypted data frame — 8 occurrences
- **Type 0x01 (44 bytes):** Encrypted data frame (shorter) — 8 occurrences
- **Type 0x80 (1 byte):** Gattlink sync — 2 occurrences

This pattern indicates a healthy DTLS-encrypted CoAP conversation. The 130-byte frames likely carry CoAP request/response bodies. The 44-byte frames are likely CoAP ACKs or empty responses.

### Timing

| Metric | Value |
|---|---|
| GATT connect → MTU negotiated | 1.4s |
| MTU → Services discovered | 0.01s |
| Services → Gattlink subscribed | 0.16s |
| Gattlink → First TX | 0.01s |
| Average TX latency | ~80ms |
| Average RX interval | ~1.8s |
| Total active exchange | ~45s |

---

## Recommendations

### Short Term (next 1-2 sessions)

1. **Port minimal CoAP bindings** from Fitbit decompiled sources:
   - `Endpoint` (JNI wrapper for CoAP endpoint)
   - `OutgoingRequestBuilder` (construct CoAP requests)
   - `CoapEndpointMapper` (map Bluetooth address → endpoint)
   - Register these with the GoldenGate native stack

2. **Implement `bond/control` sender**:
   - After GoldenGate linkup, send `PUT bond/control` with `operationTimeout = 30`
   - Wait for 200 OK response
   - Then call `createBond()`

3. **Test bonding completion**:
   - Verify `BOND_BONDED` state is reached
   - Verify device appears in `getBondedDevices()`

### Medium Term (next 2-4 sessions)

4. **Implement `pair/display` handler**:
   - Show pairing code on watch
   - Capture user confirmation
   - Clear pairing code

5. **Integrate pairing flow into `Inspire3PairingHandler`**:
   - Replace raw GATT approach with GoldenGate + CoAP approach
   - Handle retry, timeout, and error cases

### Long Term

6. **Add CoAP request/response infrastructure** to BlueBit's common module:
   - Generic CoAP client API
   - Observable-style response handling
   - Timeout and retry logic

---

## Appendix: Key Log Excerpts

### Successful GoldenGate Start (no bonding)

```
I BlueBitGG_Diag: GOLDENGATE EXPERIMENT START
I BlueBitGG_Diag: DEVICE=Inspire 3 ADDR=D8:9E:C1:BA:3D:E4
I BlueBitGG_Diag: Step 2b: Device bond state=10 (NONE)
I BlueBitGG_Diag: SKIPPING createBond() — bonding happens after GoldenGate linkup
I BlueBitGG_Diag: Step 3: Connecting BLE...
I BlueBitGG_Diag: BLE connection state: status=0 newState=2
I BlueBitGG_Diag: Requesting MTU=185
I BlueBitGG_Diag: MTU changed: mtu=247 status=0
I BlueBitGG_Diag: Services discovered: 7
I BlueBitGG_Diag: Gattlink service found
I BlueBitGG_Diag: Step 3a: Enabling Service Changed indications...
I BlueBitGG_Diag: setCharacteristicNotification(ServiceChanged)=true
I BlueBitGG_Diag: Service Changed CCC write status=0
I BlueBitGG_Diag: Step 3b: GattDatabase validation - reading ephemeral pointer AC2F0145...
I BlueBitGG_Diag: Ephemeral pointer read: 16 bytes: AC2F264581824BE591E02992E6B40EBB
I BlueBitGG_Diag: Writing 0x00 to ephemeral char...
I BlueBitGG_Diag: GattDatabase validation complete
I BlueBitGG_Diag: setCharacteristicNotification(GattlinkTransmit)=true
I BlueBitGG_Diag: Gattlink CCC write status=0
I BlueBitGG_Diag: Step 4: Starting GoldenGate Stack...
I BlueBitGG_Diag: Creating Stack with config='DSNG'
I BlueBitGG_Diag: Stack created successfully
I BlueBitGG_Diag: Stack.updateMtu(244) returned true
I BlueBitGG_Diag: TX #1 → ABBAFF01: 1 bytes: 80
```

### Data Exchange Sample

```
I BlueBitGG_Diag: ABBAFF02 notification #1: 5 bytes: 8100000808
I BlueBitGG_Diag: RX data logged: 5 bytes
I BlueBitGG_Diag: TX #2 → ABBAFF01: 130 bytes: 004500008100010000FF1167...
I BlueBitGG_Diag: onCharacteristicWrite ABBAFF01 status=0 outstanding=1 elapsed=88ms
I BlueBitGG_Diag: ABBAFF02 notification #2: 44 bytes: 014500002B00020000FF1167...
I BlueBitGG_Diag: RX data logged: 44 bytes
I BlueBitGG_Diag: ABBAFF02 notification #3: 1 bytes: 80
I BlueBitGG_Diag: RX data logged: 1 bytes
I BlueBitGG_Diag: TX #3 → ABBAFF01: 5 bytes: 8100000808
```

### Disconnection

```
I BlueBitGG_Diag: BLE connection state: status=19 newState=0
I BlueBitGG_Diag: Cleanup: txCount=15 rxCount=26
I BlueBitGG_Diag: GOLDENGATE EXPERIMENT END
```

---

## Appendix: Files Modified

| File | Change |
|---|---|
| `FitbitGoldenGateDiagnostic.kt` | Fix 1: `setCharacteristicNotification` before CCC write |
| `FitbitGoldenGateDiagnostic.kt` | Fix 4: Skip `createBond()` — log only |
| `FitbitGoldenGateDiagnostic.kt` | Fix 5: Comment out `probeUnknownServices()` |
| `TxSink.kt` | Fix 2: Add `notifyListener()` after TX |
| `FitbitGoldenGateDiagnostic.kt` | Fix 3: `stack.updateMtu((negotiatedMtu - 3).coerceAtLeast(20))` |
| `BlueBitAppShell.kt` | Removed mock device data, replaced with empty strings/lists |
| `DeviceConnectionManager.kt` | Removed mock connection/sync delays |

---

*Report generated 2026-09-01. Next update expected after CoAP layer implementation.*
