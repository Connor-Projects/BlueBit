# BlueBit — GoldenGate Reset ACK Investigation Report

## Executive Summary

BlueBit's genuine Fitbit GoldenGate native stack successfully initializes and transmits Gattlink reset packets to the Inspire 3, but the device does not ACK them. After extensive APK analysis and controlled experiments, the most likely root cause is that **BlueBit uses bare Gattlink (config "G") while the Inspire 3 requires DTLS-encrypted Gattlink (config "DSNG")**.

---

## 1. What Works (Verified)

| Component | Status |
|---|---|
| `GoldenGate.init()` | Loads `libxp.so`, `initModulesJNI()=0`, `registerLoggerJNI()=0` |
| `RunLoop` | Starts, `onLoopCreated()` fires on background thread |
| `Bridge` (TxSink + RxSource) | Created, `isReady=true` |
| BLE Connection | Connects, negotiates MTU 247, discovers 7 services |
| Gattlink Service | `ABBAFF00` found with `ABBAFF01` (RX) and `ABBAFF02` (TX) |
| CCC Notifications | Successfully enabled on `ABBAFF02` |
| GattDatabase Validation | Reads `AC2F0145` → writes `0x00` to `AC2F2645` |
| Service Changed | Indications enabled on `00002A05` |
| `Stack.create()` | Returns success, `attachEventListener=0` |
| `Stack.start()` | Returns 0, triggers reset packet TX |
| `updateMtu(247)` | Returns 0 |
| TX Packets | Native sends `0x80` reset to `ABBAFF01` every ~4s |
| Stack Events | Firing (type=1735160611 with various codes) |

---

## 2. Fitbit Startup Sequence (from APK)

```
BLE CONNECT
  ↓
GATT SERVICE DISCOVERY
  ↓
SUBSCRIBE Generic Attribute Service Changed (indications) on 00002A05
  ↓
VALIDATE GATT DATABASE:
  READ AC2F0145 (ephemeral pointer)
  ↓
  PARSE UUID from bytes (big-endian MSB+LSB)
  ↓
  WRITE 0x00 to ephemeral char (e.g., AC2F2645) with WRITE_TYPE_DEFAULT
  ↓
SUBSCRIBE Gattlink ABBAFF02 (notifications)
  ↓
Bridge.start() → sets isBridgeReady=true
  ↓
Stack.start() → native "starting gattlink session" → SendResetPacket
  ↓
waitForConnectedState() → expect PeerConnectionStatus.CONNECTED
```

---

## 3. BlueBit Startup Sequence (Current)

```
BLE CONNECT
  ↓
GATT SERVICE DISCOVERY
  ↓
SUBSCRIBE Generic Attribute Service Changed (indications) on 00002A05  [ADDED]
  ↓
VALIDATE GATT DATABASE:
  READ AC2F0145 (ephemeral pointer)
  ↓
  PARSE UUID from bytes (big-endian MSB+LSB)  [FIXED]
  ↓
  WRITE 0x00 to ephemeral char with WRITE_TYPE_DEFAULT  [FIXED]
  ↓
SUBSCRIBE Gattlink ABBAFF02 (notifications)
  ↓
Bridge.start() → sets isBridgeReady=true
  ↓
Stack.start() → native "starting gattlink session" → SendResetPacket
  ↓
updateMtu(247)  [ADDED]
  ↓
[TIMEOUT after 60s, 15 TX, 0 RX]
```

---

## 4. Side-by-Side Differences

| Aspect | Fitbit APK | BlueBit | Status |
|---|---|---|---|
| Stack Config | `"DSNG"` (DTLS+Socket+Netif+Gattlink) | `"G"` (bare Gattlink) | **CRITICAL** |
| `isNode` parameter | `true` (Central role) | `true` (fixed) | Fixed |
| GattDatabase Validation | Yes, with WRITE_TYPE_DEFAULT | Yes (added) | Fixed |
| UUID Parsing | Big-endian MSB+LSB | Was little-endian, now fixed | Fixed |
| Service Changed | Indications enabled first | Added | Fixed |
| TlsKeyResolver | `TlsKeyResolverRegistry` with HELLO+BOOTSTRAP keys | `null` | **MISSING** |
| updateMtu() | Called after Stack.start() | Added | Fixed |
| TX Write Type | WRITE_TYPE_DEFAULT (1) | WRITE_TYPE_NO_RESPONSE | **NEEDS CHECK** |

---

## 5. Root Cause Hypothesis

### Primary: Missing DTLS Layer

Fitbit's `CoapNodeProvider` and `CoapStackServiceProvider` both default to `DtlsSocketNetifGattlink` with config descriptor `"DSNG"` for tracker (Peripheral) connections. The native library contains extensive DTLS code:

- `GG_DtlsProtocol_Create`
- `GG_StackDtlsElement_Create`
- `mbedtls_ssl_handshake_step`
- `TLS-PSK-WITH-AES-256-CCM`
- `TLS-PSK-WITH-AES-128-CCM`
- `resolving PSK identity, size=%u`

The Inspire 3 likely rejects unencrypted Gattlink reset packets and expects a DTLS handshake first. Without DTLS:

1. BlueBit sends bare `0x80` reset
2. Inspire 3 drops it (expects DTLS ClientHello or encrypted packet)
3. No ACK → retransmission loop

### Supporting Evidence

- Native string: `"Client %p destroyed in NotifySessionReady (OnCanPut), exiting early"` — session readiness is checked
- Native string: `"Not in READY state (%d), skipping packet transmission"` — the stack may not reach READY without DTLS
- Stack events show codes 12010, 12013, 24022, 36027, 48036 — these are likely retry/timeout counters, not state transitions

---

## 6. PSK Key Material (from APK)

Fitbit's `TlsKeyResolverRegistry` provides two hardcoded keys:

### HELLO Key
- **ID**: `"hello"` (ASCII)
- **Key**: `[0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F]`

### BOOTSTRAP Key
- **ID**: `"BOOTSTRAP"` (ASCII)
- **Key**: `[0x81, 0x06, 0x54, 0xE3, 0x36, 0xAD, 0xCA, 0xB0, 0xA0, 0x3C, 0x60, 0xF7, 0x4A, 0xA0, 0xB6, 0xFB]`

The DTLS handshake uses these PSKs for the `TLS-PSK-WITH-AES-256-CCM` cipher suite.

---

## 7. Exact GoldenGate TX Packet

```
Timestamp: 23:55:42.043
Characteristic: ABBAFF01 (ReceiveCharacteristic)
Length: 1 byte
Hex: 80
Decoded: Gattlink RESET packet (PSN=0, control packet)
Attempt: 1 (retransmits every ~4 seconds)
```

The packet itself is correct — the native library produces a standard Gattlink reset. The issue is that the device expects this packet to be encrypted within a DTLS record.

---

## 8. Changes Made During Investigation

1. **Fixed `GoldenGate.Version` constructor** — added 8 fields to match native expectation
2. **Fixed JNI method names** — `startNative`→`start`, `updateMtuNative`→`updateMtu`
3. **Fixed `TxSink.putData` return type** — changed to `Int` with `create(...,"([B)I")`
4. **Fixed `isNode` parameter** — changed from `false` to `true` for Central role
5. **Added `updateMtu(247)` call** — after `Stack.start()`
6. **Added GattDatabase validation** — read `AC2F0145` → write `0x00` to ephemeral char
7. **Fixed UUID parsing** — big-endian MSB+LSB (no endianness swap)
8. **Added Service Changed indication subscription** — before Gattlink subscription

---

## 9. What Remains to be Tested

### Critical: DTLS Configuration

To test the DTLS hypothesis, BlueBit needs:

1. **Stack config**: Change from `"G"` to `"DSNG"`
2. **TlsKeyResolver**: Implement and pass to `Stack.create()`
3. **PSK keys**: Use HELLO and BOOTSTRAP keys from APK

```kotlin
// Proposed TlsKeyResolver
class FitbitTlsKeyResolver : TlsKeyResolver {
    override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? {
        return when {
            keyId.contentEquals("hello".toByteArray()) -> 
                byteArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15)
            keyId.contentEquals("BOOTSTRAP".toByteArray()) -> 
                byteArrayOf(0x81,0x06,0x54,0xE3,0x36,0xAD,0xCA,0xB0,0xA0,0x3C,0x60,0xF7,0x4A,0xA0,0xB6,0xFB)
            else -> null
        }
    }
}
```

### Secondary: TX Write Type

Fitbit's `RemoteGattlinkNodeDataSender` passes `writeType=1` (WRITE_TYPE_DEFAULT) for Gattlink writes. BlueBit currently uses `WRITE_TYPE_NO_RESPONSE`. While unlikely to be the root cause, this should be aligned.

---

## 10. Next Steps

1. **Implement DTLS config** (`"DSNG"` + TlsKeyResolver)
2. **Test with Inspire 3**
3. If still no ACK:
   - Check if device requires a specific PSK (not just HELLO/BOOTSTRAP)
   - Investigate `NodeDataSender` write type (DEFAULT vs NO_RESPONSE)
   - Check if `PeerRole.Peripheral` vs `PeerRole.Central` affects DTLS behavior
   - Verify `TlsKeyResolverRegistry` registration order

---

## 11. Conclusion

The GoldenGate native stack is correctly initialized and functional. The reset packet is properly formed. The missing ACK from the Inspire 3 is most likely due to the absence of the DTLS encryption layer that the device expects. Fitbit's APK defaults to `DtlsSocketNetifGattlink` for all tracker connections, and the native library contains the full DTLS/PSK handshake implementation. Implementing DTLS support is the next logical step.

---

*Report compiled: 2026-09-01*
*APK analyzed: Fitbit-base.apk (decoded with JADX 1.5.6)*
*Device: Samsung Galaxy A17, Android 16*
*Target: Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)*
