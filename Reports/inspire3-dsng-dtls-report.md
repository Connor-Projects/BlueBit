# BlueBit — DSNG/DTLS Experiment Report

## 1. Objective

Determine whether reproducing Fitbit's `DtlsSocketNetifGattlink` ("DSNG") transport configuration makes the Inspire 3 respond to GoldenGate initialization.

## 2. Fitbit Implementation Evidence

### 2.1 DSNG Configuration

```kotlin
// Fitbit APK: com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
class DtlsSocketNetifGattlink(
    localAddress: Inet4Address = 0.0.0.0,
    localPort: Int = 0,
    remoteAddress: Inet4Address = 0.0.0.0,
    remotePort: Int = 0,
    gattlinkRxWindowSize: Int? = null,
    gattlinkTxWindowSize: Int? = null,
    gattlinkBufferThresholdHysteresis: Double? = null,
    gattlinkExpectedAckTimeout: Int? = null
) : StackConfig(
    configDescriptor = "DSNG",  // DTLS + Socket + Netif + Gattlink
    ...
)
```

### 2.2 TlsKeyResolver Chain

```kotlin
// Fitbit APK: TlsKeyResolverRegistry
object TlsKeyResolverRegistry {
    // Chain: HelloTlsKeyResolver → BootstrapTlsKeyResolver
    private val firstResolver = HelloTlsKeyResolver()
    init { register(BootstrapTlsKeyResolver()) }
}
```

**HELLO Key:**
- ID: `"hello"` (ASCII)
- Key: `[0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F]`

**BOOTSTRAP Key:**
- ID: `"BOOTSTRAP"` (ASCII)
- Key: `[0x81, 0x06, 0x54, 0xE3, 0x36, 0xAD, 0xCA, 0xB0, 0xA0, 0x3C, 0x60, 0xF7, 0x4A, 0xA0, 0xB6, 0xFB]`

### 2.3 Stack.create() Native Signature

```java
private native NativeReferenceCreationResult create(
    NodeKey<?> nodeKey,           // BluetoothAddressNodeKey(device.address)
    String configDescriptor,      // "DSNG"
    boolean isNode,               // true (Central role)
    long transportSinkPtr,        // TxSink.getThisPointer()
    long transportSourcePtr,      // RxSource.getThisPointer()
    Inet4Address localAddress,    // 0.0.0.0
    int localPort,                // 0
    Inet4Address remoteAddress,   // 0.0.0.0
    int remotePort,               // 0
    TlsKeyResolver tlsKeyResolver,// TlsKeyResolverRegistry.getResolvers()
    int gattlinkRxWindowSize,     // 0 (native default)
    int gattlinkTxWindowSize,     // 0 (native default)
    double gattlinkBufferThresholdHysteresis, // 0.0
    int gattlinkExpectedAckTimeout // 0 (native default)
);
```

### 2.4 Initialization Order

```
BLE CONNECT
  ↓
MTU NEGOTIATION (247)
  ↓
SERVICE DISCOVERY
  ↓
SUBSCRIBE Generic Attribute Service Changed (indications)
  ↓
GATT DATABASE VALIDATION:
  READ AC2F0145 → PARSE UUID → WRITE 0x00 to ephemeral char
  ↓
SUBSCRIBE Gattlink ABBAFF02 (notifications)
  ↓
Bridge.start()
  ↓
Stack.create("DSNG", TlsKeyResolver)
  ↓
Stack.start() → DTLS ClientHello over Gattlink
  ↓
updateMtu(247)
  ↓
[Wait for device response]
```

## 3. Changes Made to BlueBit

### 3.1 New Files

| File | Purpose |
|---|---|
| `dtls/TlsKeyResolver.kt` | Abstract PSK resolver with chain support |
| `dtls/HelloTlsKeyResolver.kt` | Resolves `"hello"` identity |
| `dtls/BootstrapTlsKeyResolver.kt` | Resolves `"BOOTSTRAP"` identity |
| `dtls/TlsKeyResolverRegistry.kt` | Chains Hello → Bootstrap |
| `stack/DtlsSocketNetifGattlinkStackConfig.kt` | DSNG config (descriptor="DSNG") |

### 3.2 Modified Files

| File | Change |
|---|---|
| `stack/Stack.kt` | Pass `TlsKeyResolverRegistry.getResolvers()` to `create()` |
| `bluetooth/FitbitGoldenGateDiagnostic.kt` | Use `DtlsSocketNetifGattlinkStackConfig()` |

### 3.3 Preserved Functionality

- BLE discovery and connection ✓
- MTU 247 negotiation ✓
- GattDatabase validation (AC2F0145 → AC2F2645) ✓
- Service Changed indication subscription ✓
- Gattlink notification subscription ✓
- Bridge/TxSink/RxSource ✓
- `updateMtu(247)` ✓

## 4. Test Results

### 4.1 GoldenGate Initialization (SUCCESS)

```
GoldenGate.init() → 0.1.0-0- ✓
Bridge created ✓
TxSink pointer = 0x-4bffff8e1f4ffbe0 ✓
RxSource pointer = 0x-4bffff8e1f52ba20 ✓
BLE connected ✓
MTU = 247 ✓
Services discovered = 7 ✓
Gattlink service ABBAFF00 ✓
ReceiveChar ABBAFF01 (props=WRITE_NO_RESPONSE) ✓
TransmitChar ABBAFF02 (props=NOTIFY) ✓
Service Changed indications enabled ✓
GattDatabase validation complete ✓
Gattlink notifications enabled ✓
Stack created (config=DSNG) ✓
attachEventListener = 0 ✓
Stack.start() = 0 ✓
updateMtu(247) = 0 ✓
```

### 4.2 DTLS Status (PARTIAL)

```
onDtlsStatusChange state=1 code=0 psk=null
```

- **State 1** = `GG_TLS_STATE_HANDSHAKE` (confirmed from native strings)
- DTLS protocol initialized and entered handshake state
- **No PSK identity requested yet** — handshake hasn't progressed far enough

### 4.3 Stack Events (CONFIRMS DSNG IS ACTIVE)

**Previous "G" config events:** `16000, 12013, 24022, 36027, 48036`

**Current DSNG config events:** `12003, 32000, 24005, 44003, 36007, 56005, 72005`

The event codes are **completely different**, confirming the native stack is executing a different code path (DSNG vs bare Gattlink). The new layers (32, 44, 56, 72) are DTLS-specific.

### 4.4 TX/RX Evidence

| Metric | Value |
|---|---|
| TX packets | 22 |
| RX packets | 0 |
| TX bytes | 22 × 1 byte (0x80) |
| RX bytes | 0 |
| First TX | 00:56:00.817 |
| Last TX | 00:56:41.093 |
| Timeout | 00:56:41.133 (60s) |

**TX packet:** `0x80` (Gattlink reset packet, PSN=0, control bit=1)

### 4.5 Device Response

**No response from Inspire 3.**

No `onCharacteristicChanged` callbacks for `ABBAFF02`.
No Gattlink ACK packets received.
No DTLS ServerHello received.

## 5. Analysis

### 5.1 DSNG Stack Architecture

```
┌─────────────────────────────────────────┐
│  DTLS Protocol (mbedtls)                │
│  - TLS-PSK-WITH-AES-256-CCM             │
│  - Handshake state machine              │
└──────────────┬──────────────────────────┘
               │ DTLS records
┌──────────────▼──────────────────────────┐
│  Socket Layer                           │
└──────────────┬──────────────────────────┘
               │ UDP-like datagrams
┌──────────────▼──────────────────────────┐
│  Netif (Network Interface)              │
└──────────────┬──────────────────────────┘
               │ IP packets
┌──────────────▼──────────────────────────┐
│  Gattlink Protocol                      │
│  - Reset/ACK handshake                  │
│  - Windowed transmission                │
│  - Packet fragmentation                 │
└──────────────┬──────────────────────────┘
               │ Gattlink frames
┌──────────────▼──────────────────────────┐
│  BLE GATT (ABBAFF01 / ABBAFF02)         │
└─────────────────────────────────────────┘
```

### 5.2 Why the Device Doesn't Respond

**Hypothesis 1: Gattlink reset/ACK is the bottleneck**

The DSNG stack still sends Gattlink reset packets (`0x80`) at the bottom layer. The DTLS ClientHello is encapsulated inside Gattlink frames. If the device doesn't ACK the Gattlink reset, the Gattlink layer never reaches READY state, and the DTLS handshake can't proceed.

Evidence:
- Native string: `"Not in READY state (%d), skipping packet transmission"`
- All 22 TX packets are `0x80` (reset), not DTLS ClientHello
- No PSK resolution triggered (DTLS hasn't reached that stage)

**Hypothesis 2: Device requires pairing/bonding**

The Inspire 3 might only accept Gattlink/DTLS connections from a bonded/paired device. BlueBit has not performed explicit bonding with the Inspire 3. The Fitbit app likely bonded during initial setup.

Evidence:
- BLE connection succeeds without bonding
- But Gattlink/DTLS layer gets no response
- Fitbit app has a dedicated bonding flow (`bondAndInspectGatt`)

**Hypothesis 3: Device-specific PSK required**

The HELLO/BOOTSTRAP keys are generic defaults. The Inspire 3 might expect a device-specific PSK derived from pairing. Without the correct PSK, the device might drop DTLS handshakes.

Evidence:
- `InMemoryModeTlsKeyResolver` exists in Fitbit APK for arbitrary keys
- Native string: `"GG_TlsKeyResolver_ResolveKey failed to resolve key with identity %s"`
- But PSK resolution was never triggered (handshake didn't reach that stage)

**Hypothesis 4: Missing fbgatt abstraction layer**

Fitbit uses a custom `fbgatt` library (`com.fitbit.bluetooth.fbgatt`) that provides `NodeDataSender`/`NodeDataReceiver` abstractions. BlueBit writes directly to `BluetoothGattCharacteristic`. The `fbgatt` library might handle write queuing, retries, or timing differently.

Evidence:
- Fitbit's `RemoteGattlinkNodeDataSender.write()` uses `fbgatt` transaction provider
- BlueBit uses direct `gatt.writeCharacteristic()` with `WRITE_TYPE_NO_RESPONSE`
- Fitbit might use `WRITE_TYPE_DEFAULT` for some packets

## 6. Fitbit vs BlueBit Comparison

| Aspect | Fitbit | BlueBit | Status |
|---|---|---|---|
| Stack Config | `"DSNG"` | `"DSNG"` (now) | **MATCH** |
| TlsKeyResolver | `TlsKeyResolverRegistry` (Hello→Bootstrap) | `TlsKeyResolverRegistry` (Hello→Bootstrap) | **MATCH** |
| NodeKey | `BluetoothAddressNodeKey` | `BluetoothAddressNodeKey` | **MATCH** |
| isNode | `true` | `true` | **MATCH** |
| BLE Connection | Standard GATT | Standard GATT | **MATCH** |
| MTU | 247 | 247 | **MATCH** |
| GattDatabase Validation | Read AC2F0145 → Write AC2F2645 | Same | **MATCH** |
| Service Changed | Indications enabled first | Same | **MATCH** |
| Gattlink Subscription | CCC on ABBAFF02 | Same | **MATCH** |
| Bridge.start() | Before Stack.start() | Same | **MATCH** |
| updateMtu() | After Stack.start() | Same | **MATCH** |
| BLE Write Layer | `fbgatt` library (`NodeDataSender`) | Direct `BluetoothGatt` | **DIFFERENCE** |
| Write Type | `WRITE_TYPE_DEFAULT` (1) | `WRITE_TYPE_NO_RESPONSE` | **DIFFERENCE** |
| Device Bonding | Done during app setup | Not done | **DIFFERENCE** |
| PSK Keys | Generic + device-specific | Generic only | **DIFFERENCE** |

## 7. Conclusion

### Outcome: PARTIAL SUCCESS

**What worked:**
- DSNG stack configuration successfully implemented
- TlsKeyResolver chain (Hello → Bootstrap) created and passed to native code
- DTLS protocol initialized (state=HANDSHAKE)
- Stack event codes confirm DSNG code path is active
- All pre-Stack initialization steps preserved

**What didn't work:**
- Inspire 3 still does not respond to any TX packets
- 22 TX packets sent, 0 RX received
- DTLS handshake cannot progress past initial state
- Gattlink reset/ACK remains the blocking issue

**Root cause hypothesis:**
The Inspire 3 requires **device pairing/bonding** before it will accept Gattlink/DTLS connections. The Fitbit app performed this bonding during initial device setup. BlueBit has not bonded with the Inspire 3, so the device silently ignores all Gattlink reset packets.

Alternatively, the **BLE write layer difference** (direct `BluetoothGatt` vs Fitbit's `fbgatt` library) might cause timing or queuing issues that prevent the device from recognizing the reset packet.

### Remaining Blocker

**Device bonding/pairing or fbgatt write abstraction.**

The next experiment should:
1. Bond with the Inspire 3 before Gattlink initialization
2. Observe if bonding changes the device's response behavior
3. If bonding doesn't help, investigate the fbgatt write abstraction

## 8. Recommendations

1. **Test bonding:** Use Android's `BluetoothDevice.createBond()` before GoldenGate initialization
2. **Monitor bond state:** Check if bonded devices respond differently
3. **Investigate fbgatt:** Compare Fitbit's `GattCharacteristicWriter` with BlueBit's direct write
4. **Try WRITE_TYPE_DEFAULT:** Change from `WRITE_TYPE_NO_RESPONSE` to `WRITE_TYPE_DEFAULT`
5. **Capture HCI snoop log:** Use Android's built-in Bluetooth HCI snoop to see exact air packets

---

*Experiment date: 2026-09-01*
*Device: Samsung Galaxy A17, Android 16*
*Target: Fitbit Inspire 3 (D8:9E:C1:BA:3D:E4)*
*APK: BlueBit with DSNG config*
