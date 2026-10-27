# BlueBit — Fitbit fbgatt Write-Path Investigation Report

## 1. Objective

Determine whether differences in the BLE write transport path between Fitbit's `fbgatt` library and BlueBit's direct `BluetoothGatt` implementation explain why the Inspire 3 does not acknowledge Gattlink reset packets.

## 2. Fitbit APK Write-Path Findings

### 2.1 Complete Call Chain (GoldenGate → BLE)

```
GoldenGate native stack (libxp.so)
  ↓ JNI callback
TxSink.putData(byte[])
  ↓
Bridge.onTxData(byte[])
  ↓
RemoteGattlinkNodeDataSender.write(byte[])
  ↓  writeType = 1 (WRITE_TYPE_DEFAULT)
GattCharacteristicWriter.write(serviceUuid, charUuid, data, writeType)
  ↓  RxJava Completable
WriteGattCharacteristicTransactionProvider.provide(connection, char, data, writeType)
  ↓  Creates transaction
ntz (WriteCharacteristicTransaction)
  ↓  Executes on BLE thread
BluetoothGatt.writeCharacteristic(characteristic, data, writeType)
```

### 2.2 Key Implementation Details

**RemoteGattlinkNodeDataSender.write():**
```java
public void write(byte[] data) {
    return this.gattCharacteristicWriter.write(
        this.serviceId,
        ReceiveCharacteristic.Companion.getUuid(),
        data,
        1  // WRITE_TYPE_DEFAULT
    );
}
```

**GattCharacteristicWriter.write() — RxJava pipeline:**
1. Gets `BluetoothGattService` by UUID
2. Gets `BluetoothGattCharacteristic` by UUID
3. Creates `WriteGattCharacteristicTransaction` via provider
4. Runs transaction reactively via `runTxReactive()`
5. Schedules on `ReadWriteCharacteristicProvider.INSTANCE.getScheduler()`

**ntz (WriteCharacteristicTransaction).e() — actual write:**
```java
// Android API < 33 (pre-Android 13):
bluetoothGattCharacteristic.setValue(data);
bluetoothGattCharacteristic.setWriteType(num.intValue());  // 1 = DEFAULT
bluetoothGattA.writeCharacteristic(bluetoothGattCharacteristic);

// Android API >= 33:
bluetoothGattA.writeCharacteristic(this.b, this.c, num.intValue());
```

### 2.3 Write Type

| Constant | Value | Fitbit Usage | BlueBit (old) |
|---|---|---|---|
| `WRITE_TYPE_DEFAULT` | 1 | ✅ YES | ❌ NO |
| `WRITE_TYPE_NO_RESPONSE` | 2 | ❌ NO | ✅ YES |

Fitbit explicitly passes `1` (WRITE_TYPE_DEFAULT) for all Gattlink writes.

### 2.4 Transaction Semantics

- **Queue:** One transaction at a time per connection (enforced by `runTxReactive`)
- **Callback:** Waits for `onCharacteristicWrite()` before completing
- **Retry:** 3 retry attempts with exponential backoff (2s delay)
- **Error handling:** Resets GATT state on failure, dumps service warnings

### 2.5 Bonding Flow

```
CoAP command "bond" received
  ↓
BondCommandsHandler.createBond(context, address)
  ↓
PeripheralBondCreator.create(address, timeoutSeconds)
  ↓
FitbitGattExtKt.getGattConnection(fitbitGatt, address)
  ↓
CreateBondTransactionProvider.provide(connection, timeout)
  ↓
ntf (CreateBondTransaction)
  ↓
BluetoothDevice.createBond()
  ↓
Register BroadcastReceiver for BOND_STATE_CHANGED
  ↓
Wait up to 120 seconds
  ↓
Bond complete or timeout
```

**Critical finding:** Bonding is triggered by a **CoAP command**, not during Gattlink initialization. The Fitbit app sends a CoAP request to the device, and the device responds with a "bond" command. This is a post-connection operation, not a prerequisite.

## 3. BlueBit Write-Path

```
GoldenGate native stack (libxp.so)
  ↓ JNI callback
TxSink.putData(byte[])
  ↓
Bridge.onTxData(byte[])
  ↓  (no queue, fire-and-forget)
sendToBle(byte[])
  ↓
BluetoothGatt.writeCharacteristic(receiveChar, data, WRITE_TYPE_NO_RESPONSE)
```

**Key differences:**
- No transaction queue
- No RxJava pipeline
- No retry logic
- WRITE_TYPE_NO_RESPONSE instead of DEFAULT

## 4. Side-by-Side Comparison

| Aspect | Fitbit fbgatt | BlueBit | Status |
|---|---|---|---|
| Write type | WRITE_TYPE_DEFAULT (1) | WRITE_TYPE_DEFAULT (now) | **MATCH** |
| Queuing | Single transaction, serialized | Fire-and-forget | DIFFERENCE |
| Callback handling | Waits for `onCharacteristicWrite()` | Fire-and-forget | DIFFERENCE |
| Retry logic | 3 retries, 2s backoff | None | DIFFERENCE |
| Error handling | GATT state reset, service dump | Log error | DIFFERENCE |
| Threading | Dedicated scheduler | Main thread callback | DIFFERENCE |
| Bonding | CoAP-triggered, post-connection | Tested, rejected | N/A |

## 5. WRITE_TYPE_DEFAULT Experiment Results

### 5.1 Method

Changed ONLY `WRITE_TYPE_NO_RESPONSE` → `WRITE_TYPE_DEFAULT`. All other parameters preserved:
- DSNG stack config
- TlsKeyResolver chain
- NodeKey (BluetoothAddressNodeKey)
- isNode=true
- MTU 247
- GattDatabase validation
- Service Changed + Gattlink subscriptions

### 5.2 Results

| Metric | Value |
|---|---|
| TX packets | 17 |
| Write callback status | 100% GATT_SUCCESS (status=0) |
| Write callback latency | 16–1211ms (average ~80ms) |
| RX packets | 0 |
| ABBAFF02 notifications | 0 |
| Gattlink READY | Not reached |
| DTLS state | state=1 (HANDSHAKE), no PSK resolution |
| Stack events | 12003, 32000, 24005, 44003, 36007, 56005, 72005 |

### 5.3 Conclusion

**WRITE_TYPE_DEFAULT does NOT cause the Inspire 3 to respond.** The device receives the writes (Android confirms delivery via `onCharacteristicWrite(status=0)`), but the device chooses not to send any Gattlink ACK or notifications.

## 6. Bonding Experiment Results

### 6.1 Method

Added `BluetoothDevice.createBond()` before BLE connection. All other parameters preserved.

### 6.2 Results

| Event | Timestamp | Detail |
|---|---|---|
| `createBond()` | 12:03:05 | Returned `true` |
| Bond state | 12:03:05 | `NONE → BONDING` |
| BLE connect | 12:03:06 | Success |
| MTU 247 | 12:03:07 | Success |
| Gattlink resets | 12:03:07–12:03:35 | 8 TX packets, all confirmed |
| Bond state | 12:03:35 | `BONDING → NONE` (FAILED) |
| Disconnect | 12:03:35 | `status=22` (peer terminated) |

### 6.3 Conclusion

**The Inspire 3 actively rejects bonding from BlueBit.** The device:
1. Accepts the BLE connection
2. Processes Gattlink resets for ~30 seconds (no response)
3. Rejects the bonding request
4. Initiates disconnect

The device is not passively ignoring BlueBit — it is **actively managing the connection** and choosing not to respond to Gattlink resets or accept bonding.

## 7. Root Cause Analysis

### 7.1 Eliminated Hypotheses

| Hypothesis | Evidence | Status |
|---|---|---|
| WRITE_TYPE_NO_RESPONSE | WRITE_TYPE_DEFAULT tested, no change | ❌ ELIMINATED |
| Missing write callback | Callbacks fire with status=0 | ❌ ELIMINATED |
| fbgatt serialization | Device receives all writes | ❌ ELIMINATED |
| Missing bonding | Bonding rejected by device | ❌ ELIMINATED |
| Connection issues | BLE connects, MTU negotiates, services discovered | ❌ ELIMINATED |

### 7.2 Remaining Hypotheses

**Hypothesis A: Device requires pre-existing bond from Fitbit app setup**

The Inspire 3 may have been paired with the Fitbit app using a specific pairing method (e.g., LE Secure Connections with OOB, Passkey Entry, or Numeric Comparison). The bond is stored in the phone's Bluetooth stack. BlueBit cannot access this bond because:
- The bond was created by the Fitbit app (different app signature)
- The bond uses a specific LTK (Long Term Key) that BlueBit doesn't have
- The device expects encrypted packets that BlueBit cannot generate

**Hypothesis B: Device requires encrypted Gattlink/DTLS before responding**

The Inspire 3 may be configured to only accept encrypted Gattlink packets. The DTLS handshake should provide this encryption, but:
- DTLS is in HANDSHAKE state (state=1)
- The handshake cannot proceed because the Gattlink layer hasn't reached READY
- The Gattlink layer hasn't reached READY because the device doesn't ACK the reset
- This creates a deadlock

**Hypothesis C: Missing pre-Gattlink initialization step**

There may be a missing initialization step that BlueBit hasn't reproduced:
- A specific GATT characteristic write before Gattlink reset
- A specific timing sequence
- A specific packet that the native library sends before the first `0x80` reset

**Hypothesis D: Device firmware whitelist/blacklist**

The Inspire 3 firmware may maintain a whitelist of approved central devices. BlueBit is not on this list, so the device ignores all packets from it.

## 8. Recommendations

### 8.1 Immediate Next Steps

1. **Check if Inspire 3 is bonded with Fitbit app:**
   ```bash
   adb shell dumpsys bluetooth_manager | grep -i "Inspire\|D8:9E:C1"
   ```
   If the device IS bonded with the Fitbit app, the LTK might be usable.

2. **Capture Bluetooth HCI snoop log:**
   Enable Android's built-in HCI snoop log, run the Fitbit app against the Inspire 3, then compare air packets with BlueBit's.
   ```bash
   adb shell settings put secure bluetooth_hci_log 1
   ```

3. **Check for LE Secure Connections requirement:**
   The Inspire 3 may require LE Secure Connections (Bluetooth 4.2+) with a specific pairing method. Check the device's IO capabilities and pairing requirements.

### 8.2 Medium-Term Investigation

1. **Fitbit app runtime analysis:** Use the existing Fitbit app on the phone to connect to the Inspire 3 and capture the exact packet sequence using HCI snoop or a BLE sniffer (e.g., nRF52840 Dongle).

2. **Native library version check:** Verify that BlueBit's `libxp.so` is the exact same version as the one in the Fitbit APK. Version mismatch could cause protocol incompatibility.

3. **Device firmware analysis:** The Inspire 3 firmware may have changed its Gattlink/DTLS requirements in a recent update. Check if the device firmware version matches what the Fitbit app expects.

## 9. Summary

| Experiment | Result |
|---|---|
| WRITE_TYPE_DEFAULT | ❌ No device response |
| Bonding attempt | ❌ Device rejected bond and disconnected |
| Write callback confirmation | ✅ All writes delivered successfully |
| DTLS initialization | ✅ Enters HANDSHAKE state |

**The Inspire 3 receives BlueBit's Gattlink reset packets but actively chooses not to respond.** The most likely explanation is that the device requires a pre-existing bond/encryption key that BlueBit cannot provide, or the device firmware whitelists only the official Fitbit app.

---

*Report date: 2026-09-01*
*Device: Samsung Galaxy A17, Android 16*
*Target: Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)*
*APK analyzed: Fitbit-base.apk (JADX 1.5.6)*
